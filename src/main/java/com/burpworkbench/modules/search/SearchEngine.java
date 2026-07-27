package com.burpworkbench.modules.search;

import com.burpworkbench.core.filter.MimeCategory;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class SearchEngine {
    private static final Duration DEFAULT_REGEX_ITEM_TIMEOUT = Duration.ofSeconds(2);
    private static final int STREAM_DECODE_BUFFER_SIZE = 8 * 1024;

    private final long regexItemTimeoutNanos;
    private final long regexItemTimeoutMillis;
    private final LongSupplier nanoTime;

    public SearchEngine() {
        this(DEFAULT_REGEX_ITEM_TIMEOUT, System::nanoTime);
    }

    SearchEngine(Duration regexItemTimeout, LongSupplier nanoTime) {
        Objects.requireNonNull(regexItemTimeout, "regexItemTimeout");
        if (regexItemTimeout.isZero() || regexItemTimeout.isNegative()) {
            throw new IllegalArgumentException("regex item timeout must be positive");
        }
        this.regexItemTimeoutNanos = regexItemTimeout.toNanos();
        this.regexItemTimeoutMillis = regexItemTimeout.toMillis();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public List<SearchResult> search(List<HttpExchange> exchanges, SearchOptions options) {
        if (exchanges == null || exchanges.isEmpty()) {
            return List.of();
        }

        PreparedSearch preparedSearch = prepare(options);
        List<SearchResult> results = new ArrayList<>();
        for (HttpExchange exchange : exchanges) {
            if (preparedSearch.matches(exchange)) {
                results.add(toResult(exchange));
            }
        }
        return results;
    }

    public PreparedSearch prepare(SearchOptions options) {
        return new PreparedSearch(safeOptions(options));
    }

    public SearchResult toResult(HttpExchange exchange) {
        String mime = mimeLabel(exchange);
        long length = responseLength(exchange);
        return new SearchResult(exchange.copyToTempFile(), mime, length);
    }

    private SearchOptions safeOptions(SearchOptions options) {
        return options == null
                ? new SearchOptions("", SearchMode.TEXT, false, false, false, true, true, true, true,
                true, false, false, false, true, Set.of(), Set.of(), Set.of())
                : options;
    }

    boolean matchesFilters(HttpExchange exchange, SearchOptions options) {
        if (!options.allStatus()) {
            int statusCode = exchange.statusCode();
            if (!matchesCustomStatus(statusCode, options.statusPatterns())) {
                return false;
            }
        }

        if (options.mimeFilterEnabled() && options.mimeCategories().isEmpty()) {
            return false;
        }
        if (!options.mimeCategories().isEmpty()) {
            if (!options.mimeCategories().contains(mimeCategory(exchange))) {
                return false;
            }
        }

        if (!options.extensions().isEmpty()) {
            String extension = extension(exchange);
            if (!options.extensions().contains(extension)) {
                return false;
            }
        }
        if (!options.excludedExtensions().isEmpty()) {
            String extension = extension(exchange);
            if (options.excludedExtensions().contains(extension)) {
                return false;
            }
        }

        return true;
    }

    private boolean matchesCustomStatus(int statusCode, Set<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        String code = Integer.toString(statusCode);
        if (code.length() != 3) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern.endsWith("xx")) {
                if (code.charAt(0) == pattern.charAt(0)) {
                    return true;
                }
            } else if (pattern.equals(code)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSelectedParts(
            HttpExchange exchange,
            PreparedSearch preparedSearch,
            RegexOperationGuard regexGuard
    ) {
        SearchOptions options = preparedSearch.options;
        HttpRequestResponse requestResponse = exchange.requestResponse();

        try {
            HttpMessage request = requestResponse.request();
            if (options.requestHeaders() && preparedSearch.matches(partForHeaders(request), regexGuard)) {
                return true;
            }
            if (options.requestBody() && preparedSearch.matches(partForBody(request), regexGuard)) {
                return true;
            }
            if (requestResponse.hasResponse() && requestResponse.response() != null) {
                HttpMessage response = requestResponse.response();
                if (options.responseHeaders() && preparedSearch.matches(partForHeaders(response), regexGuard)) {
                    return true;
                }
                if (options.responseBody() && preparedSearch.matches(partForBody(response), regexGuard)) {
                    return true;
                }
            }
        } catch (SearchItemTimeoutException exception) {
            throw exception;
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException ignored) {
            return false;
        }

        return false;
    }

    private MessagePart partForHeaders(HttpMessage message) {
        ByteArray bytes = message.toByteArray();
        int end = Math.max(0, Math.min(message.bodyOffset(), bytes.length()));
        return new MessagePart(message, bytes, 0, end);
    }

    private MessagePart partForBody(HttpMessage message) {
        ByteArray bytes = message.body();
        return new MessagePart(message, bytes, 0, bytes.length());
    }

    private boolean containsBytes(MessagePart part, byte[] needle) {
        if (needle.length == 0 || part.length() < needle.length) {
            return false;
        }
        int lastStart = part.endExclusive() - needle.length;
        for (int index = part.startInclusive(); index <= lastStart; index++) {
            if ((index & 0x3fff) == 0) {
                ensureSearchNotCancelled();
            }
            boolean matched = true;
            for (int needleIndex = 0; needleIndex < needle.length; needleIndex++) {
                byte actual = part.bytes().getByte(index + needleIndex);
                byte expected = needle[needleIndex];
                if (actual != expected) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAsciiText(MessagePart part, String query, boolean caseSensitive) {
        ensureSearchNotCancelled();
        return part.bytes().indexOf(
                query,
                caseSensitive,
                part.startInclusive(),
                part.endExclusive()
        ) >= 0;
    }

    private byte[] copyBytes(MessagePart part, RegexOperationGuard regexGuard) {
        ensureSearchNotCancelled();
        if (regexGuard != null) {
            regexGuard.check();
        }
        byte[] copy = new byte[part.length()];
        for (int index = 0; index < copy.length; index++) {
            if ((index & 0x3fff) == 0) {
                ensureSearchNotCancelled();
                if (regexGuard != null) {
                    regexGuard.check();
                }
            }
            copy[index] = part.bytes().getByte(part.startInclusive() + index);
        }
        return copy;
    }

    private boolean containsTextIgnoringCase(String text, String query) {
        int lastStart = text.length() - query.length();
        for (int index = 0; index <= lastStart; index++) {
            if ((index & 0x3fff) == 0) {
                ensureSearchNotCancelled();
            }
            if (text.regionMatches(true, index, query, 0, query.length())) {
                return true;
            }
        }
        return false;
    }

    private void ensureSearchNotCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    private byte[] parseHex(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "");
        if (normalized.length() % 2 != 0 || !normalized.matches("[0-9a-fA-F]*")) {
            return new byte[0];
        }
        byte[] bytes = new byte[normalized.length() / 2];
        for (int i = 0; i < normalized.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(normalized.substring(i, i + 2), 16);
        }
        return bytes;
    }

    private List<Charset> charsets(HttpMessage message) {
        return charsetCandidates(message).values();
    }

    private CharsetCandidates charsetCandidates(HttpMessage message) {
        try {
            String contentType = message.headerValue("Content-Type");
            if (contentType != null) {
                for (String part : contentType.split(";")) {
                    String trimmed = part.trim();
                    if (trimmed.toLowerCase(Locale.ROOT)
                            .startsWith("charset=")) {
                        String declaredName = trimmed.substring(
                                "charset=".length()
                        ).replace("\"", "").trim();
                        try {
                            return new CharsetCandidates(
                                    true,
                                    List.of(Charset.forName(declaredName))
                            );
                        } catch (RuntimeException invalidDeclaration) {
                            // A declaration is authoritative even when invalid:
                            // do not reinterpret the bytes using fallback sets.
                            return new CharsetCandidates(true, List.of());
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return new CharsetCandidates(true, List.of());
        }

        List<Charset> charsets = new ArrayList<>();
        addIfMissing(charsets, StandardCharsets.UTF_8);
        addIfMissing(charsets, Charset.forName("MS949"));
        addIfMissing(charsets, Charset.forName("EUC-KR"));
        addIfMissing(charsets, StandardCharsets.ISO_8859_1);
        return new CharsetCandidates(false, List.copyOf(charsets));
    }

    private void addIfMissing(List<Charset> values, Charset value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private MimeCategory mimeCategory(HttpExchange exchange) {
        MimeCategory category = MimeCategory.from(contentType(exchange), safeFileNamePath(fileName(exchange)));
        return category == MimeCategory.FONT || category == MimeCategory.ARCHIVE ? MimeCategory.OTHER : category;
    }

    private Path safeFileNamePath(String fileName) {
        StringBuilder sanitized = new StringBuilder(fileName.length());
        for (int index = 0; index < fileName.length(); index++) {
            char character = fileName.charAt(index);
            sanitized.append(isInvalidWindowsFileNameCharacter(character) ? '_' : character);
        }
        return Path.of(sanitized.toString());
    }

    private boolean isInvalidWindowsFileNameCharacter(char character) {
        return character < 32 || switch (character) {
            case '<', '>', ':', '"', '/', '\\', '|', '?', '*' -> true;
            default -> false;
        };
    }

    private String mimeLabel(HttpExchange exchange) {
        return mimeCategory(exchange).label();
    }

    private String contentType(HttpExchange exchange) {
        try {
            return exchange.requestResponse().hasResponse() && exchange.requestResponse().response() != null
                    ? exchange.requestResponse().response().headerValue("Content-Type")
                    : "";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private long responseLength(HttpExchange exchange) {
        try {
            return exchange.requestResponse().hasResponse() && exchange.requestResponse().response() != null
                    ? exchange.requestResponse().response().body().length()
                    : 0;
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private String extension(HttpExchange exchange) {
        String fileName = fileName(exchange).toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot);
    }

    private String fileName(HttpExchange exchange) {
        try {
            String path = URI.create(exchange.url()).getPath();
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    public final class PreparedSearch {
        private final SearchOptions options;
        private final Pattern textPattern;
        private final byte[] hexNeedle;

        private PreparedSearch(SearchOptions options) {
            this.options = options;
            Pattern compiledPattern = null;
            if (options.mode() == SearchMode.TEXT && options.regex() && !options.query().isBlank()) {
                try {
                    compiledPattern = Pattern.compile(
                            options.query(),
                            options.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                    );
                } catch (PatternSyntaxException exception) {
                    throw new IllegalArgumentException(
                            "Invalid regular expression: " + exception.getDescription(),
                            exception
                    );
                }
            }
            this.textPattern = compiledPattern;
            this.hexNeedle = options.mode() == SearchMode.HEX ? parseHex(options.query()) : new byte[0];
        }

        public boolean matches(HttpExchange exchange) {
            if (exchange == null || !SearchEngine.this.matchesFilters(exchange, options)) {
                return false;
            }
            if (options.query().isBlank()) {
                return !options.negativeMatch();
            }

            RegexOperationGuard regexGuard =
                    textPattern == null ? null : new RegexOperationGuard();
            boolean matched = switch (options.mode()) {
                case HEX -> hexNeedle.length > 0 && matchesSelectedParts(exchange, this, null);
                case TEXT -> matchesSelectedParts(exchange, this, regexGuard);
            };
            return options.negativeMatch() ? !matched : matched;
        }

        public boolean matchesFilters(HttpExchange exchange) {
            return exchange != null && SearchEngine.this.matchesFilters(exchange, options);
        }

        public boolean hasExchangeFilters() {
            return !options.allStatus()
                    || options.mimeFilterEnabled()
                    || !options.mimeCategories().isEmpty()
                    || !options.extensions().isEmpty()
                    || !options.excludedExtensions().isEmpty();
        }

        public boolean supportsNativeWholeMessageSearch() {
            return options.mode() == SearchMode.TEXT
                    && !options.query().isBlank()
                    && !options.regex()
                    && isAscii(options.query())
                    && options.requestHeaders()
                    && options.requestBody()
                    && options.responseHeaders()
                    && options.responseBody();
        }

        public boolean matchesNativeQuery(HttpRequestResponse requestResponse) {
            if (!supportsNativeWholeMessageSearch() || requestResponse == null) {
                return false;
            }
            boolean matched = requestResponse.contains(options.query(), options.caseSensitive());
            return options.negativeMatch() ? !matched : matched;
        }

        public boolean matchesNativeQuery(ProxyHttpRequestResponse requestResponse) {
            if (!supportsNativeWholeMessageSearch() || requestResponse == null) {
                return false;
            }
            boolean matched = requestResponse.contains(options.query(), options.caseSensitive());
            return options.negativeMatch() ? !matched : matched;
        }

        private boolean matches(MessagePart part, RegexOperationGuard regexGuard) {
            if (options.mode() == SearchMode.HEX) {
                return containsBytes(part, hexNeedle);
            }
            if (options.regex()) {
                return matchesDecoded(part, true, regexGuard);
            }
            if (isAscii(options.query())) {
                return containsAsciiText(part, options.query(), options.caseSensitive());
            }
            return matchesDecoded(part, false, null);
        }

        private String decodeStrict(byte[] bytes, Charset charset) {
            try {
                return charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException exception) {
                return null;
            }
        }

        private boolean matchesDecoded(
                MessagePart part,
                boolean regex,
                RegexOperationGuard regexGuard
        ) {
            if (!regex) {
                for (Charset charset : charsets(part.message())) {
                    if (matchesDecodedLiteralStreaming(part, charset)) {
                        return true;
                    }
                }
                return false;
            }

            byte[] bytes = copyBytes(part, regexGuard);
            for (Charset charset : charsets(part.message())) {
                ensureSearchNotCancelled();
                if (regexGuard != null) {
                    regexGuard.check();
                }
                if (matchesDecoded(bytes, charset, regex, regexGuard)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesDecodedLiteralStreaming(
                MessagePart part,
                Charset charset
        ) {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer input = ByteBuffer.allocate(STREAM_DECODE_BUFFER_SIZE);
            CharBuffer output = CharBuffer.allocate(STREAM_DECODE_BUFFER_SIZE);
            StreamingSubstringMatcher matcher =
                    new StreamingSubstringMatcher(
                            options.query(),
                            options.caseSensitive()
                    );
            int sourceOffset = part.startInclusive();
            boolean endOfInput = sourceOffset >= part.endExclusive();

            while (true) {
                ensureSearchNotCancelled();
                while (!endOfInput && input.hasRemaining()) {
                    input.put(part.bytes().getByte(sourceOffset++));
                    endOfInput = sourceOffset >= part.endExclusive();
                }
                input.flip();

                CoderResult decodeResult;
                do {
                    decodeResult = decoder.decode(input, output, endOfInput);
                    consumeDecodedCharacters(output, matcher);
                    if (decodeResult.isError()) {
                        return false;
                    }
                } while (decodeResult.isOverflow());

                input.compact();
                if (!endOfInput) {
                    continue;
                }
                if (input.position() != 0) {
                    // A strict decoder leaves an incomplete terminal sequence
                    // unconsumed when end-of-input is reached.
                    return false;
                }

                CoderResult flushResult;
                do {
                    flushResult = decoder.flush(output);
                    consumeDecodedCharacters(output, matcher);
                    if (flushResult.isError()) {
                        return false;
                    }
                } while (flushResult.isOverflow());
                return matcher.matched();
            }
        }

        private void consumeDecodedCharacters(
                CharBuffer output,
                StreamingSubstringMatcher matcher
        ) {
            output.flip();
            while (output.hasRemaining()) {
                matcher.accept(output.get());
            }
            output.clear();
            ensureSearchNotCancelled();
        }

        private boolean matchesDecoded(
                byte[] bytes,
                Charset charset,
                boolean regex,
                RegexOperationGuard regexGuard
        ) {
            String text = decodeStrict(bytes, charset);
            if (text == null) {
                return false;
            }
            if (regexGuard != null) {
                regexGuard.check();
            }
            if (regex) {
                return textPattern.matcher(
                        new InterruptibleCharSequence(text, regexGuard)
                ).find();
            }
            return options.caseSensitive()
                    ? text.contains(options.query())
                    : containsTextIgnoringCase(text, options.query());
        }

        private boolean isAscii(String value) {
            return value.chars().allMatch(character -> character <= 0x7f);
        }

    }

    private final class RegexOperationGuard {
        private final long startedAt = nanoTime.getAsLong();

        private void check() {
            ensureSearchNotCancelled();
            if (nanoTime.getAsLong() - startedAt >= regexItemTimeoutNanos) {
                throw new SearchItemTimeoutException(regexItemTimeoutMillis);
            }
        }
    }

    private record InterruptibleCharSequence(
            CharSequence delegate,
            RegexOperationGuard regexGuard
    ) implements CharSequence {
        @Override
        public int length() {
            regexGuard.check();
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            regexGuard.check();
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            regexGuard.check();
            return new InterruptibleCharSequence(delegate.subSequence(start, end), regexGuard);
        }

        @Override
        public String toString() {
            regexGuard.check();
            return delegate.toString();
        }
    }

    private static final class StreamingSubstringMatcher {
        private final char[] query;
        private final int[] prefixLengths;
        private final boolean caseSensitive;
        private int matchedLength;
        private boolean matched;

        private StreamingSubstringMatcher(
                String query,
                boolean caseSensitive
        ) {
            this.query = query.toCharArray();
            this.caseSensitive = caseSensitive;
            if (!caseSensitive) {
                for (int index = 0; index < this.query.length; index++) {
                    this.query[index] = foldCase(this.query[index]);
                }
            }
            this.prefixLengths = prefixLengths(this.query);
        }

        private void accept(char value) {
            if (matched || query.length == 0) {
                matched = true;
                return;
            }
            char comparable = caseSensitive ? value : foldCase(value);
            while (matchedLength > 0 && query[matchedLength] != comparable) {
                matchedLength = prefixLengths[matchedLength - 1];
            }
            if (query[matchedLength] == comparable) {
                matchedLength++;
            }
            if (matchedLength == query.length) {
                matched = true;
            }
        }

        private boolean matched() {
            return matched;
        }

        private static int[] prefixLengths(char[] query) {
            int[] prefixes = new int[query.length];
            int matched = 0;
            for (int index = 1; index < query.length; index++) {
                while (matched > 0 && query[index] != query[matched]) {
                    matched = prefixes[matched - 1];
                }
                if (query[index] == query[matched]) {
                    matched++;
                }
                prefixes[index] = matched;
            }
            return prefixes;
        }

        private static char foldCase(char value) {
            return Character.toLowerCase(Character.toUpperCase(value));
        }
    }

    private record MessagePart(
            HttpMessage message,
            ByteArray bytes,
            int startInclusive,
            int endExclusive
    ) {
        private int length() {
            return endExclusive - startInclusive;
        }
    }

    private record CharsetCandidates(
            boolean declared,
            List<Charset> values
    ) {
    }
}
