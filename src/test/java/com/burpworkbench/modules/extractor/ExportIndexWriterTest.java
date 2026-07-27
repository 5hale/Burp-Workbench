package com.burpworkbench.modules.extractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportIndexWriterTest {
    @Test
    void escapesHtmlAndLinksSavedPath(@TempDir Path tempDir) throws Exception {
        ExportIndexWriter writer = new ExportIndexWriter();
        Path output = tempDir.resolve("extract_index.html");
        try (ExportIndexWriter.Sink sink = writer.open(output)) {
            sink.write(new ExportIndexRow(
                    "saved",
                    "GET",
                    "https://example.com/a?<x>",
                    200,
                    "html",
                    "text/html",
                    12,
                    "example.com\\a b.html",
                    "",
                    "",
                    "decoded <ok>"
            ));
        }
        String html = Files.readString(output);

        assertTrue(html.contains("<p>Total rows: 1</p>"));
        assertTrue(html.contains("href=\"example.com/a%20b.html\""));
        assertTrue(html.contains("https://example.com/a?&lt;x&gt;"));
        assertTrue(html.contains("decoded &lt;ok&gt;"));
    }

    @Test
    void streamingWriterProducesExpectedOutputAndCleansSpool(@TempDir Path tempDir) throws Exception {
        ExportIndexWriter writer = new ExportIndexWriter();
        List<ExportIndexRow> rows = List.of(
                new ExportIndexRow(
                        "saved", "GET", "https://example.com/a", 200, "text", "text/plain",
                        1, "example.com\\a.txt", "", "", "saved"
                ),
                new ExportIndexRow(
                        "duplicate", "GET", "https://example.com/b", 200, "text", "text/plain",
                        1, "", "example.com\\a.txt", "https://example.com/a", "duplicate body sha256"
                )
        );
        Path output = tempDir.resolve("extract_index.html");

        try (ExportIndexWriter.Sink sink = writer.open(output)) {
            for (ExportIndexRow row : rows) {
                sink.write(row);
            }
        }

        String html = Files.readString(output);
        assertTrue(html.contains("<p>Total rows: 2</p>"));
        assertTrue(html.contains("<tr class=\"saved\">"));
        assertTrue(html.contains("<tr class=\"duplicate\">"));
        assertTrue(html.indexOf("https://example.com/a") < html.indexOf("https://example.com/b"));
        assertTrue(html.endsWith("</tbody></table>\n</body></html>\n"));
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of("extract_index.html"), files
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList());
        }
    }

    @Test
    void manifestWriterOpenFailureRemovesOwnedTemporaryFile(@TempDir Path tempDir) {
        IOException expected = new IOException("synthetic writer-open failure");

        IOException actual = assertThrows(
                IOException.class,
                () -> new ManifestWriter(
                        tempDir.resolve("extract_manifest.jsonl"),
                        path -> {
                            throw expected;
                        }
                )
        );

        assertSame(expected, actual);
        assertDirectoryEmpty(tempDir);
    }

    @Test
    void indexWriterOpenFailureRemovesOwnedRowSpool(@TempDir Path tempDir) {
        IOException expected = new IOException("synthetic writer-open failure");
        ExportIndexWriter writer = new ExportIndexWriter(path -> {
            throw expected;
        });

        IOException actual = assertThrows(
                IOException.class,
                () -> writer.open(tempDir.resolve("extract_index.html"))
        );

        assertSame(expected, actual);
        assertDirectoryEmpty(tempDir);
    }

    private void assertDirectoryEmpty(Path directory) {
        try (var paths = Files.list(directory)) {
            assertEquals(0, paths.count());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
