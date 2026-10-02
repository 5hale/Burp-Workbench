package com.burpworkbench.platform;

import java.awt.*;
import java.awt.event.*;
import java.util.function.*;
import javax.swing.*;

/** Only intercepts assigned keys inside this extension's own UI, not other applications. */
public final class WorkbenchKeys implements KeyEventDispatcher,AutoCloseable {
    private final KeyboardFocusManager manager;
    private final Predicate<KeyEvent> matches;
    private final BiConsumer<KeyEvent,Component> action;
    private boolean installed,closed;
    public WorkbenchKeys(Predicate<KeyEvent> matches,BiConsumer<KeyEvent,Component> action){
        this(KeyboardFocusManager.getCurrentKeyboardFocusManager(),matches,action);
    }
    public WorkbenchKeys(KeyboardFocusManager manager,Predicate<KeyEvent> matches,BiConsumer<KeyEvent,Component> action){this.manager=manager;this.matches=matches;this.action=action;}
    public void install(){if(!closed&&!installed){manager.addKeyEventDispatcher(this);installed=true;}}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(closed||event.isConsumed()||event.getID()!=KeyEvent.KEY_PRESSED||!matches.test(event))return false;
        Component focus=manager.getFocusOwner();if(focus==null)focus=event.getComponent();
        if(!surface(focus)||capturingShortcut(focus)||capturingShortcut(event.getComponent()))return false;
        action.accept(event,focus);event.consume();return true;
    }
    public static boolean surface(Component component){
        for(Component node=component;node!=null;node=node.getParent()){
            if(node.getClass().getName().startsWith("com.burpworkbench."))return true;
            if(node instanceof JComponent c&&Boolean.TRUE.equals(c.getClientProperty("workbench.surface")))return true;
            if(node instanceof JPopupMenu popup&&popup.getInvoker()!=node&&surface(popup.getInvoker()))return true;
        }return false;
    }
    public static boolean capturingShortcut(Component component){
        for(Component node=component;node!=null;node=node.getParent()){
            if(node instanceof JComponent c&&Boolean.TRUE.equals(c.getClientProperty("workbench.hotkey.capture")))return true;
            String name=node.getClass().getName();
            if(name.startsWith("com.burpworkbench.")&&(name.endsWith("HotkeyDialog")||name.endsWith("HotkeySettingsDialog")))return true;
        }return false;
    }
    public static Component context(InputEvent event){Component focus=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();return focus!=null?focus:event==null?null:event.getComponent();}
    /** Native and Swing routes may report the same physical key event. */
    public static final class Delivery {
        private Press last;
        private record Press(long when,int code,int modifiers){}
        public synchronized boolean claim(InputEvent event){
            if(!(event instanceof KeyEvent key))return true;
            Press press=new Press(key.getWhen(),key.getKeyCode(),key.getModifiersEx());
            if(press.equals(last))return false;last=press;return true;
        }
        public synchronized void reset(){last=null;}
    }
    @Override public void close(){closed=true;if(installed){manager.removeKeyEventDispatcher(this);installed=false;}}
}
