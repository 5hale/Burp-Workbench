package com.burpworkbench.modules.extractor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ExportSummary {
    private final Path outputDirectory;
    private final int selectedCount;
    private final List<String> errors = new ArrayList<>();
    private int candidateCount;
    private int savedCount;
    private int skippedNoResponseCount;
    private int filteredCount;
    private int duplicateCount;
    private int failedCount;
    private int beautifiedCount;
    private int beautifyFailedCount;
    private int cancelledRemainingCount;
    private boolean cancelled;
    private long rawBytes;
    private long savedBytes;
    private long beautifiedBytes;

    public ExportSummary(Path outputDirectory, int selectedCount) {
        this.outputDirectory = outputDirectory;
        this.selectedCount = selectedCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public void incrementSaved(long rawByteCount, long savedByteCount) {
        savedCount++;
        rawBytes += rawByteCount;
        savedBytes += savedByteCount;
    }

    public void incrementSkippedNoResponse() {
        skippedNoResponseCount++;
    }

    public void incrementFiltered() {
        filteredCount++;
    }

    public void incrementDuplicate() {
        duplicateCount++;
    }

    public void incrementFailed(String error) {
        failedCount++;
        if (error != null && !error.isBlank() && errors.size() < 10) {
            errors.add(error);
        }
    }

    public void incrementBeautified(long byteCount) {
        beautifiedCount++;
        beautifiedBytes += Math.max(byteCount, 0);
    }

    public void incrementBeautifiedJavascript(long byteCount) {
        incrementBeautified(byteCount);
    }

    public void incrementBeautifyFailed(String error) {
        beautifyFailedCount++;
        if (error != null && !error.isBlank() && errors.size() < 10) {
            errors.add(error);
        }
    }

    public void markCancelled(int remainingCount) {
        cancelled = true;
        cancelledRemainingCount = Math.max(remainingCount, 0);
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public int savedCount() {
        return savedCount;
    }

    public int skippedCount() {
        return skippedNoResponseCount + filteredCount;
    }

    public int duplicateCount() {
        return duplicateCount;
    }

    public int failedCount() {
        return failedCount;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public String toDialogMessage() {
        return "Saved: " + savedCount
                + "\nSkipped without response: " + skippedNoResponseCount
                + "\nDuplicate: " + duplicateCount
                + "\nFailed: " + failedCount
                + "\nBeautified files: " + beautifiedCount
                + "\nBeautify failed: " + beautifyFailedCount
                + "\nCancelled: " + cancelled
                + "\n\nOutput:\n" + outputDirectory;
    }

    public String toLogMessage() {
        return "Extractor completed. selected=" + selectedCount
                + ", candidates=" + candidateCount
                + ", saved=" + savedCount
                + ", skippedNoResponse=" + skippedNoResponseCount
                + ", duplicate=" + duplicateCount
                + ", failed=" + failedCount
                + ", beautified=" + beautifiedCount
                + ", beautifyFailed=" + beautifyFailedCount
                + ", cancelled=" + cancelled
                + ", output=" + outputDirectory;
    }

    public String toSummaryFileText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Extractor Summary\n");
        builder.append("=================\n");
        builder.append("Output directory: ").append(outputDirectory).append('\n');
        builder.append("Selected items: ").append(selectedCount).append('\n');
        builder.append("Candidate items: ").append(candidateCount).append('\n');
        builder.append("Saved: ").append(savedCount).append('\n');
        builder.append("Skipped without response: ").append(skippedNoResponseCount).append('\n');
        builder.append("Duplicate: ").append(duplicateCount).append('\n');
        builder.append("Failed: ").append(failedCount).append('\n');
        builder.append("Beautified files: ").append(beautifiedCount).append('\n');
        builder.append("Beautify failed: ").append(beautifyFailedCount).append('\n');
        builder.append("Cancelled: ").append(cancelled).append('\n');
        builder.append("Cancelled remaining: ").append(cancelledRemainingCount).append('\n');
        builder.append("Raw bytes: ").append(rawBytes).append('\n');
        builder.append("Saved bytes: ").append(savedBytes).append('\n');
        builder.append("Beautified bytes: ").append(beautifiedBytes).append('\n');
        if (!errors.isEmpty()) {
            builder.append('\n').append("Errors:\n");
            for (String error : errors) {
                builder.append("- ").append(error).append('\n');
            }
        }
        return builder.toString();
    }
}
