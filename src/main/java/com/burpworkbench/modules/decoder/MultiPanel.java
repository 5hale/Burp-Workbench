package com.burpworkbench.modules.decoder;

import java.awt.*;
import java.util.*;
import javax.swing.*;

/** Peer editors: typing in any reversible representation updates the others, never the active text. */
final class MultiPanel extends JPanel implements AutoCloseable {
    static final Codecs.Kind[] KINDS={Codecs.Kind.TEXT,Codecs.Kind.UTF16,Codecs.Kind.UTF32,Codecs.Kind.UTF8,Codecs.Kind.URL,Codecs.Kind.BASE,Codecs.Kind.DECIMAL,Codecs.Kind.HASH};
    static final String[] TITLES={"Unicode text","UTF-16","UTF-32","UTF-8 bytes","Percent / URL","Base","Decimal","Hash"};
    final Map<Codecs.Kind,Field> fields=new EnumMap<>(Codecs.Kind.class);
    final JPanel grid;final JScrollPane gridScroll;
    final JLabel status=Ui.errorLabel();private final LatestTask task=new LatestTask();private final javax.swing.Timer debounce;
    private String canonical="";private boolean rendering,closed,busy,uncommitted;private Field source;
    enum WrapMode { AUTO, ON, OFF }
    private WrapMode wrapMode=WrapMode.AUTO;
    static final int LONG_LINE=8192;
    private Map<Codecs.Kind,Value> cached=Map.of();
    private Map<Codecs.Kind,Codecs.Options> cachedOptions=Map.of();
    private String cachedCanonical;
    private static final int CARD_GAP=12,SECTION_GAP=6,MIN_EDITOR_HEIGHT=60;
    private int commonHeaderHeight=36;
    final class Field extends JPanel {
        final Codecs.Kind kind;final String title;final JTextArea text;final OptionPanel options;final JButton copy;final JScrollPane editor;final JPanel header,labels;final JLabel label,count=Ui.lengthLabel();
        Field(Codecs.Kind kind,String title,Font font){
            super(null);setOpaque(false);this.kind=kind;this.title=title;
            text=Ui.text(font,kind!=Codecs.Kind.HASH);options=new OptionPanel(kind,()->optionChanged(this));
            ((javax.swing.text.AbstractDocument)text.getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter(){
                @Override public void insertString(FilterBypass fb,int offset,String value,javax.swing.text.AttributeSet attrs)throws javax.swing.text.BadLocationException{replace(fb,offset,0,value,attrs);}
                @Override public void replace(FilterBypass fb,int offset,int length,String value,javax.swing.text.AttributeSet attrs)throws javax.swing.text.BadLocationException{
                    // Avoid wrapping a large paste before the asynchronous conversion has run.
                    if(wrapMode==WrapMode.AUTO&&fb.getDocument().getLength()-length+(value==null?0:value.length())>LONG_LINE)text.setLineWrap(false);
                    fb.replace(offset,length,value,attrs);
                }
            });
            // Advanced puts every option inside the title/count header, immediately before Copy.
            options.setCompact(true);options.removeAll();options.setLayout(new RightWrap());
            for(Component control:options.dropdowns.getComponents())options.add(control);
            for(Component control:options.flags.getComponents())options.add(control);
            copy=Ui.button("Copy",()->Ui.copy(text,status));header=Ui.panel(new HeaderLayout());
            label=Ui.caption(title);label.setFont(font.deriveFont(Font.BOLD));count.setFont(font.deriveFont(Font.BOLD));
            labels=Ui.panel(new GridLayout(2,1,0,2));labels.add(label);labels.add(count);count.setToolTipText("Displayed text: Unicode characters and UTF-8 bytes");
            header.add(labels);header.add(options);header.add(copy);
            editor=Ui.scroll(text);add(header);add(editor);
            Ui.changes(text,()->{if(!rendering&&!closed){source=this;uncommitted=true;schedule();}});
        }
        @Override public void doLayout(){
            header.setBounds(0,0,getWidth(),commonHeaderHeight);
            int y=commonHeaderHeight+SECTION_GAP;
            editor.setBounds(0,y,getWidth(),Math.max(MIN_EDITOR_HEIGHT,getHeight()-y));
        }
        @Override public Dimension getPreferredSize(){return new Dimension(480,cardHeight(100));}
        private int minimumHeaderWidth(){
            int optionWidth=0;for(Component c:options.getComponents())optionWidth=Math.max(optionWidth,c.getPreferredSize().width);
            return labels.getPreferredSize().width+copy.getPreferredSize().width+optionWidth+16;
        }
        private final class HeaderLayout implements LayoutManager {
            public void addLayoutComponent(String name,Component c){}public void removeLayoutComponent(Component c){}
            public Dimension preferredLayoutSize(Container p){
                int width=p.getWidth()>0?p.getWidth():580;
                int optionWidth=Math.max(1,width-labels.getPreferredSize().width-copy.getPreferredSize().width-16);
                options.setSize(optionWidth,1);
                return new Dimension(width,Math.max(labels.getPreferredSize().height,Math.max(copy.getPreferredSize().height,options.getPreferredSize().height)));
            }
            public Dimension minimumLayoutSize(Container p){return new Dimension(minimumHeaderWidth(),preferredLayoutSize(p).height);}
            public void layoutContainer(Container p){
                Dimension left=labels.getPreferredSize(),button=copy.getPreferredSize();int height=p.getHeight();
                int optionWidth=Math.max(0,p.getWidth()-left.width-button.width-16);options.setSize(optionWidth,1);
                int optionHeight=options.getPreferredSize().height;
                labels.setBounds(0,Math.max(0,(height-left.height)/2),left.width,left.height);
                options.setBounds(left.width+8,Math.max(0,(height-optionHeight)/2),optionWidth,optionHeight);
                copy.setBounds(p.getWidth()-button.width,Math.max(0,(height-button.height)/2),button.width,button.height);
            }
        }
    }
    /** Right-aligned compact option rows; wrap individual controls, never clip a complete dropdown. */
    private static final class RightWrap implements LayoutManager {
        private static final int GAP=6;
        public void addLayoutComponent(String name,Component c){}public void removeLayoutComponent(Component c){}
        public Dimension preferredLayoutSize(Container p){return measure(p,false);}
        public Dimension minimumLayoutSize(Container p){return preferredLayoutSize(p);}
        public void layoutContainer(Container p){measure(p,true);}
        private Dimension measure(Container p,boolean place){
            int available=Math.max(1,p.getWidth()),width=0,height=0,y=0,maxWidth=0;java.util.List<Component> row=new ArrayList<>();
            for(Component c:p.getComponents())if(c.isVisible()){
                Dimension d=c.getPreferredSize();int next=width+(row.isEmpty()?0:GAP)+d.width;
                if(!row.isEmpty()&&next>available){if(place)place(row,available,width,height,y);maxWidth=Math.max(maxWidth,width);y+=height+GAP;row.clear();width=0;height=0;}
                width+=(row.isEmpty()?0:GAP)+d.width;height=Math.max(height,d.height);row.add(c);
            }
            if(!row.isEmpty()){if(place)place(row,available,width,height,y);maxWidth=Math.max(maxWidth,width);y+=height;}
            return new Dimension(maxWidth,y);
        }
        private void place(java.util.List<Component> row,int available,int width,int height,int y){
            int x=Math.max(0,available-width);for(Component c:row){Dimension d=c.getPreferredSize();c.setBounds(x,y+(height-d.height)/2,d.width,d.height);x+=d.width+GAP;}
        }
    }
    record Value(String value,String error,String length){}
    record Result(String canonical,Map<Codecs.Kind,Value> values,String selectedLength,String error){}
    MultiPanel(Font font,Runnable hotkeys){
        super(new BorderLayout());debounce=new javax.swing.Timer(160,e->calculate());debounce.setRepeats(false);
        JPanel toolbar=Ui.toolbar(this,hotkeys,status);
        JComboBox<String> wrap=new JComboBox<>(new String[]{"Auto","On","Off"});wrap.setName("advanced-wrap");
        wrap.setToolTipText("Auto: 8192자를 넘는 긴 줄은 가로 스크롤로 표시합니다. 원문은 변경하지 않습니다.");
        wrap.addActionListener(e->wrapMode(WrapMode.values()[wrap.getSelectedIndex()]));
        toolbar.add(Ui.row(Ui.caption("Wrap"),wrap),BorderLayout.WEST);
        add(toolbar,BorderLayout.NORTH);
        JPanel content=Ui.panel(new BorderLayout(0,6));content.setBorder(BorderFactory.createEmptyBorder(12,20,12,20));
        grid=new FieldGrid();for(int i=0;i<KINDS.length;i++){Field f=new Field(KINDS[i],TITLES[i],font);fields.put(KINDS[i],f);grid.add(f);}
        gridScroll=new JScrollPane(grid);gridScroll.setBorder(null);gridScroll.getVerticalScrollBar().setUnitIncrement(24);gridScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        content.add(gridScroll,BorderLayout.CENTER);add(content,BorderLayout.CENTER);finishAppearance();applyEditorFont(font);
    }
    private int cardHeight(int editorHeight){return commonHeaderHeight+SECTION_GAP+editorHeight;}
    private final class FieldGrid extends JPanel implements Scrollable {
        private int columns=2;
        FieldGrid(){super(new GridLayout(0,2,20,CARD_GAP));setOpaque(false);}
        private int metrics(){
            int width=getParent() instanceof JViewport vp&&vp.getWidth()>0?vp.getWidth():1120;
            int minimumCard=0;for(Field f:fields.values())minimumCard=Math.max(minimumCard,f.minimumHeaderWidth());
            columns=width<Math.max(880,2*minimumCard+20)?1:2;((GridLayout)getLayout()).setColumns(columns);
            int cardWidth=(width-(columns-1)*20)/columns;int maxHeader=36;
            for(Field f:fields.values()){f.header.setSize(cardWidth,1);maxHeader=Math.max(maxHeader,f.header.getPreferredSize().height);}
            commonHeaderHeight=maxHeader;
            return width;
        }
        private int totalHeight(int editorHeight){int rows=(KINDS.length+columns-1)/columns;return rows*cardHeight(editorHeight)+(rows-1)*CARD_GAP;}
        @Override public Dimension getPreferredSize(){
            int width=metrics();
            // Use all available height at normal size; preserve usable editors with genuine scrolling below this threshold.
            int editorHeight=columns==2?MIN_EDITOR_HEIGHT:100;
            return new Dimension(width,totalHeight(editorHeight));
        }
        @Override public void doLayout(){metrics();super.doLayout();}
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 24;}
        public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(24,visible.height-24);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){
            metrics();return getParent() instanceof JViewport vp&&vp.getHeight()>=totalHeight(columns==2?MIN_EDITOR_HEIGHT:100);
        }
    }
    void setText(String text){
        if(closed)return;
        // Keep the supplied input visible/editable even when conversion rejects its size or contents.
        canonical=text;source=fields.get(Codecs.Kind.TEXT);uncommitted=true;debounce.stop();
        rendering=true;display(source.text,text);source.text.setCaretPosition(0);rendering=false;calculate();
    }
    void wrapMode(WrapMode mode){wrapMode=mode;for(Field f:fields.values())applyWrap(f.text);}
    private void applyWrap(JTextArea editor){
        boolean wrap=wrapMode==WrapMode.ON;
        if(wrapMode==WrapMode.AUTO){
            wrap=true;var root=editor.getDocument().getDefaultRootElement();
            for(int i=0;i<root.getElementCount();i++){var line=root.getElement(i);if(line.getEndOffset()-line.getStartOffset()-1>LONG_LINE){wrap=false;break;}}
        }
        if(editor.getLineWrap()!=wrap)editor.setLineWrap(wrap);
    }
    private boolean display(JTextArea editor,String value){
        if(editor.getText().equals(value)){applyWrap(editor);return false;}
        editor.setLineWrap(false);editor.setText(value);applyWrap(editor);return true;
    }
    String canonical(){return canonical;}
    boolean busy(){return busy;}
    private void optionChanged(Field field){
        if(closed||rendering)return;
        // A settled option changes the representation, not the meaning of its old formatted text.
        if(!uncommitted)source=null;
        schedule();
    }
    private void schedule(){task.cancel();busy=true;Ui.error(status,null);fields.values().forEach(f->f.copy.setEnabled(f==source));debounce.setInitialDelay(source!=null&&source.text.getDocument().getLength()>32000?400:160);debounce.restart();}
    private void calculate(){
        if(closed)return;debounce.stop();busy=true;Ui.error(status,null);
        Field selected=uncommitted?source:null;String input=selected==null?canonical:selected.text.getText();
        Map<Codecs.Kind,Codecs.Options> opts=new EnumMap<>(Codecs.Kind.class);fields.forEach((kind,f)->{opts.put(kind,f.options.snapshot());f.copy.setEnabled(f==selected);});
        String previousCanonical=cachedCanonical;Map<Codecs.Kind,Value> previousValues=cached;Map<Codecs.Kind,Codecs.Options> previousOptions=cachedOptions;
        task.submit(()->{
            String selectedLength=selected==null?null:Ui.lengthText(input);String text;
            try{text=selected==null?Codecs.convert(input,Codecs.Kind.TEXT,true,Codecs.Options.defaults()):Codecs.convert(input,selected.kind,false,opts.get(selected.kind));}
            catch(IllegalArgumentException e){return new Result(null,Map.of(),selectedLength,e.getMessage());}
            Map<Codecs.Kind,Value> result=new EnumMap<>(Codecs.Kind.class);
            for(Codecs.Kind kind:KINDS){Codecs.check(0);
                if(text.equals(previousCanonical)&&opts.get(kind).equals(previousOptions.get(kind))&&previousValues.containsKey(kind)){result.put(kind,previousValues.get(kind));continue;}
                try{String value=Codecs.convert(text,kind,true,opts.get(kind));result.put(kind,new Value(value,null,Ui.lengthText(value)));}catch(IllegalArgumentException e){result.put(kind,new Value("",e.getMessage(),Ui.lengthText("")));}}
            return new Result(text,result,selectedLength,null);
        },result->{
            if(result.error!=null){reject(selected,result.error,result.selectedLength);return;}
            canonical=result.canonical;uncommitted=false;busy=false;rendering=true;java.util.List<String> errors=new ArrayList<>();
            cachedCanonical=canonical;cached=result.values;cachedOptions=Map.copyOf(opts);
            for(Codecs.Kind kind:KINDS){Field f=fields.get(kind);Value v=result.values.get(kind);
                if(f!=selected){if(display(f.text,v.value))f.text.setCaretPosition(0);}else applyWrap(f.text);f.count.setText(f==selected?result.selectedLength:v.length);f.copy.setEnabled(v.error==null||f==selected);if(v.error!=null)errors.add(f.title+" · "+v.error);
            }
            rendering=false;Ui.error(status,errors.isEmpty()?null:String.join(" / ",errors));
        },error->reject(selected,Ui.error(error),null));
    }
    private void reject(Field selected,String error,String selectedLength){
        busy=false;rendering=true;
        for(Field f:fields.values())if(f!=selected){display(f.text,"");f.count.setText(Ui.lengthText(""));f.copy.setEnabled(false);}else if(selectedLength!=null){f.count.setText(selectedLength);applyWrap(f.text);}
        rendering=false;Ui.error(status,(selected==null?"":selected.title+" · ")+error);
    }
    void finishAppearance(){Ui.style(this);}
    void applyEditorFont(Font font){fields.values().forEach(f->f.text.setFont(font));Ui.labelFont(this,font);revalidate();}
    @Override public void close(){closed=true;debounce.stop();task.close();busy=false;canonical="";cachedCanonical=null;cached=Map.of();cachedOptions=Map.of();rendering=true;fields.values().forEach(f->{display(f.text,"");f.count.setText(Ui.lengthText(""));});source=null;}
}
