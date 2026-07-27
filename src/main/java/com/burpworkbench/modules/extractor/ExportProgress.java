package com.burpworkbench.modules.extractor;

record ExportProgress(
        int processed,
        int total,
        String currentUrl,
        int saved,
        int skipped,
        int duplicate,
        int failed,
        boolean cancelled
) {
}
