package com.burpworkbench.modules.decoder;

import java.awt.*;
import java.util.*;
import javax.swing.*;

final class OptionPanel extends JPanel {
    final JComboBox<String> charset=new SizedCombo(Codecs.CHARSETS),notation=new SizedCombo();
    final JComboBox<String> unit=new SizedCombo("UTF-16","UTF-32","UTF-8");
    final JComboBox<String> endian=new SizedCombo("Big endian","Little endian");
    final JComboBox<String> base=new SizedCombo(Codecs.BASES),hash=new SizedCombo(Codecs.HASHES);
    final JToggleButton spaces=Ui.toggle("Space",true),padding=Ui.toggle("Padding",true);
    final JToggleButton form=Ui.toggle("Space",false);
    final JPanel dropdowns=Ui.panel(new OptionRow()),flags=Ui.panel(new OptionRow());
    private final java.util.List<JPanel> fieldGroups=new ArrayList<>();
    private final Codecs.Kind kind;private boolean setup=true,compact;
    OptionPanel(Codecs.Kind kind,Runnable changed){
        super(new BorderLayout(0,4));setOpaque(false);this.kind=kind;
        switch(kind){
            case BASE->{field("Base",base,154);field("Charset",charset,112);flags.add(padding);}
            case HASH->{hash.setSelectedItem("SHA-256");field("Charset",charset,112);field("Hash",hash,150);}
            case HEX->{fillNotation("None","%","0x","\\x");field("Prefix",notation,84);field("Charset",charset,112);flags.add(spaces);}
            case URL->{field("Charset",charset,112);form.setToolTipText("켜면 공백을 +로 인코딩하고 +를 공백으로 디코딩합니다.");flags.add(form);}
            case UNICODE,UTF8,UTF16,UTF32->{
                fillNotation("U+","0x","\\u","%","None");
                if(kind==Codecs.Kind.UTF8)fillNotation("\\x","0x","%","None");
                else if(kind==Codecs.Kind.UTF32)fillNotation("U+","0x","\\U","%","None");
                if(kind==Codecs.Kind.UNICODE)field("Unit",unit,90);
                field("Format",notation,78);if(kind!=Codecs.Kind.UTF8)field("",endian,120);flags.add(spaces);
                notation.setToolTipText("%: 실제 UTF bytes · UTF-32 escape: \\U + 8자리");
            }
            default->{} // No permanent instructions consuming editor space.
        }
        if(dropdowns.getComponentCount()>0)add(dropdowns,BorderLayout.NORTH);
        if(flags.getComponentCount()>0)add(flags,BorderLayout.SOUTH);
        for(JComboBox<String> box:java.util.List.of(charset,notation,unit,endian,base,hash))box.addActionListener(e->{updateEnabled();if(!setup){changed.run();revalidate();}});
        for(AbstractButton box:java.util.List.of(spaces,padding,form))box.addActionListener(e->{if(!setup)changed.run();});
        updateEnabled();setup=false;
    }
    private void updateEnabled(){endian.setEnabled("%".equals(notation.getSelectedItem())&&!(kind==Codecs.Kind.UNICODE&&"UTF-8".equals(unit.getSelectedItem())));endian.setToolTipText("% byte 표기에만 byte order를 적용합니다.");padding.setEnabled(!"Base16".equals(base.getSelectedItem())&&!"Base58 Bitcoin".equals(base.getSelectedItem()));}
    private void field(String name,JComponent component,int width){JPanel p=Ui.panel(new BorderLayout(6,0));if(!name.isEmpty()){p.add(Ui.caption(name),BorderLayout.WEST);component.getAccessibleContext().setAccessibleName(name);component.setToolTipText(name);}if(component instanceof SizedCombo box){box.minimumWidth=width;box.regularMinimumWidth=width;}else component.setPreferredSize(new Dimension(width,32));p.add(component,BorderLayout.CENTER);fieldGroups.add(p);dropdowns.add(p);}
    private void fillNotation(String...values){notation.removeAllItems();for(String value:values)notation.addItem(value);}
    Codecs.Options snapshot(){return new Codecs.Options((String)charset.getSelectedItem(),Objects.toString(notation.getSelectedItem(),"None"),spaces.isSelected(),endian.getSelectedIndex()==1,(String)unit.getSelectedItem(),(String)base.getSelectedItem(),padding.isSelected(),(String)hash.getSelectedItem(),form.isSelected());}
    void charset(String name){setup=true;charset.setSelectedItem(name);setup=false;}
    /** Advanced headers name their converter already, so retain options without duplicate captions. */
    void setCompact(boolean compact){
        if(this.compact==compact)return;this.compact=compact;
        for(JPanel field:fieldGroups){
            BorderLayout layout=(BorderLayout)field.getLayout();Component caption=layout.getLayoutComponent(BorderLayout.WEST);
            if(caption!=null&&caption.isVisible()==compact)caption.setVisible(!compact);
            if(layout.getLayoutComponent(BorderLayout.CENTER) instanceof SizedCombo box)box.minimumWidth=compact?0:box.regularMinimumWidth;
            field.revalidate();
        }
        revalidate();
    }
    int dropdownWidth(boolean compact){
        int width=0;
        for(JPanel field:fieldGroups){
            BorderLayout layout=(BorderLayout)field.getLayout();Component caption=layout.getLayoutComponent(BorderLayout.WEST),control=layout.getLayoutComponent(BorderLayout.CENTER);
            width+=control instanceof SizedCombo box?box.preferredSize(compact?0:box.regularMinimumWidth).width:control.getPreferredSize().width;
            if(!compact&&caption!=null)width+=caption.getPreferredSize().width+layout.getHgap();
        }
        return width+Math.max(0,fieldGroups.size()-1)*8;
    }
    /** Fit every choice using the current Burp UI font, including custom renderer/arrow space. */
    private static final class SizedCombo extends JComboBox<String> {
        int minimumWidth,regularMinimumWidth;
        SizedCombo(String...values){super(values);}
        @Override public Dimension getPreferredSize(){
            return preferredSize(minimumWidth);
        }
        Dimension preferredSize(int minimumWidth){
            Font font=getFont();if(font==null)return super.getPreferredSize();
            FontMetrics metrics=getFontMetrics(font);Insets in=getInsets();int textWidth=0;
            for(int i=0;i<getItemCount();i++)textWidth=Math.max(textWidth,metrics.stringWidth(Objects.toString(getItemAt(i),"")));
            int height=Math.max(32,metrics.getHeight()+6+in.top+in.bottom);
            // BasicComboBoxUI may make the arrow square even when its preferred width is 23.
            int arrowWidth=Math.max(23,height-in.top-in.bottom);
            return new Dimension(Math.max(minimumWidth,textWidth+10+arrowWidth+in.left+in.right+2),height);
        }
        @Override public Dimension getMinimumSize(){return getPreferredSize();}
    }
    /** Each option family has one right-aligned fixed row, with no implicit wrapping. */
    private static final class OptionRow implements LayoutManager {
        private static final int GAP=8;
        public void addLayoutComponent(String name,Component component){}
        public void removeLayoutComponent(Component component){}
        public Dimension preferredLayoutSize(Container parent){
            int width=0,height=0,count=0;for(Component c:parent.getComponents())if(c.isVisible()){Dimension d=c.getPreferredSize();width+=d.width;height=Math.max(height,d.height);count++;}
            Insets in=parent.getInsets();return new Dimension(width+Math.max(0,count-1)*GAP+in.left+in.right,height+in.top+in.bottom);
        }
        public Dimension minimumLayoutSize(Container parent){return preferredLayoutSize(parent);}
        public void layoutContainer(Container parent){
            Insets in=parent.getInsets();int contentWidth=preferredLayoutSize(parent).width-in.left-in.right;
            int x=Math.max(in.left,parent.getWidth()-in.right-contentWidth),height=parent.getHeight()-in.top-in.bottom;
            for(Component c:parent.getComponents())if(c.isVisible()){Dimension d=c.getPreferredSize();c.setBounds(x,in.top+Math.max(0,(height-d.height)/2),d.width,d.height);x+=d.width+GAP;}
        }
    }
}
