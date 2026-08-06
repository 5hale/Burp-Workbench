package com.burpworkbench.modules.extractor;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingWorker;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractorContextMenuProviderTest {
    @Test
    void searchPlusExportKeepsSameEndpointTransactionsWhileMenuRetainsResolverPolicy(
            @TempDir Path outputRoot
    ) throws Exception {
        String url = "https://example.com/api/items";
        HttpRequestResponse first = requestResponse("GET", url, "first body");
        HttpRequestResponse second = requestResponse("GET", url, "second body");
        HttpRequestResponse duplicateBody = requestResponse("GET", url, "first body");
        ExtractorContextMenuProvider provider = new ExtractorContextMenuProvider(null);

        ExportSummary searchPlusSummary = provider.exportSearchPlusSelection(
                List.of(first, second, duplicateBody),
                outputRoot,
                ExportOptions.defaults(),
                new NoOpExportProgressListener()
        );
        ExportSummary menuSummary = provider.exportMenuSelection(
                List.of(first, second),
                false,
                outputRoot,
                ExportOptions.defaults(),
                new NoOpExportProgressListener()
        );

        assertTrue(searchPlusSummary.toSummaryFileText().contains("Candidate items: 3"));
        assertEquals(2, searchPlusSummary.savedCount());
        assertEquals(1, searchPlusSummary.duplicateCount());
        assertTrue(menuSummary.toSummaryFileText().contains("Candidate items: 1"));
        assertEquals(1, menuSummary.savedCount());
    }

    @Test
    void cancellationWaitsForActualBackgroundFinally() throws Exception {
        AtomicBoolean removed = new AtomicBoolean();
        ExtractorContextMenuProvider.ActiveExport active =
                new ExtractorContextMenuProvider.ActiveExport(null, ignored -> removed.set(true));

        assertTrue(active.beginBackground());
        active.cancelAndClose();
        assertFalse(active.awaitBackground(1, TimeUnit.MILLISECONDS));
        assertFalse(removed.get());

        active.finishBackground();
        assertTrue(active.awaitBackground(1, TimeUnit.SECONDS));
        assertTrue(removed.get());
    }

    @Test
    void cancellationBeforeWorkerStartsCompletesLifecycleImmediately() throws Exception {
        AtomicBoolean removed = new AtomicBoolean();
        ExtractorContextMenuProvider.ActiveExport active =
                new ExtractorContextMenuProvider.ActiveExport(null, ignored -> removed.set(true));

        active.cancelAndClose();

        assertTrue(active.awaitBackground(1, TimeUnit.SECONDS));
        assertTrue(removed.get());
        assertFalse(active.beginBackground());
    }

    @Test
    void cancellationUsesCooperativeTokenWithoutInterruptingRunningWorker() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExtractorContextMenuProvider.ActiveExport active =
                new ExtractorContextMenuProvider.ActiveExport(null, ignored -> {
                });
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                active.beginBackground();
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    active.finishBackground();
                }
                return null;
            }
        };
        active.attachWorker(worker);
        worker.execute();
        assertTrue(started.await(1, TimeUnit.SECONDS));

        active.cancelAndClose();
        assertFalse(active.awaitBackground(1, TimeUnit.MILLISECONDS));
        release.countDown();

        assertTrue(active.awaitBackground(1, TimeUnit.SECONDS));
        assertFalse(interrupted.get());
    }

    private HttpRequestResponse requestResponse(
            String requestMethod,
            String url,
            String responseBody
    ) {
        HttpRequest request = proxy(HttpRequest.class, (method, arguments) -> switch (method.getName()) {
            case "method" -> requestMethod;
            case "url" -> url;
            default -> defaultValue(method.getReturnType());
        });
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        ByteArray bodyBytes = proxy(ByteArray.class, (method, arguments) -> switch (method.getName()) {
            case "length" -> body.length;
            case "getByte" -> body[(int) arguments[0]];
            case "getBytes" -> body.clone();
            default -> defaultValue(method.getReturnType());
        });
        HttpResponse response = proxy(HttpResponse.class, (method, arguments) -> switch (method.getName()) {
            case "statusCode" -> (short) 200;
            case "body" -> bodyBytes;
            case "headerValue" -> "Content-Type".equalsIgnoreCase((String) arguments[0])
                    ? "text/plain; charset=utf-8"
                    : null;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            case "hasResponse" -> true;
            default -> defaultValue(method.getReturnType());
        });
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
}
