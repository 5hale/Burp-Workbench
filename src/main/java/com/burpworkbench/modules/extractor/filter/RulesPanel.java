package com.burpworkbench.modules.extractor.filter;

import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import static com.burpworkbench.modules.extractor.filter.FilterSettings.*;
import static com.burpworkbench.platform.TableMenus.*;

/** Extractor suite tab remains a rule editor; export uses the original folder chooser. */
public final class RulesPanel extends JPanel implements AutoCloseable {
    final List<Rule> rules=new ArrayList<>();
    final RuleModel model=new RuleModel();
    final JTable table=new JTable(model);
    final JTextField search=new JTextField(22);
    final JComboBox<String> categoryFilter=new JComboBox<>(new String[]{"All categories"});
    final JLabel status=new JLabel(" ");
    final JButton add=iconButton("Add",0),catalog=new JButton("Rulesets…"),copy=iconButton("Copy",1),remove=iconButton("Remove",2),up=iconButton("Up",3),down=iconButton("Down",4),test=new JButton("Test rules…");
    final JButton importButton=new JButton("Import…"),exportButton=new JButton("Export…");
    final JButton detailsToggle=new JButton("Hide details");
    final JPanel toolbar=new JPanel(new BorderLayout(8,0));
    final JPanel center=new JPanel(new BorderLayout());
    final JScrollPane listScroll=new JScrollPane(table);
    final JSplitPane split;
    private double listWidthFraction=.45;
    final JToggleButton advancedToggle=new JToggleButton("Advanced");
    final JPanel advancedPanel=new JPanel(new GridBagLayout());
    final JLabel advancedSummary=new JLabel(" ");
    JScrollPane detailScroll;
    private final TableRowSorter<RuleModel> sorter=new TableRowSorter<>(model);
    private final MontoyaApi api;
    private FilterRulesStore store;
    private boolean loadFailed,closed,loading,changed,editorDirty;
    private String editingId;
    private final javax.swing.Timer saveTimer=new javax.swing.Timer(400,e->flush());
    private final List<Window> windows=new ArrayList<>();
    private final JTextField name=new JTextField(),category=new JTextField(),tag=new JTextField(),mime=new JTextField(),field=new JTextField(),exclude=new JTextField();
    private final JTextArea pattern=new JTextArea(4,24),description=new JTextArea(3,24);
    private final JCheckBox regex=new JCheckBox("Regex",true),numbered=new JCheckBox("Number tags · §TYPE_1§");
    private final JComboBox<Match> match=new JComboBox<>(Match.values());
    private final JComboBox<Scope> scope=new JComboBox<>(Scope.values());
    private final JComboBox<Target> target=new JComboBox<>(Target.values());
    private final JSpinner group=new JSpinner(new SpinnerNumberModel(0,0,100,1));
    private final JButton apply=new JButton("Apply changes");
    private final JLabel detailTitle=new JLabel("Rule details");

