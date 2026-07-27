package com.burpworkbench.modules.extractor;

record ExportIndexRow(
        String action,
        String method,
        String url,
        int statusCode,
        String mimeCategory,
        String contentType,
        long size,
        String outputPath,
        String duplicateOfPath,
        String duplicateOfUrl,
        String reason
) {
}
