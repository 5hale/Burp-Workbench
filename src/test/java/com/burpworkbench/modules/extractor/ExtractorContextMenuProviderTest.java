package com.burpworkbench.modules.extractor;

import org.junit.jupiter.api.Test;

import javax.swing.SwingWorker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractorContextMenuProviderTest {
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
}
