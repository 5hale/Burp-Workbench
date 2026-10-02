package com.burpworkbench.platform;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.editor.Editor;
import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Explicit local editor context. Called synchronously on the EDT, never stored globally. */
public final class WorkbenchInput {
    public static final int LIMIT=1024*1024;
    private static final String KEY="workbench.input.source";
    private static final String FULL_KEY="workbench.input.full";
    public record Value(byte[] bytes,String kind,String source,String url,HttpRequest request) {}
    @FunctionalInterface public interface Source { Value capture(boolean selectedOnly); }
    private WorkbenchInput(){}
    public static void bind(Component component,Source source){
        if(component instanceof JComponent c){c.putClientProperty(KEY,source);c.putClientProperty("workbench.surface",source==null?null:Boolean.TRUE);if(source==null)c.putClientProperty(FULL_KEY,null);}
    }
    public static void bindFull(Component component, Supplier<Value> source) {
        if(component instanceof JComponent c)c.putClientProperty(FULL_KEY,source);
    }
    public static boolean hasFullSource(Component focus) {
        if(focus instanceof JTextField||WorkbenchKeys.capturingShortcut(focus))return false;
        for(Component node=focus;node!=null;node=node.getParent())
            if(node instanceof JComponent c&&c.getClientProperty(FULL_KEY) instanceof Supplier<?>)return true;
        return false;
    }
    /** Full messages only: never substitute a selection or an editor's search field. */
    public static Value captureFull(Component focus) {
        if(focus==null||focus instanceof JTextField||WorkbenchKeys.capturingShortcut(focus))return null;
        for(Component node=focus;node!=null;node=node.getParent())
            if(node instanceof JComponent c&&c.getClientProperty(FULL_KEY) instanceof Supplier<?> source)
                return (Value)source.get();
        return null;
    }
    public static Value capture(Component focus,boolean selectedOnly){
        if(focus==null||WorkbenchKeys.capturingShortcut(focus)||focus instanceof JPasswordField)return null;
        // Native editor search boxes and ordinary fields must never leak their parent message.
        if(focus instanceof JTextField field)return text(field,true);
        for(Component node=focus;node!=null;node=node.getParent())
            if(node instanceof JComponent c&&c.getClientProperty(KEY) instanceof Source source)return source.capture(selectedOnly);
        return focus instanceof JTextComponent text?text(text,selectedOnly):null;
    }
    private static Value text(JTextComponent field,boolean selectedOnly){
        String selected=field.getSelectedText();
        if(selected==null||selected.isEmpty()){
            if(selectedOnly)return null;
            if(field.getDocument().getLength()>LIMIT)throw new IllegalArgumentException("Compare limit: select at most 1 MiB.");
            selected=field.getText();
        }
        if(selected.isEmpty())return null;
        if(selected.length()>LIMIT)throw new IllegalArgumentException("Selection limit: 1 MiB.");
        byte[] bytes=selected.getBytes(StandardCharsets.UTF_8);check(bytes.length);
        return new Value(bytes,"Text","Workbench text","",null);
    }
    public static void bindNative(Editor editor,Supplier<ByteArray> full,String kind,Supplier<HttpRequest> request){
        bindFull(editor.uiComponent(),()->{
            ByteArray bytes=full.get();if(bytes==null)return null;
            check(bytes.length());HttpRequest original=request.get();String url="";
            if(original!=null)try{url=original.url();}catch(RuntimeException ignored){}
            return new Value(bytes.getBytes(),kind,kind,url,original);
        });
        bind(editor.uiComponent(),selectedOnly->{
            ByteArray originalBytes=full.get();
            if(originalBytes==null)return null;
            ByteArray bytes=null;boolean selected=false;
            var selection=editor.selection();
            if(selection!=null&&selection.isPresent()){
                ByteArray value=selection.get().contents();
                if(value==null){
                    var range=selection.get().offsets();
                    if(range!=null){
                        int start=range.startIndexInclusive(),end=range.endIndexExclusive();
                        if(start<0||end<start||end>originalBytes.length())throw new IllegalArgumentException("Selection offsets unavailable.");
                        check(end-start);if(end>start)value=originalBytes.subArray(start,end);
                    }
                }
                if(value!=null&&value.length()>0){bytes=value;selected=true;}
            }
            if(bytes==null){if(selectedOnly)return null;bytes=originalBytes;}
            if(bytes==null||bytes.length()==0)return null;
            check(bytes.length());HttpRequest original=request.get();String url="";
            if(original!=null)try{url=original.url();}catch(RuntimeException ignored){}
            String source=(selected?kind+" selection":kind)+(url.isEmpty()?"":" — "+url);
            return new Value(bytes.getBytes(),selected?"Text":kind,source,url,original);
        });
    }
    public static void check(int length){if(length>LIMIT)throw new IllegalArgumentException("Selection limit: 1 MiB. Select a smaller region.");}
}
