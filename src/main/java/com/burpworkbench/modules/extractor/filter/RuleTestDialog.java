package com.burpworkbench.modules.extractor.filter;

import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultHighlighter;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Local test/preview and saving; no original written when filter is enabled. */
final class RuleTestDialog {
    private final JDialog dialog;
    private final FilterSettings config;
    private final JTextArea input=new JTextArea(),output=new JTextArea();
    private final JComboBox<Documents.Format> format=new JComboBox<>(Documents.Format.values());
    private final JTextField mime=new JTextField(25);
    private final JLabel status=new JLabel(" ");
    private final DefaultTableModel hits=new DefaultTableModel(new String[]{"Rule","Tag","Start","End","Context"},0){public boolean isCellEditable(int r,int c){return false;}};
    private SwingWorker<?,?> worker;
    private int revision;
    private boolean closed;
    private boolean replacingInput,navigating;
    private int currentChange=-1;
    private List<TestOutputNavigation.Change> changes=List.of();
    private final JButton previous=new JButton("‹"),next=new JButton("›");
    private final JLabel position=new JLabel("0 / 0");
    private Documents.Input lastInput;
    private final JButton save=new JButton("Save test output…");
    RuleTestDialog(Window owner,FilterSettings config,Font font,MontoyaApi api){
        this.config=config;dialog=new JDialog(owner,"Extractor · rule test",Dialog.ModalityType.MODELESS);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        input.setFont(font);output.setFont(font);input.setLineWrap(true);output.setLineWrap(true);input.setWrapStyleWord(false);output.setWrapStyleWord(false);output.setEditable(false);save.setEnabled(false);
        input.setName("test.input");output.setName("test.output");mime.setName("test.mime");format.setName("test.format");
        input.setText("{\"tel\":\"010-1234-5678\",\"email\":\"analyst@example.test\",\"value\":\"123456789012\",\"orderId\":\"01012345678\"}");
        JButton load=new JButton("Load file…"),run=new JButton("Run"),cancel=new JButton("Cancel");
        load.setName("test.load");run.setName("test.run");cancel.setName("test.cancel");
        JPanel top=new JPanel(new BorderLayout(12,0));JPanel fileOptions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));fileOptions.add(load);fileOptions.add(new JLabel("Format"));fileOptions.add(format);
        JPanel options=new JPanel(new BorderLayout(8,0));options.add(fileOptions,BorderLayout.WEST);JPanel contentType=new JPanel(new BorderLayout(6,0));contentType.add(new JLabel("Content-Type"),BorderLayout.WEST);contentType.add(mime);options.add(contentType);top.add(options);
        JCheckBox wrap=new JCheckBox("Wrap",true);wrap.setName("test.wrap");wrap.addActionListener(e->{input.setLineWrap(wrap.isSelected());output.setLineWrap(wrap.isSelected());});
        JPanel execution=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));execution.add(wrap);execution.add(run);execution.add(cancel);top.add(execution,BorderLayout.EAST);
        previous.setName("test.previous");next.setName("test.next");previous.setToolTipText("Previous replacement");next.setToolTipText("Next replacement");previous.getAccessibleContext().setAccessibleName("Previous replacement");next.getAccessibleContext().setAccessibleName("Next replacement");position.setName("test.position");navigationState();
        JPanel filtered=pane("Filtered",output);JPanel navigation=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));navigation.add(previous);navigation.add(next);navigation.add(position);JPanel filteredHeader=new JPanel(new BorderLayout());filteredHeader.add(new JLabel("Filtered"));filteredHeader.add(navigation,BorderLayout.EAST);filtered.add(filteredHeader,BorderLayout.NORTH);
        JSplitPane editors=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,pane("Input",input),filtered);editors.setResizeWeight(.5);editors.setContinuousLayout(true);editors.setDividerLocation(540);
        input.setMinimumSize(new Dimension(0,0));output.setMinimumSize(new Dimension(0,0));editors.getLeftComponent().setMinimumSize(new Dimension(120,100));editors.getRightComponent().setMinimumSize(new Dimension(120,100));
        JTable table=new JTable(hits);table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);RulesPanel.plainCells(table);table.setRowHeight(25);
        table.setName("test.matches");table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!navigating){int row=com.burpworkbench.platform.TableSelection.lead(table);for(int i=0;i<changes.size();i++)if(changes.get(i).hitIndex()==row){jump(i,table);break;}}});
        previous.addActionListener(e->jump(currentChange<0?changes.size()-1:Math.floorMod(currentChange-1,Math.max(1,changes.size())),table));next.addActionListener(e->jump(currentChange<0?0:(currentChange+1)%Math.max(1,changes.size()),table));
        output.addCaretListener(e->{if(navigating)return;int at=e.getDot();for(int i=0;i<changes.size();i++){var c=changes.get(i);if(at>=c.start()&&at<=c.end()){currentChange=i;navigationState();return;}}});
        for(JTextArea area:List.of(input,output))area.getInputMap().put(KeyStroke.getKeyStroke("F3"),"test-next-change");
        for(JTextArea area:List.of(input,output)){area.getInputMap().put(KeyStroke.getKeyStroke("shift F3"),"test-previous-change");area.getActionMap().put("test-next-change",new AbstractAction(){public void actionPerformed(ActionEvent e){next.doClick();}});area.getActionMap().put("test-previous-change",new AbstractAction(){public void actionPerformed(ActionEvent e){previous.doClick();}});}
        com.burpworkbench.platform.TextUndo.install(input,()->!closed&&!replacingInput);com.burpworkbench.platform.TextUndo.install(mime,()->!closed);
        JSplitPane center=new JSplitPane(JSplitPane.VERTICAL_SPLIT,editors,new JScrollPane(table));center.setResizeWeight(.78);center.setDividerLocation(430);
        JPanel bottom=new JPanel(new BorderLayout(8,0));bottom.add(status);bottom.add(save,BorderLayout.EAST);
        JPanel body=new JPanel(new BorderLayout(8,8));body.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));body.add(top,BorderLayout.NORTH);body.add(center);body.add(bottom,BorderLayout.SOUTH);dialog.add(body);dialog.setSize(1150,730);dialog.setLocationRelativeTo(owner);
        input.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){stale();}public void removeUpdate(DocumentEvent e){stale();}public void changedUpdate(DocumentEvent e){stale();}});
        mime.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){stale();}public void removeUpdate(DocumentEvent e){stale();}public void changedUpdate(DocumentEvent e){stale();}});format.addActionListener(e->stale());
        load.addActionListener(e->{JFileChooser c=new JFileChooser();if(c.showOpenDialog(dialog)==JFileChooser.APPROVE_OPTION)try{var doc=Documents.parse(Documents.file(c.getSelectedFile().toPath(),Documents.Format.AUTO,"UTF-8"));format.setSelectedItem(doc.format);replacingInput=true;try{input.setText(doc.source);input.setCaretPosition(0);}finally{replacingInput=false;}}catch(Exception ex){status.setText("File load failed: unsupported format/charset/8 MiB limit");}});
        run.addActionListener(e->{cancel();int current=++revision;clearNavigation();hits.setRowCount(0);lastInput=null;Documents.Input sample=new Documents.Input("test.txt",input.getText().getBytes(StandardCharsets.UTF_8),mime.getText(),"",(Documents.Format)format.getSelectedItem());
            status.setText("Testing…");save.setEnabled(false);worker=new SwingWorker<FilterSession.Result,Void>(){
                final FilterSession session=new FilterSession(config,()->isCancelled());
                protected FilterSession.Result doInBackground()throws Exception{return session.filter(sample);}
                protected void done(){if(closed||isCancelled()||current!=revision)return;try{var r=get();output.setText(r.text());output.setCaretPosition(0);hits.setRowCount(0);for(var h:session.locations())hits.addRow(new Object[]{h.ruleName(),h.tag(),h.start(),h.end(),h.context()});highlight(r.original(),r.text(),session);lastInput=sample;save.setEnabled(true);status.setText(r.matches()+" matches · "+r.format());status.setToolTipText("Start/End are local to each matched value/source. Navigation follows Filtered tags; ambiguous tags already present in input are not navigated.");}catch(Exception ex){output.setText("");clearNavigation();hits.setRowCount(0);status.setText("Test failed: "+cause(ex));}}
            };worker.execute();});
        cancel.addActionListener(e->{revision++;cancel();lastInput=null;save.setEnabled(false);clearNavigation();hits.setRowCount(0);status.setText("Cancelled");});
        save.addActionListener(e->{if(lastInput==null)return;JFileChooser c=new JFileChooser();c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(c.showSaveDialog(dialog)!=JFileChooser.APPROVE_OPTION)return;Path destination=c.getSelectedFile().toPath();Documents.Input sample=lastInput;save.setEnabled(false);worker=new SwingWorker<LocalExtraction.Result,Void>(){
            protected LocalExtraction.Result doInBackground()throws Exception{return LocalExtraction.extract(List.of(new LocalExtraction.Source(null,sample)),destination,new FilterSession(config,()->isCancelled()),()->isCancelled(),s->{});}
            protected void done(){if(closed||isCancelled())return;try{var r=get();status.setText("Saved "+r.saved()+" · Failed "+r.failed());status.setToolTipText(r.directory().toString());}catch(Exception ex){status.setText("Save failed");}save.setEnabled(lastInput!=null);}
        };worker.execute();});
        dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed=true;cancel();}});if(api!=null)api.userInterface().applyThemeToComponent(dialog);
    }
    private void stale(){revision++;cancel();lastInput=null;save.setEnabled(false);clearNavigation();hits.setRowCount(0);status.setText("Input changed · run again");}
    private void cancel(){if(worker!=null&&!worker.isDone())worker.cancel(true);}
    private static String cause(Exception ex){Throwable t=ex;while(t.getCause()!=null)t=t.getCause();return t.getMessage()==null?"Invalid pattern/input or execution limit":t.getMessage();}
    private static JPanel pane(String title,JTextArea area){JPanel p=new JPanel(new BorderLayout());p.add(new JLabel(title),BorderLayout.NORTH);p.add(new JScrollPane(area));return p;}
    private void clearNavigation(){changes=List.of();currentChange=-1;output.getHighlighter().removeAllHighlights();navigationState();}
    private void navigationState(){previous.setEnabled(!changes.isEmpty());next.setEnabled(!changes.isEmpty());position.setText((currentChange+1)+" / "+changes.size());}
    private void highlight(String original,String text,FilterSession session){clearNavigation();changes=TestOutputNavigation.locate(original,text,session.locations().stream().map(FilterSession.Hit::tag).toList());for(var change:changes){try{output.getHighlighter().addHighlight(change.start(),change.end(),new DefaultHighlighter.DefaultHighlightPainter(new Color(255,210,120)));}catch(javax.swing.text.BadLocationException ex){throw new IllegalStateException(ex);}}navigationState();}
    private void jump(int index,JTable table){if(index<0||index>=changes.size())return;var change=changes.get(index);navigating=true;try{currentChange=index;output.requestFocusInWindow();output.setCaretPosition(change.start());output.moveCaretPosition(change.end());if(change.hitIndex()>=0){int row=table.convertRowIndexToView(change.hitIndex());if(row>=0){table.setRowSelectionInterval(row,row);table.scrollRectToVisible(table.getCellRect(row,0,true));}}navigationState();}finally{navigating=false;}}
    Window window(){return dialog;}
    void show(){dialog.setVisible(true);}
}
