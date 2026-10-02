package com.burpworkbench.modules.decoder;

import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** At most one active transform per tab. Stale work cannot repaint newer input or a closed tab. */
final class LatestTask implements AutoCloseable {
    private final com.burpworkbench.platform.LatestWork worker;
    LatestTask(){this(SwingUtilities::invokeLater);}
    LatestTask(Consumer<Runnable> dispatch){worker=new com.burpworkbench.platform.LatestWork("decoder-transform",dispatch);}
    synchronized <T> void submit(Callable<T> work,Consumer<T> success,Consumer<Throwable> error){
        worker.submit(work,success,error);
    }
    synchronized void cancel(){worker.cancel();}
    @Override public synchronized void close(){worker.close();}
}
