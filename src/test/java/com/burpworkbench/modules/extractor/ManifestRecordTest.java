package com.burpworkbench.modules.extractor;

import com.burpworkbench.core.filter.MimeCategory;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestRecordTest {
    @Test
    void writesDuplicateRecordAsJsonLine() {
        byte[] body = "same".getBytes(StandardCharsets.UTF_8);
        Path path = Path.of("example.com", "b.js");
        ExportCandidate candidate = new ExportCandidate(
                "GET",
                "https://example.com/b.js",
                true,
                200,
                "text/javascript",
                "",
                "",
                path,
                MimeCategory.from("text/javascript", path),
                false,
                "test body",
                Hashes.sha256Hex(body),
                body.length,
                body.length
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
