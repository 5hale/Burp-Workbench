package com.burpworkbench.modules.extractor;

final class NoOpExportProgressListener implements ExportProgressListener {
    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void onProgress(ExportProgress progress) {
    }
}
