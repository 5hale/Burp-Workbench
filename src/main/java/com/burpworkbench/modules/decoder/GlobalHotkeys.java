package com.burpworkbench.modules.decoder;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.*;
import javax.swing.*;

/** Burp-process fallback for extension panes and modeless windows not covered by Montoya. */
final class GlobalHotkeys implements KeyEventDispatcher,AutoCloseable {
    private final Hotkeys keys;
    private final KeyboardFocusManager manager;
    private final Predicate<Component> scope;
    private final Consumer<Runnable> afterEvent;
    private boolean installed,closed;

    GlobalHotkeys(Hotkeys keys,Supplier<Window> suite){
        this(keys,KeyboardFocusManager.getCurrentKeyboardFocusManager(),
                component->workbenchSurface(component)||ownedBy(component,suite.get()),
                action->SwingUtilities.invokeLater(()->SwingUtilities.invokeLater(action)));
    }
    GlobalHotkeys(Hotkeys keys,KeyboardFocusManager manager,Predicate<Component> scope,Consumer<Runnable> afterEvent){
        this.keys=keys;this.manager=manager;this.scope=scope;this.afterEvent=afterEvent;
    }
    void install(){if(!closed&&!installed){manager.addKeyEventDispatcher(this);installed=true;}}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(closed||event.isConsumed()||event.getID()!=KeyEvent.KEY_PRESSED)return false;
        int slot=keys.matching(event);if(slot<0)return false;
        Component focus=manager.getFocusOwner(),source=event.getComponent();
        Component context=focus!=null?focus:source;
        if(context==null||capturingShortcut(context)||capturingShortcut(source))return false;
        try{if(!scope.test(context))return false;}catch(RuntimeException|LinkageError unavailable){return false;}
        Capture capture=workbenchSurface(context)?Capture.fromWorkbench(context):Capture.fromTextSelection(source,focus);
        // Extension UI is handled before its own key bindings. Native Burp editors get first
        // opportunity to supply selected raw bytes; the queued Swing capture is a fallback.
        boolean local=workbenchSurface(context);
        keys.fromSwing(slot,event,capture,local?Runnable::run:afterEvent);
        if(local)event.consume();
        return local;
    }
    static boolean workbenchSurface(Component component){
        return com.burpworkbench.platform.WorkbenchKeys.surface(component);
    }
    static boolean capturingShortcut(Component component){
        return com.burpworkbench.platform.WorkbenchKeys.capturingShortcut(component);
    }
    static boolean ownedBy(Component component,Window suite){
        if(suite==null)return false;
        Window window=component instanceof Window w?w:SwingUtilities.getWindowAncestor(component);
        while(window!=null){if(window==suite)return true;window=window.getOwner();}
        return false;
    }
    @Override public void close(){closed=true;if(installed){manager.removeKeyEventDispatcher(this);installed=false;}}
}
