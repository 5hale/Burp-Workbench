package com.burpworkbench.modules.compare;

import java.util.concurrent.*;
import java.util.function.*;

/** At most one running computation and one queued replacement; stale callbacks never publish. */
final class LatestComparison implements AutoCloseable {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),task->{Thread t=new Thread(task,"compare-worker");t.setDaemon(true);return t;});
    private final Consumer<Runnable> dispatch;
    private Future<?> active;
    private volatile long generation;
    private volatile boolean closed;
    LatestComparison(Consumer<Runnable> dispatch) { this.dispatch=dispatch; }
    synchronized void submit(Callable<Comparison.View> compute,Consumer<Comparison.View> success,Consumer<String> failure) {
        if(closed)return;
        cancel(); long run=generation;
        active=worker.submit(()->{
            try {
                Comparison.View value=compute.call();
                dispatch.accept(()->{if(!closed&&generation==run)success.accept(value);});
            } catch(CancellationException ignored) {
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();
            } catch(Exception error) {
                String message=error instanceof DiffEngine.Limit?error.getMessage():"Comparison failed: "+error.getClass().getSimpleName();
                dispatch.accept(()->{if(!closed&&generation==run)failure.accept(message);});
            }
        });
    }
    synchronized void cancel(){generation++; if(active!=null)active.cancel(true);active=null;worker.getQueue().clear();}
    @Override public synchronized void close(){closed=true;cancel();worker.shutdownNow();}
}
