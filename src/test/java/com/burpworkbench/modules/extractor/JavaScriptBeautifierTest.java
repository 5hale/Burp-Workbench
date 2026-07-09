package com.burpworkbench.modules.extractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaScriptBeautifierTest {
    @Test
    void beautifiesMinifiedJavascriptWithRhino() {
        String beautified = new JavaScriptBeautifier().beautify("function x(){return 1+2;}");

        assertTrue(beautified.contains("function x() {"));
        assertTrue(beautified.contains("return 1 + 2;"));
    }

    @Test
    void beautifiesJson() {
        BodyBeautifier beautifier = new BodyBeautifier();

        String json = beautifier.beautify(BeautifyType.JSON, "{\"a\":1,\"b\":[2]}").text();

        assertTrue(json.contains("\"a\": 1"));
        assertTrue(json.contains("\n  \"b\""));
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
        ExportCandidate candidate = candidate(
                "text/javascript",
                Path.of("example.com", "assets", "app.js"),
                "function x(){return 1+2;}"
        );
        ExportPlan plan = new ExportPlan(1, 1, List.of(ExportAction.saved(candidate)));

        ExportSummary summary = new ExportService(null).executePlan(
                plan,
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
        return ExportCandidate.withBody(
                "GET",
                "https://example.com/" + path.getFileName(),
                200,
                contentType,
                path,
                body.getBytes(StandardCharsets.UTF_8)
        );
    }
}
