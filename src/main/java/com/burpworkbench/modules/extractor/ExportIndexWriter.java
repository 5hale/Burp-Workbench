package com.burpworkbench.modules.extractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ExportIndexWriter {
    public void write(Path path, List<ExportIndexRow> rows) throws IOException {
        Files.writeString(path, toHtml(rows), StandardCharsets.UTF_8);
    }

    public String toHtml(List<ExportIndexRow> rows) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html><head><meta charset=\"utf-8\">\n");
        html.append("<title>Extractor Index</title>\n");
        html.append("<style>");
        html.append("body{font-family:Segoe UI,Arial,sans-serif;margin:24px;color:#1f2937}");
        html.append("table{border-collapse:collapse;width:100%;font-size:13px}");
        html.append("th,td{border:1px solid #d1d5db;padding:6px 8px;vertical-align:top}");
        html.append("th{background:#f3f4f6;text-align:left}");
        html.append("tr.saved{background:#f0fdf4}tr.duplicate{background:#fffbeb}tr.skipped{background:#f9fafb}tr.failed{background:#fef2f2}");
        html.append("code{font-family:Consolas,monospace;white-space:pre-wrap;word-break:break-all}");
        html.append("</style></head><body>\n");
        html.append("<h1>Extractor Index</h1>\n");
        html.append("<p>Total rows: ").append(rows == null ? 0 : rows.size()).append("</p>\n");
        html.append("<table><thead><tr>");
        html.append("<th>Action</th><th>Status</th><th>MIME</th><th>Size</th><th>Saved Path</th><th>Duplicate Source</th><th>URL</th><th>Reason</th>");
        html.append("</tr></thead><tbody>\n");
        if (rows != null) {
            for (ExportIndexRow row : rows) {
                appendRow(html, row);
            }
        }
        html.append("</tbody></table>\n</body></html>\n");
        return html.toString();
    }

    private void appendRow(StringBuilder html, ExportIndexRow row) {
        String action = row.action() == null ? "" : row.action();
        html.append("<tr class=\"").append(escapeAttribute(action.toLowerCase())).append("\">");
        html.append("<td>").append(escape(action)).append("</td>");
        html.append("<td>").append(row.statusCode()).append("</td>");
        html.append("<td>").append(escape(row.mimeCategory())).append("<br><code>").append(escape(row.contentType())).append("</code></td>");
        html.append("<td>").append(row.size()).append("</td>");
        html.append("<td>").append(pathLink(row.outputPath())).append("</td>");
        html.append("<td>");
        if (row.duplicateOfPath() != null && !row.duplicateOfPath().isBlank()) {
            html.append(pathLink(row.duplicateOfPath()));
            if (row.duplicateOfUrl() != null && !row.duplicateOfUrl().isBlank()) {
                html.append("<br><code>").append(escape(row.duplicateOfUrl())).append("</code>");
            }
        }
        html.append("</td>");
        html.append("<td><code>").append(escape(row.method())).append(" ").append(escape(row.url())).append("</code></td>");
        html.append("<td>").append(escape(row.reason())).append("</td>");
        html.append("</tr>\n");
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
}
