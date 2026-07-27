package com.burpworkbench.modules.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchFilterExecutorTest {
    @Test
    void cancelledQueuedSnapshotsAreRemovedBeforeTheNewestIsSubmitted() throws Exception {
        ThreadPoolExecutor executor = SearchFilterExecutor.create(runnable -> {
            Thread thread = new Thread(runnable, "search-filter-executor-test");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch releaseRunning = new CountDownLatch(1);
        try {
            Future<?> active = executor.submit(() -> {
                running.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        releaseRunning.await();
                        released = true;
                    } catch (InterruptedException ignored) {
                        // Simulate filtering code that is slow to observe cancellation.
                    }
                }
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));

            SearchFilterExecutor.cancelAndPurge(executor, active);
            Future<?> staleQueued = executor.submit(() -> {
            });
            assertEquals(1, executor.getQueue().size());

            SearchFilterExecutor.cancelAndPurge(executor, staleQueued);
            Future<?> newestQueued = executor.submit(() -> {
            });

            assertEquals(1, executor.getQueue().size());
            assertSame(newestQueued, executor.getQueue().peek());
        } finally {
            releaseRunning.countDown();
            executor.shutdownNow();
        }
    }
}
