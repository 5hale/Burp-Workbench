package com.burpworkbench.modules.extractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import com.burpworkbench.core.filter.MimeCategory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaScriptBeautifierTest {
    private static final long TEST_OUTPUT_BUDGET = 1024 * 1024;

    @Test
    void beautifiesMinifiedJavascriptWithRhino() {
        String beautified = new JavaScriptBeautifier().beautify(
                "function x(){return 1+2;}",
                TEST_OUTPUT_BUDGET,
                () -> false
        );

        assertTrue(beautified.contains("function x() {"));
        assertTrue(beautified.contains("return 1 + 2;"));
    }

    @Test
    void beautifiesJson() {
        BodyBeautifier beautifier = new BodyBeautifier();

        String json = beautifier.beautify(
                BeautifyType.JSON,
                "{\"a\":1,\"b\":[2]}",
                TEST_OUTPUT_BUDGET,
                () -> false
        ).text();

        assertEquals("""
                {
                  "a": 1,
                  "b": [
                    2
                  ]
                }
                """, json);
    }

    @Test
    void deeplyNestedJsonStopsAtOutputBudget() {
        String source = "[".repeat(32) + "0" + "]".repeat(32);

        BeautifyLimitException exception = assertThrows(
                BeautifyLimitException.class,
                () -> new BodyBeautifier().beautify(BeautifyType.JSON, source, 256, () -> false)
        );

        assertTrue(exception.getMessage().contains("output exceeds budget of 256 bytes"));
    }

    @Test
    void jsonIndentationHonorsCancellation() {
        AtomicInteger probes = new AtomicInteger();
        String source = "[".repeat(100) + "0" + "]".repeat(100);

        assertThrows(
                CancellationException.class,
                () -> new BodyBeautifier().beautify(
                        BeautifyType.JSON,
                        source,
                        TEST_OUTPUT_BUDGET,
                        () -> probes.incrementAndGet() >= 20
                )
        );

        assertTrue(probes.get() >= 20);
    }

    @Test
    void javascriptRhinoExecutionHonorsCancellation() {
        AtomicInteger probes = new AtomicInteger();

        assertThrows(
                CancellationException.class,
                () -> new JavaScriptBeautifier().beautify(
                        "function x(){return 1+2;}",
                        TEST_OUTPUT_BUDGET,
                        () -> probes.incrementAndGet() >= 5
                )
        );

        assertTrue(probes.get() >= 5);
    }

    @Test
    void deeplyNestedJavascriptFailsConservativePreflightBudget() {
        String source = "{".repeat(24) + "x=1;" + "}".repeat(24);

        BeautifyLimitException exception = assertThrows(
                BeautifyLimitException.class,
                () -> new JavaScriptBeautifier().beautify(source, 512, () -> false)
        );

        assertTrue(exception.getMessage().contains("output budget of 512 bytes"));
    }

    @Test
    void jsonOutputBudgetFailureSavesDecodedOriginal(@TempDir Path tempDir) throws Exception {
        String original = "[".repeat(32) + "0" + "]".repeat(32);
        HttpRequestResponse exchange = exchange(
                "application/json",
                "https://example.com/data.json",
                original
        );

        ExportSummary summary = new ExportService(null, 256).export(
                java.util.List.of(exchange),
                false,
                tempDir,
                ExportOptions.defaults().withBeautify(true),
                new NoOpExportProgressListener()
        );

        Path savedPath = summary.outputDirectory().resolve("example.com").resolve("data.json");
        String manifest = Files.readString(
                summary.outputDirectory().resolve("extract_manifest.jsonl"),
                StandardCharsets.UTF_8
        );

        assertEquals(original, Files.readString(savedPath, StandardCharsets.UTF_8));
        assertTrue(manifest.contains("\"action\":\"beautify_failed\""));
        assertTrue(manifest.contains("output exceeds budget of 256 bytes"));
        assertTrue(summary.toSummaryFileText().contains("Beautify failed: 1"));
        assertEquals(1, summary.savedCount());
    }

    @Test
    void javascriptCandidateDetectionUsesMimeAndExtension() {
        assertTrue(ExportService.isJavascriptCandidate(candidate("text/javascript", Path.of("app.txt"))));
        assertTrue(ExportService.isJavascriptCandidate(candidate("application/octet-stream", Path.of("app.js"))));
        assertFalse(ExportService.isJavascriptCandidate(candidate("text/css", Path.of("app.css"))));
    }

    @Test
    void beautifyTypeDetectionUsesMimeAndExtension() {
        assertEquals(BeautifyType.JAVASCRIPT, ExportService.beautifyTypeFor(candidate("text/javascript", Path.of("app.txt"))));
        assertEquals(BeautifyType.JSON, ExportService.beautifyTypeFor(candidate("application/json", Path.of("data.bin"))));
        assertEquals(null, ExportService.beautifyTypeFor(candidate("application/problem+xml", Path.of("problem.bin"))));
        assertEquals(null, ExportService.beautifyTypeFor(candidate("text/plain", Path.of("index.html"))));
        assertEquals(null, ExportService.beautifyTypeFor(candidate("text/css", Path.of("style.css"))));
    }

    @Test
    void beautifySavesFormattedBodyAtOriginalPathOnly(@TempDir Path tempDir) throws Exception {
        HttpRequestResponse exchange = exchange(
                "text/javascript",
                "https://example.com/assets/app.js",
                "function x(){return 1+2;}"
        );

        ExportSummary summary = new ExportService(null).export(
                java.util.List.of(exchange),
                false,
                tempDir,
                ExportOptions.defaults().withBeautify(true),
                new NoOpExportProgressListener()
        );

        Path savedPath = summary.outputDirectory().resolve("example.com").resolve("assets").resolve("app.js");
        Path extraBeautifiedPath = summary.outputDirectory().resolve("example.com").resolve("assets").resolve("app.beautified.js");
        String saved = Files.readString(savedPath, StandardCharsets.UTF_8);
        String manifest = Files.readString(summary.outputDirectory().resolve("extract_manifest.jsonl"), StandardCharsets.UTF_8);

        assertTrue(saved.contains("function x() {"));
        assertFalse(Files.exists(extraBeautifiedPath));
        assertTrue(summary.toSummaryFileText().contains("Beautified files: 1"));
        assertTrue(manifest.contains("\"action\":\"saved\""));
        assertFalse(manifest.contains("\"action\":\"beautified\""));
    }

    private ExportCandidate candidate(String contentType, Path path) {
        return candidate(contentType, path, "body");
    }

    private ExportCandidate candidate(String contentType, Path path, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ExportCandidate(
                "GET",
                "https://example.com/" + path.getFileName(),
                true,
                200,
                contentType,
                "",
                "",
                path,
                MimeCategory.from(contentType, path),
                false,
                "test body",
                Hashes.sha256Hex(bytes),
                bytes.length,
                bytes.length
        );
    }

    private HttpRequestResponse exchange(String contentType, String url, String body) {
        HttpRequest request = proxy(HttpRequest.class, (method, arguments) -> switch (method.getName()) {
            case "method" -> "GET";
            case "url" -> url;
            default -> defaultValue(method.getReturnType());
        });
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        ByteArray byteArray = proxy(ByteArray.class, (method, arguments) -> switch (method.getName()) {
            case "getBytes" -> bodyBytes;
            case "length" -> bodyBytes.length;
            default -> defaultValue(method.getReturnType());
        });
        HttpResponse response = proxy(HttpResponse.class, (method, arguments) -> switch (method.getName()) {
            case "statusCode" -> (short) 200;
            case "headerValue" -> "Content-Type".equalsIgnoreCase((String) arguments[0])
                    ? contentType
                    : null;
            case "body" -> byteArray;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            case "hasResponse" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, ProxyHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> handler.invoke(
                        method,
                        arguments == null ? new Object[0] : arguments
                )
        );
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
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
        return null;
    }

    private interface ProxyHandler {
        Object invoke(java.lang.reflect.Method method, Object[] arguments);
    }
}
