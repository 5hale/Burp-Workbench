package com.burpworkbench.modules.extractor;

interface ExportProgressListener {
    boolean isCancelled();

    void onProgress(ExportProgress progress);
}
