package com.burpworkbench.modules.extractor;

public record ExportAction(
        ExportActionType type,
        ExportCandidate candidate,
        String reason,
        String duplicateOfPath,
        String duplicateOfUrl
) {
    public static ExportAction saved(ExportCandidate candidate) {
        return new ExportAction(ExportActionType.SAVED, candidate, "", "", "");
    }

    public static ExportAction skipped(ExportCandidate candidate, String reason) {
        return new ExportAction(ExportActionType.SKIPPED, candidate, reason, "", "");
    }

    public static ExportAction duplicate(ExportCandidate candidate, ExportCandidate original) {
        return new ExportAction(
                ExportActionType.DUPLICATE,
                candidate,
                "duplicate body sha256",
                original.relativePath().toString(),
                original.url()
        );
    }

    public static ExportAction failed(ExportCandidate candidate, String reason) {
        return new ExportAction(ExportActionType.FAILED, candidate, reason, "", "");
    }
}

