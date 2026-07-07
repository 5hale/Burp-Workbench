package com.burpworkbench.modules.extractor;

public interface ExportProgressListener {
    boolean isCancelled();

    void onProgress(ExportProgress progress);
}

