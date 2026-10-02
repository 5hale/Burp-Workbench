package com.burpworkbench.modules.decoder;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.event.*;
import javax.swing.plaf.basic.*;

/** Local popup styling. No UIManager writes or shared mutable paint colors. */
final class Ui {
    record Colors(Color bg,Color editorBg,Color editorFg,Color fg,Color muted,Color line,Color accent,Color selection,Color error){}
    private static final Colors LIGHT=new Colors(new Color(0xfafafa),Color.WHITE,new Color(0x16191d),new Color(0x16191d),new Color(0x454d57),new Color(0xaeb6c1),new Color(0x245f98),new Color(0xeaf2fc),new Color(0xb42332));
    private static final Colors DARK=new Colors(new Color(0x323334),new Color(0x202328),new Color(0xc1c7ce),new Color(0xf2f3f5),new Color(0xc1c7ce),new Color(0x666d77),new Color(0x85baff),new Color(0x2c5175),new Color(0xff9ea6));
    private static Boolean preferredDark;
    static Colors colors(Component component){
        for(Component c=component;c!=null;c=c.getParent())if(c instanceof JComponent jc&&jc.getClientProperty("decoder.colors") instanceof Colors p)return p;
        return defaultDark()?DARK:LIGHT;
    }
    private static boolean defaultDark(){return preferredDark==null||preferredDark;}
    static boolean isDark(Component root){return root instanceof JComponent c&&c.getClientProperty("decoder.theme.dark") instanceof Boolean b?b:colors(root)==DARK;}
    static void setDark(JComponent root,boolean dark){
        root.putClientProperty("decoder.theme.dark",dark);preferredDark=dark;
        if(root instanceof QuickPanel q)q.finishAppearance();else if(root instanceof MultiPanel m)m.finishAppearance();else style(root);
        root.revalidate();root.repaint();
    }
    static JPanel panel(LayoutManager layout){JPanel p=new JPanel(layout);p.setOpaque(false);return p;}
    static JTextArea text(Font font,boolean editable){JTextArea t=new JTextArea();t.setFont(font);t.setEditable(editable);t.setLineWrap(true);t.setWrapStyleWord(false);t.setMargin(new Insets(11,12,11,12));return t;}
    static void initialText(JTextArea editor,String value){
        // Inserting before the first layout otherwise computes wraps at zero width for huge lines.
        boolean wrap=editor.getLineWrap();editor.setLineWrap(false);
        try{editor.setText(value);}finally{editor.setLineWrap(wrap);}
    }
    static JScrollPane scroll(JTextArea text){JScrollPane p=new JScrollPane(text);p.setBorder(new RoundBorder());p.setMinimumSize(new Dimension(120,60));p.setPreferredSize(new Dimension(360,170));p.getVerticalScrollBar().setUnitIncrement(20);return p;}
    static void changes(JTextArea area,Runnable action){area.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){action.run();}public void removeUpdate(DocumentEvent e){action.run();}public void changedUpdate(DocumentEvent e){action.run();}});}
    static JButton button(String title,Runnable action){JButton b=new ActionButton(title);b.addActionListener(e->action.run());return b;}
    static JToggleButton toggle(String title,boolean selected){return new Toggle(title,selected);}
    static JPanel row(Component...components){JPanel p=panel(new FlowLayout(FlowLayout.LEFT,7,0));for(Component c:components)p.add(c);return p;}
    static JLabel caption(String text){JLabel l=new JLabel(text);l.putClientProperty("decoder.label",true);l.setFont(l.getFont().deriveFont(Font.BOLD));return l;}
    static void labelFont(Component component,Font inputFont){
        if(component instanceof JLabel label&&Boolean.TRUE.equals(label.getClientProperty("decoder.label")))label.setFont(inputFont.deriveFont(Font.BOLD));
        if(component instanceof Container container)for(Component child:container.getComponents())labelFont(child,inputFont);
    }
    static JPanel toolbar(JComponent root,Runnable hotkeys,JLabel status){
        if(!(root.getClientProperty("decoder.theme.dark") instanceof Boolean))root.putClientProperty("decoder.theme.dark",defaultDark());
        JPanel bar=panel(new BorderLayout(12,0));bar.putClientProperty("decoder.heading",true);
        // Reserve one line independently of the message's text or visibility. Long errors
        // ellipsize inside the available width; the tooltip retains the complete message.
        JPanel message=new JPanel(new BorderLayout()){
            @Override public Dimension getPreferredSize(){return new Dimension(0,Math.max(32,status.getFontMetrics(status.getFont()).getHeight()));}
            @Override public Dimension getMinimumSize(){return getPreferredSize();}
        };
        message.setOpaque(false);status.setBorder(null);status.setHorizontalAlignment(SwingConstants.LEFT);status.setVerticalAlignment(SwingConstants.CENTER);
        message.add(status,BorderLayout.CENTER);bar.add(message,BorderLayout.CENTER);
        JToggleButton theme=toggle("Light",isDark(root));theme.putClientProperty("decoder.theme.toggle",true);theme.setName("decoder-theme-toggle");
        theme.addActionListener(e->setDark(root,theme.isSelected()));
        JPanel actions=panel(new FlowLayout(FlowLayout.RIGHT,8,0));actions.add(button("Hotkeys",hotkeys));actions.add(theme);bar.add(actions,BorderLayout.EAST);return bar;
    }
    /** Display-string length: Unicode code points and UTF-8 bytes, not underlying decoded bytes. */
    static String lengthText(String text){
        int chars=0,bytes=0;boolean valid=true;
        for(int i=0;i<text.length();i++){
            if((i&4095)==0)Codecs.check(0);char c=text.charAt(i);chars++;
            if(c<0x80)bytes++;else if(c<0x800)bytes+=2;
            else if(Character.isHighSurrogate(c)&&i+1<text.length()&&Character.isLowSurrogate(text.charAt(i+1))){bytes+=4;i++;}
            else if(Character.isSurrogate(c))valid=false;else bytes+=3;
        }
        return chars+" chars · "+(valid?Integer.toString(bytes):"—")+" bytes";
    }
    static JLabel lengthLabel(){JLabel l=caption("0 chars · 0 bytes");l.setToolTipText("표시된 텍스트의 Unicode 문자 수 · UTF-8 바이트 수");return l;}
    static void copy(JTextArea area,JLabel ignored){try{Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(area.getText()),null);}catch(RuntimeException e){area.requestFocusInWindow();area.selectAll();}}
    static String error(Throwable e){String message=e.getMessage();return message==null?e.getClass().getSimpleName():message;}
    static void error(JLabel label,String message){label.setText(message==null?"":message);label.setToolTipText(message);label.setForeground(colors(label).error());label.setVisible(message!=null&&!message.isBlank());label.revalidate();}
    static JLabel errorLabel(){JLabel l=new JLabel("");l.putClientProperty("decoder.error",true);l.setBorder(BorderFactory.createEmptyBorder(6,0,0,0));l.setVisible(false);return l;}
    static void style(JComponent root){
        if(!(root.getClientProperty("decoder.theme.dark") instanceof Boolean))root.putClientProperty("decoder.theme.dark",defaultDark());
        Colors p=isDark(root)?DARK:LIGHT;styleTree(root,p);root.setOpaque(true);root.setBackground(p.bg());
    }
    static void styleControls(Component component,JComponent root){styleTree(component,colors(root));}
    private static void styleTree(Component c,Colors p){
        if(c instanceof JComponent jc)jc.putClientProperty("decoder.colors",p);
        if(c instanceof JPanel panel){panel.setBackground(p.bg());if(Boolean.TRUE.equals(panel.getClientProperty("decoder.heading")))panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,p.line()),BorderFactory.createEmptyBorder(10,10,10,10)));}
        if(c instanceof JLabel l)l.setForeground(Boolean.TRUE.equals(l.getClientProperty("decoder.error"))?p.error():p.fg());
        if(c instanceof JList<?> list){list.setBackground(p.editorBg());list.setForeground(p.fg());list.setSelectionBackground(p.selection());list.setSelectionForeground(p.fg());list.setOpaque(true);}
        if(c instanceof JTextArea t){t.setForeground(p.editorFg());t.setBackground(p.editorBg());t.setCaretColor(p.editorFg());t.setSelectionColor(p.selection());t.setSelectedTextColor(p.fg());}
        if(c instanceof JComboBox<?> box){
            box.setUI(new ComboUI());box.setBackground(p.bg());box.setForeground(p.fg());box.setBorder(new RoundBorder());
            box.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){super.getListCellRendererComponent(list,value,index,selected,focus);Colors palette=colors(box);setBackground(selected&&index>=0?palette.selection():palette.bg());setForeground(box.isEnabled()?palette.fg():palette.muted());setBorder(BorderFactory.createEmptyBorder(3,7,3,3));return this;}});
        }
        if(c instanceof JSplitPane split){split.setBackground(p.bg());if(!(split.getUI() instanceof PlainSplitUI))split.setUI(new PlainSplitUI());split.setBorder(null);}
        if(c instanceof JCheckBox b){b.setOpaque(false);b.setForeground(p.fg());b.setFocusPainted(false);b.setIcon(new CheckIcon(false));b.setSelectedIcon(new CheckIcon(true));}
        if(c instanceof AbstractButton b){
            b.setForeground(p.fg());
            if(Boolean.TRUE.equals(b.getClientProperty("decoder.theme.toggle"))){boolean dark=p==DARK;b.setSelected(dark);b.setText(dark?"Dark":"Light");b.setToolTipText("Decoder 테마: "+(dark?"Dark":"Light")+" · 클릭하여 전환 (Burp 설정은 변경하지 않음)");}
            if(b instanceof ActionButton||b instanceof Toggle){b.setContentAreaFilled(false);b.setBorder(new RoundBorder());b.setFocusPainted(false);b.setOpaque(false);}
        }
        if(c instanceof JScrollPane scroll){scroll.setBackground(p.bg());scroll.getViewport().setBackground(p.bg());if(scroll.getViewport().getView() instanceof JTextArea)scroll.setBorder(new RoundBorder());}
        if(c instanceof JScrollBar bar){bar.setBackground(p.bg());bar.setForeground(p.line());bar.setUI(new ScrollUI());}
        if(c instanceof Container container)for(Component child:container.getComponents())styleTree(child,p);
    }
    static double contrast(Color a,Color b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    private static double luminance(Color c){return .2126*channel(c.getRed())+.7152*channel(c.getGreen())+.0722*channel(c.getBlue());}
    private static double channel(int n){double v=n/255.;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}
    private static final class PlainSplitUI extends BasicSplitPaneUI {
        public BasicSplitPaneDivider createDefaultDivider(){return new BasicSplitPaneDivider(this){public void paint(Graphics g){g.setColor(colors(splitPane).bg());g.fillRect(0,0,getWidth(),getHeight());}};}
    }
    private static final class ComboUI extends BasicComboBoxUI {
        protected ComboPopup createPopup(){
            BasicComboPopup popup=new BasicComboPopup(comboBox){public void show(){stylePopup(this);super.show();}};
            stylePopup(popup);return popup;
        }
        private void stylePopup(BasicComboPopup popup){Colors p=colors(comboBox);styleTree(popup,p);popup.setBackground(p.bg());popup.setBorder(BorderFactory.createLineBorder(p.line()));popup.getList().setBackground(p.bg());}
        protected JButton createArrowButton(){JButton b=new JButton(){protected void paintComponent(Graphics g){Colors p=colors(comboBox);g.setColor(p.bg());g.fillRect(0,0,getWidth(),getHeight());g.setColor(isEnabled()?p.fg():p.muted());int x=getWidth()/2,y=getHeight()/2;g.drawLine(x-4,y-2,x,y+2);g.drawLine(x,y+2,x+4,y-2);}};b.setBorder(BorderFactory.createEmptyBorder());b.setPreferredSize(new Dimension(23,28));return b;}
        public void paintCurrentValueBackground(Graphics g,Rectangle bounds,boolean focus){g.setColor(colors(comboBox).bg());g.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);}
        public void paintCurrentValue(Graphics g,Rectangle bounds,boolean focus){Colors p=colors(comboBox);Component renderer=comboBox.getRenderer().getListCellRendererComponent(listBox,comboBox.getSelectedItem(),-1,false,false);renderer.setFont(comboBox.getFont());renderer.setBackground(p.bg());renderer.setForeground(comboBox.isEnabled()?p.fg():p.muted());currentValuePane.paintComponent(g,renderer,comboBox,bounds.x,bounds.y,bounds.width,bounds.height,renderer instanceof JPanel);}
    }
    private static final class ScrollUI extends BasicScrollBarUI {
        protected JButton createDecreaseButton(int orientation){return hiddenButton();}protected JButton createIncreaseButton(int orientation){return hiddenButton();}
        private JButton hiddenButton(){JButton b=new JButton();b.setPreferredSize(new Dimension(0,0));b.setMinimumSize(new Dimension(0,0));b.setMaximumSize(new Dimension(0,0));return b;}
        protected void paintTrack(Graphics g,JComponent c,Rectangle r){g.setColor(colors(c).bg());g.fillRect(r.x,r.y,r.width,r.height);}
        protected void paintThumb(Graphics g,JComponent c,Rectangle r){g.setColor(colors(c).line());g.fillRoundRect(r.x+3,r.y+3,Math.max(1,r.width-6),Math.max(1,r.height-6),6,6);}
    }
    private record CheckIcon(boolean checked) implements Icon {
        public int getIconWidth(){return 16;}public int getIconHeight(){return 16;}
        public void paintIcon(Component c,Graphics graphics,int x,int y){Colors p=colors(c);Graphics2D g=(Graphics2D)graphics.create();g.setColor(checked?p.accent():p.bg());g.fillRoundRect(x+1,y+1,13,13,3,3);g.setColor(p.line());g.drawRoundRect(x+1,y+1,13,13,3,3);if(checked){g.setColor(p==DARK?p.editorBg():Color.WHITE);g.setStroke(new BasicStroke(2));g.drawLine(x+4,y+7,x+7,y+10);g.drawLine(x+7,y+10,x+12,y+4);}g.dispose();}
    }
    static void clampDialog(JDialog dialog,Dimension requested){Rectangle screen=dialog.getGraphicsConfiguration().getBounds();Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());dialog.setSize(Math.min(requested.width,screen.width-insets.left-insets.right-30),Math.min(requested.height,screen.height-insets.top-insets.bottom-40));}
    static final class RoundBorder extends AbstractBorder{
        public Insets getBorderInsets(Component c){return new Insets(1,1,1,1);}
        public void paintBorder(Component c,Graphics graphics,int x,int y,int w,int h){Colors p=colors(c);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(c.hasFocus()?p.accent():p.line());g.drawRoundRect(x,y,w-1,h-1,9,9);g.dispose();}
    }
    private static void buttonBackground(AbstractButton b,Graphics graphics){
        Colors p=colors(b);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        boolean primary=Boolean.TRUE.equals(b.getClientProperty("decoder.primary"));Color fill=b.isSelected()?p.selection():primary?p.accent():p.bg();
        g.setColor(fill);g.fillRoundRect(0,0,b.getWidth(),b.getHeight(),8,8);g.dispose();
        b.setForeground(!b.isEnabled()?p.muted():primary?(p==DARK?p.editorBg():Color.WHITE):b.isSelected()?p.accent():p.fg());
    }
    private static final class ActionButton extends JButton{
        ActionButton(String text){super(text);setMargin(new Insets(6,12,6,12));setRolloverEnabled(true);setContentAreaFilled(false);}
        public Dimension getPreferredSize(){Dimension d=super.getPreferredSize();return new Dimension(Math.max(62,d.width+16),Math.max(32,d.height+8));}
        protected void paintComponent(Graphics g){buttonBackground(this,g);super.paintComponent(g);}
    }
    private static final class Toggle extends JToggleButton{
        Toggle(String text,boolean selected){super(text,selected);setMargin(new Insets(6,12,6,12));setContentAreaFilled(false);}
        public Dimension getPreferredSize(){Dimension d=super.getPreferredSize();return new Dimension(Math.max(82,d.width+8),32);}
        protected void paintComponent(Graphics g){buttonBackground(this,g);super.paintComponent(g);}
    }
    /** Wrapping layout for option groups, always anchored to the left. */
    static final class Wrap implements LayoutManager{
        final int gap;Wrap(int gap){this.gap=gap;}public void addLayoutComponent(String n,Component c){}public void removeLayoutComponent(Component c){}
        public Dimension preferredLayoutSize(Container p){return measure(p,false);}public Dimension minimumLayoutSize(Container p){return new Dimension(100,32);}
        private Dimension measure(Container p,boolean place){Insets in=p.getInsets();int width=p.getWidth()>0?p.getWidth():600,usable=Math.max(1,width-in.left-in.right);int x=0,y=0,row=0,max=0;for(Component c:p.getComponents())if(c.isVisible()){Dimension d=c.getPreferredSize();int w=Math.min(d.width,usable);if(x>0&&x+w>usable){max=Math.max(max,x-gap);x=0;y+=row+gap;row=0;}if(place)c.setBounds(in.left+x,in.top+y,w,d.height);x+=w+gap;row=Math.max(row,d.height);}return new Dimension(Math.max(max,Math.max(0,x-gap))+in.left+in.right,y+row+in.top+in.bottom);}
        public void layoutContainer(Container p){measure(p,true);}
    }
}
