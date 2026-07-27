package com.burpworkbench.modules.extractor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class ManifestRecord {
    private final Map<String, Object> values;

    private ManifestRecord(Map<String, Object> values) {
        this.values = values;
    }

    public static ManifestRecord saved(
            String url,
            String method,
            int statusCode,
            String contentEncoding,
            String contentType,
            String mimeCategory,
            String outputPath,
            boolean decoded,
            long rawBytes,
            long savedBytes,
            String sha256,
            String note
    ) {
        Map<String, Object> values = base("saved", url, method, statusCode);
        values.put("contentEncoding", contentEncoding);
        values.put("contentType", contentType);
        values.put("mimeCategory", mimeCategory);
        values.put("outputPath", outputPath);
        values.put("decoded", decoded);
        values.put("rawBytes", rawBytes);
        values.put("savedBytes", savedBytes);
        values.put("sha256", sha256);
        values.put("note", note);
        return new ManifestRecord(values);
    }

    public static ManifestRecord skipped(ExportCandidate candidate, String reason) {
        Map<String, Object> values = base("skipped", candidate.url(), candidate.method(), candidate.statusCode());
        values.put("contentType", candidate.contentType());
        values.put("mimeCategory", candidate.mimeCategory().label());
        values.put("outputPath", candidate.relativePath().toString());
        values.put("rawBytes", candidate.rawByteCount());
        values.put("savedBytes", candidate.savedByteCount());
        values.put("sha256", candidate.bodySha256());
        values.put("reason", reason);
        return new ManifestRecord(values);
    }

    public static ManifestRecord duplicate(ExportCandidate candidate, String duplicateOfPath, String duplicateOfUrl) {
        Map<String, Object> values = base("duplicate", candidate.url(), candidate.method(), candidate.statusCode());
        values.put("contentType", candidate.contentType());
        values.put("mimeCategory", candidate.mimeCategory().label());
        values.put("outputPath", candidate.relativePath().toString());
        values.put("duplicateOfPath", duplicateOfPath);
        values.put("duplicateOfUrl", duplicateOfUrl);
        values.put("rawBytes", candidate.rawByteCount());
        values.put("savedBytes", candidate.savedByteCount());
        values.put("sha256", candidate.bodySha256());
        values.put("reason", "duplicate body sha256");
        return new ManifestRecord(values);
    }

    public static ManifestRecord failed(ExportCandidate candidate, String error) {
        Map<String, Object> values = base("failed", candidate.url(), candidate.method(), candidate.statusCode());
        values.put("contentEncoding", candidate.contentEncoding());
        values.put("contentType", candidate.contentType());
        values.put("mimeCategory", candidate.mimeCategory().label());
        values.put("outputPath", candidate.relativePath().toString());
        values.put("rawBytes", candidate.rawByteCount());
        values.put("savedBytes", candidate.savedByteCount());
        values.put("sha256", candidate.bodySha256());
        values.put("error", error);
        return new ManifestRecord(values);
    }

    public static ManifestRecord beautifyFailed(ExportCandidate candidate, String outputPath, String error) {
        Map<String, Object> values = base("beautify_failed", candidate.url(), candidate.method(), candidate.statusCode());
        values.put("contentEncoding", candidate.contentEncoding());
        values.put("contentType", candidate.contentType());
        values.put("mimeCategory", candidate.mimeCategory().label());
        values.put("outputPath", outputPath);
        values.put("rawBytes", candidate.rawByteCount());
        values.put("savedBytes", candidate.savedByteCount());
        values.put("sha256", candidate.bodySha256());
        values.put("error", error);
        return new ManifestRecord(values);
    }

    public static ManifestRecord cancelled(String reason, int remaining) {
        Map<String, Object> values = base("cancelled", "", "", -1);
        values.put("reason", reason);
        values.put("remaining", remaining);
        return new ManifestRecord(values);
    }

    public String toJsonLine() {
        return JsonLines.toJsonObject(values);
    }

    private static Map<String, Object> base(String action, String url, String method, int statusCode) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("timestamp", Instant.now().toString());
        values.put("action", action);
        values.put("method", method);
        values.put("url", url);
        values.put("statusCode", statusCode);
        return values;
    }
}
