package com.burpworkbench.modules.compare;

import com.burpworkbench.modules.extractor.BeautifyService;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Formats disposable presentation copies. Items, request snapshots and Hex keep original bytes. */
final class PrettyComparison {
    private static final int OUTPUT_LIMIT = 1024 * 1024;
    private static final Pattern HTTP_START = Pattern.compile("^(?:HTTP/\\d(?:\\.\\d)? [0-9]{3}.*|[A-Z]+ \\S+ HTTP/\\d(?:\\.\\d)?)$");
    private static final Pattern JS_START = Pattern.compile("^(?:(?:export\\s+)?(?:async\\s+)?function\\b|(?:var|let|const|class|import|export)\\s|\\(function\\b|!function\\b)");
    record Formatted(byte[] bytes, String format) {}

    static Comparison.View prepare(CompareItem a, CompareItem b, Comparison.Options options) {
        try {
            DiffEngine.check();
            Formatted left = format(a, options.charset()), right = format(b, options.charset());
            DiffEngine.check();
            // Always interpret the disposable formatted UTF-8 copies as UTF-8, not their retained HTTP header charset.
            var formattedOptions = new Comparison.Options(options.mode(), false, options.differencesOnly(), "UTF-8");
            var value = Comparison.build(left.bytes(), right.bytes(), formattedOptions);
            var marks = value.marks().stream().map(m -> new Comparison.Mark(m.a0(), m.a1(), m.b0(), m.b1(), m.kind(),
                    "Formatted UTF-8 · " + m.location())).toList();
            String note = "Pretty · " + left.format() + " / " + right.format()
                    + " · formatted text/positions; whitespace differences may be hidden; originals preserved; HTTP headers describe the original body";
            return new Comparison.View(value.left(), value.right(), marks, note, value.a(), value.b(), value.problem());
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            DiffEngine.check();
            var raw = Comparison.prepare(a.bytes, b.bytes,
                    new Comparison.Options(options.mode(), false, options.differencesOnly(), options.charset()));
            String reason = failure instanceof Unavailable ? failure.getMessage() : failure.getClass().getSimpleName();
            return new Comparison.View(raw.left(), raw.right(), raw.marks(),
                    "Pretty unavailable (" + reason + ") · Raw comparison shown · " + raw.note(), raw.a(), raw.b(), raw.problem());
        }
    }

    static Formatted format(CompareItem item, String charset) {
        DiffEngine.check();
        if(item.bytes.length>MessageCapture.MAX_ITEM)throw new Unavailable("input limit");
        var decoded = Comparison.decode(item.bytes, charset);
        if (!decoded.exact) throw new Unavailable("text decoding would lose bytes");
        String source = decoded.text;
        int end = source.indexOf("\r\n\r\n"), separator = 4;
        if (end < 0) { end = source.indexOf("\n\n"); separator = 2; }
        int firstLineEnd = source.indexOf('\n');
        String firstLine = firstLineEnd < 0 ? source : source.substring(0, firstLineEnd).stripTrailing();
        boolean http = HTTP_START.matcher(firstLine).matches();
        String headers = "", body = source;
        BeautifyService.Format format;
        if (http) {
            if (end < 0) throw new Unavailable("HTTP header/body separator missing");
            headers = source.substring(0, end + separator);
            body = source.substring(end + separator);
            String headerBlock = source.substring(0, end);
            String coding = header(headerBlock, "Content-Encoding");
            String transfer = header(headerBlock, "Transfer-Encoding");
            if ((!coding.isBlank() && !coding.equalsIgnoreCase("identity")) || !transfer.isBlank())
                throw new Unavailable("encoded/transfer-coded body; use Raw or decoded content");
            format = mimeFormat(header(headerBlock, "Content-Type"));
            if (format == null) throw new Unavailable("HTTP body is not declared JS/JSON");
        } else {
            String trimmed = source.stripLeading();
            if (trimmed.startsWith("\uFEFF")) trimmed = trimmed.substring(1).stripLeading();
            if (item.source.toLowerCase(Locale.ROOT).matches(".*\\.(?:m?js|cjs)(?:[?#].*)?$")
                    || JS_START.matcher(trimmed).find()) format = BeautifyService.Format.JAVASCRIPT;
            else if (trimmed.startsWith("{") || trimmed.startsWith("[")) format = BeautifyService.Format.JSON;
            else throw new Unavailable("unrecognized JS/JSON text");
        }
        if (body.isBlank()) throw new Unavailable("empty body");
        if (body.indexOf('\0') >= 0) throw new Unavailable("binary content");
        String formatted = BeautifyService.format(format, body, OUTPUT_LIMIT,
                () -> Thread.currentThread().isInterrupted());
        if ((long)headers.length() + formatted.length() > OUTPUT_LIMIT) throw new Unavailable("formatted output limit");
        byte[] bytes = (headers + formatted).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > OUTPUT_LIMIT) throw new Unavailable("formatted output limit");
        return new Formatted(bytes, format == BeautifyService.Format.JSON ? "JSON" : "JavaScript");
    }

    private static String header(String headers, String name) {
        var matcher = Pattern.compile("(?im)^" + Pattern.quote(name) + ":[ \\t]*([^\\r\\n]*)").matcher(headers);
        StringBuilder value = new StringBuilder();
        while (matcher.find()) { if (!value.isEmpty()) value.append(','); value.append(matcher.group(1).trim()); }
        return value.toString();
    }
    private static BeautifyService.Format mimeFormat(String contentType) {
        String mime = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (mime.equals("application/json") || mime.equals("text/json") || mime.endsWith("+json")) return BeautifyService.Format.JSON;
        if (mime.equals("application/javascript") || mime.equals("text/javascript") || mime.equals("application/x-javascript")
                || mime.equals("application/ecmascript") || mime.equals("text/ecmascript")) return BeautifyService.Format.JAVASCRIPT;
        return null;
    }
    private static final class Unavailable extends RuntimeException { Unavailable(String message) { super(message); } }
}
