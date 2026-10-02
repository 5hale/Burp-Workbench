package com.burpworkbench.platform;

import java.awt.event.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.event.*;

/** Row operations reuse the same callbacks as the toolbar; right-click selects the clicked row. */
public final class TableMenus {
    public record Entry(String label,BooleanSupplier enabled,Runnable action){}
    private TableMenus(){}
    public static Entry entry(String label,BooleanSupplier enabled,Runnable action){return new Entry(label,enabled,action);}
    public static void install(JTable table,Entry... entries){
        JPopupMenu popup=new JPopupMenu();
        for(Entry entry:entries){JMenuItem item=new JMenuItem(entry.label());item.addActionListener(e->{if(entry.enabled().getAsBoolean())entry.action().run();});popup.add(item);}
        popup.addPopupMenuListener(new PopupMenuListener(){
            public void popupMenuWillBecomeVisible(PopupMenuEvent e){for(int i=0;i<entries.length;i++)popup.getComponent(i).setEnabled(entries[i].enabled().getAsBoolean());}
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e){}
            public void popupMenuCanceled(PopupMenuEvent e){}
        });
        table.addMouseListener(new MouseAdapter(){
            public void mousePressed(MouseEvent e){if(SwingUtilities.isRightMouseButton(e)){int row=table.rowAtPoint(e.getPoint());if(row<0)table.clearSelection();else if(!table.isRowSelected(row))table.setRowSelectionInterval(row,row);}}
        });
        table.setComponentPopupMenu(popup);
    }
}
