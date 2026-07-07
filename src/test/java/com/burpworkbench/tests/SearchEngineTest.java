package com.burpworkbench.tests;

import com.burpworkbench.modules.extractor.ExportOptions;
import com.burpworkbench.core.http.HttpExchange;
import com.burpworkbench.core.filter.MimeCategory;
import com.burpworkbench.modules.search.SearchEngine;
import com.burpworkbench.modules.search.SearchMode;
import com.burpworkbench.modules.search.SearchOptions;
import com.burpworkbench.modules.search.SearchResult;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchEngineTest {
    private final SearchEngine engine = new SearchEngine();

    @Test
    void searchesUtf8KoreanText() {
        List<HttpExchange> exchanges = List.of(exchange(response("text/plain; charset=utf-8", "한글 검색".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, engine.search(exchanges, options("한글", SearchMode.TEXT)).size());
    }

    @Test
    void searchesMs949KoreanTextFallback() {
        List<HttpExchange> exchanges = List.of(exchange(response("text/plain", "한글 검색".getBytes(Charset.forName("MS949")))));

        assertEquals(1, engine.search(exchanges, options("한글", SearchMode.TEXT)).size());
    }

    @Test
    void searchesHexBytes() {
        List<HttpExchange> exchanges = List.of(exchange(response("application/octet-stream", new byte[]{0x41, 0x42, 0x43, 0x44})));

        assertEquals(1, engine.search(exchanges, options("42 43", SearchMode.HEX)).size());
    }

    @Test
    void supportsNegativeMatch() {
        List<HttpExchange> exchanges = List.of(exchange(response("text/plain; charset=utf-8", "hello".getBytes(StandardCharsets.UTF_8))));
        SearchOptions options = new SearchOptions(
                "missing",
                SearchMode.TEXT,
                false,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                Set.of()
        );

        assertEquals(1, engine.search(exchanges, options).size());
    }

    @Test
    void customStatusPatternsIncludeExactAndFamilies() {
        List<HttpExchange> exchanges = List.of(
                exchange("https://example.com/ok.txt", response(200, "text/plain", bytes("ok"))),
                exchange("https://example.com/redirect.txt", response(301, "text/plain", bytes("redirect"))),
                exchange("https://example.com/not-found.txt", response(404, "text/plain", bytes("not-found"))),
                exchange("https://example.com/error.txt", response(500, "text/plain", bytes("error")))
        );

        SearchOptions options = new SearchOptions(
                "",
                SearchMode.TEXT,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                Set.of("200", "404", "3xx"),
                Set.of(),
                Set.of()
        );

        assertEquals(3, engine.search(exchanges, options).size());
    }

    @Test
    void rejectsInvalidStatusPattern() {
        assertThrows(IllegalArgumentException.class, () -> new SearchOptions(
                "",
                SearchMode.TEXT,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                Set.of("20x"),
                Set.of(),
                Set.of()
        ));
    }

    @Test
    void appliesMimeAndExtensionFilters() {
        List<HttpExchange> exchanges = List.of(
                exchange("https://example.com/a.jpg", response(200, "image/jpeg", bytes("jpg"))),
                exchange("https://example.com/b.png", response(200, "image/png", bytes("png"))),
                exchange("https://example.com/c.js", response(200, "text/javascript", bytes("js")))
        );

        SearchOptions imageJpg = options("", SearchMode.TEXT, Set.of(MimeCategory.IMAGE), Set.of(".jpg"));
        SearchOptions pngOnly = options("", SearchMode.TEXT, Set.of(), Set.of(".png"));
        SearchOptions everything = options("", SearchMode.TEXT, Set.of(), Set.of());

        assertEquals(1, engine.search(exchanges, imageJpg).size());
        assertEquals(1, engine.search(exchanges, pngOnly).size());
        assertEquals(3, engine.search(exchanges, everything).size());
    }

    @Test
    void extensionShowOnlyAndHideApplyIncludeThenExclude() {
        List<HttpExchange> exchanges = List.of(
                exchange("https://example.com/a.jpg", response(200, "image/jpeg", bytes("jpg"))),
                exchange("https://example.com/b.png", response(200, "image/png", bytes("png"))),
                exchange("https://example.com/c.svg", response(200, "image/svg+xml", bytes("svg"))),
                exchange("https://example.com/d.css", response(200, "text/css", bytes("css")))
        );

        SearchOptions options = uiFilterOptions(
                true,
                Set.of(),
                false,
                Set.of(),
                ExportOptions.parseExtensions("jpg,png,svg"),
                ExportOptions.parseExtensions("svg")
        );

        List<SearchResult> results = engine.search(exchanges, options);

        assertEquals(List.of(
                "https://example.com/a.jpg",
                "https://example.com/b.png"
        ), results.stream().map(result -> result.exchange().url()).toList());
    }

    @Test
    void uiMimeDefaultsExcludeImageAndCss() {
        List<HttpExchange> exchanges = List.of(
                exchange("https://example.com/index.html", response(200, "text/html", bytes("html"))),
                exchange("https://example.com/app.js", response(200, "text/javascript", bytes("js"))),
                exchange("https://example.com/style.css", response(200, "text/css", bytes("css"))),
                exchange("https://example.com/logo.png", response(200, "image/png", bytes("png"))),
                exchange("https://example.com/data.json", response(200, "application/json", bytes("json"))),
                exchange("https://example.com/readme.txt", response(200, "text/plain", bytes("text"))),
                exchange("https://example.com/download.bin", response(200, "application/octet-stream", bytes("other")))
        );

        SearchOptions options = uiFilterOptions(
                true,
                Set.of(),
                true,
                Set.of(MimeCategory.HTML, MimeCategory.JAVASCRIPT, MimeCategory.JSON, MimeCategory.TEXT, MimeCategory.OTHER, MimeCategory.FONT, MimeCategory.ARCHIVE),
                Set.of(),
                Set.of()
        );

        assertEquals(List.of("html", "script", "json", "text", "other"),
                engine.search(exchanges, options).stream().map(SearchResult::mime).toList());
    }

    @Test
    void uiMimeOtherIncludesFontAndArchive() {
        List<HttpExchange> exchanges = List.of(
                exchange("https://example.com/font.woff2", response(200, "font/woff2", bytes("font"))),
                exchange("https://example.com/archive.zip", response(200, "application/zip", bytes("zip"))),
                exchange("https://example.com/logo.png", response(200, "image/png", bytes("png")))
        );

        SearchOptions options = uiFilterOptions(
                true,
                Set.of(),
                true,
                Set.of(MimeCategory.OTHER, MimeCategory.FONT, MimeCategory.ARCHIVE),
                Set.of(),
                Set.of()
        );

        assertEquals(2, engine.search(exchanges, options).size());
    }

    @Test
    void uiMimeEmptySelectionMatchesNothing() {
        List<HttpExchange> exchanges = List.of(
                exchange("https://example.com/index.html", response(200, "text/html", bytes("html"))),
                exchange("https://example.com/logo.png", response(200, "image/png", bytes("png")))
        );

        SearchOptions options = uiFilterOptions(true, Set.of(), true, Set.of(), Set.of(), Set.of());

        assertEquals(0, engine.search(exchanges, options).size());
    }

    @Test
    void statusFamilyFilterMatchesSelectedFamiliesOnly() {
        List<HttpExchange> exchanges = List.of(
                exchange("https://example.com/ok", response(200, "text/plain", bytes("ok"))),
                exchange("https://example.com/redirect", response(302, "text/plain", bytes("redirect"))),
                exchange("https://example.com/missing", response(404, "text/plain", bytes("missing"))),
                exchange("https://example.com/error", response(500, "text/plain", bytes("error")))
        );

        SearchOptions twoAndFour = uiFilterOptions(false, Set.of("2xx", "4xx"), false, Set.of(), Set.of(), Set.of());
        SearchOptions none = uiFilterOptions(false, Set.of(), false, Set.of(), Set.of(), Set.of());

        assertEquals(List.of(200, 404), engine.search(exchanges, twoAndFour).stream()
                .map(result -> result.exchange().statusCode())
                .toList());
        assertEquals(0, engine.search(exchanges, none).size());
    }

    @Test
    void javascriptMimeFilterExcludesOtherCategory() {
        List<HttpExchange> exchanges = List.of(
                exchange("https://example.com/app.js", response(200, "text/javascript", bytes("js"))),
                exchange("https://example.com/download.bin", response(200, "application/octet-stream", bytes("binary")))
        );

        SearchOptions javascriptOnly = options("", SearchMode.TEXT, Set.of(MimeCategory.JAVASCRIPT), Set.of());
        List<SearchResult> results = engine.search(exchanges, javascriptOnly);

        assertEquals(1, results.size());
        assertEquals("script", results.get(0).mime());
    }

    @Test
    void fontAndArchiveAreGroupedAsOtherForSearchPlus() {
        List<HttpExchange> exchanges = List.of(
                exchange("https://example.com/app.woff2", response(200, "font/woff2", bytes("font"))),
                exchange("https://example.com/archive.zip", response(200, "application/zip", bytes("zip")))
        );

        SearchOptions otherOnly = options("", SearchMode.TEXT, Set.of(MimeCategory.OTHER), Set.of());
        List<SearchResult> results = engine.search(exchanges, otherOnly);

        assertEquals(2, results.size());
        assertEquals(List.of("other", "other"), results.stream().map(SearchResult::mime).toList());
    }

    @Test
    void malformedUrlDoesNotAbortSearch() {
        List<HttpExchange> exchanges = List.of(
                exchange("z5N6o6SqQ_yxbzUGR-eUgw==:HbTYZKT3jDOIR6OGjTmFkXe", response(200, "text/plain", bytes("needle")))
        );

        assertEquals(1, engine.search(exchanges, options("needle", SearchMode.TEXT)).size());
    }

    private SearchOptions options(String query, SearchMode mode) {
        return new SearchOptions(
                query,
                mode,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private HttpExchange exchange(HttpResponse response) {
        return exchange("https://example.com/", response);
    }

    private HttpExchange exchange(String url, HttpResponse response) {
        HttpRequest request = request(url);
        HttpService service = proxy(HttpService.class, (method, args) -> switch (method.getName()) {
            case "host" -> "example.com";
            case "port" -> 443;
            case "secure" -> true;
            default -> defaultValue(method.getReturnType());
        });
        HttpRequestResponse requestResponse = proxy(HttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            case "hasResponse" -> true;
            case "httpService" -> service;
            default -> defaultValue(method.getReturnType());
        });
        return new HttpExchange("test", requestResponse, null);
    }

    private HttpResponse response(String contentType, byte[] body) {
        return response(200, contentType, body);
    }

    private HttpResponse response(int statusCode, String contentType, byte[] body) {
        byte[] header = ("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = new byte[header.length + body.length];
        System.arraycopy(header, 0, raw, 0, header.length);
        System.arraycopy(body, 0, raw, header.length, body.length);
        return proxy(HttpResponse.class, (method, args) -> switch (method.getName()) {
            case "body" -> byteArray(body);
            case "toByteArray" -> byteArray(raw);
            case "bodyOffset" -> header.length;
            case "headerValue" -> "Content-Type".equalsIgnoreCase(String.valueOf(args[0])) ? contentType : "";
            case "statusCode" -> (short) statusCode;
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequest request() {
        return request("https://example.com/");
    }

    private HttpRequest request(String url) {
        byte[] header = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        HttpService service = proxy(HttpService.class, (method, args) -> switch (method.getName()) {
            case "host" -> "example.com";
            case "port" -> 443;
            case "secure" -> true;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequest.class, (method, args) -> switch (method.getName()) {
            case "method" -> "GET";
            case "url" -> url;
            case "httpService" -> service;
            case "body" -> byteArray(new byte[0]);
            case "toByteArray" -> byteArray(header);
            case "bodyOffset" -> header.length;
            case "headerValue" -> "";
            default -> defaultValue(method.getReturnType());
        });
    }

    private SearchOptions options(String query, SearchMode mode, Set<MimeCategory> mimeCategories, Set<String> extensions) {
        return new SearchOptions(
                query,
                mode,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                true,
                Set.of(),
                mimeCategories,
                extensions
        );
    }

    private SearchOptions uiFilterOptions(
            boolean allStatus,
            Set<String> statusPatterns,
            boolean mimeFilterEnabled,
            Set<MimeCategory> mimeCategories,
            Set<String> extensions,
            Set<String> excludedExtensions
    ) {
        return new SearchOptions(
                "",
                SearchMode.TEXT,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                allStatus,
                statusPatterns,
                mimeFilterEnabled,
                mimeCategories,
                extensions,
                excludedExtensions
        );
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private ByteArray byteArray(byte[] bytes) {
        return proxy(ByteArray.class, (method, args) -> switch (method.getName()) {
            case "getBytes" -> bytes;
            case "length" -> bytes.length;
            case "toString" -> new String(bytes, StandardCharsets.ISO_8859_1);
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, ProxyHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(method, args == null ? new Object[0] : args)
        );
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private interface ProxyHandler {
        Object invoke(java.lang.reflect.Method method, Object[] args);
    }
}
