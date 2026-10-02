package com.burpworkbench.modules.extractor.filter;

import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import static com.burpworkbench.platform.TableMenus.*;

/** The suite tab is ONLY a rule editor. Extraction starts from the original folder chooser. */
public final class RulesPanel extends JPanel implements AutoCloseable {
    final JCheckBox phone=new JCheckBox("휴대폰",true),email=new JCheckBox("이메일",true);
    final DefaultTableModel model=new DefaultTableModel(new String[]{"문자열", "태그", "범위", "매치 방식"},0);
    final JTable table=new JTable(model);
    final JButton add=new JButton("Add"),copy=new JButton("Copy"),remove=new JButton("Remove"),up=new JButton("Up"),down=new JButton("Down"),test=new JButton("Test file…");
    final JLabel status=new JLabel(" ");
    private final MontoyaApi api;
    private final List<Window> windows=new ArrayList<>();
    private SwingWorker<?,?> worker;
    private boolean closed;
    private FilterRuleStore store;
    private boolean loadFailed,dirty;
    private final javax.swing.Timer saveTimer=new javax.swing.Timer(400,e->flushRules());

    public RulesPanel(MontoyaApi api){
        super(new BorderLayout(8,8));this.api=api;setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        JPanel toolbar=new JPanel(new BorderLayout());
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));for(JButton b:new JButton[]{add,copy,remove,up,down})buttons.add(b);
        JPanel automatic=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));automatic.add(new JLabel("자동 탐지"));automatic.add(phone);automatic.add(email);automatic.add(test);
        toolbar.add(buttons,BorderLayout.WEST);toolbar.add(automatic,BorderLayout.EAST);add(toolbar,BorderLayout.NORTH);
        table.setRowHeight(28);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);plainCells(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(430);table.getColumnModel().getColumn(1).setPreferredWidth(240);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(FilterSettings.Scope.values())));
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(FilterSettings.Match.values())));
        add(new JScrollPane(table),BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(8,0));
        JLabel hint=new JLabel("LITERAL: 문자열 그대로 · HOST: 호스트 일치 · 위쪽 규칙 우선");
        hint.setToolTipText("ALL: 전체 / BODY: 본문 / METADATA: URL·헤더·파일명. 태그 예: §회사§. 직접 입력한 원문과 규칙은 Burp 프로젝트에 저장됩니다.");
        bottom.add(hint,BorderLayout.WEST);bottom.add(status,BorderLayout.EAST);add(bottom,BorderLayout.SOUTH);
        add.addActionListener(e->{commit();model.addRow(new Object[]{"","",FilterSettings.Scope.ALL,FilterSettings.Match.LITERAL});select(model.getRowCount()-1);});
        copy.addActionListener(e->{commit();int row=table.getSelectedRow();if(row<0)return;model.insertRow(row+1,new Object[]{model.getValueAt(row,0),model.getValueAt(row,1),model.getValueAt(row,2),model.getValueAt(row,3)});select(row+1);});
        remove.addActionListener(e->{commit();int row=table.getSelectedRow();if(row>=0){model.removeRow(row);if(model.getRowCount()>0)select(Math.min(row,model.getRowCount()-1));}});
        up.addActionListener(e->move(-1));down.addActionListener(e->move(1));
        saveTimer.setRepeats(false);
        if(api!=null){
            try{restore(new FilterRuleStore(api.persistence().extensionData()));}
            catch(RuntimeException failure){loadFailed=true;status.setText("규칙 로드 실패 · 기존 저장값 유지");}
        }
        model.addTableModelListener(e->rulesChanged());
        phone.addItemListener(e->rulesChanged());email.addItemListener(e->rulesChanged());
        test.addActionListener(e->testFile());
        install(table,
                entry("Add",()->!closed,add::doClick),
                entry("Copy",()->!closed&&table.getSelectedRow()>=0,copy::doClick),
                entry("Remove",()->!closed&&table.getSelectedRow()>=0,remove::doClick),
                entry("Up",()->!closed&&table.getSelectedRow()>0,up::doClick),
                entry("Down",()->!closed&&table.getSelectedRow()>=0&&table.getSelectedRow()<table.getRowCount()-1,down::doClick));
        applyFont();
    }
    void restore(FilterRuleStore value){
        store=value;
        try{
            var state=store.load();phone.setSelected(state.phone());email.setSelected(state.email());
            model.setRowCount(0);
            for(var rule:state.rules())model.addRow(new Object[]{rule.source(),rule.tag(),rule.scope(),rule.match()});
            dirty=false;saveTimer.stop();loadFailed=false;status.setText(model.getRowCount()+" rules · Project");
        }catch(RuntimeException failure){loadFailed=true;status.setText("규칙 로드 실패 · 기존 저장값 유지");}
    }
    private void rulesChanged(){
        if(closed||loadFailed)return;
        dirty=true;status.setText(model.getRowCount()+" rules");if(store!=null)saveTimer.restart();
    }
    void flushRules(){
        saveTimer.stop();if(store==null||loadFailed||!dirty)return;
        try{
            List<FilterRuleStore.Draft> rules=new ArrayList<>();
            for(int row=0;row<model.getRowCount();row++)rules.add(new FilterRuleStore.Draft(value(row,0),value(row,1),FilterSettings.Scope.valueOf(value(row,2)),FilterSettings.Match.valueOf(value(row,3))));
            store.save(new FilterRuleStore.State(1,phone.isSelected(),email.isSelected(),rules));
            dirty=false;status.setText(model.getRowCount()+" rules · Saved");
        }catch(RuntimeException failure){status.setText("규칙 저장 실패 · 현재 창에만 유지");}
    }
    private void select(int row){table.setRowSelectionInterval(row,row);table.scrollRectToVisible(table.getCellRect(row,0,true));}
    private void commit(){if(table.isEditing()&&!table.getCellEditor().stopCellEditing())throw new IllegalArgumentException("규칙 편집을 완료하세요.");}
    private void move(int delta){commit();int row=table.getSelectedRow(),next=row+delta;if(row>=0&&next>=0&&next<model.getRowCount()){model.moveRow(row,row,next);select(next);}}
    /** Called on the EDT when Save is pressed with Filter checked: no stale Apply snapshot. */
    public FilterSettings snapshot(){
        if(loadFailed)throw new IllegalArgumentException("저장된 필터 규칙을 불러오지 못했습니다. 확장을 다시 로드하세요.");
        commit();List<FilterSettings.Rule> rules=new ArrayList<>();
        for(int row=0;row<model.getRowCount();row++){
            try{rules.add(new FilterSettings.Rule(value(row,0),value(row,1),FilterSettings.Scope.valueOf(value(row,2)),FilterSettings.Match.valueOf(value(row,3))));}
            catch(Exception e){select(row);throw new IllegalArgumentException("규칙 "+(row+1)+": "+e.getMessage());}
        }
        return new FilterSettings(phone.isSelected(),email.isSelected(),rules);
    }
    private String value(int row,int col){Object value=model.getValueAt(row,col);return value==null?"":value.toString();}
    public void applyFont(){JTextField probe=new JTextField();if(api!=null)api.userInterface().applyThemeToComponent(probe);table.setFont(probe.getFont());}
    static void plainCells(JTable table){table.setDefaultRenderer(Object.class,new DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable t,Object v,boolean s,boolean f,int r,int c){putClientProperty("html.disable",Boolean.TRUE);return super.getTableCellRendererComponent(t,v,s,f,r,c);}});}
    private void testFile(){
        if(worker!=null&&!worker.isDone())return;
        FilterSettings config;try{config=snapshot();}catch(Exception e){JOptionPane.showMessageDialog(this,e.getMessage(),"Filter rules",JOptionPane.WARNING_MESSAGE);return;}
        JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path file=chooser.getSelectedFile().toPath();
        test.setEnabled(false);status.setText("Testing…");
        worker=new SwingWorker<FilterSession.Result,Void>(){
            final FilterSession session=new FilterSession(config);
            protected FilterSession.Result doInBackground()throws Exception{return session.filter(Documents.file(file,Documents.Format.AUTO,"UTF-8"));}
            protected void done(){test.setEnabled(true);if(closed)return;try{var result=get();status.setText(result.matches()+" matches");showTest(file,config,result,session);}catch(Exception e){status.setText("테스트 실패: 형식/인코딩/8MiB 한도를 확인하세요.");}}
        };worker.execute();
    }
    private void showTest(Path file,FilterSettings config,FilterSession.Result result,FilterSession session){
        JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(this),"Filter rule test",Dialog.ModalityType.MODELESS);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel panel=new JPanel(new BorderLayout(8,8));panel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,textPane("Original",result.original()),textPane("Filtered",result.text()));split.setResizeWeight(.5);panel.add(split);
        DefaultTableModel counts=new DefaultTableModel(new String[]{"태그","규칙","매치 수"},0){public boolean isCellEditable(int r,int c){return false;}};
        for(var attr:session.attributes())counts.addRow(new Object[]{attr.get("tag"),attr.get("rule"),attr.get("occurrences")});JTable hits=new JTable(counts);plainCells(hits);
        JScrollPane hitScroll=new JScrollPane(hits);hitScroll.setPreferredSize(new Dimension(300,110));
        JPanel bottom=new JPanel(new BorderLayout(8,8));bottom.add(hitScroll);JButton save=new JButton("Save test output…");JLabel resultLabel=new JLabel(result.matches()+" matches");JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));actions.add(resultLabel);actions.add(save);bottom.add(actions,BorderLayout.SOUTH);panel.add(bottom,BorderLayout.SOUTH);
        save.addActionListener(e->{JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(chooser.showSaveDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;Path root=chooser.getSelectedFile().toPath();save.setEnabled(false);SwingWorker<LocalExtraction.Result,Void> saving=new SwingWorker<>(){
            protected LocalExtraction.Result doInBackground()throws Exception{return LocalExtraction.extract(List.of(new LocalExtraction.Source(null,Documents.Input.text(file.getFileName().toString(),result.original(),Documents.extension(file.getFileName().toString())))),root,new FilterSession(config,()->closed),()->closed,s->{});}
            protected void done(){save.setEnabled(true);try{var out=get();resultLabel.setText("Saved: "+out.saved()+" · Failed: "+out.failed());resultLabel.setToolTipText(out.directory().toString());}catch(Exception ex){resultLabel.setText("Save failed");}}
        };saving.execute();});
        dialog.add(panel);dialog.setSize(1050,650);dialog.setLocationRelativeTo(this);windows.add(dialog);dialog.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent e){windows.remove(dialog);}});dialog.setVisible(true);
    }
    private JPanel textPane(String title,String text){JTextArea area=new JTextArea(text);area.setEditable(false);area.setLineWrap(true);area.setFont(table.getFont());area.setCaretPosition(0);JPanel panel=new JPanel(new BorderLayout());panel.add(new JLabel(title),BorderLayout.NORTH);panel.add(new JScrollPane(area));return panel;}
    public void close(){if(closed)return;try{commit();flushRules();}finally{closed=true;saveTimer.stop();if(worker!=null)worker.cancel(true);for(Window window:List.copyOf(windows))window.dispose();windows.clear();}}
}
