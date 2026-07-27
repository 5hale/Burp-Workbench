package com.burpworkbench.modules.search;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Grants at most one source-scan permit across all Search++ windows.
 *
 * <p>The coordinator never queues work and never cancels the current owner.
 * A caller either acquires the permit immediately or leaves the existing run
 * untouched.</p>
 */
final class SearchExecutionCoordinator implements AutoCloseable {
    private final AtomicReference<Permit> activePermit = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public Optional<Permit> tryAcquire(Object owner, long runIdentity) {
        Objects.requireNonNull(owner, "owner");
        if (closed.get()) {
            return Optional.empty();
        }

        Permit candidate = new Permit(this, owner, runIdentity);
        if (!activePermit.compareAndSet(null, candidate)) {
            return Optional.empty();
        }
        if (closed.get()) {
            activePermit.compareAndSet(candidate, null);
            candidate.markReleased();
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    public boolean isBusy() {
        return activePermit.get() != null;
    }

    @Override
    public void close() {
        closed.set(true);
        // A running scan retains its permit until its executing thread leaves.
        // Closing prevents every new acquisition without pretending that the
        // active scan has already terminated.
    }

    private void release(Permit permit) {
        activePermit.compareAndSet(permit, null);
    }

    public static final class Permit implements AutoCloseable {
        private final SearchExecutionCoordinator coordinator;
        private final Object owner;
        private final long runIdentity;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(SearchExecutionCoordinator coordinator, Object owner, long runIdentity) {
            this.coordinator = coordinator;
            this.owner = owner;
            this.runIdentity = runIdentity;
        }

        public Object owner() {
            return owner;
        }

        public long runIdentity() {
            return runIdentity;
        }

        public boolean isReleased() {
            return released.get();
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                coordinator.release(this);
            }
        }

        private void markReleased() {
            released.set(true);
        }
    }
}
