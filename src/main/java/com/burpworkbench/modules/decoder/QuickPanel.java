package com.burpworkbench.modules.decoder;

import java.awt.*;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Approved focus popup: left rail, equal editors, a compact live controls row. */
final class QuickPanel extends JPanel implements AutoCloseable {
    private static final String[] LABELS={"URL","Base","Hex","HTML","Unicode","Hash"};
    private static final String[] SYMBOLS={"↗","01","<>","</>","{}","#"};
    private static final Codecs.Kind[] KINDS={Codecs.Kind.URL,Codecs.Kind.BASE,Codecs.Kind.HEX,Codecs.Kind.HTML,Codecs.Kind.UNICODE,Codecs.Kind.HASH};
    final JTextArea input,output;
    final JList<String> navigation=new JList<>(LABELS);
    final JToggleButton decode=Ui.toggle("Decode",true),encode=Ui.toggle("Encode",false);
    final JLabel status=Ui.errorLabel(),count=Ui.lengthLabel(),resultCount=Ui.lengthLabel();
    final JButton swap,copy,multi;
    final Map<Codecs.Kind,OptionPanel> options=new EnumMap<>(Codecs.Kind.class);
    final JScrollPane inputScroll,outputScroll;
    private final JPanel optionHost=Ui.panel(new BorderLayout()),flagHost=Ui.panel(new BorderLayout());
    private JPanel modes,resultHead,rail;
    private final LatestTask task=new LatestTask();private final javax.swing.Timer debounce;
    private final Consumer<String> openMulti;private boolean updating,validResult,closed,restoringNavigation,navigationRepairQueued;private String semanticText="";
    QuickPanel(Font font,Runnable hotkeys,Consumer<String> openMulti){
        super(new BorderLayout());this.openMulti=openMulti;input=Ui.text(font,true);output=Ui.text(font,false);
        inputScroll=Ui.scroll(input);outputScroll=Ui.scroll(output);
        debounce=new javax.swing.Timer(140,e->run());debounce.setRepeats(false);
        add(Ui.toolbar(this,hotkeys,status),BorderLayout.NORTH);
        rail=new JPanel(new BorderLayout(0,12));rail.putClientProperty("decoder.soft",true);rail.setBorder(BorderFactory.createEmptyBorder(18,10,10,10));rail.setPreferredSize(new Dimension(140,300));
        JLabel railTitle=Ui.caption("CONVERT");railTitle.setBorder(BorderFactory.createEmptyBorder(0,10,0,0));rail.add(railTitle,BorderLayout.NORTH);
        navigation.setOpaque(false);navigation.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);navigation.setFixedCellHeight(44);navigation.setSelectedIndex(0);
        restoreNavigationAppearance();rail.add(navigation,BorderLayout.CENTER);add(rail,BorderLayout.WEST);
        JPanel main=Ui.panel(new BorderLayout(0,10));main.setBorder(BorderFactory.createEmptyBorder(16,20,14,20));add(main,BorderLayout.CENTER);
        for(Codecs.Kind kind:KINDS)options.put(kind,new OptionPanel(kind,this::schedule));
        showOptionRows();
        ButtonGroup group=new ButtonGroup();group.add(decode);group.add(encode);decode.addActionListener(e->schedule());encode.addActionListener(e->schedule());
        modes=Ui.row(decode,encode);
        JPanel controls=alignedRow(modes,optionHost);controls.setBorder(BorderFactory.createEmptyBorder(12,0,6,0));
        JPanel inputHead=Ui.panel(new FlowLayout(FlowLayout.LEFT,0,0));inputHead.add(Ui.caption("INPUT"));count.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));inputHead.add(count);
        JPanel before=Ui.panel(new BorderLayout(0,8));before.add(inputHead,BorderLayout.NORTH);before.add(inputScroll,BorderLayout.CENTER);before.add(controls,BorderLayout.SOUTH);
        resultHead=Ui.panel(new FlowLayout(FlowLayout.LEFT,0,0));resultHead.add(Ui.caption("RESULT"));resultCount.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));resultHead.add(resultCount);
        JPanel resultRow=alignedRow(resultHead,flagHost);
        JPanel after=Ui.panel(new BorderLayout(0,8));after.add(resultRow,BorderLayout.NORTH);after.add(outputScroll,BorderLayout.CENTER);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,before,after){
            private boolean placed;private int lastExtra,lastWidth,lastHeight;
            @Override public void doLayout(){
                super.doLayout();
                int extra=inputHead.getPreferredSize().height+8+controls.getPreferredSize().height-resultRow.getPreferredSize().height;
                if(getHeight()>0&&(!placed||lastExtra!=extra||lastWidth!=getWidth()||lastHeight!=getHeight())){
                    setDividerLocation((getHeight()-getDividerSize()+extra)/2);placed=true;lastExtra=extra;lastWidth=getWidth();lastHeight=getHeight();super.doLayout();
                }
            }
        };
        split.setResizeWeight(.5);split.setContinuousLayout(true);split.setDividerSize(6);split.setBorder(null);main.add(split,BorderLayout.CENTER);
        swap=Ui.button("Swap",this::swap);copy=Ui.button("Copy",()->Ui.copy(output,status));copy.putClientProperty("decoder.primary",true);multi=Ui.button("Advanced",this::openMulti);
        rail.add(multi,BorderLayout.SOUTH);
        JPanel footer=Ui.panel(new BorderLayout());JPanel actions=Ui.panel(new FlowLayout(FlowLayout.RIGHT,0,0));actions.add(swap);actions.add(Box.createHorizontalStrut(8));actions.add(copy);footer.add(actions,BorderLayout.CENTER);main.add(footer,BorderLayout.SOUTH);
        navigation.addListSelectionListener(e->{if(!e.getValueIsAdjusting()){showOptionRows();boolean hash=kind()==Codecs.Kind.HASH;decode.setEnabled(!hash);encode.setEnabled(!hash);schedule();}});
        navigation.addPropertyChangeListener(event->{
            if(!restoringNavigation&&!closed&&event.getPropertyName()!=null&&java.util.Set.of("cellRenderer","UI","font","foreground","background","selectionForeground","selectionBackground").contains(event.getPropertyName())&&!navigationRepairQueued){
                navigationRepairQueued=true;SwingUtilities.invokeLater(()->{navigationRepairQueued=false;if(!closed)restoreNavigationAppearance();});
            }
        });
        Ui.changes(input,this::schedule);setActions(false);finishAppearance();applyEditorFont(font);
    }
    private void showOptionRows(){
        OptionPanel selected=options.get(kind());optionHost.removeAll();flagHost.removeAll();
        optionHost.add(selected.dropdowns,BorderLayout.CENTER);flagHost.add(selected.flags,BorderLayout.CENTER);
        optionHost.revalidate();flagHost.revalidate();revalidate();repaint();
    }
    private int labelColumnWidth(){return Math.max(modes==null?0:modes.getPreferredSize().width,resultHead==null?0:resultHead.getPreferredSize().width);}
    /** Required content width for any converter, using the current UI font and counters. */
    int minimumContentWidth(){
        int optionWidth=0;for(OptionPanel panel:options.values())optionWidth=Math.max(optionWidth,Math.max(panel.dropdownWidth(true),panel.flags.getPreferredSize().width));
        return Math.max(820,rail.getPreferredSize().width+40+labelColumnWidth()+16+optionWidth);
    }
    private JPanel alignedRow(JComponent left,JComponent right){
        JPanel row=Ui.panel(new LayoutManager(){
            public void addLayoutComponent(String name,Component c){}public void removeLayoutComponent(Component c){}
            public Dimension preferredLayoutSize(Container p){Insets in=p.getInsets();return new Dimension(labelColumnWidth()+16+right.getPreferredSize().width+in.left+in.right,Math.max(32,Math.max(left.getPreferredSize().height,right.getPreferredSize().height))+in.top+in.bottom);}
            public Dimension minimumLayoutSize(Container p){return preferredLayoutSize(p);}
            public void layoutContainer(Container p){Insets in=p.getInsets();int h=Math.max(0,p.getHeight()-in.top-in.bottom),x=in.left,column=labelColumnWidth();Dimension d=left.getPreferredSize();left.setBounds(x,in.top+(h-d.height)/2,column,d.height);int available=Math.max(0,p.getWidth()-in.right-x-column-16);if(right==optionHost){OptionPanel option=options.get(kind());option.setCompact(option.dropdownWidth(false)>available);}right.setBounds(x+column+16,in.top,available,h);}
        });row.add(left);row.add(right);return row;
    }
    private static final class NavItem extends JLabel{
        final boolean selected;final Ui.Colors colors;
        NavItem(String label,int index,boolean selected,Ui.Colors colors,Font font,Color foreground){super(label,new Symbol(SYMBOLS[index]),LEADING);this.selected=selected;this.colors=colors;setIconTextGap(12);setBorder(BorderFactory.createEmptyBorder(0,10,0,4));setForeground(foreground);setFont(font);}
        protected void paintComponent(Graphics graphics){if(selected){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(colors.selection());g.fillRoundRect(0,2,getWidth(),getHeight()-4,8,8);g.dispose();}super.paintComponent(graphics);}
    }
    private record Symbol(String value) implements Icon{
        public int getIconWidth(){return 20;}public int getIconHeight(){return 20;}
        public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.setColor(c.getForeground());g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,value.length()>2?11:15));g.drawString(value,x,y+15);g.dispose();}
    }
    Codecs.Kind kind(){return KINDS[Math.max(0,navigation.getSelectedIndex())];}
    void setInput(String text,String origin,String charset){updating=true;Ui.initialText(input,text);input.setCaretPosition(0);for(OptionPanel panel:options.values())panel.charset(charset);updating=false;run();}
    void schedule(){if(updating||closed)return;task.cancel();validResult=false;setActions(false);Ui.error(status,null);debounce.restart();count.setText("");resultCount.setText("");}
    private record Conversion(String text,String inputLength,String resultLength,String error){}
    void run(){
        if(closed)return;debounce.stop();task.cancel();String value=input.getText();Codecs.Kind kind=kind();Codecs.Options opt=options.get(kind).snapshot();boolean enc=kind==Codecs.Kind.HASH||encode.isSelected();
        validResult=false;setActions(false);Ui.error(status,null);count.setText("");resultCount.setText("");
        task.submit(()->{
            String inputLength=Ui.lengthText(value);
            try{String result=Codecs.convert(value,kind,enc,opt);return new Conversion(result,inputLength,Ui.lengthText(result),null);}
            catch(IllegalArgumentException e){return new Conversion("",inputLength,Ui.lengthText(""),Ui.error(e));}
        },result->{
            count.setText(result.inputLength);resultCount.setText(result.resultLength);output.setText(result.text);output.setCaretPosition(0);
            semanticText=result.error==null?(enc?value:result.text):"";validResult=result.error==null;setActions(validResult);Ui.error(status,result.error);
        },error->{validResult=false;output.setText("");resultCount.setText("0 chars · 0 bytes");setActions(false);Ui.error(status,Ui.error(error));});
    }
    private void setActions(boolean success){copy.setEnabled(success&&!closed);swap.setEnabled(success&&!closed);multi.setEnabled(!closed);}
    void swap(){
        if(!validResult)return;debounce.stop();task.cancel();String oldInput=input.getText(),oldOutput=output.getText();updating=true;input.setText(oldOutput);output.setText(oldInput);
        if(kind()!=Codecs.Kind.HASH){boolean wasEncode=encode.isSelected();decode.setSelected(wasEncode);encode.setSelected(!wasEncode);}
        updating=false;semanticText=kind()==Codecs.Kind.HASH||encode.isSelected()?oldOutput:oldInput;validResult=true;Ui.error(status,null);String oldCount=count.getText();count.setText(resultCount.getText());resultCount.setText(oldCount);input.setCaretPosition(0);output.setCaretPosition(0);
    }
    void openMulti(){if(!closed)openMulti.accept(validResult?semanticText:input.getText());}
    boolean validResult(){return validResult;}
    private void restoreNavigationAppearance(){
        restoringNavigation=true;
        try{
            Font labelFont=input.getFont().deriveFont(Font.BOLD);Color labelColor=Ui.colors(this).fg();
            navigation.setFont(labelFont);navigation.setForeground(labelColor);navigation.setSelectionForeground(labelColor);
            navigation.setBackground(input.getBackground());navigation.setSelectionBackground(Ui.colors(this).selection());navigation.setOpaque(true);
            // Burp's theme pass can replace the list renderer. Reinstall it after that pass, not only in the constructor.
            navigation.setCellRenderer((list,value,index,selected,focus)->new NavItem(value,index,selected,Ui.colors(this),labelFont,labelColor));
            int width=120;for(int i=0;i<LABELS.length;i++)width=Math.max(width,new NavItem(LABELS[i],i,false,Ui.colors(this),labelFont,labelColor).getPreferredSize().width);
            rail.setPreferredSize(new Dimension(width+20,300));navigation.setFixedCellHeight(Math.max(44,navigation.getFontMetrics(labelFont).getHeight()+12));
            revalidate();navigation.repaint();
        }finally{restoringNavigation=false;}
    }
    void finishAppearance(){Ui.style(this);options.values().forEach(o->{Ui.styleControls(o.dropdowns,this);Ui.styleControls(o.flags,this);});restoreNavigationAppearance();}
    void applyEditorFont(Font font){input.setFont(font);output.setFont(font);Ui.labelFont(this,font);options.values().forEach(o->{Ui.labelFont(o.dropdowns,font);Ui.labelFont(o.flags,font);});restoreNavigationAppearance();}
    @Override public void close(){closed=true;validResult=false;setActions(false);debounce.stop();task.close();updating=true;input.setText("");output.setText("");semanticText="";}
}
