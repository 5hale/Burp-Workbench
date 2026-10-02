package com.burpworkbench.modules.compare;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.CharBuffer;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.burpworkbench.platform.TableMenus.*;

final class ComparePanel extends JPanel implements AutoCloseable {
    final List<CompareItem> items=new ArrayList<>();
    final JComboBox<CompareItem> leftChoice=new JComboBox<>(),rightChoice=new JComboBox<>();
    final SyntaxEditor left=new SyntaxEditor(),right=new SyntaxEditor();
    final JComboBox<DiffEngine.Mode> mode=new JComboBox<>(DiffEngine.Mode.values());
    final JTabbedPane leftTabs=new JTabbedPane(),rightTabs=new JTabbedPane();
    final JComboBox<String> charset=new JComboBox<>(new String[]{"Auto","UTF-8","MS949","ISO-8859-1"});
    final JCheckBox differences=new JCheckBox("Differences only"),sync=new JCheckBox("Sync",true),wrap=new JCheckBox("Wrap",false);
    final JLabel status=new JLabel("Bring two items from a message editor, Paste, or Load."),position=new JLabel("0 / 0 differences"),location=new JLabel(" ");
    final JTextField search=new JTextField(20);
    final JLabel searchStatus=new JLabel(" ");
    final JLabel comparisonNotice=new JLabel();
    private final JPanel noticeRow=new JPanel(new BorderLayout());
    final ItemTable model=new ItemTable();
    final JTable table=new JTable(model);
    final LatestComparison jobs=new LatestComparison(SwingUtilities::invokeLater);
    final JScrollPane leftScroll=new JScrollPane(left),rightScroll=new JScrollPane(right);
    final JButton previous=button("‹",()->navigate(-1)),next=button("›",()->navigate(1));
    final JButton findPrevious=button("‹",()->find(-1)),findNext=button("›",()->find(1));
    final JButton cancel=button("Cancel",this::cancelComparison);
    private final JLabel count=new JLabel("Items · 0");
    private final JPanel library=new JPanel(new BorderLayout(0,6));
    private final JSplitPane outer;
    private Comparison.View result;
    private long nextId=1,totalBytes;
    private boolean refreshing,scrolling,closed;
    private int current=-1,libraryWidth=320;
    private Font editorFont;
    private JTextArea activeSearch;
    private JTextArea lastSearchArea;
    private String lastQuery="";
    private int lastMatch=-1;
    private SwingWorker<byte[],Void> loading;
    private final Consumer<CompareItem> repeater;
    private final Map<CompareItem,byte[]> leftDrafts=new IdentityHashMap<>(),rightDrafts=new IdentityHashMap<>();
    private Charset leftEncoding=StandardCharsets.UTF_8,rightEncoding=StandardCharsets.UTF_8;
    private boolean presenting;
    private final javax.swing.Timer editTimer=new javax.swing.Timer(250,e->compareCurrent(true));
    private final javax.swing.undo.UndoManager leftUndo=new javax.swing.undo.UndoManager(),rightUndo=new javax.swing.undo.UndoManager();

    ComparePanel(Font editorFont,Runnable hotkeys) {
        this(editorFont,hotkeys,item->{});
    }
    ComparePanel(Font editorFont,Runnable hotkeys,Consumer<CompareItem> repeater) {
        super(new BorderLayout(0,8));this.editorFont=editorFont;this.repeater=repeater;
        setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        JPanel toolbar=new JPanel(new BorderLayout()); JPanel fileActions=flow();
        JButton add=button("Add",this::addBlank);add.setName("compare.add");add.setToolTipText("Add empty item · paste directly into Raw");fileActions.add(add);
        fileActions.add(button("Paste",this::paste));fileActions.add(button("Load",this::load));fileActions.add(button("Copy",this::copySelected));
        fileActions.add(button("Remove",this::removeSelected));fileActions.add(button("Clear",this::confirmClear));
        JToggleButton show=new JToggleButton("Items",true);fileActions.add(show);
        toolbar.add(fileActions,BorderLayout.WEST);toolbar.add(button("Hotkeys",hotkeys),BorderLayout.EAST);
        add(toolbar,BorderLayout.NORTH);

        table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(Math.max(25,table.getRowHeight()+6));table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);table.setName("compare.items");
        install(table,
                entry("Use as A",()->!closed&&table.getSelectedRow()>=0,()->model.setValueAt(true,table.convertRowIndexToModel(table.getSelectedRow()),0)),
                entry("Use as B",()->!closed&&table.getSelectedRow()>=0,()->model.setValueAt(true,table.convertRowIndexToModel(table.getSelectedRow()),1)),
                entry("Copy",()->!closed&&table.getSelectedRow()>=0,this::copySelected),
                entry("Remove",()->!closed&&table.getSelectedRow()>=0,this::removeSelected));
        int[] widths={30,30,40,72,72,280};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        for(int i=0;i<3;i++)table.getColumnModel().getColumn(i).setMaxWidth(widths[i]);
        count.setBorder(BorderFactory.createEmptyBorder(0,3,3,0));library.add(count,BorderLayout.NORTH);
        JScrollPane listScroll=new JScrollPane(table);listScroll.setColumnHeaderView(table.getTableHeader());library.add(listScroll);
        JLabel hint=new JLabel("Click A or B to compare immediately");hint.setBorder(BorderFactory.createEmptyBorder(3,3,0,0));library.add(hint,BorderLayout.SOUTH);
        library.setMinimumSize(new Dimension(170,120));

