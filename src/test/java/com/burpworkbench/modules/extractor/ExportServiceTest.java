package com.burpworkbench.modules.extractor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.sitemap.SiteMap;
import burp.api.montoya.sitemap.SiteMapFilter;
import burp.api.montoya.sitemap.SiteMapNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportServiceTest {
    @Test
    void preservesOrderAndFirstDecodedBodyWins(@TempDir Path outputRoot) throws Exception {
        HttpRequestResponse first = exchange(
                "https://example.com/a.txt",
                "text/plain",
                "same body"
        );
        HttpRequestResponse second = exchange(
                "https://example.com/b.txt",
                "text/plain",
                "same body"
        );

        ExportSummary summary = new ExportService(null).exportResolved(
                List.of(first, second),
                2,
                outputRoot,
                ExportOptions.defaults(),
                new NoOpExportProgressListener()
        );

        Path runDirectory = summary.outputDirectory();
        String manifest = Files.readString(runDirectory.resolve("extract_manifest.jsonl"));
        String index = Files.readString(runDirectory.resolve("extract_index.html"));

        assertTrue(Files.exists(runDirectory.resolve("example.com").resolve("a.txt")));
        assertFalse(Files.exists(runDirectory.resolve("example.com").resolve("b.txt")));
        assertTrue(manifest.indexOf("\"action\":\"saved\"") < manifest.indexOf("\"action\":\"duplicate\""));
        assertTrue(manifest.contains("\"duplicateOfUrl\":\"https://example.com/a.txt\""));
        assertTrue(index.indexOf("https://example.com/a.txt") < index.indexOf("https://example.com/b.txt"));
        assertEquals(1, summary.savedCount());
        assertEquals(1, summary.duplicateCount());
    }

    @Test
    void duplicateHashUsesDecodedBodyRatherThanWireBytes(@TempDir Path outputRoot) throws Exception {
        byte[] plain = "decoded duplicate".getBytes(StandardCharsets.UTF_8);
        HttpRequestResponse compressed = exchange(
                "https://example.com/compressed.txt",
                "text/plain",
                gzip(plain),
                "gzip"
        );
        HttpRequestResponse uncompressed = exchange(
                "https://example.com/plain.txt",
                "text/plain",
                plain,
                ""
        );

        ExportSummary summary = new ExportService(null).exportResolved(
                List.of(compressed, uncompressed),
                2,
                outputRoot,
                ExportOptions.defaults(),
                new NoOpExportProgressListener()
        );

        String manifest = Files.readString(summary.outputDirectory().resolve("extract_manifest.jsonl"));
        assertEquals(1, summary.savedCount());
        assertEquals(1, summary.duplicateCount());
        assertTrue(manifest.contains("\"duplicateOfUrl\":\"https://example.com/compressed.txt\""));
        assertEquals(
                "decoded duplicate",
                Files.readString(summary.outputDirectory().resolve("example.com").resolve("compressed.txt"))
        );
    }

    @Test
    void noResponseDoesNotClaimTheEmptyBodyHash(@TempDir Path outputRoot) throws Exception {
        HttpRequest request = request("https://example.com/no-response.txt");
        HttpRequestResponse noResponse = proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> null;
            case "hasResponse" -> false;
            default -> defaultValue(method.getReturnType());
        });
        HttpRequestResponse emptyResponse = exchange(
                "https://example.com/empty.txt",
                "text/plain",
                new byte[0],
                ""
        );

        ExportSummary summary = new ExportService(null).exportResolved(
                List.of(noResponse, emptyResponse),
                2,
                outputRoot,
                ExportOptions.defaults(),
                new NoOpExportProgressListener()
        );

        assertEquals(1, summary.skippedCount());
        assertEquals(1, summary.savedCount());
        assertEquals(0, summary.duplicateCount());
        assertTrue(Files.exists(
                summary.outputDirectory().resolve("example.com").resolve("empty.txt")
        ));
    }

    @Test
    void skipsBeautifyOverBudgetAndSavesDecodedOriginal(@TempDir Path outputRoot) throws Exception {
        String original = "{\"longProperty\":12345}";
        HttpRequestResponse item = exchange(
                "https://example.com/data.json",
                "application/json",
                original
        );

        ExportSummary summary = new ExportService(null, 8).exportResolved(
                List.of(item),
                1,
                outputRoot,
                ExportOptions.defaults().withBeautify(true),
                new NoOpExportProgressListener()
        );

        Path runDirectory = summary.outputDirectory();
        String saved = Files.readString(runDirectory.resolve("example.com").resolve("data.json"));
        String manifest = Files.readString(runDirectory.resolve("extract_manifest.jsonl"));
        String index = Files.readString(runDirectory.resolve("extract_index.html"));

        assertEquals(original, saved);
        assertTrue(manifest.contains("\"action\":\"beautify_failed\""));
        assertTrue(manifest.contains("exceeds memory budget 8 bytes"));
        assertTrue(manifest.contains("\"action\":\"saved\""));
        assertTrue(index.contains("Total rows: 2"));
        assertTrue(summary.toSummaryFileText().contains("Beautify failed: 1"));
        assertTrue(summary.toSummaryFileText().contains("Saved: 1"));
    }

    @Test
    void allocatesDeterministicSuffixForCollidingRelativePaths(@TempDir Path outputRoot) throws Exception {
        HttpRequestResponse first = exchange("http://example.com/file.txt", "text/plain", "first");
        HttpRequestResponse second = exchange("https://example.com/file.txt", "text/plain", "second");

        ExportSummary summary = new ExportService(null).exportResolved(
                List.of(first, second),
                2,
                outputRoot,
                ExportOptions.defaults(),
                new NoOpExportProgressListener()
        );

        Path host = summary.outputDirectory().resolve("example.com");
        assertEquals("first", Files.readString(host.resolve("file.txt")));
        assertEquals("second", Files.readString(host.resolve("file__2.txt")));
    }

    @Test
    void isolatesMalformedItemAndContinues(@TempDir Path outputRoot) throws Exception {
        HttpRequestResponse malformed = malformedBodyItem("https://example.com/bad.txt");
        HttpRequestResponse valid = exchange("https://example.com/good.txt", "text/plain", "good");

        ExportSummary summary = new ExportService(null).exportResolved(
                List.of(malformed, valid),
                2,
                outputRoot,
                ExportOptions.defaults(),
                new NoOpExportProgressListener()
        );

        String manifest = Files.readString(summary.outputDirectory().resolve("extract_manifest.jsonl"));
        assertTrue(manifest.indexOf("\"action\":\"failed\"") < manifest.indexOf("\"action\":\"saved\""));
        assertEquals(1, summary.failedCount());
        assertEquals(1, summary.savedCount());
        assertEquals("good", Files.readString(
                summary.outputDirectory().resolve("example.com").resolve("good.txt")
        ));
    }

    @Test
    void hasResponseFailureIsRecordedAsFailedRatherThanSkipped(@TempDir Path outputRoot) throws Exception {
        HttpRequestResponse malformed = malformedHasResponseItem("https://example.com/bad.txt");
        HttpRequestResponse valid = exchange("https://example.com/good.txt", "text/plain", "good");

        ExportSummary summary = new ExportService(null).exportResolved(
                List.of(malformed, valid),
                2,
                outputRoot,
                ExportOptions.defaults(),
                new NoOpExportProgressListener()
        );

        String manifest = Files.readString(summary.outputDirectory().resolve("extract_manifest.jsonl"));
        assertEquals(1, summary.failedCount());
        assertEquals(0, summary.skippedCount());
        assertEquals(1, summary.savedCount());
        assertTrue(manifest.contains("\"action\":\"failed\""));
        assertTrue(manifest.contains("broken hasResponse"));
    }

    @Test
    void cancellationKeepsTerminalManifestIndexAndSummary(@TempDir Path outputRoot) throws Exception {
        ExportProgressListener cancelled = new ExportProgressListener() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public void onProgress(ExportProgress progress) {
            }
        };

        ExportSummary summary = new ExportService(null).exportResolved(
                List.of(
                        exchange("https://example.com/a.txt", "text/plain", "a"),
                        exchange("https://example.com/b.txt", "text/plain", "b")
                ),
                2,
                outputRoot,
                ExportOptions.defaults(),
                cancelled
        );

        String manifest = Files.readString(summary.outputDirectory().resolve("extract_manifest.jsonl"));
        String index = Files.readString(summary.outputDirectory().resolve("extract_index.html"));
        String summaryText = Files.readString(summary.outputDirectory().resolve("extract_summary.txt"));
        assertTrue(manifest.contains("\"action\":\"cancelled\""));
        assertTrue(manifest.contains("\"remaining\":2"));
        assertTrue(index.contains("Total rows: 1"));
        assertTrue(summaryText.contains("Cancelled: true"));
        assertTrue(summaryText.contains("Cancelled remaining: 2"));
    }

    @Test
    void midRunCancellationClosesTerminalFilesAfterCompletedItems(@TempDir Path outputRoot) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        HttpRequestResponse second = exchangeThatCancelsWhenUrlIsRead(
                "https://example.com/b.txt",
                cancelled
        );
        ExportProgressListener listener = new ExportProgressListener() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void onProgress(ExportProgress progress) {
            }
        };

        ExportSummary summary = new ExportService(null).exportResolved(
                List.of(exchange("https://example.com/a.txt", "text/plain", "a"), second),
                2,
                outputRoot,
                ExportOptions.defaults(),
                listener
        );

        Path runDirectory = summary.outputDirectory();
        String manifest = Files.readString(runDirectory.resolve("extract_manifest.jsonl"));
        String index = Files.readString(runDirectory.resolve("extract_index.html"));
        String summaryText = Files.readString(runDirectory.resolve("extract_summary.txt"));
        assertTrue(Files.exists(runDirectory.resolve("example.com").resolve("a.txt")));
        assertTrue(manifest.contains("\"action\":\"saved\""));
        assertTrue(manifest.contains("\"action\":\"cancelled\""));
        assertTrue(manifest.contains("\"remaining\":1"));
        assertTrue(index.contains("Total rows: 2"));
        assertTrue(summaryText.contains("Cancelled: true"));
        assertTrue(summaryText.contains("Cancelled remaining: 1"));
    }

    @Test
    void interruptCancellationTerminalizesFilesThenRestoresInterrupt(@TempDir Path outputRoot)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<InterruptResult> future = executor.submit(() -> {
                ExportProgressListener listener = new ExportProgressListener() {
                    @Override
                    public boolean isCancelled() {
                        Thread.currentThread().interrupt();
                        return true;
                    }

                    @Override
                    public void onProgress(ExportProgress progress) {
                    }
                };
                ExportSummary summary = new ExportService(null).exportResolved(
                        List.of(exchange("https://example.com/a.txt", "text/plain", "a")),
                        1,
                        outputRoot,
                        ExportOptions.defaults(),
                        listener
                );
                boolean interruptRestored = Thread.currentThread().isInterrupted();
                Thread.interrupted();
                return new InterruptResult(summary, interruptRestored);
            });

            InterruptResult result = future.get();
            Path runDirectory = result.summary().outputDirectory();
            assertTrue(result.interruptRestored());
            assertTrue(Files.readString(runDirectory.resolve("extract_manifest.jsonl"))
                    .contains("\"action\":\"cancelled\""));
            assertTrue(Files.readString(runDirectory.resolve("extract_index.html"))
                    .contains("Total rows: 1"));
            assertTrue(Files.readString(runDirectory.resolve("extract_summary.txt"))
                    .contains("Cancelled: true"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void interruptDuringBodyCopyStillTerminalizesCancellation(@TempDir Path outputRoot)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<InterruptResult> future = executor.submit(() -> {
                ExportProgressListener listener = new ExportProgressListener() {
                    @Override
                    public boolean isCancelled() {
                        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                            if (AtomicFiles.class.getName().equals(frame.getClassName())
                                    && "copy".equals(frame.getMethodName())) {
                                Thread.currentThread().interrupt();
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public void onProgress(ExportProgress progress) {
                    }
                };
                ExportSummary summary = new ExportService(null).exportResolved(
                        List.of(exchange(
                                "https://example.com/a.bin",
                                "application/octet-stream",
                                new byte[4 * 64 * 1024],
                                ""
                        )),
                        1,
                        outputRoot,
                        ExportOptions.defaults(),
                        listener
                );
                boolean interruptRestored = Thread.currentThread().isInterrupted();
                Thread.interrupted();
                return new InterruptResult(summary, interruptRestored);
            });

            InterruptResult result = future.get();
            Path runDirectory = result.summary().outputDirectory();
            assertTrue(result.interruptRestored());
            assertFalse(Files.exists(runDirectory.resolve("example.com").resolve("a.bin")));
            assertTrue(Files.readString(runDirectory.resolve("extract_manifest.jsonl"))
                    .contains("\"action\":\"cancelled\""));
            assertTrue(Files.exists(runDirectory.resolve("extract_index.html")));
            assertTrue(Files.exists(runDirectory.resolve("extract_summary.txt")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void outOfMemoryClosesTerminalFilesWritesSummaryAndRethrowsOriginal(@TempDir Path outputRoot)
            throws Exception {
        OutOfMemoryError expected = new OutOfMemoryError("synthetic heap exhaustion");
        HttpRequestResponse item = bodyErrorItem("https://example.com/oom.txt", expected);

        OutOfMemoryError actual = assertThrows(
                OutOfMemoryError.class,
                () -> new ExportService(null).exportResolved(
                        List.of(item),
                        1,
                        outputRoot,
                        ExportOptions.defaults(),
                        new NoOpExportProgressListener()
                )
        );

        assertSame(expected, actual);
        Path runDirectory;
        try (var paths = Files.list(outputRoot)) {
            runDirectory = paths.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        assertTrue(Files.exists(runDirectory.resolve("extract_manifest.jsonl")));
        assertTrue(Files.exists(runDirectory.resolve("extract_index.html")));
        assertTrue(Files.readString(runDirectory.resolve("extract_index.html")).contains("Total rows: 0"));
        assertTrue(Files.readString(runDirectory.resolve("extract_summary.txt")).contains("Failed: 1"));
    }

    @Test
    void selectionCancellationClosesAndRecordsTerminalFiles(@TempDir Path outputRoot) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        HttpRequestResponse selected = exchange("https://example.com/", "text/plain", "selected");
        HttpRequestResponse first = exchange("https://example.com/a.txt", "text/plain", "a");
        HttpRequestResponse second = exchange("https://example.com/b.txt", "text/plain", "b");
        SiteMap siteMap = proxy(SiteMap.class, (method, arguments) -> {
            if ("requestResponses".equals(method.getName()) && arguments.length == 1) {
                SiteMapFilter filter = (SiteMapFilter) arguments[0];
                filter.matches(siteMapNode(first));
                cancelled.set(true);
                filter.matches(siteMapNode(second));
                return List.of(first, second);
            }
            return defaultValue(method.getReturnType());
        });
        MontoyaApi api = proxy(MontoyaApi.class, (method, arguments) ->
                "siteMap".equals(method.getName()) ? siteMap : defaultValue(method.getReturnType())
        );
        ExportProgressListener listener = new ExportProgressListener() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void onProgress(ExportProgress progress) {
            }
        };

        ExportSummary summary = new ExportService(api).export(
                List.of(selected),
                true,
                outputRoot,
                ExportOptions.defaults(),
                listener
        );

        Path runDirectory = summary.outputDirectory();
        assertTrue(Files.readString(runDirectory.resolve("extract_manifest.jsonl"))
                .contains("\"action\":\"cancelled\""));
        assertTrue(Files.readString(runDirectory.resolve("extract_index.html"))
                .contains("Total rows: 1"));
        String summaryText = Files.readString(runDirectory.resolve("extract_summary.txt"));
        assertTrue(summaryText.contains("Cancelled: true"));
        assertTrue(summaryText.contains("terminal outcome=cancelled phase=selection processed=0 total=1"));
        assertTrue(summaryText.contains("heapUsedMiB="));
    }

    @Test
    void selectionOutOfMemoryClosesTerminalFilesWritesSummaryAndRethrowsPrimary(@TempDir Path outputRoot)
            throws Exception {
        OutOfMemoryError expected = new OutOfMemoryError("synthetic selection exhaustion");
        HttpRequestResponse selected = exchange("https://example.com/", "text/plain", "selected");
        SiteMap siteMap = proxy(SiteMap.class, (method, arguments) -> {
            if ("requestResponses".equals(method.getName()) && arguments.length == 1) {
                SiteMapFilter filter = (SiteMapFilter) arguments[0];
                SiteMapNode failingNode = proxy(SiteMapNode.class, (nodeMethod, nodeArguments) -> {
                    if ("url".equals(nodeMethod.getName())) {
                        throw expected;
                    }
                    return defaultValue(nodeMethod.getReturnType());
                });
                filter.matches(failingNode);
            }
            return defaultValue(method.getReturnType());
        });
        MontoyaApi api = proxy(MontoyaApi.class, (method, arguments) ->
                "siteMap".equals(method.getName()) ? siteMap : defaultValue(method.getReturnType())
        );

        OutOfMemoryError actual = assertThrows(
                OutOfMemoryError.class,
                () -> new ExportService(api).export(
                        List.of(selected),
                        true,
                        outputRoot,
                        ExportOptions.defaults(),
                        new NoOpExportProgressListener()
                )
        );

        assertSame(expected, actual);
        Path runDirectory = onlyRunDirectory(outputRoot);
        assertTrue(Files.exists(runDirectory.resolve("extract_manifest.jsonl")));
        assertTrue(Files.exists(runDirectory.resolve("extract_index.html")));
        String summaryText = Files.readString(runDirectory.resolve("extract_summary.txt"));
        assertTrue(summaryText.contains("Failed: 1"));
        assertTrue(summaryText.contains("terminal outcome=fatal phase=selection processed=0 total=1"));
        assertTrue(summaryText.contains("saved=0 skipped=0 duplicate=0 failed=1"));
    }

    @Test
    void progressCallbackFailureWritesSummaryAndRethrowsPrimary(@TempDir Path outputRoot)
            throws Exception {
        IllegalStateException expected = new IllegalStateException("synthetic progress failure");
        AtomicReference<String> fatalLog = new AtomicReference<>();
        Logging logging = proxy(Logging.class, (method, arguments) -> {
            if ("logToError".equals(method.getName()) && arguments.length > 0) {
                fatalLog.set(String.valueOf(arguments[0]));
            }
            return defaultValue(method.getReturnType());
        });
        MontoyaApi api = proxy(MontoyaApi.class, (method, arguments) ->
                "logging".equals(method.getName()) ? logging : defaultValue(method.getReturnType())
        );
        ExportProgressListener listener = new ExportProgressListener() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onProgress(ExportProgress progress) {
                throw expected;
            }
        };

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> new ExportService(api).exportResolved(
                        List.of(exchange("https://example.com/a.txt", "text/plain", "a")),
                        1,
                        outputRoot,
                        ExportOptions.defaults(),
                        listener
                )
        );

        assertSame(expected, actual);
        Path runDirectory = onlyRunDirectory(outputRoot);
        assertTrue(Files.exists(runDirectory.resolve("extract_manifest.jsonl")));
        assertTrue(Files.exists(runDirectory.resolve("extract_index.html")));
        String summaryText = Files.readString(runDirectory.resolve("extract_summary.txt"));
        assertTrue(summaryText.contains("Failed: 1"));
        assertTrue(summaryText.contains("terminal outcome=fatal phase=processing processed=0 total=1"));
        assertTrue(fatalLog.get().contains("terminal outcome=fatal phase=processing processed=0 total=1"));
    }

    @Test
    void summaryWriteFailureIsSuppressedOnRunFatalPrimary(@TempDir Path outputRoot)
            throws Exception {
        IllegalStateException expected = new IllegalStateException("synthetic progress failure");
        ExportProgressListener listener = new ExportProgressListener() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onProgress(ExportProgress progress) {
                try {
                    Path runDirectory = onlyRunDirectory(outputRoot);
                    Path summaryDirectory = Files.createDirectory(runDirectory.resolve("extract_summary.txt"));
                    Files.writeString(summaryDirectory.resolve("block-replacement"), "occupied");
                } catch (Exception exception) {
                    throw new AssertionError("failed to arrange summary collision", exception);
                }
                throw expected;
            }
        };

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> new ExportService(null).exportResolved(
                        List.of(exchange("https://example.com/a.txt", "text/plain", "a")),
                        1,
                        outputRoot,
                        ExportOptions.defaults(),
                        listener
                )
        );

        assertSame(expected, actual);
        assertTrue(actual.getSuppressed().length >= 1);
        assertTrue(Files.isDirectory(onlyRunDirectory(outputRoot).resolve("extract_summary.txt")));
    }

    @Test
    void cancellationMetadataCloseFailureRemainsFatal(@TempDir Path outputRoot)
            throws Exception {
        AtomicBoolean arranged = new AtomicBoolean();
        ExportProgressListener listener = new ExportProgressListener() {
            @Override
            public boolean isCancelled() {
                if (arranged.compareAndSet(false, true)) {
                    try {
                        Path runDirectory = onlyRunDirectory(outputRoot);
                        for (String name : List.of("extract_manifest.jsonl", "extract_index.html")) {
                            Path blockingDirectory = Files.createDirectory(runDirectory.resolve(name));
                            Files.writeString(blockingDirectory.resolve("block-replacement"), "occupied");
                        }
                    } catch (Exception exception) {
                        throw new AssertionError("failed to arrange metadata collision", exception);
                    }
                }
                return true;
            }

            @Override
            public void onProgress(ExportProgress progress) {
            }
        };

        assertThrows(
                java.io.IOException.class,
                () -> new ExportService(null).exportResolved(
                        List.of(exchange("https://example.com/a.txt", "text/plain", "a")),
                        1,
                        outputRoot,
                        ExportOptions.defaults(),
                        listener
                )
        );

        Path runDirectory = onlyRunDirectory(outputRoot);
        String summaryText = Files.readString(runDirectory.resolve("extract_summary.txt"));
        assertTrue(summaryText.contains("Failed: 1"));
        assertTrue(summaryText.contains("terminal outcome=fatal phase=terminal-close"));
    }

    @Test
    void metadataCloseFailureAfterFinalProgressIsFatalEvenWhenCancelTurnsOn(
            @TempDir Path outputRoot
    ) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean arranged = new AtomicBoolean();
        ExportProgressListener listener = new ExportProgressListener() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void onProgress(ExportProgress progress) {
                if (progress.processed() != 1
                        || progress.total() != 1
                        || !arranged.compareAndSet(false, true)) {
                    return;
                }
                try {
                    Path runDirectory = onlyRunDirectory(outputRoot);
                    for (String name : List.of("extract_manifest.jsonl", "extract_index.html")) {
                        Path blockingDirectory = Files.createDirectory(runDirectory.resolve(name));
                        Files.writeString(blockingDirectory.resolve("block-replacement"), "occupied");
                    }
                    cancelled.set(true);
                } catch (Exception exception) {
                    throw new AssertionError("failed to arrange final metadata collision", exception);
                }
            }
        };

        assertThrows(
                java.io.IOException.class,
                () -> new ExportService(null).exportResolved(
                        List.of(exchange("https://example.com/a.txt", "text/plain", "a")),
                        1,
                        outputRoot,
                        ExportOptions.defaults(),
                        listener
                )
        );

        Path runDirectory = onlyRunDirectory(outputRoot);
        String summaryText = Files.readString(runDirectory.resolve("extract_summary.txt"));
        assertTrue(summaryText.contains("Failed: 1"));
        assertTrue(summaryText.contains("terminal outcome=fatal phase=terminal-close"));
    }

    @Test
    void cleanupFailureAfterCompletedOutcomeAddsDiagnosticWithoutSecondFailure(@TempDir Path outputRoot)
            throws Exception {
        Path nonEmptyDirectory = Files.createDirectory(outputRoot.resolve("decoded-temp"));
        Files.writeString(nonEmptyDirectory.resolve("child"), "keep directory non-empty");
        ExportSummary summary = new ExportSummary(outputRoot, 1);
        summary.incrementSaved(4, 4);

        ExportService.closeDecodedBody(
                new FileDecodeResult(
                        nonEmptyDirectory,
                        false,
                        "",
                        4,
                        4,
                        ""
                ),
                null,
                summary
        );

        assertEquals(1, summary.savedCount());
        assertEquals(0, summary.failedCount());
        assertTrue(summary.toSummaryFileText().contains("decoded temporary file cleanup failed"));
    }

    @Test
    void cleanupErrorIsSuppressedWhenPrimaryFailureAlreadyExists() {
        AssertionError primary = new AssertionError("primary");
        AssertionError cleanup = new AssertionError("cleanup");

        ExportService.closeDecodedBody(
                () -> {
                    throw cleanup;
                },
                primary,
                new ExportSummary(Path.of("."), 1)
        );

        assertEquals(1, primary.getSuppressed().length);
        assertSame(cleanup, primary.getSuppressed()[0]);
    }

    @Test
    void cleanupErrorWithoutPrimaryRemainsFatal() {
        AssertionError cleanup = new AssertionError("cleanup");

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> ExportService.closeDecodedBody(
                        () -> {
                            throw cleanup;
                        },
                        null,
                        new ExportSummary(Path.of("."), 1)
                )
        );

        assertSame(cleanup, actual);
    }

    @Test
    void concurrentRunsCreateDistinctDirectoriesAtomically(@TempDir Path outputRoot) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ExportSummary>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> new ExportService(null).exportResolved(
                        List.of(),
                        0,
                        outputRoot,
                        ExportOptions.defaults(),
                        new NoOpExportProgressListener()
                )));
            }

            Path first = futures.get(0).get().outputDirectory();
            Path second = futures.get(1).get().outputDirectory();
            assertNotEquals(first, second);
            assertTrue(Files.exists(first.resolve("extract_summary.txt")));
            assertTrue(Files.exists(second.resolve("extract_summary.txt")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateIndexRetainsOnlyLightweightReferences() {
        DuplicateIndex index = new DuplicateIndex();
        index.rememberFirst("hash", Path.of("example.com", "a.txt"), "https://example.com/a.txt");

        DuplicateIndex.DuplicateReference reference = index.find("hash").orElseThrow();
        assertEquals("example.com" + java.io.File.separator + "a.txt", reference.relativePath());
        assertEquals("https://example.com/a.txt", reference.url());
        assertEquals(1, index.size());
        assertEquals(List.of(String.class, String.class), List.of(
                reference.getClass().getRecordComponents()[0].getType(),
                reference.getClass().getRecordComponents()[1].getType()
        ));
        assertFalse(List.of(ExportCandidate.class.getRecordComponents()).stream()
                .anyMatch(component -> component.getType() == byte[].class
                        || component.getType() == HttpRequestResponse.class));
    }

    @Test
    void successfulRunLeavesNoInternalTemporaryFiles(@TempDir Path outputRoot) throws Exception {
        ExportSummary summary = new ExportService(null).exportResolved(
                List.of(exchange("https://example.com/a.txt", "text/plain", "body")),
                1,
                outputRoot,
                ExportOptions.defaults(),
                new NoOpExportProgressListener()
        );

        try (var paths = Files.walk(summary.outputDirectory())) {
            assertFalse(paths
                    .map(path -> path.getFileName() == null ? "" : path.getFileName().toString())
                    .anyMatch(name -> name.startsWith(".decoded-") || name.endsWith(".tmp")));
        }
        String expectedSummary = "Extractor Summary\n"
                + "=================\n"
                + "Output directory: " + summary.outputDirectory() + "\n"
                + "Selected items: 1\n"
                + "Candidate items: 1\n"
                + "Saved: 1\n"
                + "Skipped without response: 0\n"
                + "Duplicate: 0\n"
                + "Failed: 0\n"
                + "Beautified files: 0\n"
                + "Beautify failed: 0\n"
                + "Cancelled: false\n"
                + "Cancelled remaining: 0\n"
                + "Raw bytes: 4\n"
                + "Saved bytes: 4\n"
                + "Beautified bytes: 0\n";
        assertEquals(
                expectedSummary,
                Files.readString(summary.outputDirectory().resolve("extract_summary.txt"))
        );
        assertTrue(summary.toLogMessage().contains("terminal={terminal outcome=success phase=completed"));
    }

    private HttpRequestResponse exchange(String url, String contentType, String body) {
        return exchange(url, contentType, body.getBytes(StandardCharsets.UTF_8), "");
    }

    private HttpRequestResponse exchange(
            String url,
            String contentType,
            byte[] bodyBytes,
            String contentEncoding
    ) {
        HttpRequest request = request(url);
        ByteArray byteArray = proxy(ByteArray.class, (method, arguments) -> switch (method.getName()) {
            case "getBytes" -> bodyBytes;
            case "length" -> bodyBytes.length;
            default -> defaultValue(method.getReturnType());
        });
        HttpResponse response = proxy(HttpResponse.class, (method, arguments) -> switch (method.getName()) {
            case "statusCode" -> (short) 200;
            case "headerValue" -> {
                String name = (String) arguments[0];
                if ("Content-Type".equalsIgnoreCase(name)) {
                    yield contentType;
                }
                if ("Content-Encoding".equalsIgnoreCase(name)) {
                    yield contentEncoding;
                }
                yield null;
            }
            case "body" -> byteArray;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            case "hasResponse" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }

    private HttpRequest request(String url) {
        return proxy(HttpRequest.class, (method, arguments) -> switch (method.getName()) {
            case "method" -> "GET";
            case "url" -> url;
            default -> defaultValue(method.getReturnType());
        });
    }

    private SiteMapNode siteMapNode(HttpRequestResponse item) {
        return proxy(SiteMapNode.class, (method, arguments) -> switch (method.getName()) {
            case "url" -> item.request().url();
            case "requestResponse" -> item;
            default -> defaultValue(method.getReturnType());
        });
    }

    private Path onlyRunDirectory(Path outputRoot) throws Exception {
        try (var paths = Files.list(outputRoot)) {
            return paths.filter(Files::isDirectory).findFirst().orElseThrow();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, ProxyHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> handler.invoke(
                        method,
                        arguments == null ? new Object[0] : arguments
                )
        );
    }

    private HttpRequestResponse malformedBodyItem(String url) {
        HttpRequest request = request(url);
        HttpResponse response = proxy(HttpResponse.class, (method, arguments) -> switch (method.getName()) {
            case "statusCode" -> (short) 200;
            case "headerValue" -> "Content-Type".equalsIgnoreCase((String) arguments[0])
                    ? "text/plain"
                    : null;
            case "body" -> throw new IllegalStateException("broken body");
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            case "hasResponse" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequestResponse malformedHasResponseItem(String url) {
        HttpRequest request = request(url);
        return proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "request" -> request;
            case "hasResponse" -> throw new IllegalStateException("broken hasResponse");
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequestResponse bodyErrorItem(String url, Error bodyError) {
        HttpRequest request = request(url);
        HttpResponse response = proxy(HttpResponse.class, (method, arguments) -> switch (method.getName()) {
            case "statusCode" -> (short) 200;
            case "headerValue" -> null;
            case "body" -> throw bodyError;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            case "hasResponse" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequestResponse exchangeThatCancelsWhenUrlIsRead(String url, AtomicBoolean cancelled) {
        HttpRequest request = proxy(HttpRequest.class, (method, arguments) -> switch (method.getName()) {
            case "method" -> "GET";
            case "url" -> {
                cancelled.set(true);
                yield url;
            }
            default -> defaultValue(method.getReturnType());
        });
        HttpRequestResponse regular = exchange(url, "text/plain", "b");
        return proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> regular.response();
            case "hasResponse" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private interface ProxyHandler {
        Object invoke(java.lang.reflect.Method method, Object[] arguments);
    }

    private record InterruptResult(ExportSummary summary, boolean interruptRestored) {
    }
}
