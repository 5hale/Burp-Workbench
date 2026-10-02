package com.burpworkbench.modules.compare;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.ChangeListener;

/** Steady unread signal until Compare is visited, without automatic navigation. */
final class TabAttention implements AutoCloseable {
    static final Color ORANGE=new Color(255,102,51);
    private final List<Target> targets=new ArrayList<>();
    private record Target(JTabbedPane pane,Component page,Component originalHeader,Component header,ChangeListener listener){}
    void signal(Component panel){
        close();
        if(selectedPath(panel))return;
        for(Component child=panel;child!=null&&child.getParent()!=null;){
            Container parent=child.getParent();
            if(parent instanceof JTabbedPane pane){
                int index=pane.indexOfComponent(child);
                if(index>=0){
                    Component page=child,original=pane.getTabComponentAt(index);
                    JLabel header=new OrangeTitle(pane.getTitleAt(index),pane.getIconAt(index));
                    header.setFont(original==null?pane.getFont():original.getFont());
                    header.setToolTipText(pane.getToolTipTextAt(index));
                    header.setEnabled(pane.isEnabledAt(index));
                    header.addMouseListener(new MouseAdapter(){@Override public void mousePressed(MouseEvent event){
                        int current=pane.indexOfComponent(page);
                        if(SwingUtilities.isLeftMouseButton(event)&&current>=0&&pane.isEnabledAt(current))pane.setSelectedComponent(page);
                    }});
                    ChangeListener listener=e->{if(selectedPath(panel))close();};
                    targets.add(new Target(pane,page,original,header,listener));
                    pane.setTabComponentAt(index,header);pane.addChangeListener(listener);
                    pane.revalidate();pane.repaint();
                }
            }
            child=parent;
        }
    }
    private static boolean selectedPath(Component panel){
        boolean found=false;
        for(Component child=panel;child!=null&&child.getParent()!=null;){
            Container parent=child.getParent();
            if(parent instanceof JTabbedPane pane&&pane.indexOfComponent(child)>=0){
                found=true;if(pane.getSelectedComponent()!=child)return false;
            }
            child=parent;
        }
        return found;
    }
    /** Explicit header bypasses tab-UI foreground overrides and theme recoloring. */
    private static final class OrangeTitle extends JLabel {
        OrangeTitle(String title,Icon icon){super(title,icon,CENTER);setOpaque(false);}
        @Override public Color getForeground(){return ORANGE;}
    }
    @Override public void close(){
        for(Target t:targets){
            t.pane.removeChangeListener(t.listener);int i=t.pane.indexOfComponent(t.page);
            if(i>=0&&t.pane.getTabComponentAt(i)==t.header)t.pane.setTabComponentAt(i,t.originalHeader);
            t.pane.revalidate();t.pane.repaint();
        }
        targets.clear();
    }
}
