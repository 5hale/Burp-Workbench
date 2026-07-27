package com.burpworkbench.modules.search;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RepeaterCache {
    private final Map<String, HttpRequestResponse> entries = new LinkedHashMap<>();

    public synchronized void add(HttpRequestResponse item) {
        if (item != null) {
            entries.put(deduplicationKey(item), item);
        }
    }

    public synchronized List<HttpRequestResponse> snapshot() {
        return new ArrayList<>(entries.values());
    }

    public synchronized void clear() {
        entries.clear();
    }

    private String deduplicationKey(HttpRequestResponse item) {
        try {
            return item.request().method() + " " + item.request().url();
        } catch (RuntimeException exception) {
            return "malformed-" + System.identityHashCode(item);
        }
    }
}
