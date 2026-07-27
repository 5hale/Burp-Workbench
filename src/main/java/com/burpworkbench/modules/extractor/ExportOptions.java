package com.burpworkbench.modules.extractor;

record ExportOptions(boolean beautify) {
    public static ExportOptions defaults() {
        return new ExportOptions(false);
    }

    public ExportOptions withBeautify(boolean enabled) {
        return new ExportOptions(enabled);
    }
}
