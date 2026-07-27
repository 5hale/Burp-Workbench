package com.burpworkbench.platform;

import burp.api.montoya.core.Registration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Owns the resources created by one workbench module.
 *
 * <p>Resources are closed in reverse registration order. Closing is idempotent,
 * and one cleanup failure never prevents the remaining cleanup actions.</p>
 */
public final class ModuleLifetime implements AutoCloseable {
    private final String moduleName;
    private final Deque<CloseAction> closeActions = new ArrayDeque<>();
    private boolean closed;

    ModuleLifetime(String moduleName) {
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName");
    }

    public Registration own(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        register(() -> {
            if (registration.isRegistered()) {
                registration.deregister();
            }
        });
        return registration;
    }

    public <T extends AutoCloseable> T own(T resource) {
        Objects.requireNonNull(resource, "resource");
        register(resource::close);
        return resource;
    }

    public void onClose(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        register(cleanup::run);
    }

    synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        Deque<CloseAction> actions;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            actions = new ArrayDeque<>(closeActions);
            closeActions.clear();
        }

        Throwable firstFailure = null;
        while (!actions.isEmpty()) {
            try {
                actions.removeFirst().close();
            } catch (Throwable failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (failure != firstFailure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        rethrow(firstFailure);
    }

    private void register(CloseAction action) {
        synchronized (this) {
            if (!closed) {
                closeActions.addFirst(action);
                return;
            }
        }

        Throwable closeFailure = null;
        try {
            action.close();
        } catch (Throwable failure) {
            closeFailure = failure;
        }
        IllegalStateException closedFailure =
                new IllegalStateException("Module lifetime is already closed: " + moduleName);
        if (closeFailure != null) {
            closedFailure.addSuppressed(closeFailure);
        }
        throw closedFailure;
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException("Module cleanup failed", failure);
    }

    @FunctionalInterface
    private interface CloseAction {
        void close() throws Exception;
    }
}
