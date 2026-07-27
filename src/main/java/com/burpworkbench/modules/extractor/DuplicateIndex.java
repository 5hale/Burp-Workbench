package com.burpworkbench.modules.extractor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class DuplicateIndex {
    private final Map<String, DuplicateReference> firstByHash = new HashMap<>();

    Optional<DuplicateReference> find(String sha256) {
        return Optional.ofNullable(firstByHash.get(sha256));
    }

    void rememberFirst(String sha256, Path relativePath, String url) {
        firstByHash.putIfAbsent(
                sha256,
                new DuplicateReference(relativePath == null ? "" : relativePath.toString(), url == null ? "" : url)
        );
    }

    int size() {
        return firstByHash.size();
    }

    record DuplicateReference(String relativePath, String url) {
    }
}
