package com.burpworkbench.modules.extractor;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExportIndexWriter {
    private static final String HEADER_PREFIX = "<!doctype html>\n<html><head><meta charset=\"utf-8\">\n"
            + "<title>Extractor Index</title>\n"
            + "<style>"
            + "body{font-family:Segoe UI,Arial,sans-serif;margin:24px;color:#1f2937}"
            + "table{border-collapse:collapse;width:100%;font-size:13px}"
            + "th,td{border:1px solid #d1d5db;padding:6px 8px;vertical-align:top}"
            + "th{background:#f3f4f6;text-align:left}"
            + "tr.saved{background:#f0fdf4}tr.duplicate{background:#fffbeb}tr.skipped{background:#f9fafb}tr.failed,tr.beautify_failed{background:#fef2f2}"
            + "code{font-family:Consolas,monospace;white-space:pre-wrap;word-break:break-all}"
            + "</style></head><body>\n"
            + "<h1>Extractor Index</h1>\n"
            + "<p>Total rows: ";
    private static final String HEADER_SUFFIX = "</p>\n"
            + "<table><thead><tr>"
            + "<th>Action</th><th>Status</th><th>MIME</th><th>Size</th><th>Saved Path</th><th>Duplicate Source</th><th>URL</th><th>Reason</th>"
            + "</tr></thead><tbody>\n";
    private static final String FOOTER = "</tbody></table>\n</body></html>\n";
    private final WriterFactory writerFactory;

    ExportIndexWriter() {
        this(path -> Files.newBufferedWriter(path, StandardCharsets.UTF_8));
    }

    ExportIndexWriter(WriterFactory writerFactory) {
        this.writerFactory = writerFactory;
    }

    public Sink open(Path path) throws IOException {
        return new Sink(path);
    }

    final class Sink implements Closeable {
        private final Path target;
        private final Path rowSpool;
        private final BufferedWriter rowWriter;
        private long rowCount;
        private boolean closed;

        private Sink(Path target) throws IOException {
            this.target = target;
            Path createdRowSpool = AtomicFiles.createTempSibling(target);
            BufferedWriter openedRowWriter;
            try {
                openedRowWriter = writerFactory.open(createdRowSpool);
            } catch (IOException | RuntimeException | Error failure) {
                AtomicFiles.cleanupTemporary(createdRowSpool, failure);
                throw failure;
            }
            this.rowSpool = createdRowSpool;
            this.rowWriter = openedRowWriter;
        }

        public void write(ExportIndexRow row) throws IOException {
            if (closed) {
                throw new IOException("index sink is closed");
            }
            boolean restoreInterrupt = Thread.interrupted();
            try {
                writeRow(rowWriter, row);
                rowCount++;
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            boolean restoreInterrupt = Thread.interrupted();
            try {
                Path outputTemporary = null;
                boolean completed = false;
                Throwable primaryFailure = null;
                try {
                    rowWriter.close();
                    outputTemporary = AtomicFiles.createTempSibling(target);
                    try (OutputStream output = Files.newOutputStream(outputTemporary)) {
                        output.write((HEADER_PREFIX + rowCount + HEADER_SUFFIX).getBytes(StandardCharsets.UTF_8));
                        Files.copy(rowSpool, output);
                        output.write(FOOTER.getBytes(StandardCharsets.UTF_8));
                    }
                    AtomicFiles.moveIntoPlace(outputTemporary, target);
                    completed = true;
                } catch (IOException | RuntimeException | Error failure) {
                    primaryFailure = failure;
                    throw failure;
                } finally {
                    AtomicFiles.cleanupTemporary(rowSpool, primaryFailure);
                    if (!completed && outputTemporary != null) {
                        AtomicFiles.cleanupTemporary(outputTemporary, primaryFailure);
                    }
                }
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void writeRow(Writer html, ExportIndexRow row) throws IOException {
        String action = row.action() == null ? "" : row.action();
        html.write("<tr class=\"");
        html.write(escapeAttribute(action.toLowerCase()));
        html.write("\"><td>");
        html.write(escape(action));
        html.write("</td><td>");
        html.write(Integer.toString(row.statusCode()));
        html.write("</td><td>");
        html.write(escape(row.mimeCategory()));
        html.write("<br><code>");
        html.write(escape(row.contentType()));
        html.write("</code></td><td>");
        html.write(Long.toString(row.size()));
        html.write("</td><td>");
        html.write(pathLink(row.outputPath()));
        html.write("</td><td>");
        if (row.duplicateOfPath() != null && !row.duplicateOfPath().isBlank()) {
            html.write(pathLink(row.duplicateOfPath()));
            if (row.duplicateOfUrl() != null && !row.duplicateOfUrl().isBlank()) {
                html.write("<br><code>");
                html.write(escape(row.duplicateOfUrl()));
                html.write("</code>");
            }
        }
        html.write("</td><td><code>");
        html.write(escape(row.method()));
        html.write(" ");
        html.write(escape(row.url()));
        html.write("</code></td><td>");
        html.write(escape(row.reason()));
        html.write("</td></tr>\n");
    }

    private String pathLink(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String href = path.replace('\\', '/').replace(" ", "%20");
        return "<a href=\"" + escapeAttribute(href) + "\"><code>" + escape(path) + "</code></a>";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String escapeAttribute(String value) {
        return escape(value).replace("'", "&#39;");
    }

    @FunctionalInterface
    interface WriterFactory {
        BufferedWriter open(Path path) throws IOException;
    }
}