        JPanel compare=new JPanel(new BorderLayout(0,6));
        JPanel controls=new JPanel();controls.setLayout(new BoxLayout(controls,BoxLayout.Y_AXIS));
        JPanel selection=new JPanel(new BorderLayout(6,0));JPanel choices=new JPanel(new GridLayout(1,2,10,0));
        choices.add(choice("A",leftChoice));choices.add(choice("B",rightChoice));selection.add(choices);selection.add(button("⇄",this::swap),BorderLayout.EAST);
        controls.add(selection);controls.add(Box.createVerticalStrut(6));
        JPanel options=flow();options.add(mode);options.add(new JLabel("Encoding"));options.add(charset);
        controls.add(options);JPanel viewing=flow();viewing.add(differences);viewing.add(sync);viewing.add(wrap);viewing.add(cancel);controls.add(viewing);
        comparisonNotice.setBorder(BorderFactory.createEmptyBorder(3,6,3,6));comparisonNotice.setVisible(false);
        noticeRow.add(comparisonNotice);noticeRow.setVisible(false);controls.add(noticeRow);
        compare.add(controls,BorderLayout.NORTH);
        configureEditor(left);configureEditor(right);
        editTimer.setRepeats(false);
        leftScroll.setRowHeaderView(left.gutter);rightScroll.setRowHeaderView(right.gutter);
        left.gutter.setToolTipText("Text: original line number · Hex: row number and original byte offset (hex)");
        right.gutter.setToolTipText(left.gutter.getToolTipText());
        configureTabs(leftTabs,leftScroll);configureTabs(rightTabs,rightScroll);
        JSplitPane editors=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,leftTabs,rightTabs);
        editors.setResizeWeight(.5);editors.setDividerLocation(.5);editors.setContinuousLayout(true);editors.setBorder(null);
        leftScroll.setMinimumSize(new Dimension(100,100));rightScroll.setMinimumSize(new Dimension(100,100));
        compare.add(editors);

        JPanel bottom=new JPanel();bottom.setLayout(new BoxLayout(bottom,BoxLayout.Y_AXIS));
        JPanel nav=new JPanel(new BorderLayout());JPanel arrows=flow();previous.setToolTipText("Previous difference (Alt+Up)");next.setToolTipText("Next difference (Alt+Down)");
        arrows.add(previous);arrows.add(next);arrows.add(position);nav.add(arrows,BorderLayout.WEST);bottom.add(nav);
        JPanel locations=flow();locations.add(location);bottom.add(locations);
        JPanel find=flow();find.add(new JLabel("Find"));find.add(search);find.add(findPrevious);find.add(findNext);find.add(searchStatus);
        findPrevious.setToolTipText("Previous text match (Shift+Enter)");findNext.setToolTipText("Next text match (Enter)");
        search.setToolTipText("Literal text search in the last focused pane. Enter = next match; Shift+Enter = previous match.");
        search.addActionListener(e->find(1));search.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"),"previous-find");search.getActionMap().put("previous-find",new AbstractAction(){public void actionPerformed(ActionEvent e){find(-1);}});
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){resetFind();}public void removeUpdate(DocumentEvent e){resetFind();}public void changedUpdate(DocumentEvent e){resetFind();}});
        resetFind();
        bottom.add(find);
        JPanel legend=flow();legend.add(swatch("Modified",Comparison.Kind.Modified));legend.add(swatch("Deleted",Comparison.Kind.Deleted));legend.add(swatch("Added",Comparison.Kind.Added));bottom.add(legend);
        compare.add(bottom,BorderLayout.SOUTH);compare.setMinimumSize(new Dimension(480,220));
        outer=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,library,compare);outer.setName("compare.items.split");outer.setDividerLocation(libraryWidth);outer.setResizeWeight(0);outer.setContinuousLayout(true);outer.setBorder(null);add(outer);
        show.addActionListener(e->{if(show.isSelected()){library.setVisible(true);outer.setDividerSize(6);outer.setDividerLocation(libraryWidth);}else{libraryWidth=outer.getDividerLocation();library.setVisible(false);outer.setDividerSize(0);outer.setDividerLocation(0);}});
        status.setBorder(BorderFactory.createEmptyBorder(4,3,0,0));add(status,BorderLayout.SOUTH);
        leftChoice.addActionListener(e->selectionChanged());rightChoice.addActionListener(e->selectionChanged());
        mode.addActionListener(e->selectionChanged());charset.addActionListener(e->selectionChanged());differences.addActionListener(e->selectionChanged());
        wrap.addActionListener(e->applyWrap());
        leftScroll.getVerticalScrollBar().addAdjustmentListener(e->syncScroll(leftScroll,rightScroll));
        rightScroll.getVerticalScrollBar().addAdjustmentListener(e->syncScroll(rightScroll,leftScroll));
        bindNavigation("alt UP",-1);bindNavigation("alt DOWN",1);
        cancel.setEnabled(false);previous.setEnabled(false);next.setEnabled(false);
    }
    private void configureEditor(JTextArea area){
        area.setEditable(false);area.setFont(editorFont);area.setMargin(new Insets(8,10,8,10));area.setWrapStyleWord(false);area.setTabSize(4);
        com.burpworkbench.platform.WorkbenchInput.bind(area,selectedOnly->captureEditor(area,selectedOnly));
        com.burpworkbench.platform.WorkbenchInput.bindFull(area,()->captureFullEditor(area));
        area.addFocusListener(new FocusAdapter(){public void focusGained(FocusEvent e){activeSearch=area;}});
        for(String modifier:new String[]{"ctrl","meta"}){
            area.getInputMap().put(KeyStroke.getKeyStroke(modifier+" F"),"compare-find");
            area.getInputMap().put(KeyStroke.getKeyStroke(modifier+" R"),"compare-repeater");
        }
        area.getActionMap().put("compare-find",new AbstractAction(){public void actionPerformed(ActionEvent e){focusFind(area);}});
        area.getActionMap().put("compare-repeater",new AbstractAction(){public void actionPerformed(ActionEvent e){sendToRepeater(area);}});
        var undo=area==left?leftUndo:rightUndo;
        area.getDocument().addUndoableEditListener(e->{if(!presenting&&area.isEditable())undo.addEdit(e.getEdit());});
        for(String modifier:new String[]{"ctrl","meta"}){
            area.getInputMap().put(KeyStroke.getKeyStroke(modifier+" Z"),"compare-undo");
            area.getInputMap().put(KeyStroke.getKeyStroke(modifier+" shift Z"),"compare-redo");
        }
        area.getActionMap().put("compare-undo",new AbstractAction(){public void actionPerformed(ActionEvent e){if(area.isEditable()&&undo.canUndo())undo.undo();}});
        area.getActionMap().put("compare-redo",new AbstractAction(){public void actionPerformed(ActionEvent e){if(area.isEditable()&&undo.canRedo())undo.redo();}});
        ((AbstractDocument)area.getDocument()).setDocumentFilter(new DocumentFilter(){
            @Override public void insertString(FilterBypass fb,int offset,String text,AttributeSet attrs)throws BadLocationException{replace(fb,offset,0,text,attrs);}
            @Override public void remove(FilterBypass fb,int offset,int length)throws BadLocationException{replace(fb,offset,length,"",null);}
            @Override public void replace(FilterBypass fb,int offset,int length,String text,AttributeSet attrs)throws BadLocationException{
                if(presenting||!area.isEditable()){fb.replace(offset,length,text,attrs);return;}
                String current=fb.getDocument().getText(0,fb.getDocument().getLength());
                String updated=current.substring(0,offset)+(text==null?"":text)+current.substring(offset+length);
                byte[] bytes=encode(area,updated);if(bytes==null)return;
                fb.replace(offset,length,text,attrs);
            }
        });
        area.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){edited(area);}public void removeUpdate(DocumentEvent e){edited(area);}public void changedUpdate(DocumentEvent e){}
        });
    }
    void focusFind(JTextArea area){activeSearch=area;search.requestFocusInWindow();search.selectAll();}
    private com.burpworkbench.platform.WorkbenchInput.Value captureFullEditor(JTextArea area){
        if(closed)return null;
        CompareItem item=(CompareItem)(area==right?rightChoice:leftChoice).getSelectedItem();
        if(item==null||!("Request".equals(item.kind)||"Response".equals(item.kind)))return null;
        var original=item.request==null?null:item.request.toRequest();
        return new com.burpworkbench.platform.WorkbenchInput.Value(workingBytes(area,item).clone(),item.kind,"Compare #"+item.id,original==null?"":original.url(),original);
    }
    private com.burpworkbench.platform.WorkbenchInput.Value captureEditor(JTextArea area,boolean selectedOnly){
        if(closed)return null;
        CompareItem item=(CompareItem)(area==right?rightChoice:leftChoice).getSelectedItem();
        if(item==null)return null;String selected=area.getSelectedText();
        boolean hasSelection=selected!=null&&!selected.isEmpty();if(selectedOnly&&!hasSelection)return null;
        byte[] bytes=hasSelection?selected.getBytes(StandardCharsets.UTF_8):workingBytes(area,item).clone();
        com.burpworkbench.platform.WorkbenchInput.check(bytes.length);
        burp.api.montoya.http.message.requests.HttpRequest original=null;
        try{if(item.request!=null)original=item.request.toRequest();}catch(RuntimeException ignored){}
        String url=original==null?"":original.url();
        return new com.burpworkbench.platform.WorkbenchInput.Value(bytes,hasSelection?"Text":item.kind,"Compare #"+item.id,url,original);
    }
    void sendToRepeater(JTextArea area){
        if(closed)return;CompareItem item=(CompareItem)(area==right?rightChoice:leftChoice).getSelectedItem();
        if(item==null){status.setText("Select an item first.");return;}
        if(item.request==null){status.setText("No original request available. Import from a Burp request/response editor (request limit: 1 MiB).");return;}
        try{repeater.accept(item);status.setText("Original request for #"+item.id+" added to Repeater; no network request sent.");}
        catch(RuntimeException|LinkageError error){status.setText("Send to Repeater failed: "+error.getClass().getSimpleName());}
    }
    private void configureTabs(JTabbedPane tabs,JScrollPane scroll){
        for(String name:new String[]{"Pretty","Raw","Hex"})tabs.addTab(name,new JPanel(new BorderLayout()));
        tabs.setToolTipTextAt(0,"Format this side only; mixed views may include formatting differences.");
        tabs.setToolTipTextAt(1,"Editable working text");tabs.setToolTipTextAt(2,"Working bytes · fixed-width columns (read-only)");
        tabs.setSelectedIndex(1);((JPanel)tabs.getSelectedComponent()).add(scroll);
        tabs.addChangeListener(e->{((JPanel)tabs.getSelectedComponent()).add(scroll);tabs.revalidate();tabs.repaint();selectionChanged();});
    }
    void applyEditorFont(Font font){editorFont=font;applyViewFonts();}
    private void applyViewFonts(){
        Font fixed=new Font(Font.MONOSPACED,editorFont.getStyle(),editorFont.getSize()).deriveFont(editorFont.getSize2D());
        left.setFont(leftTabs.getSelectedIndex()==2?fixed:editorFont);
        right.setFont(rightTabs.getSelectedIndex()==2?fixed:editorFont);
    }
    Font editorFont(){return editorFont;}
    private void applyWrap(){boolean a=leftTabs.getSelectedIndex()!=2,b=rightTabs.getSelectedIndex()!=2;wrap.setEnabled(a||b);wrap.setToolTipText("Wrap applies to Pretty/Raw only; Hex keeps 16 bytes per row.");left.setLineWrap(a&&wrap.isSelected());right.setLineWrap(b&&wrap.isSelected());applyViewFonts();}
    private JPanel choice(String side,JComboBox<CompareItem> box){JPanel p=new JPanel(new BorderLayout(6,0));p.add(new JLabel(side),BorderLayout.WEST);box.setPrototypeDisplayValue(new CompareItem(999,new MessageCapture(new byte[0],"Response","https://example.test/api/profile")));p.add(box);JButton reset=new JButton("↶");reset.setName("compare.reset."+side);reset.setToolTipText("Restore original "+side);reset.getAccessibleContext().setAccessibleName(reset.getToolTipText());reset.addActionListener(e->restore(side.equals("A")?left:right));p.add(reset,BorderLayout.EAST);return p;}
    private static JPanel flow(){return new JPanel(new FlowLayout(FlowLayout.LEFT,6,1));}
    private static JButton button(String title,Runnable action){JButton b=com.burpworkbench.platform.ActionIcons.button(title);b.addActionListener(e->action.run());return b;}
    private Color color(Comparison.Kind kind){
        return left.differenceColor(kind);
    }
    private JComponent swatch(String text,Comparison.Kind kind){JLabel label=new JLabel("  "+text+"  ");label.setOpaque(true);label.setBackground(color(kind));return label;}
    private void bindNavigation(String key,int delta){getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key),key);getActionMap().put(key,new AbstractAction(){public void actionPerformed(ActionEvent e){navigate(delta);}});}

    boolean add(MessageCapture input){
        if(closed||input==null)return false;
        if(input.bytes()==null){status.setText(input.source());return false;}
        if(input.bytes().length>MessageCapture.MAX_ITEM){status.setText("Compare limit: 1 MiB per item. Nothing was added.");return false;}
        long retained=(long)input.bytes().length+(input.request()==null?0:input.request().length());
        if(items.size()>=200||retainedTotal()+retained>32L*1024*1024){status.setText("Compare collection limit reached (200 items / 32 MiB including original requests and edits). Remove items first.");return false;}
        CompareItem item=new CompareItem(nextId++,input);items.add(item);totalBytes+=item.retainedBytes();
        CompareItem a=(CompareItem)leftChoice.getSelectedItem(),b=(CompareItem)rightChoice.getSelectedItem();
        if(a==null)a=item;else if(b==null)b=item;
        refreshChoices(a,b);return true;
    }
    private void refreshChoices(CompareItem a,CompareItem b){
        refreshing=true;try{leftChoice.removeAllItems();rightChoice.removeAllItems();for(CompareItem item:items){leftChoice.addItem(item);rightChoice.addItem(item);}leftChoice.setSelectedItem(a);rightChoice.setSelectedItem(b);model.fireTableDataChanged();count.setText("Items · "+items.size());}finally{refreshing=false;}
        selectionChanged();
    }
    private void swap(){CompareItem a=(CompareItem)leftChoice.getSelectedItem(),b=(CompareItem)rightChoice.getSelectedItem();var saved=new IdentityHashMap<>(leftDrafts);leftDrafts.clear();leftDrafts.putAll(rightDrafts);rightDrafts.clear();rightDrafts.putAll(saved);refreshChoices(b,a);}
    private void selectionChanged(){
        if(refreshing||closed)return;
        editTimer.stop();jobs.cancel();clearViews();leftUndo.discardAllEdits();rightUndo.discardAllEdits();applyWrap();if(!items.isEmpty())model.fireTableRowsUpdated(0,items.size()-1);
        compareCurrent(false);
    }
    private void compareCurrent(boolean keepCaret){
        if(closed)return;
        jobs.cancel();applyWrap();
        int aView=leftTabs.getSelectedIndex(),bView=rightTabs.getSelectedIndex();
        boolean pretty=aView==0||bView==0;
        String gutter=pretty?"Pretty: formatted-copy line numbers (Raw/Hex retain original positions)":"Raw: original line number · Hex: original byte offset";
        left.gutter.setToolTipText(gutter);right.gutter.setToolTipText(gutter);
        CompareItem a=(CompareItem)leftChoice.getSelectedItem(),b=(CompareItem)rightChoice.getSelectedItem();
        if(a==null&&b==null){status.setText("Add an item, Paste, or Load.");return;}
        CompareItem currentA=a==null?null:a.workingCopy(workingBytes(left,a)),currentB=b==null?null:b.workingCopy(workingBytes(right,b));
        var options=new Comparison.Options((DiffEngine.Mode)mode.getSelectedItem(),false,differences.isSelected(),(String)charset.getSelectedItem());
        status.setText(a==null||b==null?"Loading…":"Comparing #"+a.id+" ↔ #"+b.id+"…");cancel.setEnabled(true);
        jobs.submit(()->PaneComparison.prepare(currentA,currentB,aView,bView,options),value->display(value,keepCaret),message->{showProblem(message);cancel.setEnabled(false);});
    }
    private void clearViews(){result=null;current=-1;presenting=true;try{left.setEditable(false);right.setEditable(false);left.clear();right.clear();}finally{presenting=false;}resetFind();position.setText("0 / 0 differences");location.setText(" ");previous.setEnabled(false);next.setEnabled(false);cancel.setEnabled(false);comparisonNotice.setVisible(false);noticeRow.setVisible(false);}
    private void resetFind(){lastMatch=-1;lastSearchArea=null;lastQuery="";boolean hasQuery=!search.getText().isEmpty();findPrevious.setEnabled(hasQuery);findNext.setEnabled(hasQuery);searchStatus.setText(hasQuery?"Text search":" ");}
    private void showProblem(String message){
        String escaped=message.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        comparisonNotice.setText("<html>Comparison not completed.<br>"+escaped+"</html>");comparisonNotice.setToolTipText(message);comparisonNotice.setVisible(true);noticeRow.setVisible(true);
        status.setText(message);position.setText("Not compared");
    }
    void display(Comparison.View value){
        display(value,false);
    }
    private void display(Comparison.View value,boolean keepCaret){
        if(closed)return;result=value;presenting=true;try{if(keepCaret){left.decorate(value.a());right.decorate(value.b());}else{left.show(value.a());right.show(value.b());}configureEditing(left);configureEditing(right);}finally{presenting=false;}resetFind();
        left.gutter.setToolTipText(value.note());right.gutter.setToolTipText(value.note());
        cancel.setEnabled(false);previous.setEnabled(!value.marks().isEmpty());next.setEnabled(!value.marks().isEmpty());
        comparisonNotice.setVisible(false);noticeRow.setVisible(false);
        if(value.problem()!=null){current=-1;location.setText(" ");showProblem(value.problem());return;}
        status.setText("Single pane".equals(value.note())?" ":value.marks().isEmpty()?"No differences":value.marks().size()+" differences");
        if(!value.marks().isEmpty()&&!keepCaret){current=-1;navigate(1);}else{current=-1;position.setText("0 / "+value.marks().size()+" differences");location.setText(" ");}
    }
    void navigate(int delta){
        if(result==null||result.marks().isEmpty())return;
        current=Math.floorMod(current+delta,result.marks().size());var m=result.marks().get(current);
        scrolling=true;try{reveal(left,m.a0());reveal(right,m.b0());}finally{scrolling=false;}
        position.setText((current+1)+" / "+result.marks().size()+" differences");location.setText(m.kind()+" · "+m.location());location.setToolTipText(result.note()+" · "+location.getText());
    }
    private void reveal(JTextArea area,int offset){area.setCaretPosition(Math.min(offset,area.getDocument().getLength()));try{var rect=area.modelToView2D(area.getCaretPosition());if(rect!=null)area.scrollRectToVisible(rect.getBounds());}catch(BadLocationException ignored){}}
    void find(int direction){
        JTextArea area=activeSearch==null?left:activeSearch;String needle=search.getText();
        if(needle.isEmpty()){resetFind();return;}
        String text=area.getText();boolean repeat=lastSearchArea==area&&lastQuery.equals(needle)&&lastMatch>=0;
        int start=repeat?lastMatch:area.getCaretPosition(),index;
        if(direction>0){index=text.indexOf(needle,Math.min(text.length(),start+(repeat?needle.length():0)));if(index<0)index=text.indexOf(needle);}else{index=text.lastIndexOf(needle,start-1);if(index<0)index=text.lastIndexOf(needle);}
        if(index<0){searchStatus.setText("No match in "+(area==left?"A":"B"));return;}
        // Selection is temporary, leaving the diff highlighter intact.
        lastSearchArea=area;lastQuery=needle;lastMatch=index;
        area.select(index,index+needle.length());area.getCaret().setSelectionVisible(true);try{var rect=area.modelToView2D(index);if(rect!=null)area.scrollRectToVisible(rect.getBounds());}catch(BadLocationException ignored){}
        searchStatus.setText((area==left?"A":"B")+" · char "+index);
    }
    private void syncScroll(JScrollPane from,JScrollPane to){if(!sync.isSelected()||scrolling)return;scrolling=true;try{JScrollBar a=from.getVerticalScrollBar(),b=to.getVerticalScrollBar();int maxA=a.getMaximum()-a.getVisibleAmount(),maxB=b.getMaximum()-b.getVisibleAmount();if(maxA>0)b.setValue((int)((long)a.getValue()*maxB/maxA));}finally{scrolling=false;}}
    private void paste(){try{Object value=Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);if(value instanceof String text){if(text.length()>MessageCapture.MAX_ITEM){status.setText("Compare limit: clipboard text is too large.");return;}add(new MessageCapture(text.getBytes(StandardCharsets.UTF_8),"Text","Clipboard"));}}catch(Exception e){status.setText("Clipboard text unavailable.");}}
    private void load(){
        if(loading!=null){status.setText("A file is already loading.");return;}
        JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        Path path=chooser.getSelectedFile().toPath();status.setText("Loading file…");
        loading=new SwingWorker<>(){
            protected byte[] doInBackground() throws Exception{try(var stream=Files.newInputStream(path)){byte[] data=stream.readNBytes(MessageCapture.MAX_ITEM+1);if(data.length>MessageCapture.MAX_ITEM)throw new IllegalArgumentException("Compare limit: 1 MiB per file.");return data;}}
            protected void done(){loading=null;if(closed||isCancelled())return;try{add(new MessageCapture(get(),"Text",path.getFileName().toString()));}catch(Exception e){status.setText("Load failed or file exceeds 1 MiB limit.");}}
        };loading.execute();
    }
    void removeSelected(){if(closed)return;int[] selected=com.burpworkbench.platform.TableSelection.rows(table);if(selected.length==0)return;CompareItem a=(CompareItem)leftChoice.getSelectedItem(),b=(CompareItem)rightChoice.getSelectedItem();for(int n=selected.length-1;n>=0;n--){CompareItem item=items.remove(selected[n]);totalBytes-=item.retainedBytes();leftDrafts.remove(item);rightDrafts.remove(item);if(a==item)a=null;if(b==item)b=null;}refreshChoices(a,b);}
    private void confirmClear(){if(items.isEmpty()||JOptionPane.showConfirmDialog(this,"Clear all captured items from this session?","Clear",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;clearItems();}
    void clearItems(){editTimer.stop();if(loading!=null)loading.cancel(true);items.clear();leftDrafts.clear();rightDrafts.clear();totalBytes=0;refreshChoices(null,null);}
    @Override public void close(){closed=true;editTimer.stop();jobs.close();if(loading!=null)loading.cancel(true);clearItems();clearViews();}
    private Map<CompareItem,byte[]> drafts(JTextArea area){return area==left?leftDrafts:rightDrafts;}
    byte[] workingBytes(JTextArea area,CompareItem item){return drafts(area).getOrDefault(item,item.bytes);}
    private long retainedTotal(){long size=totalBytes;for(var map:List.of(leftDrafts,rightDrafts))for(byte[] bytes:map.values())size+=bytes.length;return size;}
    private byte[] encode(JTextArea area,String text){
        try{
            if(text.length()>MessageCapture.MAX_ITEM)throw new IllegalArgumentException("Compare limit: 1 MiB per edit.");
            var encoding=area==left?leftEncoding:rightEncoding;
            var buffer=encoding.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);
            CompareItem item=(CompareItem)(area==left?leftChoice:rightChoice).getSelectedItem();
            byte[] old=drafts(area).get(item);long added=item!=null&&Arrays.equals(bytes,item.bytes)?0:bytes.length;
            if(bytes.length>MessageCapture.MAX_ITEM||retainedTotal()-(old==null?0:old.length)+added>32L*1024*1024)throw new IllegalArgumentException("Compare edit limit reached (1 MiB/item, 32 MiB total).");
            return bytes;
        }catch(java.nio.charset.CharacterCodingException invalid){status.setText("Cannot encode this text. Choose UTF-8 before editing.");return null;}
        catch(IllegalArgumentException limit){status.setText(limit.getMessage());return null;}
    }
    private void configureEditing(JTextArea area){
        CompareItem item=(CompareItem)(area==left?leftChoice:rightChoice).getSelectedItem();
        int view=(area==left?leftTabs:rightTabs).getSelectedIndex();boolean editable=item!=null&&view==1&&!differences.isSelected();
        if(editable){var decoded=Comparison.decode(workingBytes(area,item),(String)charset.getSelectedItem());editable=decoded.exact;if(area==left)leftEncoding=decoded.charset;else rightEncoding=decoded.charset;}
        area.setEditable(editable);area.setToolTipText(editable?"Edit working copy · originals preserved":"Read-only · edit in Raw with Differences only off and a lossless encoding");
    }
    private void edited(JTextArea area){
        if(presenting||closed||!area.isEditable())return;
        CompareItem item=(CompareItem)(area==left?leftChoice:rightChoice).getSelectedItem();if(item==null)return;
        byte[] bytes=encode(area,area.getText());if(bytes==null)return;
        if(Arrays.equals(bytes,item.bytes))drafts(area).remove(item);else drafts(area).put(item,bytes);
        jobs.cancel();result=null;left.invalidatePresentation();right.invalidatePresentation();resetFind();previous.setEnabled(false);next.setEnabled(false);position.setText("0 / 0 differences");location.setText(" ");status.setText("Updating…");cancel.setEnabled(true);editTimer.restart();
    }
    private void cancelComparison(){editTimer.stop();jobs.cancel();clearViews();status.setText("Comparison cancelled. Change the selection or mode to compare again.");}
    void restore(JTextArea area){if(closed)return;CompareItem item=(CompareItem)(area==left?leftChoice:rightChoice).getSelectedItem();if(item==null)return;drafts(area).remove(item);selectionChanged();}
    void addBlank(){
        if(closed)return;boolean useLeft=leftChoice.getSelectedItem()==null||(rightChoice.getSelectedItem()!=null&&activeSearch!=right);
        if(!add(new MessageCapture(new byte[0],"Text","Untitled")))return;
        CompareItem fresh=items.get(items.size()-1);refreshing=true;try{(useLeft?leftTabs:rightTabs).setSelectedIndex(1);}finally{refreshing=false;}
        refreshChoices(useLeft?fresh:(CompareItem)leftChoice.getSelectedItem(),useLeft?(CompareItem)rightChoice.getSelectedItem():fresh);
        JTextArea area=useLeft?left:right;activeSearch=area;SwingUtilities.invokeLater(area::requestFocusInWindow);
    }
    void copySelected(){
        if(closed)return;int[] rows=com.burpworkbench.platform.TableSelection.rows(table);if(rows.length==0)return;
        long extra=0;for(int row:rows){CompareItem item=items.get(row);extra+=copyBytes(item).length+(item.request==null?0:item.request.length());}
        if(items.size()+rows.length>200||retainedTotal()+extra>32L*1024*1024){status.setText("Compare collection limit reached. Nothing was copied.");return;}
        int insert=rows[rows.length-1]+1;List<CompareItem> copies=new ArrayList<>();for(int row:rows){CompareItem old=items.get(row);copies.add(new CompareItem(nextId++,new MessageCapture(copyBytes(old),old.kind,old.source+" copy",old.request)));}
        for(CompareItem item:copies){items.add(insert++,item);totalBytes+=item.retainedBytes();}
        refreshChoices((CompareItem)leftChoice.getSelectedItem(),(CompareItem)rightChoice.getSelectedItem());
        int[] selected=new int[copies.size()];for(int i=0;i<selected.length;i++)selected[i]=insert-selected.length+i;com.burpworkbench.platform.TableSelection.select(table,selected);
    }
    private byte[] copyBytes(CompareItem item){if(activeSearch==right&&rightChoice.getSelectedItem()==item)return workingBytes(right,item);if(leftChoice.getSelectedItem()==item)return workingBytes(left,item);if(rightChoice.getSelectedItem()==item)return workingBytes(right,item);return item.bytes;}
    final class ItemTable extends AbstractTableModel {
        private final String[] columns={"A","B","#","Type","Bytes","Source"};
        public int getRowCount(){return items.size();}public int getColumnCount(){return columns.length;}public String getColumnName(int c){return columns[c];}
        public Class<?> getColumnClass(int c){return c<2?Boolean.class:c==2?Long.class:c==4?Integer.class:String.class;}
        public boolean isCellEditable(int r,int c){return c<2;}
        public Object getValueAt(int r,int c){CompareItem i=items.get(r);return switch(c){case 0->leftChoice.getSelectedItem()==i;case 1->rightChoice.getSelectedItem()==i;case 2->i.id;case 3->i.kind;case 4->i.bytes.length;default->i.source;};}
        public void setValueAt(Object v,int row,int col){(col==0?leftChoice:rightChoice).setSelectedItem(items.get(row));}
    }
}
