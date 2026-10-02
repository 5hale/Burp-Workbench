package com.burpworkbench.modules.decoder;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.ui.contextmenu.*;
import burp.api.montoya.ui.hotkey.HotKey;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.*;

/** Workbench-owned popup sessions, selection capture and both hotkey routes. */
final class DecoderRuntime implements AutoCloseable {
    static final int QUICK_WIDTH=920,QUICK_HEIGHT=680;
    private final MontoyaApi api;
    private final Hotkeys.Registrar registrar;
    private final AtomicBoolean started=new AtomicBoolean(),closed=new AtomicBoolean();
    private final Map<Integer,WindowState> windows=new LinkedHashMap<>();
    private Hotkeys keys;
    private GlobalHotkeys globalKeys;
    private Registration menu;
    private HotkeyDialog settings;
    private javax.swing.Timer fontWatch;
    private Font font;
    private record WindowState(JDialog dialog,SessionTabs sessions){}

    DecoderRuntime(MontoyaApi api){this(api,(slot,key,callback)->api.userInterface().registerHotKeyHandler(HotKey.hotKey(slot==0?"Decoder ++ Quick":"Decoder ++ Advanced",key.value()),callback::accept));}
    DecoderRuntime(MontoyaApi api,Hotkeys.Registrar registrar){this.api=Objects.requireNonNull(api);this.registrar=Objects.requireNonNull(registrar);}

