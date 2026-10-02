package com.burpworkbench.modules.compare;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Range;
import burp.api.montoya.ui.hotkey.HotKeyEvent;
import burp.api.montoya.ui.contextmenu.InvocationType;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import java.util.Optional;
import java.util.function.Consumer;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Capture now; never retain a Burp event or original HTTP object in the UI. */
record MessageCapture(byte[] bytes, String kind, String source, RequestSnapshot request) {
    static MessageCapture fromWorkbench(Component focus,Consumer<String> log){
        try{
            var value=com.burpworkbench.platform.WorkbenchInput.capture(focus,false);
            if(value==null)return null;
            RequestSnapshot original=null;
            try{original=RequestSnapshot.capture(value.request());}catch(RuntimeException ignored){}
            return new MessageCapture(value.bytes(),value.kind(),value.source(),original);
        }catch(RuntimeException error){
            log.accept("WORKBENCH_CAPTURE_FAILED "+error.getClass().getSimpleName());
            return new MessageCapture(null,"Error","Workbench capture unavailable or exceeds 1 MiB.");
        }
    }
    MessageCapture(byte[] bytes,String kind,String source){this(bytes,kind,source,null);}
    static final int MAX_ITEM = 1024 * 1024;
    static MessageCapture from(HotKeyEvent event, Consumer<String> log) {
        if (event == null) return null;
        try {
            InvocationType type=null;Component source=null;
            try{type=event.invocationType();}catch(RuntimeException ignored){}
            try{InputEvent input=event.inputEvent();if(input!=null)source=input.getComponent();}catch(RuntimeException ignored){}
            Optional<MessageEditorHttpRequestResponse> opt = event.messageEditorRequestResponse();
            if (opt == null || opt.isEmpty()) return null;
            if(!editorInput(type,source,KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()))return null;
            return fromEditor(opt.get(),log);
        } catch (RuntimeException failure) {
            log.accept("CAPTURE_REJECTED type=" + failure.getClass().getSimpleName());
            return new MessageCapture(null, "Error", failure instanceof IllegalArgumentException ? failure.getMessage() : "Message capture unavailable.");
        }
    }

    static boolean editorInput(InvocationType type,Component source,Component focus){
        // Reject real tables, trees, input fields and our own diff/settings UI even if stale editor metadata is present.
        if(excluded(source)||excluded(focus))return false;
        boolean classified=type!=null&&type.containsHttpMessage();
        if(classified)return true;
        // Some Burp tool contexts report the enclosing tool instead of MESSAGE_*.
        // Accept only an actual multi-line editor input, never selectedRequestResponses().
        if(multiline(source))return true;
        return source!=null&&focus!=null&&SwingUtilities.isDescendingFrom(focus,source)&&multiline(focus);
    }
    private static boolean excluded(Component c){for(Component p=c;p!=null;p=p.getParent()){
        if(p instanceof JTable||p instanceof JTree||p instanceof JTextField||p instanceof ComparePanel||p instanceof HotkeySettingsDialog)return true;
    }return false;}
    private static boolean multiline(Component c){return c instanceof JTextComponent&&!(c instanceof JTextField);}

    static MessageCapture fromEditor(MessageEditorHttpRequestResponse editor) {
        return fromEditor(editor,s->{});
    }
    static MessageCapture fromEditor(MessageEditorHttpRequestResponse editor,Consumer<String> log) {
        var exchange = editor.requestResponse();
        if (exchange == null) return null;
        var context = editor.selectionContext();
        boolean response = context == MessageEditorHttpRequestResponse.SelectionContext.RESPONSE;
        if (context == null || (response && exchange.response() == null) || (!response && exchange.request() == null)) return null;
        ByteArray original = response ? exchange.response().toByteArray() : exchange.request().toByteArray();
        String kind = response ? "Response" : "Request";
        String source = exchange.request() == null ? kind : exchange.request().url();
        Optional<Range> selected = editor.selectionOffsets();
        int start = 0, end = original.length();
        if (selected != null && selected.isPresent()) {
            Range r = selected.get();
            if (r.startIndexInclusive() < 0 || r.endIndexExclusive() > end || r.startIndexInclusive() > r.endIndexExclusive())
                throw new IllegalArgumentException("Selection offsets are unavailable; use Raw view and try again.");
            if (r.startIndexInclusive() < r.endIndexExclusive()) {
                start = r.startIndexInclusive(); end = r.endIndexExclusive();
                source = kind + " selection [" + start + ", " + end + ") — " + source;
                kind = "Text";
            }
        }
        if (end - start > MAX_ITEM) throw new IllegalArgumentException("Compare limit: each item must be at most 1 MiB. Select a smaller region.");
        RequestSnapshot request=null;
        try{request=RequestSnapshot.capture(exchange.request());}catch(RuntimeException error){log.accept("REPEATER_CONTEXT_UNAVAILABLE type="+error.getClass().getSimpleName());}
        return new MessageCapture(original.subArray(start, end).getBytes(), kind, source,request);
    }
}
