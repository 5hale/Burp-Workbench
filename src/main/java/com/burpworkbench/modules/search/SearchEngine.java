package com.burpworkbench.modules.search;

import com.burpworkbench.core.filter.MimeCategory;
import com.burpworkbench.core.http.HttpExchange;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SearchEngine {
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

    private boolean matchesSelectedParts(HttpExchange exchange, PreparedSearch preparedSearch) {
        SearchOptions options = preparedSearch.options;
        HttpRequestResponse requestResponse = exchange.requestResponse();

        try {
            HttpMessage request = requestResponse.request();
            if (options.requestHeaders() && preparedSearch.matches(partForHeaders(request))) {
                return true;
            }
            if (options.requestBody() && preparedSearch.matches(partForBody(request))) {
                return true;
            }
            if (requestResponse.hasResponse() && requestResponse.response() != null) {
                HttpMessage response = requestResponse.response();
                if (options.responseHeaders() && preparedSearch.matches(partForHeaders(response))) {
                    return true;
                }
                if (options.responseBody() && preparedSearch.matches(partForBody(response))) {
                    return true;
                }
            }
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

    private boolean containsBytes(MessagePart part, byte[] needle, byte[] asciiFoldMask) {
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
                if (asciiFoldMask != null && asciiFoldMask[needleIndex] != 0
                        ? asciiLower(actual) != asciiLower(expected)
                        : actual != expected) {
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

    private byte asciiLower(byte value) {
        return value >= 'A' && value <= 'Z' ? (byte) (value + ('a' - 'A')) : value;
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

    private byte[] copyBytes(MessagePart part) {
        ensureSearchNotCancelled();
        byte[] copy = new byte[part.length()];
        for (int index = 0; index < copy.length; index++) {
            if ((index & 0x3fff) == 0) {
                ensureSearchNotCancelled();
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
        List<Charset> charsets = new ArrayList<>();
        Charset declared = declaredCharset(message);
        if (declared != null) {
            charsets.add(declared);
        }
        addIfMissing(charsets, StandardCharsets.UTF_8);
        addIfMissing(charsets, Charset.forName("MS949"));
        addIfMissing(charsets, Charset.forName("EUC-KR"));
        addIfMissing(charsets, StandardCharsets.ISO_8859_1);
        return charsets;
    }

    private Charset declaredCharset(HttpMessage message) {
        try {
            String contentType = message.headerValue("Content-Type");
            if (contentType == null) {
                return null;
            }
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    return Charset.forName(trimmed.substring("charset=".length()).replace("\"", "").trim());
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
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
        private final boolean literalByteSearch;
        private final boolean asciiFoldByteSearch;
        private final Map<Charset, EncodedNeedle> encodedNeedles = new HashMap<>();

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
            this.literalByteSearch = options.caseSensitive() || hasNoCaseVariants(options.query());
            this.asciiFoldByteSearch = !options.caseSensitive() && hasOnlyAsciiCaseVariants(options.query());
        }

        public boolean matches(HttpExchange exchange) {
            if (exchange == null || !SearchEngine.this.matchesFilters(exchange, options)) {
                return false;
            }
            if (options.query().isBlank()) {
                return !options.negativeMatch();
            }

            boolean matched = switch (options.mode()) {
                case HEX -> hexNeedle.length > 0 && matchesSelectedParts(exchange, this);
                case TEXT -> matchesSelectedParts(exchange, this);
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
            boolean matched = options.regex()
                    ? requestResponse.contains(textPattern)
                    : requestResponse.contains(options.query(), options.caseSensitive());
            return options.negativeMatch() ? !matched : matched;
        }

        public boolean matchesNativeQuery(ProxyHttpRequestResponse requestResponse) {
            if (!supportsNativeWholeMessageSearch() || requestResponse == null) {
                return false;
            }
            boolean matched = options.regex()
                    ? requestResponse.contains(textPattern)
                    : requestResponse.contains(options.query(), options.caseSensitive());
            return options.negativeMatch() ? !matched : matched;
        }

        private boolean matches(MessagePart part) {
            if (options.mode() == SearchMode.HEX) {
                return containsBytes(part, hexNeedle, null);
            }
            if (options.regex()) {
                return matchesDecoded(part, true);
            }
            if (isAscii(options.query())) {
                return containsAsciiText(part, options.query(), options.caseSensitive());
            }
            if (literalByteSearch || asciiFoldByteSearch) {
                boolean foldAsciiQueryCharacters = !literalByteSearch && asciiFoldByteSearch;
                Charset declared = declaredCharset(part.message());
                if (foldAsciiQueryCharacters && declared == null) {
                    return matchesDecoded(part, false);
                }

                List<Charset> candidateCharsets =
                        declared == null ? charsets(part.message()) : List.of(declared);
                for (Charset charset : candidateCharsets) {
                    EncodedNeedle needle = encodedNeedle(charset);
                    if (needle.bytes().length == 0) {
                        continue;
                    }
                    if (foldAsciiQueryCharacters && !needle.supportsAsciiFold()) {
                        return matchesDecoded(part, false);
                    }
                    byte[] foldMask = foldAsciiQueryCharacters ? needle.asciiFoldMask() : null;
                    if (containsBytes(part, needle.bytes(), foldMask)) {
                        return true;
                    }
                }
                return false;
            }
            return matchesDecoded(part, false);
        }

        private boolean matchesDecoded(MessagePart part, boolean regex) {
            byte[] bytes = copyBytes(part);
            Charset declared = declaredCharset(part.message());
            List<Charset> candidateCharsets =
                    declared == null ? charsets(part.message()) : List.of(declared);
            for (Charset charset : candidateCharsets) {
                ensureSearchNotCancelled();
                if (matchesDecoded(bytes, charset, regex)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesDecoded(byte[] bytes, Charset charset, boolean regex) {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String text;
            try {
                text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException exception) {
                return false;
            }
            return regex
                    ? textPattern.matcher(new InterruptibleCharSequence(text)).find()
                    : containsTextIgnoringCase(text, options.query());
        }

        private EncodedNeedle encodedNeedle(Charset charset) {
            return encodedNeedles.computeIfAbsent(charset, value -> {
                CharsetEncoder encoder = value.newEncoder();
                if (!encoder.canEncode(options.query())) {
                    return EncodedNeedle.notEncodable();
                }

                byte[] encoded = options.query().getBytes(value);
                if (!asciiFoldByteSearch || literalByteSearch) {
                    return new EncodedNeedle(encoded, null, true);
                }

                ByteArrayOutputStream segmented = new ByteArrayOutputStream(encoded.length);
                ByteArrayOutputStream foldMask = new ByteArrayOutputStream(encoded.length);
                boolean foldSupported = true;
                int offset = 0;
                while (offset < options.query().length()) {
                    int character = options.query().codePointAt(offset);
                    String segment = new String(Character.toChars(character));
                    byte[] segmentBytes = segment.getBytes(value);
                    segmented.writeBytes(segmentBytes);

                    boolean asciiLetter = (character >= 'A' && character <= 'Z')
                            || (character >= 'a' && character <= 'z');
                    if (asciiLetter
                            && (segmentBytes.length != 1 || segmentBytes[0] != (byte) character)) {
                        foldSupported = false;
                    }
                    for (int index = 0; index < segmentBytes.length; index++) {
                        foldMask.write(asciiLetter ? 1 : 0);
                    }
                    offset += Character.charCount(character);
                }

                byte[] segmentedBytes = segmented.toByteArray();
                if (!Arrays.equals(encoded, segmentedBytes)) {
                    foldSupported = false;
                }
                foldSupported = foldSupported && hasSafeAsciiByteBoundaries(value);
                return new EncodedNeedle(
                        encoded,
                        foldMask.toByteArray(),
                        foldSupported
                );
            });
        }

        private boolean hasSafeAsciiByteBoundaries(Charset charset) {
            return charset.equals(StandardCharsets.UTF_8)
                    || charset.equals(StandardCharsets.ISO_8859_1)
                    || charset.equals(Charset.forName("EUC-KR"));
        }

        private boolean isAscii(String value) {
            return value.chars().allMatch(character -> character <= 0x7f);
        }

        private boolean hasNoCaseVariants(String value) {
            return value.equals(value.toLowerCase(Locale.ROOT)) && value.equals(value.toUpperCase(Locale.ROOT));
        }

        private boolean hasOnlyAsciiCaseVariants(String value) {
            return value.codePoints().allMatch(character ->
                    character <= 0x7f
                            || (Character.toLowerCase(character) == character
                            && Character.toUpperCase(character) == character)
            );
        }
    }

    private record InterruptibleCharSequence(CharSequence delegate) implements CharSequence {
        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(delegate.subSequence(start, end));
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    private record EncodedNeedle(
            byte[] bytes,
            byte[] asciiFoldMask,
            boolean supportsAsciiFold
    ) {
        private static EncodedNeedle notEncodable() {
            return new EncodedNeedle(new byte[0], null, false);
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
}
