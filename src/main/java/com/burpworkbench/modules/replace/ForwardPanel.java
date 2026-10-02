package com.burpworkbench.modules.replace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static com.burpworkbench.platform.TableMenus.*;

/** Replace-style list/details, with no network activity from editing or preview. */
final class ForwardPanel extends JPanel {
    private final List<ForwardRule> rules;
    private final Consumer<List<ForwardRule>> changed;
    private final Model model=new Model();
    private final JTable table=new JTable(model);
    private final JTextField name=new JTextField(),origin=new JTextField(),path=new JTextField(),destination=new JTextField(),destinationPath=new JTextField();
    private final JTextField previewUrl=new JTextField("https://example.test/api/profile?item=1");
    private final JLabel preview=new JLabel(" "),status=new JLabel("Proxy traffic · applied after Replace"),storage=new JLabel();
    private final ProportionalSplitPane split;
    private double listWidthFraction=.6;
    private final JScrollPane list;
    private final JPanel center=new JPanel(new BorderLayout());
    private boolean loading,closed;
    private int displayedRow=-1;
    ForwardPanel(ForwardSession.State state,Consumer<List<ForwardRule>> changed,Consumer<Boolean> enabledChanged,Runnable hotkeys){
        super(new BorderLayout(0,5));this.rules=new ArrayList<>(state.rules());this.changed=changed;
        addHierarchyListener(e->{if((e.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0&&!isShowing())settleEdits();});
        setBorder(new EmptyBorder(8,10,6,10));
        name.setName("forward.name");origin.setName("forward.origin");path.setName("forward.path");destination.setName("forward.destination");
        destinationPath.setName("forward.destination.path");
        previewUrl.setName("forward.preview.url");preview.setName("forward.preview.result");table.setName("forward.rules");
        origin.setToolTipText("Exact http(s) origin; blank matches all origins");
        path.setToolTipText("Same as Replace: * within a segment, ** across segments; blank matches all paths");
        destination.setToolTipText("http(s)://host[:port]; put the destination path in Destination Path");
        destinationPath.setToolTipText("Blank keeps the actual request path; otherwise replace the path and keep the query");
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);table.setRowHeight(30);table.setShowGrid(false);
        table.setFillsViewportHeight(true);table.getTableHeader().setReorderingAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        install(table,
                entry("Add",()->!closed,this::addRule),
                entry("Copy",()->!closed&&table.getSelectedRow()>=0,this::copy),
                entry("Remove",()->!closed&&table.getSelectedRow()>=0,this::remove),
                entry("Up",()->!closed&&table.getSelectedRow()>0,()->move(-1)),
                entry("Down",()->!closed&&table.getSelectedRow()>=0&&table.getSelectedRow()<table.getRowCount()-1,()->move(1)));
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        list=new JScrollPane(table);list.setColumnHeaderView(table.getTableHeader());list.setMinimumSize(new Dimension(300,200));
        split=new ProportionalSplitPane(list,details());split.setResizeWeight(.6);
        split.setName("forward.split");
        split.setDividerLocation(.6);center.add(split);add(center);
        JPanel bar=new JPanel(new BorderLayout());JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));
        actions.add(button("Add","forward.add",this::addRule));actions.add(button("Copy","forward.copy",this::copy));
        actions.add(button("Remove","forward.remove",this::remove));actions.add(button("Up","forward.up",()->move(-1)));
        actions.add(button("Down","forward.down",()->move(1)));bar.add(actions,BorderLayout.WEST);
        JPanel right=new JPanel(new FlowLayout(FlowLayout.RIGHT,5,0));JButton details=new JButton("Hide details");
        details.setName("forward.details.toggle");details.addActionListener(e->{
            if(split.getParent()!=null){listWidthFraction=split.dividerFraction();split.setLeftComponent(null);center.remove(split);center.add(list);details.setText("Show details");}
            else{center.remove(list);split.setLeftComponent(list);center.add(split);split.setDividerLocation(listWidthFraction);details.setText("Hide details");}
            center.revalidate();center.repaint();
        });right.add(details);right.add(button("Hotkeys","forward.hotkeys",hotkeys));bar.add(right,BorderLayout.EAST);add(bar,BorderLayout.NORTH);
        JPanel footer=new JPanel(new BorderLayout(8,0));footer.add(status);footer.add(storage,BorderLayout.EAST);add(footer,BorderLayout.SOUTH);
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())showSelected();});
        DocumentListener edits=new DocumentListener(){public void insertUpdate(DocumentEvent e){commit();}public void removeUpdate(DocumentEvent e){commit();}public void changedUpdate(DocumentEvent e){}};
        for(JTextField field:List.of(name,origin,path,destination,destinationPath))field.getDocument().addDocumentListener(edits);
        for(JTextField field:List.of(name,origin,path,destination,destinationPath))com.burpworkbench.platform.TextUndo.install(field,()->!loading&&!closed);
        if(!rules.isEmpty())table.setRowSelectionInterval(0,0);else showSelected();
    }
    private JComponent details(){
        JPanel fields=new JPanel();fields.setLayout(new BoxLayout(fields,BoxLayout.Y_AXIS));fields.setBorder(new EmptyBorder(8,12,8,8));
        fields.add(labeled("Comment",name));fields.add(Box.createVerticalStrut(8));
        JPanel endpoints=new JPanel(new GridLayout(2,2,8,8));endpoints.setName("forward.endpoints");
        endpoints.add(labeled("Source URL",origin));endpoints.add(labeled("Source Path",path));
        endpoints.add(labeled("Destination URL",destination));endpoints.add(labeled("Destination Path",destinationPath));
        fields.add(endpoints);fields.add(Box.createVerticalStrut(8));fields.add(labeled("Test URL (no request sent)",previewUrl));
        JPanel result=new JPanel(new BorderLayout(5,0));result.add(preview);result.add(button("Test","forward.test",this::test),BorderLayout.EAST);fields.add(result);
        JPanel holder=new JPanel(new BorderLayout());holder.add(fields,BorderLayout.NORTH);
        JScrollPane scroll=new JScrollPane(holder);scroll.setMinimumSize(new Dimension(320,200));return scroll;
    }
    private static JPanel labeled(String text,JComponent field){JPanel panel=new JPanel(new BorderLayout(0,4));panel.add(new JLabel(text),BorderLayout.NORTH);panel.add(field);return panel;}
    private static JButton button(String text,String name,Runnable action){JButton b=com.burpworkbench.platform.ActionIcons.button(text);b.setName(name);b.addActionListener(e->action.run());return b;}
    private void showSelected(){
        int selected=com.burpworkbench.platform.TableSelection.lead(table);if(selected!=displayedRow)settleEdits();displayedRow=selected;
        loading=true;try{
            int row=com.burpworkbench.platform.TableSelection.lead(table);ForwardRule rule=row<0?new ForwardRule(false,"","","",""):rules.get(row);
            name.setText(rule.name());origin.setText(rule.origin());path.setText(rule.path());destination.setText(rule.destination());
            destinationPath.setText(rule.destinationPath());
            for(JTextField field:List.of(name,origin,path,destination,destinationPath))field.setEnabled(row>=0);
            preview.setText(" ");
        }finally{loading=false;}
    }
    private void commit(){
        int row=com.burpworkbench.platform.TableSelection.lead(table);if(loading||closed||row<0)return;ForwardRule old=rules.get(row);
        boolean behavior=!old.origin().equals(origin.getText())||!old.path().equals(path.getText())||!old.destination().equals(destination.getText())||!old.destinationPath().equals(destinationPath.getText());
        ForwardRule next=new ForwardRule(old.enabled(),name.getText(),origin.getText(),path.getText(),destination.getText(),destinationPath.getText());
        if(old.equals(next))return;rules.set(row,next);model.fireTableRowsUpdated(row,row);publish();preview.setText(" ");
        if(behavior){try{ForwardEngine.validate(next);status.setText("Rule changed · "+(next.enabled()?"On":"Off"));}catch(IllegalArgumentException invalid){status.setText("Invalid rule · skipped: "+invalid.getMessage());}}
    }
    private void addRule(){if(closed)return;settleEdits();displayedRow=-1;rules.add(new ForwardRule(false,"New rule","","",""));select(rules.size()-1);}
    private void copy(){settleEdits();int[] rows=com.burpworkbench.platform.TableSelection.rows(table);if(closed||rows.length==0)return;displayedRow=-1;int[] copies=new int[rows.length];for(int n=rows.length-1;n>=0;n--){int row=rows[n];ForwardRule old=rules.get(row);rules.add(row+1,new ForwardRule(false,old.name()+" copy",old.origin(),old.path(),old.destination(),old.destinationPath()));copies[n]=row+1+n;}select(copies);}
    private void remove(){int[] rows=com.burpworkbench.platform.TableSelection.rows(table);if(closed||rows.length==0)return;displayedRow=-1;for(int n=rows.length-1;n>=0;n--)rules.remove(rows[n]);select(Math.min(rows[0],rules.size()-1));}
    private void move(int direction){settleEdits();int[] rows=com.burpworkbench.platform.TableSelection.rows(table);if(closed||rows.length==0)return;displayedRow=-1;select(com.burpworkbench.platform.TableSelection.move(rules.size(),rows,direction,(a,b)->java.util.Collections.swap(rules,a,b)));}
    private void select(int row){select(row<0?new int[0]:new int[]{row});}
    private void select(int[] rows){loading=true;try{model.fireTableDataChanged();com.burpworkbench.platform.TableSelection.select(table,rows);}finally{loading=false;}showSelected();publish();}
    private void publish(){changed.accept(List.copyOf(rules));}
    void settleEdits(){if(loading||closed||displayedRow<0||displayedRow>=rules.size())return;ForwardRule rule=rules.get(displayedRow);if(!rule.enabled())return;try{ForwardEngine.validate(rule);}catch(IllegalArgumentException invalid){int row=displayedRow;rules.set(row,rule.toggled(false));model.fireTableRowsUpdated(row,row);status.setText("Invalid rule · Off: "+invalid.getMessage());publish();}}
    private void test(){
        int row=com.burpworkbench.platform.TableSelection.lead(table);if(closed||row<0)return;
        try{
            preview.setText(ForwardEngine.preview(rules.get(row),previewUrl.getText()));
        }catch(IllegalArgumentException invalid){preview.setText(invalid.getMessage());}
        preview.setToolTipText(preview.getText());
    }
    void storageStatus(String text){storage.setText(text);}
    void close(){closed=true;}
    private final class Model extends AbstractTableModel {
        final String[] titles={"On","Rule","Source URL","Source Path","Destination URL","Destination Path"};
        public int getRowCount(){return rules==null?0:rules.size();}public int getColumnCount(){return titles.length;}
        public String getColumnName(int col){return titles[col];}public Class<?> getColumnClass(int col){return col==0?Boolean.class:String.class;}
        public boolean isCellEditable(int row,int col){return col==0;}
        public Object getValueAt(int row,int col){ForwardRule r=rules.get(row);return switch(col){case 0->r.enabled();case 1->r.name();case 2->r.origin();case 3->r.path();case 4->r.destination();default->r.destinationPath();};}
        public void setValueAt(Object value,int row,int col){
            if(closed||col!=0)return;ForwardRule rule=rules.get(row).toggled(Boolean.TRUE.equals(value));
            try{if(rule.enabled())ForwardEngine.validate(rule);}
            catch(IllegalArgumentException invalid){status.setText(invalid.getMessage());return;}
            rules.set(row,rule);fireTableRowsUpdated(row,row);publish();status.setText("Proxy traffic · applied after Replace");
        }
    }
}
