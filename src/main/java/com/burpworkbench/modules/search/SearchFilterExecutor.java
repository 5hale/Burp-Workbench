package com.burpworkbench.modules.search;

import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class SearchFilterExecutor {
    private SearchFilterExecutor() {
    }

    static ThreadPoolExecutor create(ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1),
                threadFactory
        );
    }

    static void cancelAndPurge(ThreadPoolExecutor executor, Future<?> task) {
        if (task == null) {
            return;
        }
        task.cancel(true);
        if (task instanceof Runnable queuedTask) {
            executor.remove(queuedTask);
        }
        executor.purge();
    }
}