    public RulesPanel(MontoyaApi api){
        super(new BorderLayout(8,8));this.api=api;setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        JPanel management=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        for(JButton b:List.of(add,copy,remove,up,down))management.add(b);
        management.add(Box.createHorizontalStrut(8));management.add(catalog);
        JPanel transfers=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));transfers.add(detailsToggle);transfers.add(importButton);transfers.add(exportButton);
        detailsToggle.setName("extractor.details.toggle");detailsToggle.addActionListener(e->toggleDetails());
        toolbar.add(management,BorderLayout.WEST);toolbar.add(transfers,BorderLayout.EAST);
        JPanel filters=new JPanel(new BorderLayout(8,0));JPanel left=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));left.add(new JLabel("Category"));left.add(categoryFilter);left.add(new JLabel("Search"));left.add(search);filters.add(left,BorderLayout.WEST);
        JPanel north=new JPanel(new GridLayout(2,1,0,8));north.add(toolbar);north.add(filters);add(north,BorderLayout.NORTH);
        table.setRowHeight(28);table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);table.setRowSorter(sorter);plainCells(table);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.getColumnModel().getColumn(0).setMaxWidth(48);table.getColumnModel().getColumn(1).setPreferredWidth(240);table.getColumnModel().getColumn(2).setPreferredWidth(120);table.getColumnModel().getColumn(3).setPreferredWidth(120);
        listScroll.setMinimumSize(new Dimension(240,100));
        JPanel detail=details();detail.setMinimumSize(new Dimension(280,100));
        split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,listScroll,detail){boolean placed;public void doLayout(){if(!placed&&getWidth()>0){placed=true;setDividerLocation(listWidthFraction);}super.doLayout();}};
        split.setName("extractor.split");split.setContinuousLayout(true);split.setResizeWeight(.45);center.add(split);add(center,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!loading)selectionChanged();});
        categoryFilter.addActionListener(e->{if(!loading)filter();});search.getDocument().addDocumentListener(listener(this::filter));
        add.addActionListener(e->action(()->{commit();if(rules.size()>=500)throw new IllegalArgumentException("Maximum 500 rules");Rule draft=Rule.draft();rules.add(draft);updated(List.of(draft.id()),true);}));
        catalog.addActionListener(e->action(()->{commit();showCatalog();}));
        copy.addActionListener(e->action(this::copySelected));remove.addActionListener(e->action(this::removeSelected));
        up.addActionListener(e->action(()->moveSelected(-1)));down.addActionListener(e->action(()->moveSelected(1)));
        apply.addActionListener(e->action(()->{commit();status.setText("Rule applied");}));
        test.addActionListener(e->action(()->{FilterSettings settings=snapshot();showTest(settings);}));
        importButton.addActionListener(e->action(this::importRules));exportButton.addActionListener(e->action(this::exportRules));
        install(table,entry("Copy selected rules",()->table.getSelectedRowCount()>0,()->copy.doClick()),entry("Remove selected rules",()->table.getSelectedRowCount()>0,()->remove.doClick()),entry("Move up",()->up.isEnabled(),()->up.doClick()),entry("Move down",()->down.isEnabled(),()->down.doClick()),entry("Test rules",()->!rules.isEmpty(),()->test.doClick()));
        for(JTextField input:List.of(name,category,tag,mime,field,exclude))input.getDocument().addDocumentListener(listener(this::dirtyEditor));
        pattern.getDocument().addDocumentListener(listener(this::dirtyEditor));description.getDocument().addDocumentListener(listener(this::dirtyEditor));
        match.addActionListener(e->{if(!loading){loading=true;regex.setSelected(match.getSelectedItem()==Match.REGEX);loading=false;dirtyEditor();}});
        regex.addActionListener(e->{if(!loading){match.setSelectedItem(regex.isSelected()?Match.REGEX:Match.LITERAL);dirtyEditor();}});
        for(JComboBox<?> input:List.of(scope,target))input.addActionListener(e->dirtyEditor());
        numbered.addActionListener(e->dirtyEditor());group.addChangeListener(e->dirtyEditor());
        saveTimer.setRepeats(false);
        if(api!=null)try{store=new FilterRulesStore(api.persistence().extensionData());rules.addAll(store.load());}catch(Exception e){loadFailed=true;status.setText("Saved rules could not be loaded · no data overwritten");}
        model.fireTableDataChanged();categories();fill(null);buttons();applyFont();
        if(!loadFailed)status.setText(rules.size()+" rules · add only the rules you need");
    }
    private void toggleDetails(){
        if(split.getParent()!=null){
            Insets insets=split.getInsets();int available=split.getWidth()-insets.left-insets.right-split.getDividerSize();
            if(available>0)listWidthFraction=Math.max(0,Math.min(1,(double)(split.getDividerLocation()-insets.left)/available));
            split.setLeftComponent(null);center.remove(split);center.add(listScroll);detailsToggle.setText("Show details");
        }else{
            center.remove(listScroll);split.setLeftComponent(listScroll);center.add(split);detailsToggle.setText("Hide details");
            split.setDividerLocation(listWidthFraction);
            SwingUtilities.invokeLater(()->{if(!closed&&split.getParent()!=null)split.setDividerLocation(listWidthFraction);});
        }
        center.revalidate();center.repaint();
    }
    private static DocumentListener listener(Runnable action){return new DocumentListener(){public void insertUpdate(DocumentEvent e){action.run();}public void removeUpdate(DocumentEvent e){action.run();}public void changedUpdate(DocumentEvent e){action.run();}};}
    private JPanel details(){
        JPanel panel=new JPanel(new BorderLayout(8,8));panel.setBorder(BorderFactory.createEmptyBorder(6,10,6,4));panel.add(detailTitle,BorderLayout.NORTH);
        JPanel basic=new JPanel(new GridBagLayout());int row=0;
        for(JTextField input:List.of(name,category,tag,mime,field,exclude)){input.setColumns(16);input.setMinimumSize(new Dimension(0,input.getPreferredSize().height));}
        name.setName("ruleName");category.setName("ruleCategory");pattern.setName("rulePattern");tag.setName("ruleTag");description.setName("ruleDescription");regex.setName("ruleRegex");
        row=pair(basic,row,"Name",name);row=pair(basic,row,"Category",category);
        JPanel patternOptions=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));patternOptions.add(regex);row=pair(basic,row,"",patternOptions);
        pattern.setLineWrap(false);JScrollPane patternScroll=new JScrollPane(pattern);patternScroll.setPreferredSize(new Dimension(100,100));patternScroll.setMinimumSize(new Dimension(0,80));row=pair(basic,row,"Pattern",patternScroll);
        row=pair(basic,row,"Tag",tag);
        description.setLineWrap(true);description.setWrapStyleWord(true);description.setToolTipText("Optional memo. You can include a source, purpose or any notes.");
        JScrollPane memoScroll=new JScrollPane(description);memoScroll.setPreferredSize(new Dimension(100,76));memoScroll.setMinimumSize(new Dimension(0,60));pair(basic,row,"Description",memoScroll);
        match.setRenderer(friendlyRenderer(v->switch((Match)v){case REGEX->"Regex";case LITERAL->"Literal text";case HOST->"Domain only";}));
        scope.setRenderer(friendlyRenderer(v->switch((Scope)v){case BODY->"Body";case METADATA->"Headers / filenames";case ALL->"All";}));
        target.setRenderer(friendlyRenderer(v->v==Target.DOCUMENT?"Source text":"Field values"));
        group.setToolTipText("0: replace the whole match. 1+: replace only that regex capture group.");
        target.setToolTipText("Source text includes quotes/labels. Field values are decoded by the JSON/HTML/etc. parser.");
        match.setToolTipText("Domain only matches a host without scheme, port or path.");
        JPanel groupBox=new JPanel(new BorderLayout());group.setPreferredSize(new Dimension(110,group.getPreferredSize().height));groupBox.add(group,BorderLayout.WEST);
        int advancedRow=0;advancedRow=pair(advancedPanel,advancedRow,"Match mode",match);advancedRow=pair(advancedPanel,advancedRow,"Scope",scope);advancedRow=pair(advancedPanel,advancedRow,"Inspect",target);advancedRow=pair(advancedPanel,advancedRow,"Replace group",groupBox);advancedRow=pair(advancedPanel,advancedRow,"Tag numbering",numbered);advancedRow=pair(advancedPanel,advancedRow,"Content-Type regex",mime);advancedRow=pair(advancedPanel,advancedRow,"Field-name regex",field);pair(advancedPanel,advancedRow,"Exclude-value regex",exclude);
        advancedPanel.setVisible(false);
        JPanel advancedHeader=new JPanel(new FlowLayout(FlowLayout.LEFT,6,6));advancedToggle.setIcon(new FoldIcon(false));advancedHeader.add(advancedToggle);advancedHeader.add(advancedSummary);
        advancedToggle.addActionListener(e->{advancedPanel.setVisible(advancedToggle.isSelected());advancedToggle.setIcon(new FoldIcon(advancedToggle.isSelected()));detailScroll.revalidate();});
        JPanel advanced=new JPanel(new BorderLayout());advanced.add(advancedHeader,BorderLayout.NORTH);advanced.add(advancedPanel,BorderLayout.CENTER);
        JPanel stack=new JPanel(new BorderLayout(0,4));stack.add(basic,BorderLayout.NORTH);stack.add(advanced,BorderLayout.CENTER);
        WidthForm form=new WidthForm();form.setLayout(new BorderLayout());form.add(stack,BorderLayout.NORTH);
        detailScroll=new JScrollPane(form,ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);detailScroll.setBorder(null);panel.add(detailScroll,BorderLayout.CENTER);
        JPanel footer=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));footer.add(test);footer.add(apply);panel.add(footer,BorderLayout.SOUTH);return panel;
    }
    private static ListCellRenderer<Object> friendlyRenderer(java.util.function.Function<Object,String> text){return new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){return super.getListCellRendererComponent(list,value==null?"":text.apply(value),index,selected,focus);}};}
    private static final class WidthForm extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 24;}public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(24,r.height-24);}public boolean getScrollableTracksViewportWidth(){return true;}public boolean getScrollableTracksViewportHeight(){return false;}
    }
    private static JButton iconButton(String label,int kind){JButton b=new JButton(new ToolbarIcon(kind));b.setToolTipText(label);b.getAccessibleContext().setAccessibleName(label);b.setPreferredSize(new Dimension(34,30));return b;}
    private record ToolbarIcon(int kind) implements Icon {
        public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}
        public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();try{g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(c.isEnabled()?c.getForeground():UIManager.getColor("Label.disabledForeground"));g.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));switch(kind){case 0->{g.drawLine(9,3,9,15);g.drawLine(3,9,15,9);}case 1->{g.drawRect(3,3,9,10);g.drawRect(6,6,9,10);}case 2->g.drawLine(3,9,15,9);case 3->{g.drawLine(9,15,9,3);g.drawLine(9,3,4,8);g.drawLine(9,3,14,8);}default->{g.drawLine(9,3,9,15);g.drawLine(9,15,4,10);g.drawLine(9,15,14,10);}}}finally{g.dispose();}}
    }
    private record FoldIcon(boolean expanded) implements Icon {public int getIconWidth(){return 12;}public int getIconHeight(){return 12;}public void paintIcon(Component c,Graphics graphics,int x,int y){graphics.setColor(c.getForeground());int[] xs=expanded?new int[]{x+2,x+10,x+6}:new int[]{x+3,x+3,x+8};int[] ys=expanded?new int[]{y+4,y+4,y+9}:new int[]{y+2,y+10,y+6};graphics.fillPolygon(xs,ys,3);}}
    private static int pair(JPanel panel,int row,String label,Component input){
        GridBagConstraints c=new GridBagConstraints();c.gridy=row;c.gridx=0;c.anchor=GridBagConstraints.NORTHWEST;c.insets=new Insets(3,0,3,8);panel.add(new JLabel(label),c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;panel.add(input,c);return row+1;
    }
    private void dirtyEditor(){if(!loading){editorDirty=true;apply.setEnabled(editingId!=null);}}
    private int index(String id){for(int i=0;i<rules.size();i++)if(rules.get(i).id().equals(id))return i;return -1;}
    private void commit(){
        if(!editorDirty||editingId==null)return;
        int i=index(editingId);if(i<0)return;
        Rule old=rules.get(i);
        Rule edited=new Rule(pattern.getText(),tag.getText(),(Scope)scope.getSelectedItem(),(Match)match.getSelectedItem(),old.id(),name.getText(),category.getText(),(int)group.getValue(),(Target)target.getSelectedItem(),mime.getText(),field.getText(),exclude.getText(),old.enabled(),numbered.isSelected(),description.getText());
        rules.set(i,edited);editorDirty=false;changed=true;saveTimer.restart();loading=true;model.fireTableRowsUpdated(i,i);loading=false;categories();buttons();summarize(edited);
    }
    private void selectionChanged(){
        try{commit();}catch(Exception e){status.setText("Edit not applied: "+safe(e));selectIds(editingId==null?List.of():List.of(editingId));return;}
        int lead=table.getSelectionModel().getLeadSelectionIndex();Rule r=lead>=0&&lead<table.getRowCount()&&table.isRowSelected(lead)?rules.get(table.convertRowIndexToModel(lead)):null;fill(r);buttons();if(!loadFailed)status.setText(rules.size()+" rules · "+table.getSelectedRowCount()+" selected");
    }
    private void fill(Rule r){
        loading=true;editingId=r==null?null:r.id();name.setText(r==null?"":r.name());category.setText(r==null?"":r.category());pattern.setText(r==null?"":r.source());tag.setText(r==null?"":r.tag());description.setText(r==null?"":r.provenance());description.setCaretPosition(0);mime.setText(r==null?"":r.contentType());field.setText(r==null?"":r.field());exclude.setText(r==null?"":r.exclude());
        regex.setSelected(r==null||r.match()==Match.REGEX);numbered.setSelected(r!=null&&r.numbered());match.setSelectedItem(r==null?Match.REGEX:r.match());scope.setSelectedItem(r==null?Scope.BODY:r.scope());target.setSelectedItem(r==null?Target.VALUES:r.target());group.setValue(r==null?0:r.group());
        for(Component c:List.of(name,category,pattern,description,tag,mime,field,exclude,regex,numbered,match,scope,target,group,advancedToggle))c.setEnabled(r!=null);
        summarize(r);
        detailTitle.setText(r==null?"Rule details":"Rule details · "+r.name());editorDirty=false;apply.setEnabled(false);loading=false;
    }
    private void summarize(Rule r){if(r==null){advancedSummary.setText(" ");return;}int settings=(r.match()==Match.HOST?1:0)+(r.scope()!=Scope.BODY?1:0)+(r.target()!=Target.VALUES?1:0)+(r.group()!=0?1:0)+(!r.numbered()?1:0)+(!r.contentType().isEmpty()?1:0)+(!r.field().isEmpty()?1:0)+(!r.exclude().isEmpty()?1:0);advancedSummary.setText(settings==0?" ":settings+" custom settings");}
    int[] selectedModelRows(){return Arrays.stream(table.getSelectedRows()).map(table::convertRowIndexToModel).sorted().toArray();}
    void copySelected(){commit();int[] selected=selectedModelRows();if(rules.size()+selected.length>500)throw new IllegalArgumentException("Maximum 500 rules");List<Rule> copies=Arrays.stream(selected).mapToObj(i->rules.get(i).copy()).toList();rules.addAll(copies);updated(copies.stream().map(Rule::id).toList(),false);}
    void removeSelected(){commit();int[] selected=selectedModelRows();for(int i=selected.length-1;i>=0;i--)rules.remove(selected[i]);fill(null);updated(List.of(),false);}
    void moveSelected(int delta){
        commit();if(!sorter.getSortKeys().isEmpty())throw new IllegalArgumentException("Clear column sorting before changing priority");
        Set<Integer> selected=new HashSet<>();for(int i:selectedModelRows())selected.add(i);
        List<String> ids=selected.stream().map(i->rules.get(i).id()).toList();
        if(delta<0){for(int i=1;i<rules.size();i++)if(selected.contains(i)&&!selected.contains(i-1)){Collections.swap(rules,i,i-1);selected.remove(i);selected.add(i-1);}}
        else for(int i=rules.size()-2;i>=0;i--)if(selected.contains(i)&&!selected.contains(i+1)){Collections.swap(rules,i,i+1);selected.remove(i);selected.add(i+1);}
        updated(ids,false);
    }
    void addCatalogEntries(List<RuleCatalog.Entry> entries){commit();if(rules.size()+entries.size()>500)throw new IllegalArgumentException("Maximum 500 rules");List<Rule> fresh=entries.stream().map(RuleCatalog.Entry::instantiate).toList();rules.addAll(fresh);updated(fresh.stream().map(Rule::id).toList(),true);}
    private void updated(List<String> ids,boolean clearFilters){
        changed=true;loading=true;if(clearFilters){search.setText("");categoryFilter.setSelectedItem("All categories");}model.fireTableDataChanged();loading=false;categories();filter();selectIds(ids);if(!ids.isEmpty())fill(rules.get(index(ids.get(ids.size()-1))));buttons();saveTimer.restart();status.setText(rules.size()+" rules · "+table.getSelectedRowCount()+" selected");
    }
    private void selectIds(List<String> ids){loading=true;table.clearSelection();for(String id:ids){int i=index(id),view=i<0?-1:table.convertRowIndexToView(i);if(view>=0)table.addRowSelectionInterval(view,view);}loading=false;}
    private void categories(){Object selected=categoryFilter.getSelectedItem();loading=true;categoryFilter.removeAllItems();categoryFilter.addItem("All categories");rules.stream().map(Rule::category).distinct().sorted().forEach(categoryFilter::addItem);categoryFilter.setSelectedItem(selected);if(categoryFilter.getSelectedIndex()<0)categoryFilter.setSelectedIndex(0);loading=false;}
    private void filter(){if(loading)return;String query=search.getText().toLowerCase(Locale.ROOT),cat=Objects.toString(categoryFilter.getSelectedItem(),"All categories");sorter.setRowFilter(new RowFilter<>(){public boolean include(Entry<? extends RuleModel,? extends Integer> e){Rule r=rules.get(e.getIdentifier());return (cat.equals("All categories")||cat.equals(r.category()))&&(r.name()+" "+r.category()+" "+r.source()+" "+r.tag()).toLowerCase(Locale.ROOT).contains(query);}});buttons();}
    private void buttons(){boolean selected=table.getSelectedRowCount()>0;copy.setEnabled(selected);remove.setEnabled(selected);up.setEnabled(selected&&sorter.getSortKeys().isEmpty());down.setEnabled(up.isEnabled());test.setEnabled(!rules.isEmpty());}
    public FilterSettings snapshot(){commit();if(loadFailed)throw new IllegalStateException("Saved rules unavailable; Filter OFF extraction remains usable");return new FilterSettings(false,false,rules);}
    private void flush(){if(!changed||store==null||loadFailed)return;try{store.save(rules);changed=false;}catch(Exception e){loadFailed=true;status.setText("Save failed · saved data preserved; reload extension before retrying");}}
    private void action(Runnable task){try{task.run();}catch(Exception e){status.setText(safe(e));}}
    private static String safe(Exception e){return e.getMessage()==null?"Operation failed":e.getMessage();}
    public void applyFont(){JTextField probe=new JTextField();if(api!=null)api.userInterface().applyThemeToComponent(probe);Font font=probe.getFont();for(JComponent c:List.of(table,name,category,pattern,tag,mime,field,exclude,description,search))c.setFont(font);if(group.getEditor() instanceof JSpinner.DefaultEditor editor){editor.getTextField().setFont(font);editor.getTextField().setValue(group.getValue());}}
    static void plainCells(JTable table){table.setDefaultRenderer(Object.class,new DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int col){putClientProperty("html.disable",Boolean.TRUE);return super.getTableCellRendererComponent(t,value,selected,focus,row,col);}});}
    private void importRules(){
        commit();JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        try{List<Rule> incoming=FilterRulesStore.read(chooser.getSelectedFile().toPath());if(rules.size()+incoming.size()>500)throw new IllegalArgumentException("Maximum 500 rules");List<Rule> copies=incoming.stream().map(r->{Rule c=r.copy();return new Rule(c.source(),c.tag(),c.scope(),c.match(),c.id(),r.name(),c.category(),c.group(),c.target(),c.contentType(),c.field(),c.exclude(),c.enabled(),c.numbered(),c.provenance());}).toList();rules.addAll(copies);updated(copies.stream().map(Rule::id).toList(),true);}catch(Exception e){throw new IllegalArgumentException("Import failed; existing rules unchanged",e);}
    }
    private void exportRules(){commit();JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("extractor-rules.json"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Files.writeString(chooser.getSelectedFile().toPath(),FilterRulesStore.encode(rules),StandardOpenOption.CREATE_NEW);}catch(Exception e){throw new IllegalArgumentException("Export failed; choose a new filename",e);}}
    private void showCatalog(){
        JDialog dialog=dialog("Add rules from catalog");List<RuleCatalog.Entry> entries=RuleCatalog.entries();
        DefaultTableModel cm=new DefaultTableModel(new String[]{"Rule","Category","Input","Source"},0){public boolean isCellEditable(int r,int c){return false;}};
        for(var e:entries)cm.addRow(new Object[]{e.template().name(),e.template().category(),e.template().target(),e.template().provenance()});
        JTable list=new JTable(cm);list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);list.setRowHeight(27);plainCells(list);TableRowSorter<DefaultTableModel> cs=new TableRowSorter<>(cm);list.setRowSorter(cs);
        JComboBox<String> cats=new JComboBox<>();cats.addItem("All categories");entries.stream().map(e->e.template().category()).distinct().sorted().forEach(cats::addItem);JTextField find=new JTextField(25);
        Runnable refilter=()->{String q=find.getText().toLowerCase(Locale.ROOT),c=Objects.toString(cats.getSelectedItem());cs.setRowFilter(new RowFilter<>(){public boolean include(Entry<? extends DefaultTableModel,? extends Integer> e){Rule r=entries.get(e.getIdentifier()).template();return (c.equals("All categories")||r.category().equals(c))&&(r.name()+" "+r.provenance()).toLowerCase(Locale.ROOT).contains(q);}});};
        cats.addActionListener(e->refilter.run());find.getDocument().addDocumentListener(listener(refilter));
        JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT));top.add(new JLabel("Category"));top.add(cats);top.add(new JLabel("Search"));top.add(find);
        JTextArea info=new JTextArea(8,50);info.setEditable(false);info.setLineWrap(true);info.setWrapStyleWord(true);info.setFont(pattern.getFont());
        list.getSelectionModel().addListSelectionListener(e->{int row=list.getSelectedRow();if(row>=0){var entry=entries.get(list.convertRowIndexToModel(row));Rule r=entry.template();info.setText(r.source()+"\n\nCapture group: "+r.group()+" · Tag: "+r.tag()+"\n"+entry.notes()+"\n\nExample:\n"+entry.example()+"\n\n"+r.provenance());info.setCaretPosition(0);}});
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(list),new JScrollPane(info));split.setResizeWeight(.65);split.setDividerLocation(340);
        JButton choose=new JButton("Add selected rules"),cancel=new JButton("Close");JPanel bottom=new JPanel(new FlowLayout(FlowLayout.RIGHT));bottom.add(new JLabel("Templates are copied; your changes do not alter the catalog."));bottom.add(choose);bottom.add(cancel);
        choose.addActionListener(e->action(()->{List<RuleCatalog.Entry> chosen=Arrays.stream(list.getSelectedRows()).map(list::convertRowIndexToModel).sorted().mapToObj(entries::get).toList();if(chosen.isEmpty())return;addCatalogEntries(chosen);dialog.dispose();}));cancel.addActionListener(e->dialog.dispose());
        JPanel body=new JPanel(new BorderLayout(8,8));body.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));body.add(top,BorderLayout.NORTH);body.add(split);body.add(bottom,BorderLayout.SOUTH);dialog.add(body);dialog.setSize(1050,680);dialog.setLocationRelativeTo(this);dialog.setVisible(true);
    }
    private JDialog dialog(String title){JDialog d=new JDialog(SwingUtilities.getWindowAncestor(this),title,Dialog.ModalityType.MODELESS);d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);windows.add(d);d.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){windows.remove(d);}});if(api!=null)api.userInterface().applyThemeToComponent(d);return d;}
    private void showTest(FilterSettings config){RuleTestDialog test=new RuleTestDialog(SwingUtilities.getWindowAncestor(this),config,table.getFont(),api);Window w=test.window();windows.add(w);w.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){windows.remove(w);}});test.show();}
    public void close(){if(closed)return;try{commit();flush();}finally{closed=true;saveTimer.stop();for(Window w:List.copyOf(windows))w.dispose();windows.clear();}}
    final class RuleModel extends AbstractTableModel {
        final String[] columns={"On","Rule","Category","Tag"};
        public int getRowCount(){return rules.size();}public int getColumnCount(){return columns.length;}public String getColumnName(int col){return columns[col];}public Class<?> getColumnClass(int col){return col==0?Boolean.class:String.class;}public boolean isCellEditable(int row,int col){return col==0;}
        public Object getValueAt(int row,int col){Rule r=rules.get(row);return switch(col){case 0->r.enabled();case 1->r.name();case 2->r.category();default->r.tag();};}
        public void setValueAt(Object value,int row,int col){if(col!=0)return;action(()->{commit();Rule r=rules.get(row);rules.set(row,new Rule(r.source(),r.tag(),r.scope(),r.match(),r.id(),r.name(),r.category(),r.group(),r.target(),r.contentType(),r.field(),r.exclude(),Boolean.TRUE.equals(value),r.numbered(),r.provenance()));changed=true;saveTimer.restart();loading=true;fireTableRowsUpdated(row,row);loading=false;if(r.id().equals(editingId))fill(rules.get(row));});}
    }
}
