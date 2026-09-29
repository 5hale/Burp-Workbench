package com.burpworkbench.modules.replace;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.ui.hotkey.HotKey;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the Replace++ UI, project rules, hotkey and Proxy handlers as one module resource. */
final class ReplaceRuntime implements AutoCloseable {
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean unloaded = new AtomicBoolean();
    private final MontoyaApi api;
    private ProxyTabMount mount;
    private ReplacePanel panel;
    private HotkeyBinding hotkey;
    private HotkeySettingsDialog hotkeyDialog;
    private Timer retry;
    private Timer saveTimer;
    private RuleSession session;
    private volatile ProxyTrafficHandler traffic;
    private Registration requests, responses;
    private int attempts;

    ReplaceRuntime(MontoyaApi api) {
        this.api = java.util.Objects.requireNonNull(api, "api");
    }

    void start() {
        if (unloaded.get() || !started.compareAndSet(false, true)) return;
        session = new RuleSession(new RuleStore(api.persistence().extensionData()));
        log("START burp=" + api.burpSuite().version() + " compileApi=2025.12 " + session.status());
        SwingUtilities.invokeLater(() -> {
            if (unloaded.get()) return;
            try {
                mount = new ProxyTabMount(this::log);
                saveTimer = new Timer(400, event -> flushRules());
                saveTimer.setRepeats(false);
                RuleStore.State initial = session.snapshot();
                panel = new ReplacePanel(initial.rules(), initial.enabled(),
                        new BurpPreviewEditor(api.userInterface(), false),
                        new BurpPreviewEditor(api.userInterface(), true),
                        rules -> { session.rulesChanged(rules); changed(); },
                        enabled -> { session.enabledChanged(enabled); changed(); },
                        this::openHotkeys);
                panel.storageStatus(session.status());
                api.userInterface().applyThemeToComponent(panel);
                registerHotkey();
                retry = new Timer(750, event -> attempt());
                retry.setInitialDelay(0); retry.start();
            } catch (RuntimeException | LinkageError error) {
                log("INIT_FAILED type=" + error.getClass().getSimpleName());
                close();
            }
        });
    }

    private void changed() {
        if (unloaded.get()) return;
        panel.storageStatus(session.status());
        saveTimer.restart();
    }

    private void flushRules() {
        if (session == null) return;
        session.flush();
        if (panel != null) panel.storageStatus(session.status());
        if (session.dirty()) log("PROJECT_SAVE_FAILED " + session.status());
    }

    private void startTraffic() {
        if (unloaded.get() || traffic != null) return;
        traffic = new ProxyTrafficHandler(() -> unloaded.get()
                ? new RuleStore.State(java.util.List.of(), false) : session.snapshot(), this::log);
        requests = api.proxy().registerRequestHandler(traffic);
        responses = api.proxy().registerResponseHandler(traffic);
        log("PROXY_HANDLERS_READY phase=ToBeSent rules=" + session.snapshot().rules().size()
                + " enabled=" + session.snapshot().enabled());
    }

    private void registerHotkey() {
        hotkey = new HotkeyBinding((shortcut, callback) -> api.userInterface().registerHotKeyHandler(
                HotKey.hotKey("Open replace ++", shortcut.value()), callback::accept),
                this::onHotkey, this::log, SwingUtilities::invokeLater);
        HotkeyBinding.Result result = hotkey.assign(Shortcut.defaultShortcut());
        if (!result.success()) log("HOTKEY_INITIAL_KEY_UNAVAILABLE reason=" + result.message());
    }

    private void onHotkey(ScopeCapture captured) {
        if (unloaded.get()) return;
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        log("HOTKEY_DISPATCHED focusOwner=" + (owner == null ? "none" : owner.getClass().getName()));
        boolean focused = mount != null && mount.focus();
        if (panel != null) {
            if (captured.scope().isPresent()) {
                HotkeyScope scope = captured.scope().get();
                panel.addFromScope(scope.origin(), scope.path());
                log("HOTKEY_NEW_RULE source=" + captured.source() + " selected=" + captured.selectedCount());
            } else {
                panel.scopeNotImported(captured.source());
            }
            panel.hotkeyReceived(focused);
        }
        log("HOTKEY_FOCUS_RESULT opened=" + focused);
    }

    private void openHotkeys() {
        if (unloaded.get() || hotkey == null || panel == null) return;
        if (hotkeyDialog == null || !hotkeyDialog.isDisplayable()) {
            Window owner = SwingUtilities.getWindowAncestor(panel);
            hotkeyDialog = new HotkeySettingsDialog(owner, hotkey,
                    component -> api.userInterface().applyThemeToComponent(component));
        }
        hotkeyDialog.showHotkeys();
    }

    private void attempt() {
        if (unloaded.get()) { if (retry != null) retry.stop(); return; }
        attempts++;
        try {
            Frame frame = api.userInterface().swingUtils().suiteFrame();
            if (frame != null && mount.install(frame, panel)) {
                retry.stop(); startTraffic(); log("READY position=RIGHT title=replace++");
            } else if (attempts >= 8) {
                log("STOPPED no unique Proxy tab target; unload other extensions with the same tab title");
                close();
            }
        } catch (RuntimeException | LinkageError error) {
            log("FAILED type=" + error.getClass().getName());
            close();
        }
    }

    @Override public void close() {
        unloaded.set(true);
        ProxyTrafficHandler running = traffic;
        if (running != null) running.close();
        Runnable cleanup = () -> {
            if (traffic != null) traffic.close();
            requests = deregister(requests, "request");
            responses = deregister(responses, "response");
            if (retry != null) { retry.stop(); retry = null; }
            if (saveTimer != null) { saveTimer.stop(); saveTimer = null; }
            flushRules();
            if (hotkeyDialog != null) {
                try { hotkeyDialog.dispose(); hotkeyDialog = null; }
                catch (RuntimeException error) { log("HOTKEY_DIALOG_CLOSE_FAILED type=" + error.getClass().getName()); }
            }
            if (hotkey != null) {
                hotkey.close();
                if (hotkey.pendingCount() == 0) hotkey = null;
            }
            if (panel != null) {
                try { panel.closeDialogs(); }
                catch (RuntimeException error) { log("DIALOG_CLOSE_FAILED type=" + error.getClass().getName()); }
            }
            if (mount != null) {
                try { mount.remove(); mount = null; }
                catch (RuntimeException error) { log("TAB_REMOVE_FAILED type=" + error.getClass().getSimpleName()); }
            }
            panel = null;
            log("DETACHED hotkeyPending=" + (hotkey != null && hotkey.pendingCount() > 0)
                    + " handlerPending=" + (requests != null || responses != null));
        };
        if (SwingUtilities.isEventDispatchThread()) cleanup.run();
        else {
            try { SwingUtilities.invokeAndWait(cleanup); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); SwingUtilities.invokeLater(cleanup); }
            catch (java.lang.reflect.InvocationTargetException error) { log("UNLOAD_FAILED " + error.getCause()); }
        }
    }

    private Registration deregister(Registration registration, String direction) {
        if (registration == null) return null;
        try { registration.deregister(); return null; }
        catch (RuntimeException failure) {
            log("HANDLER_REMOVE_FAILED direction=" + direction + " type=" + failure.getClass().getSimpleName());
            return registration;
        }
    }

    private void log(String message) {
        try { api.logging().logToOutput("[replace++] " + message); }
        catch (RuntimeException | LinkageError ignored) {
            // Host logging can disappear during unload; resource cleanup must still continue.
        }
    }
}
