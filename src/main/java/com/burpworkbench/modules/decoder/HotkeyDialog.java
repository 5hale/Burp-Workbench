package com.burpworkbench.modules.decoder;

import java.awt.*;
import java.awt.event.*;
import java.util.function.*;
import javax.swing.*;

final class HotkeyDialog extends JDialog {
    HotkeyDialog(Window owner,Hotkeys keys,BiFunction<Integer,Shortcut,String> save,Consumer<Component> theme){
        super(owner,"Decoder ++ · Hotkeys",ModalityType.MODELESS);setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel root=new JPanel(new BorderLayout(10,12));root.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
        root.add(new JLabel("입력 칸에서 Ctrl/Cmd 조합을 누른 뒤 Apply · 기본: Ctrl+1 / Ctrl+2"),BorderLayout.NORTH);
        JPanel rows=new JPanel(new GridLayout(2,1,8,10));JLabel status=new JLabel("Burp 기본 단축키·다른 확장과 겹치지 않는 키를 사용하세요.");
        for(int index=0;index<2;index++){
            final int slot=index;JPanel row=new JPanel(new BorderLayout(8,0));row.add(new JLabel(slot==0?"Quick":"Advanced"),BorderLayout.WEST);
            JTextField key=new JTextField(18);key.setEditable(false);Shortcut[] choice={keys.current(slot)};key.setText(choice[0]==null?"미설정":choice[0].value());
            key.addKeyListener(new KeyAdapter(){@Override public void keyPressed(KeyEvent e){e.consume();try{choice[0]=Shortcut.from(e);key.setText(choice[0].value());status.setText("Apply를 누르면 적용됩니다.");}catch(IllegalArgumentException error){status.setText(error.getMessage());}}});row.add(key,BorderLayout.CENTER);
            JButton apply=Ui.button("Apply",()->{Hotkeys.Result result=keys.assign(slot,choice[0]);status.setText(result.message()+(result.success()?save.apply(slot,choice[0]):""));});
            JButton clear=Ui.button("Clear",()->{var result=keys.clear(slot);if(result.success()){choice[0]=null;key.setText("미설정");}status.setText(result.message()+(result.success()?save.apply(slot,null):""));});row.add(Ui.row(apply,clear),BorderLayout.EAST);rows.add(row);
        }
        root.add(rows,BorderLayout.CENTER);root.add(status,BorderLayout.SOUTH);setContentPane(root);theme.accept(root);pack();setSize(Math.max(710,getWidth()),getHeight()+12);setLocationRelativeTo(owner);
        getRootPane().registerKeyboardAction(e->dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
    }
}
