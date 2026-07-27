package com.burpworkbench.modules.search;

/**
 * Owns the cancellable lifetime of one Search++ run.
 *
 * <p>UI widgets and SwingWorker identity remain in the view. This controller
 * owns the run id, cancellation state, executing thread and global scan permit.</p>
 */
final class SearchRunController implements AutoCloseable {
    enum State {
        IDLE,
        RUNNING,
        CANCELLING
    }

    private ActiveRun activeRun;

    synchronized boolean begin(long runIdentity, SearchExecutionCoordinator.Permit permit) {
        if (activeRun != null) {
            permit.close();
            return false;
        }
        activeRun = new ActiveRun(runIdentity, permit);
        return true;
    }

    synchronized void attachThread(long runIdentity, Thread thread) {
        ActiveRun run = activeRun;
        if (run == null || run.runIdentity != runIdentity) {
            thread.interrupt();
            return;
        }
        run.thread = thread;
        if (run.cancellationRequested) {
            thread.interrupt();
        }
    }

    void detachThread(long runIdentity, Thread thread) {
        SearchExecutionCoordinator.Permit permit = null;
        Runnable terminalAction = null;
        synchronized (this) {
            ActiveRun run = activeRun;
            if (run != null && run.runIdentity == runIdentity && run.thread == thread) {
                run.thread = null;
                if (run.terminalRequested) {
                    activeRun = null;
                    permit = run.permit;
                    terminalAction = run.terminalAction;
                }
            }
        }
        if (permit != null) {
            permit.close();
        }
        runTerminalAction(terminalAction);
    }

    boolean requestCancellation() {
        Thread thread;
        synchronized (this) {
            ActiveRun run = activeRun;
            if (run == null) {
                return false;
            }
            run.cancellationRequested = true;
            thread = run.thread;
        }
        if (thread != null) {
            thread.interrupt();
        }
        return true;
    }

    synchronized boolean cancellationRequested(long runIdentity) {
        return activeRun == null
                || activeRun.runIdentity != runIdentity
                || activeRun.cancellationRequested;
    }

    synchronized State state() {
        if (activeRun == null) {
            return State.IDLE;
        }
        return activeRun.cancellationRequested ? State.CANCELLING : State.RUNNING;
    }

    boolean finish(long runIdentity) {
        return finish(runIdentity, null);
    }

    boolean finish(long runIdentity, Runnable terminalAction) {
        SearchExecutionCoordinator.Permit permit = null;
        Runnable actionToRun = null;
        synchronized (this) {
            ActiveRun run = activeRun;
            if (run == null || run.runIdentity != runIdentity) {
                return false;
            }
            run.terminalRequested = true;
            if (terminalAction != null) {
                run.terminalAction = terminalAction;
            }
            if (run.thread == null) {
                activeRun = null;
                permit = run.permit;
                actionToRun = run.terminalAction;
            }
        }
        if (permit != null) {
            permit.close();
        }
        runTerminalAction(actionToRun);
        return true;
    }

    @Override
    public void close() {
        Thread thread;
        SearchExecutionCoordinator.Permit permit = null;
        synchronized (this) {
            ActiveRun run = activeRun;
            if (run == null) {
                return;
            }
            run.cancellationRequested = true;
            run.terminalRequested = true;
            thread = run.thread;
            if (thread == null) {
                activeRun = null;
                permit = run.permit;
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
        if (permit != null) {
            permit.close();
        }
    }

    private void runTerminalAction(Runnable terminalAction) {
        if (terminalAction == null) {
            return;
        }
        try {
            terminalAction.run();
        } catch (Throwable ignored) {
            // Completion notification must never replace the worker outcome.
        }
    }

    private static final class ActiveRun {
        private final long runIdentity;
        private final SearchExecutionCoordinator.Permit permit;
        private boolean cancellationRequested;
        private boolean terminalRequested;
        private Thread thread;
        private Runnable terminalAction;

        private ActiveRun(long runIdentity, SearchExecutionCoordinator.Permit permit) {
            this.runIdentity = runIdentity;
            this.permit = permit;
        }
    }
}
