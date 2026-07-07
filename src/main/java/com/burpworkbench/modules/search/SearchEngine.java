package com.burpworkbench.modules.search;

import com.burpworkbench.core.http.HttpExchange;
import com.burpworkbench.core.filter.MimeCategory;

import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SearchEngine {
    public List<SearchResult> search(List<HttpExchange> exchanges, SearchOptions options) {
        if (exchanges == null || exchanges.isEmpty()) {
            return List.of();
        }

        SearchOptions safeOptions = safeOptions(options);

        List<SearchResult> results = new ArrayList<>();
        for (HttpExchange exchange : exchanges) {
            if (matches(exchange, safeOptions)) {
                results.add(toResult(exchange));
            }
        }
        return results;
    }

    public boolean matches(HttpExchange exchange, SearchOptions options) {
        if (exchange == null) {
            return false;
        }
        SearchOptions safeOptions = safeOptions(options);
        return matchesFilters(exchange, safeOptions) && matchesQuery(exchange, safeOptions);
    }

    public SearchResult toResult(HttpExchange exchange) {
        return new SearchResult(exchange, mimeLabel(exchange), responseLength(exchange));
    }

    private SearchOptions safeOptions(SearchOptions options) {
        return options == null
                ? new SearchOptions("", SearchMode.TEXT, false, false, false, true, true, true, true,
                true, false, false, false, true, Set.of(), Set.of(), Set.of())
                : options;
    }

    boolean matchesFilters(HttpExchange exchange, SearchOptions options) {
        int statusCode = exchange.statusCode();
        if (!options.allStatus() && !matchesCustomStatus(statusCode, options.statusPatterns())) {
            return false;
        }

        if (options.mimeFilterEnabled() && options.mimeCategories().isEmpty()) {
            return false;
        }
        if (!options.mimeCategories().isEmpty()) {
            if (!options.mimeCategories().contains(mimeCategory(exchange))) {
                return false;
            }
        }

        String extension = extension(exchange);
        if (!options.extensions().isEmpty()) {
            if (!options.extensions().contains(extension)) {
                return false;
            }
        }
        if (!options.excludedExtensions().isEmpty() && options.excludedExtensions().contains(extension)) {
            return false;
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

    private boolean matchesQuery(HttpExchange exchange, SearchOptions options) {
        if (options.query().isBlank()) {
            return !options.negativeMatch();
        }

        boolean matched = switch (options.mode()) {
            case HEX -> matchesHex(exchange, options);
            case TEXT -> matchesText(exchange, options);
        };
        return options.negativeMatch() ? !matched : matched;
    }

    private boolean matchesHex(HttpExchange exchange, SearchOptions options) {
        byte[] needle = parseHex(options.query());
        if (needle.length == 0) {
            return false;
        }

        for (MessagePart part : selectedParts(exchange, options)) {
            if (containsBytes(part.bytes(), needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesText(HttpExchange exchange, SearchOptions options) {
        Pattern pattern = null;
        if (options.regex()) {
            try {
                pattern = Pattern.compile(options.query(), options.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (PatternSyntaxException exception) {
                return false;
            }
        }

        for (MessagePart part : selectedParts(exchange, options)) {
            for (Charset charset : charsets(part.message())) {
                String text = new String(part.bytes(), charset);
                if (options.regex()) {
                    if (pattern.matcher(text).find()) {
                        return true;
                    }
                } else if (containsText(text, options.query(), options.caseSensitive())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<MessagePart> selectedParts(HttpExchange exchange, SearchOptions options) {
        HttpRequestResponse requestResponse = exchange.requestResponse();
        List<MessagePart> parts = new ArrayList<>();

        try {
            if (options.requestHeaders()) {
                parts.add(new MessagePart(requestResponse.request(), headersBytes(requestResponse.request())));
            }
            if (options.requestBody()) {
                parts.add(new MessagePart(requestResponse.request(), requestResponse.request().body().getBytes()));
            }
            if (requestResponse.hasResponse() && requestResponse.response() != null) {
                if (options.responseHeaders()) {
                    parts.add(new MessagePart(requestResponse.response(), headersBytes(requestResponse.response())));
                }
                if (options.responseBody()) {
                    parts.add(new MessagePart(requestResponse.response(), requestResponse.response().body().getBytes()));
                }
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }

        return parts;
    }

    private byte[] headersBytes(HttpMessage message) {
        byte[] all = message.toByteArray().getBytes();
        int bodyOffset = Math.max(0, Math.min(message.bodyOffset(), all.length));
        byte[] headers = new byte[bodyOffset];
        System.arraycopy(all, 0, headers, 0, bodyOffset);
        return headers;
    }

    private boolean containsText(String text, String query, boolean caseSensitive) {
        if (caseSensitive) {
            return text.contains(query);
        }
        return text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private boolean containsBytes(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean matched = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
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
        MimeCategory category = MimeCategory.from(contentType(exchange), Path.of(fileName(exchange)));
        return category == MimeCategory.FONT || category == MimeCategory.ARCHIVE ? MimeCategory.OTHER : category;
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

    private record MessagePart(HttpMessage message, byte[] bytes) {
    }
}
