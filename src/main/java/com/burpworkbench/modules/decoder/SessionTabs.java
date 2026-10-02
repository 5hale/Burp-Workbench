package com.burpworkbench.modules.decoder;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;

/** Each tab owns its input, options and conversion worker until explicitly closed. */
final class SessionTabs extends JPanel implements AutoCloseable {
    final JTabbedPane tabs=new JTabbedPane(JTabbedPane.TOP,JTabbedPane.SCROLL_TAB_LAYOUT);
    private final JPanel plus=new JPanel();
    private final Runnable addEmpty,onEmpty;
    private final PropertyChangeListener themeChange=event->SwingUtilities.invokeLater(()->{if(!this.closed)refreshColors();});
    private boolean changing,closed;
    private int sequence;

    SessionTabs(Runnable addEmpty,Runnable onEmpty){
        super(new BorderLayout());this.addEmpty=addEmpty;this.onEmpty=onEmpty;
        tabs.setUI(new TabUI());tabs.addTab("+",plus);tabs.setToolTipTextAt(0,"New tab");add(tabs,BorderLayout.CENTER);
        tabs.addChangeListener(event->{
            if(changing||closed)return;
            if(tabs.getSelectedComponent()==plus){
                if(pageCount()>0){changing=true;tabs.setSelectedIndex(pageCount()-1);changing=false;}
                addEmpty.run();
            }
            refreshColors();
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ctrl W"),"close-tab");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("meta W"),"close-tab");
        getActionMap().put("close-tab",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){if(tabs.getSelectedComponent() instanceof JComponent page&&page!=plus)closePage(page);}});
    }
    int pageCount(){return Math.max(0,tabs.getTabCount()-(tabs.indexOfComponent(plus)>=0?1:0));}
    List<JComponent> pages(){List<JComponent> result=new ArrayList<>();for(int i=0;i<tabs.getTabCount();i++)if(tabs.getComponentAt(i)!=plus)result.add((JComponent)tabs.getComponentAt(i));return result;}
    void addPage(JComponent page,Font font){
        if(closed)throw new IllegalStateException("Decoder window is closed");
        changing=true;
        try{
            int index=pageCount();String title=Integer.toString(++sequence);
            tabs.insertTab(title,null,page,null,index);
            JPanel header=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));header.setOpaque(false);
            JLabel label=new JLabel(title);label.setFont(font.deriveFont(Font.BOLD));header.add(label);
            JButton close=new JButton("×");close.setToolTipText("Close tab (Ctrl/Cmd+W)");close.setBorder(BorderFactory.createEmptyBorder(0,4,0,4));close.setContentAreaFilled(false);close.setFocusPainted(false);close.addActionListener(e->closePage(page));header.add(close);
            tabs.setTabComponentAt(index,header);tabs.setSelectedComponent(page);page.addPropertyChangeListener("decoder.theme.dark",themeChange);
        }finally{changing=false;}
        applyFont(font);refreshColors();
    }
    void closePage(JComponent page){
        if(closed||tabs.indexOfComponent(page)<0||page==plus)return;
        changing=true;
        try{page.removePropertyChangeListener("decoder.theme.dark",themeChange);try{closeResource(page);}finally{tabs.remove(page);if(pageCount()>0&&tabs.getSelectedComponent()==plus)tabs.setSelectedIndex(pageCount()-1);}}
        finally{changing=false;}
        if(pageCount()==0)onEmpty.run();else refreshColors();
    }
    void applyFont(Font font){
        tabs.setFont(font.deriveFont(Font.BOLD));
        for(int i=0;i<tabs.getTabCount();i++)if(tabs.getTabComponentAt(i) instanceof Container header)for(Component c:header.getComponents())c.setFont(font.deriveFont(Font.BOLD));
    }
    private void refreshColors(){
        Component selected=tabs.getSelectedComponent();Ui.Colors colors=Ui.colors(selected==null?this:selected);
        putClientProperty("decoder.colors",colors);tabs.putClientProperty("decoder.colors",colors);setBackground(colors.bg());tabs.setBackground(colors.bg());tabs.setForeground(colors.fg());plus.setBackground(colors.bg());
        for(int i=0;i<tabs.getTabCount();i++)if(tabs.getTabComponentAt(i) instanceof Container header)for(Component c:header.getComponents())c.setForeground(colors.fg());
        repaint();
    }
    private static void closeResource(JComponent page){
        if(page instanceof AutoCloseable resource)try{resource.close();}catch(Exception failure){throw new IllegalStateException("Cannot close Decoder tab",failure);}
    }
    @Override public void close(){
        if(closed)return;closed=true;RuntimeException failure=null;
        for(JComponent page:pages())try{page.removePropertyChangeListener("decoder.theme.dark",themeChange);closeResource(page);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        tabs.removeAll();if(failure!=null)throw failure;
    }
    private static final class TabUI extends BasicTabbedPaneUI {
        @Override protected void paintTabBackground(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){Ui.Colors p=Ui.colors(tabPane);g.setColor(selected?p.selection():p.bg());g.fillRect(x,y,w,h);}
        @Override protected void paintTabBorder(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){Ui.Colors p=Ui.colors(tabPane);g.setColor(selected?p.accent():p.line());g.drawLine(x,y+h-1,x+w,y+h-1);}
        @Override protected void paintContentBorder(Graphics g,int placement,int selected){}
    }
}
