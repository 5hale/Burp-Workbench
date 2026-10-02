package com.burpworkbench.platform;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.undo.UndoManager;
import java.awt.event.ActionEvent;
import java.util.function.BooleanSupplier;

/** Per-field undo, excluding programmatic rule loading. */
public final class TextUndo {
    private TextUndo(){}
    public static void install(JTextComponent field,BooleanSupplier editing){
        UndoManager undo=new UndoManager();
        field.getDocument().addUndoableEditListener(event->{if(editing.getAsBoolean())undo.addEdit(event.getEdit());else undo.discardAllEdits();});
        for(String modifier:new String[]{"ctrl","meta"}){
            field.getInputMap().put(KeyStroke.getKeyStroke(modifier+" Z"),"workbench-undo");
            field.getInputMap().put(KeyStroke.getKeyStroke(modifier+" shift Z"),"workbench-redo");
            field.getInputMap().put(KeyStroke.getKeyStroke(modifier+" Y"),"workbench-redo");
        }
        field.getActionMap().put("workbench-undo",new AbstractAction(){public void actionPerformed(ActionEvent e){if(editing.getAsBoolean()&&undo.canUndo())undo.undo();}});
        field.getActionMap().put("workbench-redo",new AbstractAction(){public void actionPerformed(ActionEvent e){if(editing.getAsBoolean()&&undo.canRedo())undo.redo();}});
    }
}
