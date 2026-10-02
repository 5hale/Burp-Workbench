package com.burpworkbench.modules.replace;

import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/** A menu selection retains only origin/path, never the original HTTP message. */
final class ReplaceContextMenu implements ContextMenuItemsProvider,AutoCloseable {
    private final Consumer<ScopeCapture> action;
    private final Consumer<String> log;
    private volatile boolean closed;
    ReplaceContextMenu(Consumer<ScopeCapture> action,Consumer<String> log){this.action=action;this.log=log;}
    @Override public List<Component> provideMenuItems(ContextMenuEvent event){
        if(closed)return List.of();
        ScopeCapture captured=ScopeCapture.fromMenu(event,log);
        JMenuItem send=new JMenuItem("Send to replace ++");
        send.setEnabled(captured.scope().isPresent());
        send.addActionListener(e->{if(!closed)SwingUtilities.invokeLater(()->{if(!closed)action.accept(captured);});});
        return List.of(send);
    }
    @Override public void close(){closed=true;}
}
