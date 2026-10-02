package com.burpworkbench.modules.replace;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.ui.hotkey.HotKey;
import com.burpworkbench.platform.WorkbenchKeys;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the Replace++ UI, project rules, hotkey and Proxy handlers as one module resource. */
final class ReplaceRuntime implements AutoCloseable {
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean unloaded = new AtomicBoolean();
    private final MontoyaApi api;
    private final java.util.function.BiFunction<burp.api.montoya.ui.UserInterface,Boolean,PreviewEditor> editors;
    private final HotkeyBinding.Registrar registrar;
    private final java.util.function.Supplier<Component> root;
    private Registration suiteTab;
    private ProxyTabMount mount;
    private Timer retry;
    private int attempts;
    private ReplacePanel panel;
    private ForwardPanel forwardPanel;
    private ForwardSession forwardSession;
    private JPanel modulePanel;
    private JTabbedPane views;
    private HotkeyBinding hotkey;
    private WorkbenchKeys localKeys;
    private HotkeySettingsDialog hotkeyDialog;
    private Timer saveTimer;
    private RuleSession session;
    private volatile ProxyTrafficHandler traffic;
    private Registration requests, responses, contextMenu;
    private ReplaceContextMenu menuProvider;

    ReplaceRuntime(MontoyaApi api) {
        this(api,(ui,readOnly)->new BurpPreviewEditor(ui,readOnly));
    }
    ReplaceRuntime(MontoyaApi api,java.util.function.BiFunction<burp.api.montoya.ui.UserInterface,Boolean,PreviewEditor> editors) {
        this(api,editors,null);
    }
    ReplaceRuntime(MontoyaApi api,java.util.function.BiFunction<burp.api.montoya.ui.UserInterface,Boolean,PreviewEditor> editors,HotkeyBinding.Registrar registrar) {
        this(api,editors,registrar,()->api.userInterface().swingUtils().suiteFrame());
    }
    ReplaceRuntime(MontoyaApi api,java.util.function.BiFunction<burp.api.montoya.ui.UserInterface,Boolean,PreviewEditor> editors,HotkeyBinding.Registrar registrar,java.util.function.Supplier<Component> root) {
        this.api = java.util.Objects.requireNonNull(api, "api");
        this.root=java.util.Objects.requireNonNull(root);
        this.editors=java.util.Objects.requireNonNull(editors);
        this.registrar=registrar!=null?registrar:(shortcut,callback)->api.userInterface().registerHotKeyHandler(
                HotKey.hotKey("Open replace ++",shortcut.value()),callback::accept);
    }

    void start() {
        if (unloaded.get() || !started.compareAndSet(false, true)) return;
        var project = api.persistence().extensionData();
        session = new RuleSession(new RuleStore(project));
        forwardSession = new ForwardSession(project);
        log("START burp=" + api.burpSuite().version() + " compileApi=2025.12 " + session.status());
        SwingUtilities.invokeLater(() -> {
            if (unloaded.get()) return;
            try {
                saveTimer = new Timer(400, event -> flushRules());
                saveTimer.setRepeats(false);
                RuleStore.State initial = session.snapshot();
                panel = new ReplacePanel(initial.rules(), initial.enabled(),
                        editors.apply(api.userInterface(), false),
                        editors.apply(api.userInterface(), true),
                        rules -> { session.rulesChanged(rules); changed(); },
                        enabled -> { session.enabledChanged(enabled); changed(); },
                        this::openHotkeys);
                panel.storageStatus(session.status());
                forwardPanel = new ForwardPanel(forwardSession.snapshot(),
                        rules -> { forwardSession.rulesChanged(rules); changed(); },
                        enabled -> { forwardSession.enabledChanged(enabled); changed(); }, this::openHotkeys);
                forwardPanel.storageStatus(forwardSession.status());
                views = new JTabbedPane(); views.addTab("Replace",panel); views.addTab("Forward",forwardPanel);
                modulePanel = new JPanel(new BorderLayout()); modulePanel.add(views);
                api.userInterface().applyThemeToComponent(modulePanel);
                registerHotkey();
                menuProvider=new ReplaceContextMenu(this::onHotkey,this::log);
                contextMenu=api.userInterface().registerContextMenuItemsProvider(menuProvider);
                mount = new ProxyTabMount(this::log);
                retry = new Timer(600, event -> mountPanel());
                retry.start(); mountPanel();
                startTraffic();
            } catch (RuntimeException | LinkageError error) {
                log("INIT_FAILED type=" + error.getClass().getSimpleName());
                close();
            }
        });
    }

