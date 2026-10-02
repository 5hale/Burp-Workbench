package com.burpworkbench.platform;

import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** One running and at most one waiting job; only the current revision can publish. */
public final class LatestWork implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Consumer<Runnable> dispatch;
    private Future<?> active;
    private long revision;
    private boolean closed;

    public LatestWork(String name) { this(name, SwingUtilities::invokeLater); }
    public LatestWork(String name, Consumer<Runnable> dispatch) {
        this.dispatch = dispatch;
        executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
    }

    public synchronized <T> void submit(Callable<T> work, Consumer<T> success, Consumer<Throwable> error) {
        if (closed) return;
        cancel();
        long id = revision;
        active = executor.submit(() -> {
            try {
                T result = work.call();
                dispatch.accept(() -> { if (current(id)) success.accept(result); });
            } catch (CancellationException ignored) {
            } catch (Exception failure) {
                dispatch.accept(() -> { if (current(id)) error.accept(failure); });
            }
        });
    }

    public synchronized void cancel() {
        revision++;
        if (active != null) active.cancel(true);
        active = null;
        executor.getQueue().clear();
    }

    private synchronized boolean current(long id) { return !closed && revision == id; }
    @Override public synchronized void close() { closed = true; cancel(); executor.shutdownNow(); }
}
