package com.burpworkbench.modules.extractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ExportPlanner {
    public ExportPlan plan(List<ExportCandidate> candidates, ExportOptions options) {
        return plan(candidates, options, candidates == null ? 0 : candidates.size());
    }

    public ExportPlan plan(List<ExportCandidate> candidates, ExportOptions options, int selectedCount) {
        List<ExportCandidate> safeCandidates = candidates == null ? List.of() : candidates;
        ExportOptions safeOptions = options == null ? ExportOptions.defaults() : options;
        DuplicateIndex duplicateIndex = new DuplicateIndex();
        List<ExportAction> actions = new ArrayList<>();

        for (ExportCandidate candidate : safeCandidates) {
            if (!safeOptions.matchesFilter(candidate)) {
                continue;
            }

            if (!candidate.hasResponse()) {
                actions.add(ExportAction.skipped(candidate, "no response"));
                continue;
            }

            Optional<ExportCandidate> duplicateOf = duplicateIndex.findDuplicate(candidate);
            if (duplicateOf.isPresent()) {
                actions.add(ExportAction.duplicate(candidate, duplicateOf.get()));
            } else {
                actions.add(ExportAction.saved(candidate));
            }
        }

        return new ExportPlan(selectedCount, actions.size(), actions);
    }
}
