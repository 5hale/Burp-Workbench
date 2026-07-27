package com.burpworkbench.modules.extractor;

import com.burpworkbench.core.filter.MimeCategory;
import com.burpworkbench.core.selection.SelectionResolver;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExportService {
    private static final DateTimeFormatter RUN_DIRECTORY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^\";\\s]+)\"?");
    private static final long DEFAULT_BEAUTIFY_MEMORY_BUDGET = 8L * 1024 * 1024;
    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;

    private final UrlPathMapper pathMapper;
    private final ResponseBodyDecoder bodyDecoder;
    private final ExportIndexWriter indexWriter;
    private final SelectionResolver selectionResolver;
    private final BodyBeautifier bodyBeautifier;
    private final long beautifyMemoryBudget;
    private final MontoyaApi api;

    public ExportService(MontoyaApi api) {
        this(api, Long.getLong(
                "burpworkbench.extractor.beautifyMemoryBudgetBytes",
                DEFAULT_BEAUTIFY_MEMORY_BUDGET
        ));
    }

    ExportService(MontoyaApi api, long beautifyMemoryBudget) {
        this.api = api;
        this.pathMapper = new UrlPathMapper();
        this.bodyDecoder = new ResponseBodyDecoder();
        this.indexWriter = new ExportIndexWriter();
        this.selectionResolver = new SelectionResolver(api);
        this.bodyBeautifier = new BodyBeautifier();
        this.beautifyMemoryBudget = Math.max(beautifyMemoryBudget, 1);
    }

    public ExportSummary export(
            List<HttpRequestResponse> selectedItems,
            boolean includeSubtree,
            Path outputRoot,
            ExportOptions options,
            ExportProgressListener progressListener
    ) throws IOException {
        int selectedCount = selectedItems == null ? 0 : selectedItems.size();
        return exportRun(
                listener -> selectionResolver.resolve(
                        selectedItems,
                        includeSubtree,
                        () -> isCancelled(listener)
                ),
                selectedCount,
                outputRoot,
                options,
                progressListener
        );
    }

    ExportSummary exportResolved(
            List<HttpRequestResponse> requestResponses,
            int selectedCount,
            Path outputRoot,
            ExportOptions options,
            ExportProgressListener progressListener
    ) throws IOException {
        List<HttpRequestResponse> resolvedItems = requestResponses == null ? List.of() : requestResponses;
        return exportRun(
                listener -> resolvedItems,
                selectedCount,
                outputRoot,
                options,
                progressListener
        );
    }

    private ExportSummary exportRun(
            ItemSource itemSource,
            int selectedCount,
            Path outputRoot,
            ExportOptions options,
            ExportProgressListener progressListener
    ) throws IOException {
        ExportOptions safeOptions = options == null ? ExportOptions.defaults() : options;
        ExportProgressListener listener = progressListener == null
                ? new NoOpExportProgressListener()
                : progressListener;
        Path runDirectory = createRunDirectory(outputRoot);
        ExportSummary summary = new ExportSummary(runDirectory, selectedCount);

        DuplicateIndex duplicateIndex = new DuplicateIndex();
        OutputPathAllocator pathAllocator = new OutputPathAllocator();
        ProgressPublisher progress = new ProgressPublisher(listener);
        int processed = 0;
        int total = Math.max(selectedCount, 0);

        Path manifestPath = runDirectory.resolve("extract_manifest.jsonl");
        Path indexPath = runDirectory.resolve("extract_index.html");
        Throwable fatalFailure = null;
        boolean restoreInterrupt = false;
        String phase = "terminal-open";
        try {
            try (ManifestWriter manifest = new ManifestWriter(manifestPath);
                 ExportIndexWriter.Sink index = indexWriter.open(indexPath)) {
                try {
                    phase = "selection";
                    List<HttpRequestResponse> loadedItems = itemSource.load(listener);
                    List<HttpRequestResponse> items = loadedItems == null ? List.of() : loadedItems;
                    total = items.size();
                    summary.setCandidateCount(total);
                    phase = "processing";
                    progress.publish(processed, items.size(), "", summary, true);

                    for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                        checkCancelled(listener);

                        HttpRequestResponse item = items.get(itemIndex);
                        String currentUrl = safeUrl(item);
                        try {
                            processOne(
                                    item,
                                    itemIndex,
                                    runDirectory,
                                    safeOptions,
                                    listener,
                                    duplicateIndex,
                                    pathAllocator,
                                    manifest,
                                    index,
                                    summary
                            );
                            processed++;
                        } catch (CancellationException exception) {
                            throw exception;
                        } catch (IOException | RuntimeException exception) {
                            if (isCancellationRequested(listener, exception)) {
                                throw cancellation(exception);
                            }
                            ExportCandidate failed = failureCandidate(item, itemIndex);
                            recordFailure(failed, message(exception), manifest, index, summary);
                            processed++;
                        }
                        progress.publish(processed, items.size(), currentUrl, summary, false);
                    }

                    if (processed < items.size()) {
                        checkCancelled(listener);
                    }
                    progress.publish(processed, items.size(), "", summary, true);
                    phase = "terminal-close";
                } catch (CancellationException exception) {
                    restoreInterrupt |= clearCancellationInterrupt(exception);
                    recordCancellation(Math.max(total - processed, 0), manifest, index, summary);
                    addTerminalDiagnostic(summary, phase, "cancelled", processed, total, null);
                    phase = "terminal-close";
                } catch (IOException | RuntimeException exception) {
                    if (!isCancellationRequested(listener, exception)) {
                        throw exception;
                    }
                    restoreInterrupt |= clearCancellationInterrupt(exception);
                    recordCancellation(Math.max(total - processed, 0), manifest, index, summary);
                    addTerminalDiagnostic(summary, phase, "cancelled", processed, total, null);
                    phase = "terminal-close";
                }
            }
        } catch (Throwable terminalFailure) {
            fatalFailure = terminalFailure;
            if (terminalFailure instanceof OutOfMemoryError) {
                summary.incrementFailed(null);
            } else {
                summary.incrementFailed("run failed: " + messageOf(terminalFailure));
            }
            addTerminalDiagnostic(summary, phase, "fatal", processed, total, terminalFailure);
            logFatalDiagnostic(summary, terminalFailure);
        }

        if (Thread.interrupted()) {
            restoreInterrupt = true;
        }
        if (fatalFailure == null && !summary.cancelled()) {
            addTerminalDiagnostic(summary, "completed", "success", processed, total, null);
        }
        try {
            if (fatalFailure != null) {
                try {
                    writeSummary(runDirectory, summary);
                } catch (Throwable summaryFailure) {
                    addSuppressed(fatalFailure, summaryFailure);
                }
                throwFailure(fatalFailure);
            }
            phase = "summary";
            writeSummary(runDirectory, summary);
            return summary;
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void processOne(
            HttpRequestResponse item,
            int itemIndex,
            Path runDirectory,
            ExportOptions options,
            ExportProgressListener listener,
            DuplicateIndex duplicateIndex,
            OutputPathAllocator pathAllocator,
            ManifestWriter manifest,
            ExportIndexWriter.Sink index,
            ExportSummary summary
    ) throws IOException {
        checkCancelled(listener);
        String method = safeMethod(item);
        String url = safeUrl(item);
        int statusCode = safeStatusCode(item);

        if (!hasUsableResponse(item)) {
            Path relativePath = pathMapper.map(method, url);
            ExportCandidate candidate = new ExportCandidate(
                    method,
                    url,
                    false,
                    statusCode,
                    "",
                    "",
                    "",
                    relativePath,
                    MimeCategory.from("", relativePath),
                    false,
                    "no response",
                    Hashes.sha256Hex(new byte[0]),
                    0,
                    0
            );
            summary.incrementSkippedNoResponse();
            manifest.write(ManifestRecord.skipped(candidate, "no response"));
            index.write(indexRow("skipped", candidate, "", "", "", "no response"));
            return;
        }

        checkCancelled(listener);
        HttpResponse response = item.response();
        String contentEncoding = safeHeader(response, "Content-Encoding");
        String contentType = safeHeader(response, "Content-Type");
        String contentDisposition = safeHeader(response, "Content-Disposition");
        Path requestedRelativePath = pathMapper.map(
                method,
                url,
                contentType,
                contentDisposition
        );

        checkCancelled(listener);
        byte[] rawBody = response.body().getBytes();
        FileDecodeResult decodedBody = bodyDecoder.decodeToTempFile(
                rawBody,
                contentEncoding,
                runDirectory,
                () -> isCancelled(listener)
        );
        Throwable bodyFailure = null;
        try {
            rawBody = null;
            checkCancelled(listener);
            ExportCandidate candidate = new ExportCandidate(
                    method,
                    url,
                    true,
                    statusCode,
                    contentType,
                    contentEncoding,
                    contentDisposition,
                    requestedRelativePath,
                    MimeCategory.from(contentType, requestedRelativePath),
                    decodedBody.decoded(),
                    decodedBody.note(),
                    decodedBody.sha256(),
                    decodedBody.rawByteCount(),
                    decodedBody.decodedByteCount()
            );

            Optional<DuplicateIndex.DuplicateReference> duplicate = duplicateIndex.find(candidate.bodySha256());
            if (duplicate.isPresent()) {
                DuplicateIndex.DuplicateReference original = duplicate.get();
                summary.incrementDuplicate();
                manifest.write(ManifestRecord.duplicate(candidate, original.relativePath(), original.url()));
                index.write(indexRow(
                        "duplicate",
                        candidate,
                        "",
                        original.relativePath(),
                        original.url(),
                        "duplicate body sha256"
                ));
                return;
            }

            checkCancelled(listener);
            Path allocatedRelativePath = pathAllocator.allocate(candidate.relativePath());
            ExportCandidate allocatedCandidate = withRelativePath(candidate, allocatedRelativePath);
            duplicateIndex.rememberFirst(
                    allocatedCandidate.bodySha256(),
                    allocatedCandidate.relativePath(),
                    allocatedCandidate.url()
            );
            saveCandidate(
                    allocatedCandidate,
                    decodedBody.path(),
                    runDirectory,
                    options,
                    listener,
                    manifest,
                    index,
                    summary
            );
        } catch (IOException | RuntimeException | Error failure) {
            bodyFailure = failure;
            throw failure;
        } finally {
            closeDecodedBody(decodedBody, bodyFailure, summary);
        }
    }

    static void closeDecodedBody(
            AutoCloseable decodedBody,
            Throwable primaryFailure,
            ExportSummary summary
    ) {
        if (decodedBody == null) {
            return;
        }
        try {
            decodedBody.close();
        } catch (Throwable cleanupFailure) {
            if (primaryFailure != null) {
                addSuppressed(primaryFailure, cleanupFailure);
                return;
            }
            if (cleanupFailure instanceof Error error) {
                throw error;
            }
            if (summary != null) {
                summary.addDiagnostic("decoded temporary file cleanup failed: " + messageOf(cleanupFailure));
            }
        }
    }

    private void saveCandidate(
            ExportCandidate candidate,
            Path decodedBodyPath,
            Path runDirectory,
            ExportOptions options,
            ExportProgressListener listener,
            ManifestWriter manifest,
            ExportIndexWriter.Sink index,
            ExportSummary summary
    ) throws IOException {
        Path outputPath = runDirectory.resolve(candidate.relativePath()).normalize();
        if (!outputPath.startsWith(runDirectory)) {
            recordFailure(candidate, "unsafe output path", manifest, index, summary);
            return;
        }

        String outputRelativePath = runDirectory.relativize(outputPath).toString();
        SavePayload payload = preparePayload(
                candidate,
                decodedBodyPath,
                outputRelativePath,
                options,
                listener,
                manifest,
                index,
                summary
        );

        try {
            checkCancelled(listener);
            if (payload.bytes() == null) {
                AtomicFiles.copy(decodedBodyPath, outputPath, () -> isCancelled(listener));
            } else {
                AtomicFiles.writeBytes(outputPath, payload.bytes());
            }
            summary.incrementSaved(candidate.rawByteCount(), payload.byteCount());
            manifest.write(ManifestRecord.saved(
                    candidate.url(),
                    candidate.method(),
                    candidate.statusCode(),
                    candidate.contentEncoding(),
                    candidate.contentType(),
                    candidate.mimeCategory().label(),
                    outputRelativePath,
                    candidate.decoded(),
                    candidate.rawByteCount(),
                    payload.byteCount(),
                    payload.sha256(),
                    payload.note()
            ));
            index.write(indexRow(
                    "saved",
                    candidate,
                    outputRelativePath,
                    "",
                    "",
                    payload.note(),
                    payload.byteCount()
            ));
        } catch (CancellationException exception) {
            throw exception;
        } catch (IOException exception) {
            if (isCancellationRequested(listener, exception)) {
                throw cancellation(exception);
            }
            recordFailure(candidate, message(exception), manifest, index, summary);
        }
    }

    private SavePayload preparePayload(
            ExportCandidate candidate,
            Path decodedBodyPath,
            String outputRelativePath,
            ExportOptions options,
            ExportProgressListener listener,
            ManifestWriter manifest,
            ExportIndexWriter.Sink index,
            ExportSummary summary
    ) throws IOException {
        if (!options.beautify()) {
            return SavePayload.original(candidate);
        }

        BeautifyType type = beautifyTypeFor(candidate);
        if (type == null) {
            return SavePayload.original(candidate);
        }

        if (candidate.savedByteCount() > beautifyMemoryBudget) {
            String reason = type.label() + " beautify skipped: decoded body "
                    + candidate.savedByteCount() + " bytes exceeds memory budget "
                    + beautifyMemoryBudget + " bytes";
            recordBeautifyFailure(candidate, outputRelativePath, reason, manifest, index, summary);
            return SavePayload.original(candidate);
        }

        try {
            checkCancelled(listener);
            byte[] originalBytes = Files.readAllBytes(decodedBodyPath);
            checkCancelled(listener);
            String source = new String(originalBytes, charset(candidate.contentType()));
            BodyBeautifier.BeautifiedText beautified = bodyBeautifier.beautify(
                    type,
                    source,
                    beautifyMemoryBudget,
                    () -> isCancelled(listener)
            );
            checkCancelled(listener);
            byte[] beautifiedBytes = beautified.text().getBytes(StandardCharsets.UTF_8);
            if (beautifiedBytes.length > beautifyMemoryBudget) {
                throw new BeautifyLimitException(
                        "beautified output exceeds budget of "
                                + beautifyMemoryBudget + " bytes"
                );
            }
            summary.incrementBeautified(beautifiedBytes.length);
            return new SavePayload(
                    beautifiedBytes,
                    beautifiedBytes.length,
                    Hashes.sha256Hex(beautifiedBytes),
                    appendNote(candidate.decodeNote(), "beautified " + type.label() + ": " + beautified.note())
            );
        } catch (CancellationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (isCancellationRequested(listener, exception)) {
                throw cancellation(exception);
            }
            String reason = type.label() + " beautify failed: " + message(exception);
            recordBeautifyFailure(candidate, outputRelativePath, reason, manifest, index, summary);
            return SavePayload.original(candidate);
        }
    }

    private void recordBeautifyFailure(
            ExportCandidate candidate,
            String outputRelativePath,
            String reason,
            ManifestWriter manifest,
            ExportIndexWriter.Sink index,
            ExportSummary summary
    ) throws IOException {
        summary.incrementBeautifyFailed(reason);
        manifest.write(ManifestRecord.beautifyFailed(candidate, outputRelativePath, reason));
        index.write(indexRow("beautify_failed", candidate, outputRelativePath, "", "", reason));
    }

    private void recordFailure(
            ExportCandidate candidate,
            String reason,
            ManifestWriter manifest,
            ExportIndexWriter.Sink index,
            ExportSummary summary
    ) throws IOException {
        String safeReason = reason == null || reason.isBlank() ? "unknown export failure" : reason;
        summary.incrementFailed(safeReason);
        manifest.write(ManifestRecord.failed(candidate, safeReason));
        index.write(indexRow(
                "failed",
                candidate,
                candidate.relativePath().toString(),
                "",
                "",
                safeReason
        ));
    }

    private void recordCancellation(
            int remaining,
            ManifestWriter manifest,
            ExportIndexWriter.Sink index,
            ExportSummary summary
    ) throws IOException {
        int safeRemaining = Math.max(remaining, 0);
        summary.markCancelled(safeRemaining);
        manifest.write(ManifestRecord.cancelled("cancelled by user", safeRemaining));
        index.write(new ExportIndexRow(
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
                "cancelled by user; remaining=" + safeRemaining
        ));
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

    private Path createRunDirectory(Path outputRoot) throws IOException {
        if (outputRoot == null) {
            throw new IOException("output root is required");
        }
        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        String baseName = "extract-file-" + LocalDateTime.now().format(RUN_DIRECTORY_FORMAT);

        for (int suffix = 0; ; suffix++) {
            String directoryName = suffix == 0 ? baseName : baseName + "-" + suffix;
            Path candidate = root.resolve(directoryName);
            try {
                return Files.createDirectory(candidate).toAbsolutePath().normalize();
            } catch (FileAlreadyExistsException ignored) {
                // Another export won the name. Retry with a deterministic suffix.
            }
        }
    }

    private ExportCandidate failureCandidate(HttpRequestResponse item, int itemIndex) {
        String method = safeMethod(item);
        String url = safeUrl(item);
        int status = safeStatusCode(item);
        String contentEncoding = "";
        String contentType = "";
        String contentDisposition = "";
        boolean hasResponse = safeHasResponse(item);
        if (hasResponse) {
            try {
                HttpResponse response = item.response();
                contentEncoding = safeHeader(response, "Content-Encoding");
                contentType = safeHeader(response, "Content-Type");
                contentDisposition = safeHeader(response, "Content-Disposition");
            } catch (RuntimeException ignored) {
                // The fallback record still identifies the item by method and URL.
            }
        }

        Path relativePath;
        try {
            relativePath = pathMapper.map(method, url, contentType, contentDisposition);
        } catch (RuntimeException exception) {
            relativePath = Path.of("unknown-host", "failed-" + itemIndex + ".bin");
        }
        return new ExportCandidate(
                method,
                url,
                hasResponse,
                status,
                contentType,
                contentEncoding,
                contentDisposition,
                relativePath,
                MimeCategory.from(contentType, relativePath),
                false,
                "",
                "",
                0,
                0
        );
    }

    private ExportCandidate withRelativePath(ExportCandidate candidate, Path relativePath) {
        return new ExportCandidate(
                candidate.method(),
                candidate.url(),
                candidate.hasResponse(),
                candidate.statusCode(),
                candidate.contentType(),
                candidate.contentEncoding(),
                candidate.contentDisposition(),
                relativePath,
                MimeCategory.from(candidate.contentType(), relativePath),
                candidate.decoded(),
                candidate.decodeNote(),
                candidate.bodySha256(),
                candidate.rawByteCount(),
                candidate.savedByteCount()
        );
    }

    private ExportIndexRow indexRow(
            String action,
            ExportCandidate candidate,
            String outputPath,
            String duplicateOfPath,
            String duplicateOfUrl,
            String reason
    ) {
        return indexRow(
                action,
                candidate,
                outputPath,
                duplicateOfPath,
                duplicateOfUrl,
                reason,
                candidate.savedByteCount()
        );
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
            if (item != null && item.hasResponse() && item.response() != null) {
                return item.response().statusCode();
            }
        } catch (RuntimeException ignored) {
            return -1;
        }
        return -1;
    }

    private boolean safeHasResponse(HttpRequestResponse item) {
        try {
            return item != null && item.hasResponse() && item.response() != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hasUsableResponse(HttpRequestResponse item) {
        if (item == null) {
            throw new IllegalArgumentException("search item is null");
        }
        return item.hasResponse() && item.response() != null;
    }

    private String safeHeader(HttpResponse response, String name) {
        try {
            String value = response == null ? null : response.headerValue(name);
            return value == null ? "" : value;
        } catch (RuntimeException exception) {
            return "";
        }
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

    private void writeSummary(Path runDirectory, ExportSummary summary) throws IOException {
        AtomicFiles.writeString(
                runDirectory.resolve("extract_summary.txt"),
                summary.toSummaryFileText(),
                StandardCharsets.UTF_8
        );
    }

    private void addTerminalDiagnostic(
            ExportSummary summary,
            String phase,
            String outcome,
            int processed,
            int total,
            Throwable primaryFailure
    ) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long heapUsedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long heapMaxMiB = runtime.maxMemory() / (1024 * 1024);
            String diagnostic = "terminal outcome=" + outcome
                    + " phase=" + phase
                    + " processed=" + Math.max(processed, 0)
                    + " total=" + Math.max(total, 0)
                    + " heapUsedMiB=" + heapUsedMiB
                    + " heapMaxMiB=" + heapMaxMiB
                    + " saved=" + summary.savedCount()
                    + " skipped=" + summary.skippedCount()
                    + " duplicate=" + summary.duplicateCount()
                    + " failed=" + summary.failedCount();
            if ("success".equals(outcome)) {
                summary.setTerminalDiagnostic(diagnostic);
            } else {
                summary.addTerminalDiagnostic(diagnostic);
            }
        } catch (Throwable diagnosticFailure) {
            if (primaryFailure != null) {
                addSuppressed(primaryFailure, diagnosticFailure);
            }
        }
    }

    private void logFatalDiagnostic(ExportSummary summary, Throwable primaryFailure) {
        if (api == null) {
            return;
        }
        try {
            var logging = api.logging();
            if (logging != null) {
                logging.logToError(
                        "Extractor " + summary.terminalDiagnostic()
                                + " error=" + primaryFailure.getClass().getName()
                                + " message=" + messageOf(primaryFailure)
                );
            }
        } catch (Throwable loggingFailure) {
            addSuppressed(primaryFailure, loggingFailure);
        }
    }

    private boolean isCancelled(ExportProgressListener listener) {
        return Thread.currentThread().isInterrupted() || listener.isCancelled();
    }

    private void checkCancelled(ExportProgressListener listener) {
        if (isCancelled(listener)) {
            throw new CancellationException("cancelled by user");
        }
    }

    private boolean isCancellationRequested(
            ExportProgressListener listener,
            Throwable failure
    ) {
        if (failure instanceof CancellationException
                || Thread.currentThread().isInterrupted()
                || causedByClosedInterrupt(failure)) {
            return true;
        }
        return listener != null && listener.isCancelled();
    }

    private boolean clearCancellationInterrupt(Throwable failure) {
        boolean interrupted = Thread.interrupted();
        return interrupted || causedByClosedInterrupt(failure);
    }

    private boolean causedByClosedInterrupt(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ClosedByInterruptException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private CancellationException cancellation(Throwable failure) {
        CancellationException cancellation = new CancellationException("cancelled by user");
        cancellation.initCause(failure);
        return cancellation;
    }

    private String message(Throwable throwable) {
        return messageOf(throwable);
    }

    private static String messageOf(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }

    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException("extractor run failed", failure);
    }

    @FunctionalInterface
    private interface ItemSource {
        List<HttpRequestResponse> load(ExportProgressListener listener);
    }

    private record SavePayload(byte[] bytes, long byteCount, String sha256, String note) {
        private static SavePayload original(ExportCandidate candidate) {
            return new SavePayload(
                    null,
                    candidate.savedByteCount(),
                    candidate.bodySha256(),
                    candidate.decodeNote()
            );
        }
    }

    private static final class ProgressPublisher {
        private final ExportProgressListener listener;
        private long lastPublishedAt = Long.MIN_VALUE;

        private ProgressPublisher(ExportProgressListener listener) {
            this.listener = listener;
        }

        private void publish(
                int processed,
                int total,
                String currentUrl,
                ExportSummary summary,
                boolean force
        ) {
            long now = System.nanoTime();
            if (!force && processed < total && now - lastPublishedAt < PROGRESS_INTERVAL_NANOS) {
                return;
            }
            lastPublishedAt = now;
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
    }
}
