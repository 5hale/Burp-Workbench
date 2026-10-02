package com.burpworkbench.modules.replace;

import burp.api.montoya.core.Registration;
import burp.api.montoya.ui.hotkey.HotKeyEvent;
import com.burpworkbench.platform.WorkbenchKeys;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns one active Montoya shortcut and retries registrations that failed to deregister. */
final class HotkeyBinding implements AutoCloseable {
    @FunctionalInterface interface Registrar {
        Registration register(Shortcut shortcut, Consumer<HotKeyEvent> callback);
    }

    record Result(boolean success, String message) {}

    private final Registrar registrar;
    private final Consumer<ScopeCapture> action;
    private final Consumer<String> log;
    private final Consumer<Runnable> dispatch;
    private final List<Registration> pending = new ArrayList<>();
    private volatile Object activeToken;
    private volatile boolean closed;
    private Registration active;
    private Shortcut current;
    private final WorkbenchKeys.Delivery delivery=new WorkbenchKeys.Delivery();

    synchronized boolean matches(KeyEvent event) {
        if(closed||current==null)return false;
        try{return current.equals(Shortcut.fromKeyEvent(event));}catch(IllegalArgumentException ignored){return false;}
    }
    void fromWorkbench(KeyEvent event,Component focus) {
        Object token;synchronized(this){if(!matches(event))return;token=activeToken;}
        deliver(token,event,ScopeCapture.fromWorkbench(focus,log));
    }
    private void deliver(Object token,InputEvent event,ScopeCapture captured) {
        synchronized(this){if(closed||activeToken!=token||captured==null||!delivery.claim(event))return;}
        dispatch.accept(()->{
            if(!closed&&activeToken==token)action.accept(captured);
        });
    }

    HotkeyBinding(Registrar registrar, Runnable action, Consumer<String> log) {
        this(registrar, action, log, Runnable::run);
    }

    HotkeyBinding(Registrar registrar, Runnable action, Consumer<String> log,
                  Consumer<Runnable> dispatch) {
        this(registrar, captured -> action.run(), log, dispatch);
    }

    HotkeyBinding(Registrar registrar, Consumer<ScopeCapture> action, Consumer<String> log,
                  Consumer<Runnable> dispatch) {
        this.registrar = Objects.requireNonNull(registrar);
        this.action = Objects.requireNonNull(action);
        this.log = Objects.requireNonNull(log);
        this.dispatch = Objects.requireNonNull(dispatch);
    }

    synchronized Result assign(Shortcut shortcut) {
        if (closed) return new Result(false, "The shortcut is closed. Reload Burp Workbench.");
        if (shortcut == null) return new Result(false, "Choose a shortcut, or use Clear.");
        retryPending();
        if (shortcut.equals(current) && active != null) {
            return new Result(true, "Already assigned: " + shortcut.value());
        }

        Object token = new Object();
        Registration candidate = null;
        try {
            candidate = registrar.register(shortcut, event -> {
                boolean accepted = !closed && activeToken == token;
                if (!accepted) return;
                InputEvent input=null;
                try{if(event!=null)input=event.inputEvent();}catch(RuntimeException ignored){}
                Component focus=WorkbenchKeys.context(input);
                if(WorkbenchKeys.capturingShortcut(focus)||(input!=null&&WorkbenchKeys.capturingShortcut(input.getComponent())))return;
                ScopeCapture captured=WorkbenchKeys.surface(focus)?ScopeCapture.fromWorkbench(focus,log):ScopeCapture.from(event,log);
                deliver(token,input,captured);
            });
            if (candidate == null) {
                log.accept("HOTKEY_REGISTER_FAILED key=" + shortcut.value() + " reason=null_registration");
                return new Result(false, "Burp did not create a hotkey registration. The previous key remains active.");
            }
            if (!candidate.isRegistered()) {
                retire(candidate);
                log.accept("HOTKEY_REGISTER_FAILED key=" + shortcut.value() + " reason=not_registered");
                return new Result(false, "Burp did not activate that key. The previous key remains active.");
            }
        } catch (RuntimeException error) {
            retire(candidate);
            log.accept("HOTKEY_REGISTER_FAILED key=" + shortcut.value() + " type=" + error.getClass().getName()
                    + " message=" + error.getMessage());
            return new Result(false, "Burp rejected the shortcut: " + explain(error)
                    + ". The previous key remains active.");
        }

        Registration previous = active;
        active = candidate;
        current = shortcut;
        activeToken = token;
        delivery.reset();
        retire(previous);
        return new Result(true, "Assigned " + shortcut.value() + pendingSuffix());
    }

    synchronized Result clear() {
        if (closed) return new Result(false, "The shortcut is closed. Reload Burp Workbench.");
        retryPending();
        Registration previous = active;
        activeToken = null;
        active = null;
        current = null;
        retire(previous);
        return new Result(true, "Shortcut disabled" + pendingSuffix());
    }

    synchronized Shortcut current() { return current; }

    synchronized int pendingCount() { return pending.size(); }

    synchronized boolean isClosed() { return closed; }

    synchronized Result retryCleanup() {
        retryPending();
        return new Result(pending.isEmpty(), pending.isEmpty()
                ? "Previous registrations are released." : pending.size() + " registration(s) still await cleanup.");
    }

    @Override public synchronized void close() {
        closed = true;
        activeToken = null;
        Registration previous = active;
        active = null;
        current = null;
        retryPending();
        retire(previous);
    }

    private void retryPending() {
        if (pending.isEmpty()) return;
        List<Registration> retry = new ArrayList<>(pending);
        pending.clear();
        for (Registration registration : retry) retire(registration);
    }

    private void retire(Registration registration) {
        if (registration == null) return;
        try {
            registration.deregister();
        } catch (RuntimeException error) {
            pending.add(registration);
            log.accept("HOTKEY_UNREGISTER_FAILED type=" + error.getClass().getName()
                    + " message=" + error.getMessage() + " pending=" + pending.size());
        }
    }

    private String pendingSuffix() {
        return pending.isEmpty() ? "." : "; " + pending.size() + " previous registration(s) await cleanup.";
    }

    private static String explain(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
