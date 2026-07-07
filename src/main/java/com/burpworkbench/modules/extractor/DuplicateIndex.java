package com.burpworkbench.modules.extractor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class DuplicateIndex {
    private final Map<String, ExportCandidate> firstByHash = new HashMap<>();

    public Optional<ExportCandidate> findDuplicate(ExportCandidate candidate) {
        if (!candidate.hasResponse()) {
            return Optional.empty();
        }

        ExportCandidate existing = firstByHash.get(candidate.bodySha256());
        if (existing == null) {
            firstByHash.put(candidate.bodySha256(), candidate);
            return Optional.empty();
        }
        return Optional.of(existing);
    }
}

