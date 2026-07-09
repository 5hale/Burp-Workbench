package com.burpworkbench.modules.extractor;

enum BeautifyType {
    JAVASCRIPT("js"),
    JSON("json");

    private final String label;

    BeautifyType(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
