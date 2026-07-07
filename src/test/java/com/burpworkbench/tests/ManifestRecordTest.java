package com.burpworkbench.tests;

import com.burpworkbench.modules.extractor.ExportCandidate;
import com.burpworkbench.modules.extractor.ManifestRecord;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestRecordTest {
    @Test
    void writesDuplicateRecordAsJsonLine() {
        ExportCandidate candidate = ExportCandidate.withBody(
                "GET",
                "https://example.com/b.js",
                200,
                "text/javascript",
                Path.of("example.com", "b.js"),
                "same".getBytes(StandardCharsets.UTF_8)
        );

        String json = ManifestRecord.duplicate(candidate, "example.com\\a.js", "https://example.com/a.js").toJsonLine();

        assertTrue(json.contains("\"action\":\"duplicate\""));
        assertTrue(json.contains("\"duplicateOfPath\":\"example.com\\\\a.js\""));
        assertTrue(json.contains("\"sha256\""));
    }

    @Test
    void writesCancelledRecordAsJsonLine() {
        String json = ManifestRecord.cancelled("cancelled by user", 3).toJsonLine();

        assertTrue(json.contains("\"action\":\"cancelled\""));
        assertTrue(json.contains("\"remaining\":3"));
    }
}

