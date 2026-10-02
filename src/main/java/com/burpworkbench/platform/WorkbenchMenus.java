package com.burpworkbench.platform;

import java.awt.*;
import java.awt.event.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;

/** Lazy, extension-scoped menus. No Burp private reflection, clipboard reads, or global UI defaults. */
public final class WorkbenchMenus implements AutoCloseable, AWTEventListener {
    public enum Command {
        REPLACE("Send to replace ++"), COMPARE("Send to compare ++"),
        DECODE("decode ++"), ADVANCED("decode ++ Advanced");
        final String label;
        Command(String label){this.label=label;}
    }
    private static final String ATTACHMENT="workbench.context.menu";
    private final Map<Command,Consumer<Component>> handlers=new java.util.concurrent.ConcurrentHashMap<>();
    private final List<WeakReference<JComponent>> attached=new ArrayList<>();
    private boolean installed;
    private volatile boolean closed;
    private record Attachment(JPopupMenu original,JPopupMenu popup,JMenu extra,PopupMenuListener listener){}

    public AutoCloseable register(Command command,Consumer<Component> action){
        handlers.put(command,action);
        return ()->handlers.remove(command,action);
    }
    public void install(){
        if(closed||installed)return;
        Toolkit.getDefaultToolkit().addAWTEventListener(this,AWTEvent.MOUSE_EVENT_MASK|AWTEvent.FOCUS_EVENT_MASK);
        installed=true;
    }
    @Override public void eventDispatched(AWTEvent event){
        if(closed)return;
        if(event instanceof FocusEvent focus&&focus.getID()==FocusEvent.FOCUS_GAINED)attach(focus.getComponent());
        if(event instanceof MouseEvent mouse&&(mouse.getID()==MouseEvent.MOUSE_ENTERED||mouse.getID()==MouseEvent.MOUSE_PRESSED)){
            attach(mouse.getComponent());
            if(mouse.getID()==MouseEvent.MOUSE_PRESSED&&SwingUtilities.isRightMouseButton(mouse)
                    &&mouse.getComponent() instanceof JTable table&&table.getClientProperty(ATTACHMENT)!=null){
                int row=table.rowAtPoint(mouse.getPoint());if(row<0)table.clearSelection();else if(!table.isRowSelected(row))table.setRowSelectionInterval(row,row);
            }
        }
    }
    /** Public for headless integration checks; the invoker, not later keyboard focus, owns capture. */
    public void attach(Component component){
        if(closed||!(component instanceof JComponent text)||text instanceof JPasswordField
                ||!(text instanceof JTextComponent||text instanceof JTable&&WorkbenchInput.hasFullSource(text))
                ||!WorkbenchKeys.surface(component)||WorkbenchKeys.capturingShortcut(component))return;
        if(text.getClientProperty(ATTACHMENT)!=null)return;
        WeakReference<JComponent> reference=new WeakReference<>(text);
        JPopupMenu original=text.getComponentPopupMenu();
        // Keep native/existing actions intact. An inherited menu is not mutated for another invoker.
        if(text.getInheritsPopupMenu()&&original!=null)return;
        JPopupMenu popup=original==null?new JPopupMenu():original;
        JMenu extra=original==null?null:new JMenu("Burp Workbench");
        if(extra!=null)popup.add(extra);
        JPopupMenu target=extra==null?popup:extra.getPopupMenu();
        PopupMenuListener listener=new PopupMenuListener(){
            public void popupMenuWillBecomeVisible(PopupMenuEvent e){
                JComponent source=reference.get();target.removeAll();
                if(source!=null&&!closed)fill(target,source,original==null);
            }
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e){}
            public void popupMenuCanceled(PopupMenuEvent e){}
        };
        popup.addPopupMenuListener(listener);
        text.setComponentPopupMenu(popup);
        text.putClientProperty(ATTACHMENT,new Attachment(original,popup,extra,listener));
        attached.removeIf(ref->ref.get()==null);attached.add(new WeakReference<>(text));
    }
    JPopupMenu menuFor(JComponent source){JPopupMenu menu=new JPopupMenu();fill(menu,source,true);return menu;}
    private void fill(JPopupMenu menu,JComponent source,boolean editing){
        boolean enabled=source.isEnabled(),selection=source instanceof JTextComponent text&&text.getSelectionEnd()>text.getSelectionStart();
        if(editing&&source instanceof JTextComponent text){
            item(menu,"Cut",enabled&&text.isEditable()&&selection,text::cut);
            item(menu,"Copy",enabled&&selection,text::copy);
            item(menu,"Paste",enabled&&text.isEditable(),text::paste);
            item(menu,"Select all",enabled&&text.getDocument().getLength()>0,text::selectAll);
            menu.addSeparator();
        }
        for(Command command:Command.values()){
            boolean available=enabled&&handlers.containsKey(command);
            if(command==Command.REPLACE)available&=WorkbenchInput.hasFullSource(source);
            if(command==Command.COMPARE)available&=selection||(!(source instanceof JTextField)&&source instanceof JTextComponent text&&text.getDocument().getLength()>0)||source instanceof JTable table&&table.getSelectedRow()>=0;
            item(menu,command.label,available,()->{
                Consumer<Component> handler=handlers.get(command);
                if(closed||handler==null)return;
                try{handler.accept(source);}
                catch(RuntimeException error){JOptionPane.showMessageDialog(source,"This editor context is unavailable or exceeds the input limit.","Burp Workbench",JOptionPane.WARNING_MESSAGE);}
            });
        }
    }
    private static void item(JPopupMenu menu,String label,boolean enabled,Runnable action){
        JMenuItem item=new JMenuItem(label);item.setEnabled(enabled);item.addActionListener(e->action.run());menu.add(item);
    }
    @Override public void close(){
        if(closed)return;closed=true;
        if(installed){Toolkit.getDefaultToolkit().removeAWTEventListener(this);installed=false;}
        Runnable cleanup=()->{
            for(var reference:attached){JComponent component=reference.get();if(component==null)continue;
                if(component.getClientProperty(ATTACHMENT) instanceof Attachment binding){
                    binding.popup.removePopupMenuListener(binding.listener);
                    if(binding.extra!=null)binding.popup.remove(binding.extra);
                    if(component.getComponentPopupMenu()==binding.popup)component.setComponentPopupMenu(binding.original);
                    component.putClientProperty(ATTACHMENT,null);
                }
            }
            attached.clear();handlers.clear();
        };
        if(SwingUtilities.isEventDispatchThread())cleanup.run();else try{SwingUtilities.invokeAndWait(cleanup);}
        catch(InterruptedException e){Thread.currentThread().interrupt();SwingUtilities.invokeLater(cleanup);}
        catch(java.lang.reflect.InvocationTargetException e){throw new IllegalStateException(e.getCause());}
    }
}
