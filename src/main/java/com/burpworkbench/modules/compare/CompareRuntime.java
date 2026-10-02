package com.burpworkbench.modules.compare;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.ui.hotkey.HotKey;
import com.burpworkbench.platform.WorkbenchKeys;
import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Owns the comparison UI, capture registrations, timers and cancellable worker. */
final class CompareRuntime implements AutoCloseable {
    private final MontoyaApi api;
    private final Supplier<Component> root;
    private final HotkeyBinding.Registrar registrar;
    private final AtomicBoolean started=new AtomicBoolean(), closed=new AtomicBoolean();
    private ComparePanel panel;
    private ProxyTabMount mount;
    private HotkeyBinding hotkey;
    private WorkbenchKeys localKeys;
    private final TabAttention attention=new TabAttention();
    private HotkeySettingsDialog dialog;
    private Registration fallback,contextMenu;
    private Timer retry,fontWatch;
    private int attempts;

    CompareRuntime(MontoyaApi api){this(api,()->api.userInterface().swingUtils().suiteFrame());}
    CompareRuntime(MontoyaApi api,Supplier<Component> root){this(api,root,(key,callback)->api.userInterface().registerHotKeyHandler(HotKey.hotKey("Send to compare ++",key.value()),callback::accept));}
    CompareRuntime(MontoyaApi api,Supplier<Component> root,HotkeyBinding.Registrar registrar){this.api=Objects.requireNonNull(api);this.root=Objects.requireNonNull(root);this.registrar=Objects.requireNonNull(registrar);}

    void start(){
        if(closed.get()||!started.compareAndSet(false,true))return;
        SwingUtilities.invokeLater(()->{
            if(closed.get())return;
            try{
                panel=new ComparePanel(inputFont(),this::openHotkeys,this::sendToRepeater);
                api.userInterface().applyThemeToComponent(panel);panel.applyEditorFont(inputFont());
                fontWatch=new Timer(1000,e->{if(!closed.get()&&panel!=null&&panel.isShowing()){
                    Font font=inputFont();if(!font.equals(panel.editorFont()))panel.applyEditorFont(font);}});fontWatch.start();
                mount=new ProxyTabMount(this::log);
                hotkey=new HotkeyBinding(registrar,this::captured,this::log,SwingUtilities::invokeLater);
                localKeys=new WorkbenchKeys(hotkey::matches,hotkey::fromWorkbench);localKeys.install();
                if(!hotkey.assign(Shortcut.defaultShortcut()).success())log("HOTKEY_NOT_ASSIGNED use_Hotkeys_button");
                contextMenu=api.userInterface().registerContextMenuItemsProvider(new burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider(){
                    @Override public java.util.List<Component> provideMenuItems(burp.api.montoya.ui.contextmenu.ContextMenuEvent event){
                        if(closed.get())return java.util.List.of();
                        var editor=event.messageEditorRequestResponse();if(editor.isEmpty())return java.util.List.of();
                        JMenuItem send=new JMenuItem("Send to compare ++");
                        send.addActionListener(e->{if(closed.get())return;try{
                            MessageCapture input=MessageCapture.fromEditor(editor.get(),CompareRuntime.this::log);
                            SwingUtilities.invokeLater(()->captured(input));
                        }catch(RuntimeException failure){log("CONTEXT_CAPTURE_FAILED "+failure.getClass().getSimpleName());}});
                        return java.util.List.of(send);
                    }
                });
                retry=new Timer(600,e->install());retry.setInitialDelay(0);retry.start();
                log("START compileApi=2025.12 burp="+api.burpSuite().version()+" readOnly=true shortcut="+Shortcut.defaultShortcut().value());
            }catch(RuntimeException|LinkageError error){log("INIT_FAILED "+error.getClass().getSimpleName());close();}
        });
    }

    void install(){
        if(closed.get()){if(retry!=null)retry.stop();return;}
        if(fallback!=null)return;
        attempts++;
        try{
            if(mount.install(root.get(),panel)){retry.stop();return;}
            if(attempts>=8){retry.stop();fallback=api.userInterface().registerSuiteTab("compare ++",panel);log("TOP_LEVEL_FALLBACK no_unique_proxy_anchor");}
        }catch(RuntimeException|LinkageError failure){log("MOUNT_FAILED "+failure.getClass().getSimpleName());close();}
    }
    void fromMenu(Component source){captured(MessageCapture.fromWorkbench(source,this::log));}
    private void captured(MessageCapture input){
        if(closed.get()||panel==null||input==null)return;
        if(panel.add(input)){attention.signal(panel);}
        else log("CAPTURE_NOT_ADDED reason="+(input.bytes()==null?"capture_error":"collection_limit"));
    }
    private void sendToRepeater(CompareItem item){
        if(closed.get()||item.request==null)return;
        api.repeater().sendToRepeater(item.request.toRequest(),"Compare #"+item.id);
    }
    private Font inputFont(){JTextField reference=new JTextField();api.userInterface().applyThemeToComponent(reference);return reference.getFont();}
    private void openHotkeys(){if(closed.get()||hotkey==null||panel==null)return;if(dialog==null||!dialog.isDisplayable())dialog=new HotkeySettingsDialog(SwingUtilities.getWindowAncestor(panel),hotkey,c->api.userInterface().applyThemeToComponent(c));dialog.showHotkeys();}

    @Override public void close(){
        closed.set(true);
        Runnable cleanup=()->{
            if(retry!=null){retry.stop();retry=null;}if(fontWatch!=null){fontWatch.stop();fontWatch=null;}
            safely(attention::close);
            safely(()->{if(localKeys!=null){localKeys.close();localKeys=null;}});
            safely(()->{if(hotkey!=null){hotkey.close();if(hotkey.pendingCount()==0)hotkey=null;}});
            safely(()->{if(dialog!=null){dialog.dispose();dialog=null;}});
            safely(()->{if(panel!=null)panel.close();});
            contextMenu=release(contextMenu);fallback=release(fallback);
            safely(()->{if(mount!=null){mount.remove();mount=null;}});
            panel=null;
            log("DETACHED registrationsPending="+(contextMenu!=null||fallback!=null||hotkey!=null));
        };
        if(SwingUtilities.isEventDispatchThread())cleanup.run();
        else try{SwingUtilities.invokeAndWait(cleanup);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();SwingUtilities.invokeLater(cleanup);}
        catch(Exception failure){log("CLEANUP_FAILED "+failure.getClass().getSimpleName());}
    }
    private Registration release(Registration registration){
        if(registration==null)return null;
        try{registration.deregister();return null;}
        catch(RuntimeException|LinkageError error){log("DEREGISTER_FAILED "+error.getClass().getSimpleName());return registration;}
    }
    private void safely(Runnable action){try{action.run();}catch(RuntimeException|LinkageError error){log("CLEANUP_STEP_FAILED "+error.getClass().getSimpleName());}}
    private void log(String message){try{api.logging().logToOutput("[compare++] "+message);}catch(RuntimeException|LinkageError ignored){}}
}
