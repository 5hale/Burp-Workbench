package com.burpworkbench.modules.replace;

import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Pure per-message engine, shared by local Test and the Proxy send callbacks. */
final class TrafficEngine {
    static final int MAX_WIRE_BYTES = 8 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    static final int MAX_HEADER_BYTES = 256 * 1024;
    private static final int MAX_APPLICABLE_RULES = 1000;
    private static final long RULE_NANOS = 250_000_000L;
    private static final long MESSAGE_NANOS = 1_000_000_000L;
    private static final Pattern REQUEST_LINE = Pattern.compile("[^\\s]+[\\t ]+([^\\s]+)[\\t ]+HTTP/\\d+(?:\\.\\d+)?");
    private static final Pattern RESPONSE_LINE = Pattern.compile("HTTP/\\d+(?:\\.\\d+)?[\\t ]+\\d{3}(?:[\\t ][^\\r\\n\\x00]*)?");
    private static final Pattern CHARSET = Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))");

    record Result(byte[] message, int replacements, List<String> issues) {}
    private record Change(byte[] bytes, int count) {}
    private record TextChange(String text, int count) {}
    private record DecodedBody(byte[] bytes, String encoding, boolean rawDeflate) {}

    private TrafficEngine() {}

    static Result transform(List<RuleDraft> rules, String requestUrl, boolean request, byte[] message) {
        return transform(rules, requestUrl, request, message, System::nanoTime);
    }

    static Result transform(List<RuleDraft> rules, String requestUrl, boolean request, byte[] message, LongSupplier clock) {
        if (message == null) throw new IllegalArgumentException("Message cannot be null");
        if (message.length > MAX_WIRE_BYTES) return new Result(message, 0, List.of("Message exceeds 8 MiB; unchanged"));
        List<String> issues = new ArrayList<>();
        byte[] current = message;
        Message parsed = null;
        int count = 0, applicable = 0;
        long deadline = clock.getAsLong() + MESSAGE_NANOS;
        for (int index = 0; index < rules.size(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            // Scanning excluded rules still consumes the aggregate time budget.
            if (clock.getAsLong() - deadline >= 0) {
                issues.add("Remaining rules skipped: 1-second message limit");
                break;
            }
            RuleDraft rule = rules.get(index);
            if (rule == null || !rule.enabled() || rule.match().isEmpty() || request != rule.target().startsWith("Request")) continue;
            try {
                if (!ScopeMatcher.matches(rule, requestUrl)) continue;
                if (applicable >= MAX_APPLICABLE_RULES) {
                    issues.add("Remaining applicable rules skipped: 1000 applicable-rule message limit");
                    break;
                }
                applicable++;
                long ruleDeadline = Math.min(deadline, clock.getAsLong() + RULE_NANOS);
                Replacement replacement = new Replacement(rule, ruleDeadline, clock);
                if (parsed == null) parsed = Message.parse(current, request);
                Change change = apply(parsed, rule, replacement);
                // Even header-only changes invalidate charset/encoding/body caches.
                if (change.bytes() != current) parsed = null;
                current = change.bytes();
                count = Math.addExact(count, change.count());
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException | IOException | StackOverflowError invalid) {
                // A bad rule never commits its partially changed request/body.
                if (issues.size() < 20) issues.add("Rule " + (index + 1) + ": " + shortMessage(invalid));
            }
        }
        return new Result(current, count, List.copyOf(issues));
    }

    /** Test isolates this rule; URL/path scopes apply only to live traffic. */
    static Result preview(RuleDraft rule, boolean request, byte[] message) {
        RuleDraft testRule = new RuleDraft(true, rule.name(), rule.target(), "", "", rule.match(), rule.replacement(), rule.regex(), rule.caseSensitive());
        return transform(List.of(testRule), "", request, message);
    }

    private static String shortMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private static Change apply(Message message, RuleDraft rule, Replacement replacement) throws IOException {
        byte[] input = message.raw;
        String type = rule.target();
        if (type.equals(RuleTypes.REQUEST_FIRST_LINE) || type.equals(RuleTypes.RESPONSE_FIRST_LINE)) {
            TextChange changed = replacement.apply(message.firstLine);
            if (changed.text().equals(message.firstLine)) return new Change(input, changed.count());
            Pattern line = type.equals(RuleTypes.REQUEST_FIRST_LINE) ? REQUEST_LINE : RESPONSE_LINE;
            if (changed.text().indexOf('\r') >= 0 || changed.text().indexOf('\n') >= 0 || changed.text().indexOf('\0') >= 0 || !line.matcher(changed.text()).matches())
                throw new IllegalArgumentException("Replacement creates an invalid HTTP first line");
            return new Change(message.withHead(changed.text(), message.headers), changed.count());
        }
        if (type.equals(RuleTypes.REQUEST_HEADER) || type.equals(RuleTypes.RESPONSE_HEADER)) {
            TextChange changed = replacement.apply(message.headers);
            if (changed.text().equals(message.headers)) return new Change(input, changed.count());
            String headers = normalizeHeaders(changed.text(), message.newline);
            return new Change(message.withHead(message.firstLine, headers), changed.count());
        }
        if (RuleTypes.isParameter(type)) return parameters(message, rule, replacement);
        if (!(type.equals(RuleTypes.REQUEST_BODY) || type.equals(RuleTypes.RESPONSE_BODY))) {
            throw new IllegalArgumentException("Unknown rule type");
        }
        DecodedBody decoded = message.decodedBody(replacement);
        Charset charset = bodyCharset(message);
        String text = message.bodyText(decoded, charset);
        rejectBinaryText(message, text);
        TextChange changed = replacement.apply(text);
        if (text.equals(changed.text())) return new Change(input, changed.count());
        return new Change(message.withBody(message.firstLine, encode(changed.text(), charset), decoded, replacement), changed.count());
    }

    private static Change parameters(Message message, RuleDraft rule, Replacement replacement) throws IOException {
        Matcher line = REQUEST_LINE.matcher(message.firstLine);
        if (!line.matches()) throw new IllegalArgumentException("Parameters require an HTTP request");
        String target = line.group(1), changedTarget = target;
        int count = 0;
        int question = target.indexOf('?');
        int fragment = target.indexOf('#');
        int queryEnd = fragment < 0 ? target.length() : fragment;
        if (question >= 0 && question < queryEnd) {
            TextChange change = parameterFields(target.substring(question + 1, queryEnd), rule, replacement);
            changedTarget = target.substring(0, question + 1) + change.text() + target.substring(queryEnd);
            count += change.count();
        }
        String changedLine = message.firstLine.substring(0, line.start(1)) + changedTarget + message.firstLine.substring(line.end(1));
        String contentType = message.header("Content-Type");
        if (contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
            if (!bodyCharset(message).equals(StandardCharsets.UTF_8)) throw new IllegalArgumentException("Form parameters require UTF-8 charset");
            DecodedBody decoded = message.decodedBody(replacement);
            String body = message.bodyText(decoded, StandardCharsets.UTF_8);
            TextChange change = parameterFields(body, rule, replacement);
            count += change.count();
            if (!change.text().equals(body)) {
                return new Change(message.withBody(changedLine, encode(change.text(), StandardCharsets.UTF_8), decoded, replacement), count);
            }
        }
        return new Change(changedLine.equals(message.firstLine) ? message.raw : message.withHead(changedLine, message.headers), count);
    }

    private static TextChange parameterFields(String fields, RuleDraft rule, Replacement replacement) {
        StringBuilder result = new StringBuilder(fields.length());
        boolean names = rule.target().equals(RuleTypes.REQUEST_PARAM_NAME);
        int cursor = 0, count = 0;
        while (cursor < fields.length()) {
            replacement.check();
            int amp = fields.indexOf('&', cursor);
            int end = amp < 0 ? fields.length() : amp;
            int equals = fields.indexOf('=', cursor);
            if (equals < 0 || equals >= end) equals = -1;
            int start = names ? cursor : equals < 0 ? end : equals + 1;
            int selectedEnd = names && equals >= 0 ? equals : end;
            if (end > cursor && (names || equals >= 0)) {
                String raw = fields.substring(start, selectedEnd);
                String decoded = decodeParameter(raw);
                TextChange change = replacement.apply(decoded);
                String encoded = decoded.equals(change.text()) ? raw : encodeParameter(change.text());
                checkSize((long) result.length() + start - cursor + encoded.length() + end - selectedEnd, MAX_TEXT_BYTES);
                result.append(fields, cursor, start).append(encoded).append(fields, selectedEnd, end);
                count += change.count();
            } else result.append(fields, cursor, end);
            if (amp >= 0) result.append('&');
            cursor = end + 1;
        }
        return new TextChange(result.toString(), count);
    }

    private static String decodeParameter(String raw) {
        StringBuilder output = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length();) {
            char ch = raw.charAt(i);
            if (ch == '+') { output.append(' '); i++; }
            else if (ch != '%') { output.append(ch); i++; }
            else {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                while (i < raw.length() && raw.charAt(i) == '%') {
                    if (i + 2 >= raw.length()) throw new IllegalArgumentException("Malformed percent encoding");
                    int a = Character.digit(raw.charAt(i + 1), 16), b = Character.digit(raw.charAt(i + 2), 16);
                    if (a < 0 || b < 0) throw new IllegalArgumentException("Malformed percent encoding");
                    bytes.write((a << 4) | b); i += 3;
                }
                output.append(decode(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return output.toString();
    }

    private static String encodeParameter(String value) {
        byte[] bytes = encode(value, StandardCharsets.UTF_8);
        long length = 0;
        for (byte b : bytes) length += formSafe(b & 255) || b == 32 ? 1 : 3;
        checkSize(length, MAX_TEXT_BYTES);
        StringBuilder result = new StringBuilder((int) length);
        for (byte item : bytes) {
            int b = item & 255;
            if (formSafe(b)) result.append((char) b);
            else if (b == 32) result.append('+');
            else result.append('%').append("0123456789ABCDEF".charAt(b >>> 4)).append("0123456789ABCDEF".charAt(b & 15));
        }
        return result.toString();
    }

    private static boolean formSafe(int b) {
        return b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '*' || b == '-' || b == '.' || b == '_';
    }

    private static Charset bodyCharset(Message message) {
        Matcher matcher = CHARSET.matcher(message.header("Content-Type"));
        return matcher.find() ? Charset.forName(matcher.group(1) == null ? matcher.group(2) : matcher.group(1)) : StandardCharsets.UTF_8;
    }

    private static void rejectBinaryText(Message message, String text) {
        String mime = message.header("Content-Type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!mime.isEmpty() && !(mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")
                || mime.contains("javascript") || mime.equals("application/x-www-form-urlencoded"))) {
            throw new IllegalArgumentException("Non-text Content-Type; body unchanged");
        }
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("Binary body; unchanged");
    }

    private static DecodedBody decodeBody(Message message, Replacement replacement) throws IOException {
        replacement.check();
        byte[] wire = Arrays.copyOfRange(message.raw, message.bodyOffset, message.raw.length);
        String transfer = message.header("Transfer-Encoding").trim().toLowerCase(Locale.ROOT);
        if (!transfer.isEmpty() && !transfer.equals("identity")) {
            if (!transfer.equals("chunked")) throw new IllegalArgumentException("Unsupported Transfer-Encoding; body unchanged");
            wire = unchunk(wire, replacement);
        }
        String encoding = message.header("Content-Encoding").trim().toLowerCase(Locale.ROOT);
        byte[] decoded;
        boolean rawDeflate = false;
        switch (encoding) {
            case "", "identity" -> decoded = wire;
            case "gzip", "x-gzip" -> { try (InputStream stream = new GZIPInputStream(new ByteArrayInputStream(wire))) { decoded = readBounded(stream, replacement); } }
            case "deflate" -> {
                try { decoded = inflate(wire, false, replacement); }
                catch (IOException invalidZlib) { decoded = inflate(wire, true, replacement); rawDeflate = true; }
            }
            case "br" -> { try (InputStream stream = new BrotliInputStream(new ByteArrayInputStream(wire))) { decoded = readBounded(stream, replacement); } }
            default -> throw new IllegalArgumentException("Unsupported Content-Encoding; body unchanged");
        }
        checkSize(decoded.length, MAX_TEXT_BYTES);
        replacement.check();
        return new DecodedBody(decoded, encoding, rawDeflate);
    }

    private static byte[] inflate(byte[] bytes, boolean raw, Replacement replacement) throws IOException {
        Inflater inflater = new Inflater(raw);
        try (InputStream stream = new InflaterInputStream(new ByteArrayInputStream(bytes), inflater)) {
            return readBounded(stream, replacement);
        } finally { inflater.end(); }
    }

    private static byte[] readBounded(InputStream input, Replacement replacement) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) >= 0;) {
            replacement.check();
            checkSize((long) result.size() + read, MAX_TEXT_BYTES);
            result.write(buffer, 0, read);
        }
        return result.toByteArray();
    }

    private static byte[] unchunk(byte[] wire, Replacement replacement) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int cursor = 0;
        while (true) {
            replacement.check();
            int lineEnd = find(wire, cursor, new byte[] { '\r', '\n' });
            if (lineEnd < 0) throw new IllegalArgumentException("Malformed chunk framing");
            String sizeText = new String(wire, cursor, lineEnd - cursor, StandardCharsets.US_ASCII).split(";", 2)[0].trim();
            long size;
            try { size = Long.parseLong(sizeText, 16); }
            catch (NumberFormatException malformed) { throw new IllegalArgumentException("Malformed chunk size"); }
            if (size < 0 || size > MAX_TEXT_BYTES) throw new IllegalArgumentException("Chunk exceeds body limit");
            cursor = lineEnd + 2;
            if (size == 0) {
                // Reject trailers rather than silently dropping potentially significant fields.
                if (cursor + 2 != wire.length || wire[cursor] != '\r' || wire[cursor + 1] != '\n') {
                    throw new IllegalArgumentException("Chunk trailers unsupported; body unchanged");
                }
                return result.toByteArray();
            }
            if (cursor + size + 2 > wire.length || wire[cursor + (int) size] != '\r' || wire[cursor + (int) size + 1] != '\n') {
                throw new IllegalArgumentException("Malformed chunk body");
            }
            checkSize((long) result.size() + size, MAX_TEXT_BYTES);
            result.write(wire, cursor, (int) size);
            cursor += (int) size + 2;
        }
    }

    private static String decode(byte[] bytes, Charset charset) {
        try { return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid " + charset.name() + " text; unchanged"); }
    }

    private static byte[] encode(String text, Charset charset) {
        checkSize(text.length(), MAX_TEXT_BYTES);
        try {
            ByteBuffer bytes = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            checkSize(bytes.remaining(), MAX_TEXT_BYTES);
            byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
        } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Replacement cannot be encoded as " + charset.name()); }
    }

    private static void checkSize(long size, int maximum) {
        if (size > maximum) throw new IllegalArgumentException("Message/decoded/output size exceeds " + maximum + " bytes/chars; rule unchanged");
    }

    private static int find(byte[] data, int offset, byte[] needle) {
        outer: for (int i = offset; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static String normalizeHeaders(String headers, String newline) {
        if (headers.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in headers");
        List<String> lines = new ArrayList<>();
        for (String line : headers.split("\\r?\\n", -1)) {
            if (line.isEmpty()) continue; // Removing a complete header must not create a body separator.
            if (line.indexOf(':') <= 0 || line.indexOf('\r') >= 0) throw new IllegalArgumentException("Malformed replacement header");
            lines.add(line);
        }
        return String.join(newline, lines);
    }

    private static final class Message {
        final byte[] raw;
        final int bodyOffset;
        final String firstLine, headers, newline;
        final Charset headerCharset;
        private DecodedBody decodedBody;
        private String bodyText;
        private Charset textCharset;

        DecodedBody decodedBody(Replacement replacement) throws IOException {
            replacement.check();
            if (decodedBody == null) decodedBody = decodeBody(this, replacement);
            return decodedBody;
        }

        String bodyText(DecodedBody body, Charset charset) {
            if (bodyText == null || !charset.equals(textCharset)) {
                String decoded = decode(body.bytes(), charset);
                bodyText = decoded; textCharset = charset;
            }
            return bodyText;
        }

        private Message(byte[] raw, int bodyOffset, String firstLine, String headers, String newline, Charset headerCharset) {
            this.raw = raw; this.bodyOffset = bodyOffset; this.firstLine = firstLine;
            this.headers = headers; this.newline = newline; this.headerCharset = headerCharset;
        }

        static Message parse(byte[] raw, boolean request) {
            int boundary = find(raw, 0, new byte[] { '\r', '\n', '\r', '\n' });
            int lfBoundary = find(raw, 0, new byte[] { '\n', '\n' });
            String newline = "\r\n";
            if (boundary < 0 || lfBoundary >= 0 && lfBoundary < boundary) { boundary = lfBoundary; newline = "\n"; }
            if (boundary < 0) throw new IllegalArgumentException("HTTP header/body separator missing");
            checkSize(boundary, MAX_HEADER_BYTES);
            byte[] headBytes = Arrays.copyOf(raw, boundary);
            Charset charset = StandardCharsets.UTF_8;
            String head;
            try { head = decode(headBytes, charset); }
            catch (IllegalArgumentException invalidUtf8) { charset = StandardCharsets.ISO_8859_1; head = decode(headBytes, charset); }
            int end = head.indexOf(newline);
            String first = end < 0 ? head : head.substring(0, end);
            boolean isRequest = REQUEST_LINE.matcher(first).matches();
            boolean isResponse = RESPONSE_LINE.matcher(first).matches();
            if (request ? !isRequest : !isResponse) throw new IllegalArgumentException("Message does not match request/response direction");
            String headers = end < 0 ? "" : head.substring(end + newline.length());
            return new Message(raw, boundary + newline.length() * 2, first, headers, newline, charset);
        }

        String header(String name) {
            StringBuilder value = new StringBuilder();
            for (String line : headers.split("\\r?\\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    if (!value.isEmpty()) value.append(',');
                    value.append(line.substring(colon + 1).trim());
                }
            }
            return value.toString();
        }

        byte[] withHead(String first, String changedHeaders) {
            byte[] head = encode(first + (changedHeaders.isEmpty() ? "" : newline + changedHeaders) + newline + newline, headerCharset);
            checkSize(head.length, MAX_HEADER_BYTES);
            checkSize((long) head.length + raw.length - bodyOffset, MAX_WIRE_BYTES);
            byte[] result = Arrays.copyOf(head, head.length + raw.length - bodyOffset);
            System.arraycopy(raw, bodyOffset, result, head.length, raw.length - bodyOffset);
            return result;
        }

        byte[] withBody(String first, byte[] decoded, DecodedBody previous, Replacement replacement) throws IOException {
            byte[] body = decoded;
            if (previous.encoding().equals("gzip") || previous.encoding().equals("x-gzip")) {
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                try (GZIPOutputStream out = new GZIPOutputStream(compressed)) { out.write(decoded); }
                body = compressed.toByteArray();
            } else if (previous.encoding().equals("deflate")) {
                Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, previous.rawDeflate());
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                try (DeflaterOutputStream out = new DeflaterOutputStream(compressed, deflater)) { out.write(decoded); }
                finally { deflater.end(); }
                body = compressed.toByteArray();
            }
            replacement.check();
            StringBuilder cleaned = new StringBuilder();
            for (String line : headers.split("\\r?\\n")) {
                int colon = line.indexOf(':');
                String name = colon < 0 ? "" : line.substring(0, colon).trim();
                if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")
                        || name.equalsIgnoreCase("Trailer") || name.equalsIgnoreCase("Content-MD5")
                        || previous.encoding().equals("br") && name.equalsIgnoreCase("Content-Encoding")) continue;
                if (!line.isEmpty()) cleaned.append(line).append(newline);
            }
            cleaned.append("Content-Length: ").append(body.length);
            byte[] head = encode(first + newline + cleaned + newline + newline, headerCharset);
            checkSize(head.length, MAX_HEADER_BYTES);
            checkSize((long) head.length + body.length, MAX_WIRE_BYTES);
            byte[] result = Arrays.copyOf(head, head.length + body.length);
            System.arraycopy(body, 0, result, head.length, body.length);
            return result;
        }
    }

    private static final class Replacement {
        final RuleDraft rule;
        final long deadline;
        final LongSupplier clock;
        final Pattern pattern;

        Replacement(RuleDraft rule, long deadline, LongSupplier clock) {
            if (rule.match().length() > 4096 || rule.replacement().length() > 4096) throw new IllegalArgumentException("Match/Replace exceeds 4096 characters");
            this.rule = rule; this.deadline = deadline; this.clock = clock;
            check();
            int flags = rule.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = rule.regex() ? Pattern.compile(rule.match(), flags)
                    : rule.caseSensitive() ? null : Pattern.compile(rule.match(), flags | Pattern.LITERAL);
        }

        void check() {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (clock.getAsLong() - deadline >= 0) throw new IllegalArgumentException("Rule processing timeout (250 ms / 1 second message)");
        }

        TextChange apply(String text) {
            check();
            if (pattern == null) return literal(text);
            Matcher matcher = pattern.matcher(new TimedSequence(text, this));
            if (!matcher.find()) { check(); return new TextChange(text, 0); }
            StringBuilder output = new StringBuilder(Math.min(text.length(), 64 * 1024));
            String replacementText = rule.regex() ? rule.replacement() : Matcher.quoteReplacement(rule.replacement());
            long references = rule.regex() ? rule.replacement().chars().filter(ch -> ch == '$').count() : 0;
            int count = 0, cursor = 0;
            do {
                check();
                int largest = matcher.end() - matcher.start();
                if (references > 0) for (int group = 1; group <= matcher.groupCount(); group++) {
                    check();
                    if (matcher.start(group) >= 0) largest = Math.max(largest, matcher.end(group) - matcher.start(group));
                }
                checkSize((long) output.length() + matcher.start() - cursor + rule.replacement().length() + references * largest, MAX_TEXT_BYTES);
                matcher.appendReplacement(output, replacementText);
                cursor = matcher.end(); count++;
            } while (matcher.find());
            checkSize((long) output.length() + text.length() - cursor, MAX_TEXT_BYTES);
            matcher.appendTail(output);
            check();
            return new TextChange(output.toString(), count);
        }

        private TextChange literal(String text) {
            int cursor = 0, count = 0, next;
            if (text.indexOf(rule.match()) < 0) { check(); return new TextChange(text, 0); }
            StringBuilder output = new StringBuilder(Math.min(text.length(), 64 * 1024));
            while ((next = text.indexOf(rule.match(), cursor)) >= 0) {
                check();
                checkSize((long) output.length() + next - cursor + rule.replacement().length(), MAX_TEXT_BYTES);
                output.append(text, cursor, next).append(rule.replacement());
                cursor = next + rule.match().length(); count++;
            }
            checkSize((long) output.length() + text.length() - cursor, MAX_TEXT_BYTES);
            output.append(text, cursor, text.length());
            check();
            return new TextChange(output.toString(), count);
        }
    }

    private record TimedSequence(CharSequence text, Replacement replacement) implements CharSequence {
        public int length() { replacement.check(); return text.length(); }
        public char charAt(int index) { replacement.check(); return text.charAt(index); }
        public CharSequence subSequence(int start, int end) { replacement.check(); return new TimedSequence(text.subSequence(start, end), replacement); }
        public String toString() { replacement.check(); return text.toString(); }
    }
}
