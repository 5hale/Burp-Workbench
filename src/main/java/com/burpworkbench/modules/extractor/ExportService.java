package com.burpworkbench.modules.extractor;

import com.burpworkbench.core.codec.DecodeResult;
import com.burpworkbench.core.util.Hashes;
import com.burpworkbench.core.filter.MimeCategory;
import com.burpworkbench.core.codec.ResponseBodyDecoder;
import com.burpworkbench.core.selection.SelectionResolver;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExportService {
    private static final DateTimeFormatter RUN_DIRECTORY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^\";\\s]+)\"?");

    private final MontoyaApi api;
    private final UrlPathMapper pathMapper;
    private final ResponseBodyDecoder bodyDecoder;
    private final ExportPlanner planner;
    private final ExportIndexWriter indexWriter;
    private final SelectionResolver selectionResolver;
    private final BodyBeautifier bodyBeautifier;

    public ExportService(MontoyaApi api) {
        this.api = api;
        this.pathMapper = new UrlPathMapper();
        this.bodyDecoder = new ResponseBodyDecoder();
        this.planner = new ExportPlanner();
        this.indexWriter = new ExportIndexWriter();
        this.selectionResolver = new SelectionResolver(api);
        this.bodyBeautifier = new BodyBeautifier();
    }

    public ExportSummary export(List<HttpRequestResponse> selectedItems, boolean includeSubtree, Path outputRoot)
            throws IOException {
        return export(selectedItems, includeSubtree, outputRoot, ExportOptions.defaults(), new NoOpExportProgressListener());
    }

    public ExportSummary export(
            List<HttpRequestResponse> selectedItems,
            boolean includeSubtree,
            Path outputRoot,
            ExportOptions options,
            ExportProgressListener progressListener
    ) throws IOException {
        List<ExportCandidate> candidates = analyzeCandidates(selectedItems, includeSubtree);
        ExportPlan plan = planner.plan(candidates, options, selectedItems == null ? 0 : selectedItems.size());
        return executePlan(plan, outputRoot, options, progressListener);
    }

    public List<ExportCandidate> analyzeCandidates(List<HttpRequestResponse> selectedItems, boolean includeSubtree) {
        List<HttpRequestResponse> requestResponses = collectCandidates(selectedItems, includeSubtree);
        List<ExportCandidate> candidates = new ArrayList<>();
        for (HttpRequestResponse item : requestResponses) {
            candidates.add(toCandidate(item));
        }
        return candidates;
    }

    public ExportPlan plan(List<ExportCandidate> candidates, ExportOptions options, int selectedCount) {
        return planner.plan(candidates, options, selectedCount);
    }

    public ExportSummary executePlan(ExportPlan plan, Path outputRoot, ExportProgressListener progressListener)
            throws IOException {
        return executePlan(plan, outputRoot, ExportOptions.defaults(), progressListener);
    }

    public ExportSummary executePlan(
            ExportPlan plan,
            Path outputRoot,
            ExportOptions options,
            ExportProgressListener progressListener
    )
            throws IOException {
        ExportProgressListener listener = progressListener == null ? new NoOpExportProgressListener() : progressListener;
        ExportOptions safeOptions = options == null ? ExportOptions.defaults() : options;
        Path runDirectory = createRunDirectory(outputRoot);
        ExportSummary summary = new ExportSummary(runDirectory, plan.selectedCount());
        summary.setCandidateCount(plan.candidateCount());
        List<ExportIndexRow> indexRows = new ArrayList<>();

        Path manifestPath = runDirectory.resolve("extract_manifest.jsonl");
        try (ManifestWriter manifestWriter = new ManifestWriter(manifestPath)) {
            int processed = 0;
            publishProgress(listener, processed, plan.actions().size(), "", summary);

            for (ExportAction action : plan.actions()) {
                if (listener.isCancelled()) {
                    int remaining = plan.actions().size() - processed;
                    summary.markCancelled(remaining);
                    manifestWriter.write(ManifestRecord.cancelled("cancelled by user", remaining));
                    indexRows.add(new ExportIndexRow(
                            "cancelled",
                            "",
                            "",
                            -1,
                            "",
                            "",
                            0,
                            "",
                            "",
                            "",
                            "cancelled by user; remaining=" + remaining
                    ));
                    break;
                }

                executeOne(action, runDirectory, safeOptions, manifestWriter, summary, indexRows);
                processed++;
                publishProgress(listener, processed, plan.actions().size(), action.candidate().url(), summary);
            }
        }

        indexWriter.write(runDirectory.resolve("extract_index.html"), indexRows);
        writeSummary(runDirectory, summary);
        return summary;
    }

    private Path createRunDirectory(Path outputRoot) throws IOException {
        String baseName = "extract-file-" + LocalDateTime.now().format(RUN_DIRECTORY_FORMAT);
        Path candidate = outputRoot.resolve(baseName);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = outputRoot.resolve(baseName + "-" + suffix);
            suffix++;
        }
        Files.createDirectories(candidate);
        return candidate.toAbsolutePath().normalize();
    }

    private List<HttpRequestResponse> collectCandidates(List<HttpRequestResponse> selectedItems, boolean includeSubtree) {
        return selectionResolver.resolve(selectedItems, includeSubtree);
    }

    private ExportCandidate toCandidate(HttpRequestResponse item) {
        String method = safeMethod(item);
        String url = safeUrl(item);
        int statusCode = safeStatusCode(item);

        if (!item.hasResponse() || item.response() == null) {
            Path relativePath = pathMapper.map(method, url);
            return new ExportCandidate(
                    method,
                    url,
                    false,
                    statusCode,
                    "",
                    "",
                    "",
                    relativePath,
                    MimeCategory.from("", relativePath),
                    new byte[0],
                    new byte[0],
                    false,
                    "no response",
                    Hashes.sha256Hex(new byte[0]),
                    item
            );
        }

        HttpResponse response = item.response();
        byte[] rawBody = response.body().getBytes();
        String contentEncoding = response.headerValue("Content-Encoding");
        String contentType = response.headerValue("Content-Type");
        String contentDisposition = response.headerValue("Content-Disposition");
        DecodeResult decodedBody = bodyDecoder.decode(rawBody, contentEncoding);
        Path relativePath = pathMapper.map(method, url, contentType, contentDisposition);

        return new ExportCandidate(
                method,
                url,
                true,
                statusCode,
                contentType,
                contentEncoding,
                contentDisposition,
                relativePath,
                MimeCategory.from(contentType, relativePath),
                rawBody,
                decodedBody.bytes(),
                decodedBody.decoded(),
                decodedBody.note(),
                Hashes.sha256Hex(decodedBody.bytes()),
                item
        );
    }

    private void executeOne(
            ExportAction action,
            Path runDirectory,
            ExportOptions options,
            ManifestWriter manifestWriter,
            ExportSummary summary,
            List<ExportIndexRow> indexRows
    ) throws IOException {
        ExportCandidate candidate = action.candidate();

        switch (action.type()) {
            case SAVED -> saveCandidate(candidate, runDirectory, options, manifestWriter, summary, indexRows);
            case SKIPPED -> {
                if ("no response".equals(action.reason())) {
                    summary.incrementSkippedNoResponse();
                } else {
                    summary.incrementFiltered();
                }
                manifestWriter.write(ManifestRecord.skipped(candidate, action.reason()));
                indexRows.add(indexRow("skipped", candidate, "", "", "", action.reason()));
            }
            case DUPLICATE -> {
                summary.incrementDuplicate();
                manifestWriter.write(ManifestRecord.duplicate(candidate, action.duplicateOfPath(), action.duplicateOfUrl()));
                indexRows.add(indexRow(
                        "duplicate",
                        candidate,
                        "",
                        action.duplicateOfPath(),
                        action.duplicateOfUrl(),
                        action.reason()
                ));
            }
            case FAILED -> {
                summary.incrementFailed(action.reason());
                manifestWriter.write(ManifestRecord.failed(candidate, action.reason()));
                indexRows.add(indexRow("failed", candidate, candidate.relativePath().toString(), "", "", action.reason()));
            }
            case CANCELLED -> {
                summary.markCancelled(0);
                manifestWriter.write(ManifestRecord.cancelled(action.reason(), 0));
                indexRows.add(indexRow("cancelled", candidate, "", "", "", action.reason()));
            }
        }
    }

    private void saveCandidate(
            ExportCandidate candidate,
            Path runDirectory,
            ExportOptions options,
            ManifestWriter manifestWriter,
            ExportSummary summary,
            List<ExportIndexRow> indexRows
    ) throws IOException {
        Path outputPath = runDirectory.resolve(candidate.relativePath()).normalize();
        if (!outputPath.startsWith(runDirectory)) {
            String error = "unsafe output path";
            summary.incrementFailed(error + " for " + candidate.url());
            manifestWriter.write(ManifestRecord.failed(candidate, error));
            indexRows.add(indexRow("failed", candidate, candidate.relativePath().toString(), "", "", error));
            return;
        }

        try {
            Files.createDirectories(outputPath.getParent());
            String outputRelativePath = runDirectory.relativize(outputPath).toString();
            SavePayload payload = savePayload(candidate, outputRelativePath, options, manifestWriter, summary, indexRows);
            Files.write(outputPath, payload.bytes());
            summary.incrementSaved(candidate.rawByteCount(), payload.bytes().length);
            manifestWriter.write(ManifestRecord.saved(
                    candidate.url(),
                    candidate.method(),
                    candidate.statusCode(),
                    candidate.contentEncoding(),
                    candidate.contentType(),
                    candidate.mimeCategory().label(),
                    outputRelativePath,
                    candidate.decoded(),
                    candidate.rawByteCount(),
                    payload.bytes().length,
                    payload.sha256(),
                    payload.note()
            ));
            indexRows.add(indexRow("saved", candidate, outputRelativePath, "", "", payload.note(), payload.bytes().length));
        } catch (IOException exception) {
            summary.incrementFailed(exception.getMessage());
            manifestWriter.write(ManifestRecord.failed(candidate, exception.getMessage()));
            indexRows.add(indexRow("failed", candidate, candidate.relativePath().toString(), "", "", exception.getMessage()));
        }
    }

    private SavePayload savePayload(
            ExportCandidate candidate,
            String outputRelativePath,
            ExportOptions options,
            ManifestWriter manifestWriter,
            ExportSummary summary,
            List<ExportIndexRow> indexRows
    ) throws IOException {
        byte[] originalBytes = candidate.decodedBody();
        if (options == null || !options.beautify()) {
            return new SavePayload(originalBytes, candidate.bodySha256(), candidate.decodeNote());
        }

        BeautifyType type = beautifyTypeFor(candidate);
        if (type == null) {
            return new SavePayload(originalBytes, candidate.bodySha256(), candidate.decodeNote());
        }

        try {
            String source = new String(candidate.decodedBody(), charset(candidate.contentType()));
            BodyBeautifier.BeautifiedText beautified = bodyBeautifier.beautify(type, source);
            byte[] beautifiedBytes = beautified.text().getBytes(StandardCharsets.UTF_8);
            summary.incrementBeautified(beautifiedBytes.length);
            return new SavePayload(
                    beautifiedBytes,
                    Hashes.sha256Hex(beautifiedBytes),
                    appendNote(candidate.decodeNote(), "beautified " + type.label() + ": " + beautified.note())
            );
        } catch (RuntimeException exception) {
            String error = type.label() + " beautify failed: " + exception.getMessage();
            summary.incrementBeautifyFailed(error);
            manifestWriter.write(ManifestRecord.beautifyFailed(candidate, outputRelativePath, error));
            indexRows.add(indexRow("beautify_failed", candidate, outputRelativePath, "", "", error));
            return new SavePayload(originalBytes, candidate.bodySha256(), candidate.decodeNote());
        }
    }

    static boolean isJavascriptCandidate(ExportCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String extension = candidate.extension();
        String contentType = candidate.contentType().toLowerCase(Locale.ROOT);
        return candidate.mimeCategory() == MimeCategory.JAVASCRIPT
                || ".js".equals(extension)
                || ".mjs".equals(extension)
                || contentType.contains("javascript")
                || contentType.contains("ecmascript");
    }

    static BeautifyType beautifyTypeFor(ExportCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (isJavascriptCandidate(candidate)) {
            return BeautifyType.JAVASCRIPT;
        }
        String extension = candidate.extension();
        String contentType = normalizedContentType(candidate.contentType());
        if (candidate.mimeCategory() == MimeCategory.JSON
                || ".json".equals(extension)
                || ".map".equals(extension)
                || contentType.equals("application/json")
                || contentType.endsWith("+json")) {
            return BeautifyType.JSON;
        }
        return null;
    }

    private String appendNote(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "; " + addition;
    }

    private static String normalizedContentType(String contentType) {
        String value = contentType == null ? "" : contentType;
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Charset charset(String contentType) {
        Matcher matcher = CHARSET_PATTERN.matcher(contentType == null ? "" : contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (RuntimeException ignored) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private ExportIndexRow indexRow(
            String action,
            ExportCandidate candidate,
            String outputPath,
            String duplicateOfPath,
            String duplicateOfUrl,
            String reason
    ) {
        return indexRow(action, candidate, outputPath, duplicateOfPath, duplicateOfUrl, reason, candidate.savedByteCount());
    }

    private ExportIndexRow indexRow(
            String action,
            ExportCandidate candidate,
            String outputPath,
            String duplicateOfPath,
            String duplicateOfUrl,
            String reason,
            long savedByteCount
    ) {
        return new ExportIndexRow(
                action,
                candidate.method(),
                candidate.url(),
                candidate.statusCode(),
                candidate.mimeCategory().label(),
                candidate.contentType(),
                savedByteCount,
                outputPath,
                duplicateOfPath,
                duplicateOfUrl,
                reason
        );
    }

    private record SavePayload(byte[] bytes, String sha256, String note) {
    }

    private void publishProgress(
            ExportProgressListener listener,
            int processed,
            int total,
            String currentUrl,
            ExportSummary summary
    ) {
        listener.onProgress(new ExportProgress(
                processed,
                total,
                currentUrl,
                summary.savedCount(),
                summary.skippedCount(),
                summary.duplicateCount(),
                summary.failedCount(),
                summary.cancelled()
        ));
    }

    private String safeMethod(HttpRequestResponse item) {
        try {
            return item.request().method();
        } catch (RuntimeException exception) {
            return "GET";
        }
    }

    private String safeUrl(HttpRequestResponse item) {
        try {
            return item.request().url();
        } catch (RuntimeException exception) {
            return "http://malformed-request.local/" + System.identityHashCode(item);
        }
    }

    private int safeStatusCode(HttpRequestResponse item) {
        try {
            if (item.hasResponse() && item.response() != null) {
                return item.response().statusCode();
            }
        } catch (RuntimeException ignored) {
            return -1;
        }
        return -1;
    }

    private void writeSummary(Path runDirectory, ExportSummary summary) throws IOException {
        Files.writeString(
                runDirectory.resolve("extract_summary.txt"),
                summary.toSummaryFileText(),
                StandardCharsets.UTF_8
        );
    }
}