    void start(){
        if(closed.get()||!started.compareAndSet(false,true))return;
        SwingUtilities.invokeLater(()->{
            if(closed.get())return;
            try{
                keys=new Hotkeys(registrar,this::captured,SwingUtilities::invokeLater,this::log);
                for(int slot=0;slot<2;slot++)restoreKey(slot);
                menu=api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider(){
                    @Override public java.util.List<Component> provideMenuItems(ContextMenuEvent event){
                        if(closed.get())return java.util.List.of();
                        Capture selection=Capture.blank();
                        try{var editor=event.messageEditorRequestResponse();if(editor!=null&&editor.isPresent())selection=Capture.fromEditor(editor.get());}catch(RuntimeException ignored){}
                        Capture selected=selection;JMenuItem item=new JMenuItem("decode ++");
                        item.addActionListener(e->SwingUtilities.invokeLater(()->captured(0,selected)));
                        return java.util.List.of(item);
                    }
                });
                globalKeys=new GlobalHotkeys(keys,this::owner);globalKeys.install();
                font=inputFont();fontWatch=new javax.swing.Timer(1000,e->{
                    if(closed.get()||windows.isEmpty())return;
                    Font next=inputFont();if(!next.equals(font)){font=next;for(WindowState window:windows.values()){for(JComponent page:window.sessions.pages())applyFont(page,next);window.sessions.applyFont(next);}}
                });fontWatch.start();
                log("START readOnly=true tabs=popup persistence=hotkeys_only inputFont=textFieldDefault");
            }catch(RuntimeException|LinkageError failure){log("INIT_FAILED "+failure.getClass().getSimpleName());close();}
        });
    }
    private void restoreKey(int slot){
        try{
            String saved=api.persistence().preferences().getString(pref(slot));
            if(saved==null)saved=slot==0?"Ctrl+1":"Ctrl+2";
            if(!saved.isBlank()){Shortcut key=new Shortcut(saved);Hotkeys.Result result=keys.assign(slot,key);if(!result.success())log("SAVED_HOTKEY_NOT_ASSIGNED slot="+slot);}
        }catch(RuntimeException failure){log("HOTKEY_RESTORE_FAILED slot="+slot);}
    }
    private Window owner(){return api.userInterface().swingUtils().suiteFrame();}
    static Font themedTextFieldFont(Consumer<JTextField> applyTheme){JTextField reference=new JTextField();applyTheme.accept(reference);return reference.getFont();}
    private Font inputFont(){return themedTextFieldFont(field->api.userInterface().applyThemeToComponent(field));}
    private static void applyFont(JComponent page,Font font){if(page instanceof QuickPanel quick)quick.applyEditorFont(font);if(page instanceof MultiPanel advanced)advanced.applyEditorFont(font);}

    void fromMenu(int slot,Component source){captured(slot,Capture.fromWorkbench(source));}
    private void captured(int slot,Capture capture){
        if(closed.get())return;if(capture==null)capture=Capture.blank();
        if(capture.error()!=null){open(slot,"","UTF-8");JOptionPane.showMessageDialog(owner(),capture.error(),"Decoder ++",JOptionPane.WARNING_MESSAGE);return;}
        String text,charset="UTF-8";
        try{text=Codecs.decode(capture.bytes(),charset);}catch(IllegalArgumentException error){
            Object selected=JOptionPane.showInputDialog(owner(),"UTF-8로 읽을 수 없는 데이터입니다. 입력 바이트의 문자셋을 선택하세요.","Decoder ++ · Input charset",JOptionPane.QUESTION_MESSAGE,null,Codecs.CHARSETS,"MS949");
            if(selected==null){open(slot,"",charset);return;}charset=selected.toString();
            try{text=Codecs.decode(capture.bytes(),charset);}catch(IllegalArgumentException failure){open(slot,"",charset);JOptionPane.showMessageDialog(owner(),failure.getMessage(),"Decoder ++",JOptionPane.WARNING_MESSAGE);return;}
        }
        open(slot,text,charset);
    }
    private void open(int slot,String text,String charset){
        if(closed.get())return;
        Font current=inputFont();
        JComponent page=slot==0?new QuickPanel(current,this::openHotkeys,value->open(1,value,"UTF-8")):new MultiPanel(current,this::openHotkeys);
        try{
            api.userInterface().applyThemeToComponent(page);
            if(page instanceof QuickPanel quick)quick.finishAppearance();else ((MultiPanel)page).finishAppearance();
            applyFont(page,current);
            WindowState window=windows.get(slot);boolean fresh=window==null||!window.dialog.isDisplayable();
            if(fresh){window=createWindow(slot);windows.put(slot,window);}
            window.sessions.addPage(page,current);
            if(page instanceof QuickPanel quick)quick.setInput(text,"",charset);else ((MultiPanel)page).setText(text);
            if(fresh){
                JDialog dialog=window.dialog;dialog.pack();Insets frame=dialog.getInsets();
                int minimumWidth=page instanceof QuickPanel quick?quick.minimumContentWidth():740;
                dialog.setMinimumSize(new Dimension(minimumWidth+frame.left+frame.right,550+frame.top+frame.bottom));
                Ui.clampDialog(dialog,slot==0?new Dimension(QUICK_WIDTH,QUICK_HEIGHT):new Dimension(1220,940));dialog.setLocationRelativeTo(owner());
            }
            window.dialog.setVisible(true);window.dialog.toFront();
            if(page instanceof QuickPanel quick)quick.input.requestFocusInWindow();else ((MultiPanel)page).fields.get(Codecs.Kind.TEXT).text.requestFocusInWindow();
        }catch(RuntimeException|LinkageError failure){
            boolean retained=windows.values().stream().anyMatch(window->window.sessions.pages().contains(page));
            if(!retained)safely(()->{try{((AutoCloseable)page).close();}catch(Exception error){throw new IllegalStateException(error);}});
            log("OPEN_FAILED "+failure.getClass().getSimpleName());
        }
    }
    private WindowState createWindow(int slot){
        JDialog dialog=conversionDialog();Window suite=owner();if(suite!=null)dialog.setIconImages(suite.getIconImages());
        SessionTabs sessions=new SessionTabs(()->open(slot,"","UTF-8"),dialog::dispose);dialog.setContentPane(sessions);
        WindowState window=new WindowState(dialog,sessions);
        dialog.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent event){windows.remove(slot,window);safely(sessions::close);}});
        dialog.getRootPane().registerKeyboardAction(e->dialog.dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        return window;
    }
    static JDialog conversionDialog(){
        JDialog dialog=new JDialog((Window)null,"Decoder ++",Dialog.ModalityType.MODELESS);
        dialog.setAlwaysOnTop(false);dialog.setResizable(true);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);return dialog;
    }
    private void openHotkeys(){if(closed.get()||keys==null)return;if(settings==null||!settings.isDisplayable())settings=new HotkeyDialog(owner(),keys,this::saveKey,c->api.userInterface().applyThemeToComponent(c));settings.setVisible(true);settings.toFront();}
    private static String pref(int slot){return "decoder."+(slot==0?"quick":"advanced")+".hotkey";}
    private String saveKey(int slot,Shortcut key){try{api.persistence().preferences().setString(pref(slot),key==null?"":key.value());return " · 저장됨";}catch(RuntimeException failure){log("HOTKEY_SAVE_FAILED");return " · 저장 실패, 이번 실행에서만 적용됨";}}
    @Override public void close(){
        closed.set(true);
        Runnable cleanup=()->{
            if(fontWatch!=null){fontWatch.stop();fontWatch=null;}
            safely(()->{if(globalKeys!=null){globalKeys.close();globalKeys=null;}});
            safely(()->{if(keys!=null){keys.close();if(keys.pendingCount()==0)keys=null;}});
            safely(()->{if(settings!=null){settings.dispose();settings=null;}});
            for(WindowState window:new ArrayList<>(windows.values())){safely(window.sessions::close);safely(window.dialog::dispose);}windows.clear();
            if(menu!=null)try{menu.deregister();menu=null;}catch(RuntimeException|LinkageError failure){log("DEREGISTER_FAILED");}
            log("DETACHED");
        };
        if(SwingUtilities.isEventDispatchThread())cleanup.run();else try{SwingUtilities.invokeAndWait(cleanup);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();SwingUtilities.invokeLater(cleanup);}catch(Exception failure){log("CLEANUP_FAILED "+failure.getClass().getSimpleName());}
    }
    private void safely(Runnable action){try{action.run();}catch(RuntimeException|LinkageError failure){log("CLEANUP_STEP_FAILED "+failure.getClass().getSimpleName());}}
    private void log(String message){try{api.logging().logToOutput("[decoder++] "+message);}catch(RuntimeException|LinkageError ignored){}}
}