    private void mountPanel() {
        if(unloaded.get()){if(retry!=null)retry.stop();return;}
        try {
            if(mount.install(root.get(),modulePanel)){retry.stop();return;}
            if(++attempts>=8){retry.stop();suiteTab=api.userInterface().registerSuiteTab("replace ++",modulePanel);}
        } catch(RuntimeException | LinkageError failure){log("MOUNT_FAILED "+failure.getClass().getSimpleName());retry.stop();}
    }

    void fromMenu(Component source) { onHotkey(ScopeCapture.fromWorkbench(source,this::log)); }

    private void changed() {
        if (unloaded.get()) return;
        panel.storageStatus(session.status());
        if(forwardPanel!=null)forwardPanel.storageStatus(forwardSession.status());
        saveTimer.restart();
    }

    private void flushRules() {
        if (session == null) return;
        session.flush();
        if(forwardSession!=null){forwardSession.flush();if(forwardPanel!=null)forwardPanel.storageStatus(forwardSession.status());}
        if (panel != null) panel.storageStatus(session.status());
        if (session.dirty()) log("PROJECT_SAVE_FAILED " + session.status());
    }

    private void startTraffic() {
        if (unloaded.get() || traffic != null) return;
        traffic = new ProxyTrafficHandler(() -> unloaded.get()
                ? new RuleStore.State(java.util.List.of(), false) : session.snapshot(),
                () -> unloaded.get() ? ForwardSession.State.empty() : forwardSession.snapshot(), this::log);
        requests = api.proxy().registerRequestHandler(traffic);
        responses = api.proxy().registerResponseHandler(traffic);
        log("PROXY_HANDLERS_READY phase=ToBeSent rules=" + session.snapshot().rules().size()
                + " enabled=" + session.snapshot().enabled());
    }

    private void registerHotkey() {
        hotkey = new HotkeyBinding(registrar,
                this::onHotkey, this::log, SwingUtilities::invokeLater);
        localKeys=new WorkbenchKeys(hotkey::matches,hotkey::fromWorkbench);localKeys.install();
        HotkeyBinding.Result result = hotkey.assign(Shortcut.defaultShortcut());
        if (!result.success()) log("HOTKEY_INITIAL_KEY_UNAVAILABLE reason=" + result.message());
    }

    private void onHotkey(ScopeCapture captured) {
        if (unloaded.get()) return;
        focusTab();
        if (panel != null) {
            if(views!=null)views.setSelectedComponent(panel);
            if (captured.scope().isPresent()) {
                HotkeyScope scope = captured.scope().get();
                panel.addFromScope(scope.origin(), scope.path(), captured.response(), captured.message(), captured.previewIssue());
            } else {
                panel.scopeNotImported(captured.source());
            }
        }
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

    private boolean focusTab() {
        boolean selected=false;
        for(Component child=modulePanel;child!=null&&child.getParent()!=null;child=child.getParent()){
            if(child.getParent() instanceof JTabbedPane tabs){tabs.setSelectedComponent(child);selected=true;}
        }
        return selected;
    }

    @Override public void close() {
        unloaded.set(true);
        ProxyTrafficHandler running = traffic;
        if (running != null) running.close();
        Runnable cleanup = () -> {
            if(menuProvider!=null){menuProvider.close();menuProvider=null;}
            contextMenu=deregister(contextMenu,"context-menu");
            if (traffic != null) traffic.close();
            requests = deregister(requests, "request");
            responses = deregister(responses, "response");
            suiteTab=deregister(suiteTab,"suite-tab");
            if(retry!=null){retry.stop();retry=null;}
            if(mount!=null){try{mount.remove();mount=null;}catch(RuntimeException error){log("TAB_REMOVE_FAILED "+error.getClass().getSimpleName());}}
            if (saveTimer != null) { saveTimer.stop(); saveTimer = null; }
            flushRules();
            if (hotkeyDialog != null) {
                try { hotkeyDialog.dispose(); hotkeyDialog = null; }
                catch (RuntimeException error) { log("HOTKEY_DIALOG_CLOSE_FAILED type=" + error.getClass().getName()); }
            }
            if (hotkey != null) {
                if(localKeys!=null){localKeys.close();localKeys=null;}
                hotkey.close();
                if (hotkey.pendingCount() == 0) hotkey = null;
            }
            if (panel != null) {
                try { panel.closeDialogs(); }
                catch (RuntimeException error) { log("DIALOG_CLOSE_FAILED type=" + error.getClass().getName()); }
            }
            if(forwardPanel!=null){forwardPanel.close();forwardPanel=null;}
            panel = null;
            modulePanel = null; views = null;
            log("DETACHED hotkeyPending=" + (hotkey != null && hotkey.pendingCount() > 0)
                    + " handlerPending=" + (requests != null || responses != null)+" menuPending="+(contextMenu!=null));
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
