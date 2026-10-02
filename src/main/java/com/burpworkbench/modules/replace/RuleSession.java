package com.burpworkbench.modules.replace;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable traffic snapshots; storage writes are explicitly debounced by the UI owner. */
final class RuleSession {
    private final RuleStore store;
    private final AtomicReference<RuleStore.State> state;
    private boolean dirty;
    private String status;

    RuleSession(RuleStore store) {
        this.store = store;
        RuleStore.State loaded;
        try {
            loaded = store.load();
            // The old global flag is no longer a user setting. Individual rule On remains authoritative.
            loaded = new RuleStore.State(loaded.rules(), true);
            status = "Project rules loaded";
        } catch (RuleStore.StoreException failure) {
            loaded = new RuleStore.State(List.of(), false);
            status = "Load failed: " + failure.kind() + " · stored data preserved";
        }
        state = new AtomicReference<>(loaded);
    }

    RuleStore.State snapshot() { return state.get(); }
    synchronized String status() { return status; }
    synchronized boolean dirty() { return dirty; }

    synchronized void rulesChanged(List<RuleDraft> rules) {
        update(new RuleStore.State(rules, state.get().enabled()));
    }

    synchronized void enabledChanged(boolean enabled) {
        // Compatibility entry point for callers predating the always-on UI policy.
    }

    private void update(RuleStore.State changed) {
        if (state.get().equals(changed)) return;
        state.set(changed);
        dirty = true;
        status = "Unsaved project changes";
    }

    synchronized void flush() {
        if (!dirty) return;
        try {
            store.save(state.get());
            dirty = false;
            status = "Project rules saved";
        } catch (RuleStore.StoreException failure) {
            status = "Save failed: " + failure.kind() + " · current rules remain in memory";
        }
    }
}
