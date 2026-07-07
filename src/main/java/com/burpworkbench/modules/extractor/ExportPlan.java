package com.burpworkbench.modules.extractor;

import java.util.List;

public record ExportPlan(int selectedCount, int candidateCount, List<ExportAction> actions) {
    public ExportPlan {
        actions = List.copyOf(actions);
    }

    public long count(ExportActionType type) {
        return actions.stream().filter(action -> action.type() == type).count();
    }

    public long filteredCount() {
        return actions.stream()
                .filter(action -> action.type() == ExportActionType.SKIPPED)
                .filter(action -> action.reason() != null && action.reason().startsWith("filtered"))
                .count();
    }

    public long skippedNoResponseCount() {
        return actions.stream()
                .filter(action -> action.type() == ExportActionType.SKIPPED)
                .filter(action -> "no response".equals(action.reason()))
                .count();
    }
}

