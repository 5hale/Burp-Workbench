package com.burpworkbench.modules.decoder;

import burp.api.montoya.ui.hotkey.HotKeyEvent;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.Optional;
import javax.swing.text.JTextComponent;

/** Captures only an explicit text selection. No selection means a blank popup. */
record Capture(byte[] bytes,String label,String error) {
    static Capture blank(){return new Capture(new byte[0],"",null);}

    static Capture from(HotKeyEvent event){
        if(event==null)return blank();
        try{
            Component localFocus=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if(GlobalHotkeys.workbenchSurface(localFocus))return fromWorkbench(localFocus);
            Optional<MessageEditorHttpRequestResponse> editor;
            try{editor=event.messageEditorRequestResponse();}catch(RuntimeException unavailable){editor=Optional.empty();}
            if(editor!=null&&editor.isPresent()){
                Capture selected=fromEditor(editor.get());
                if(selected.error()!=null||selected.bytes().length>0)return selected;
            }
            Component source=null;
            try{if(event.inputEvent()!=null)source=event.inputEvent().getComponent();}catch(RuntimeException ignored){}
            Component focus=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            return fromTextSelection(source,focus);
        }catch(RuntimeException e){return new Capture(new byte[0],"","선택한 텍스트를 읽을 수 없습니다.");}
    }

    static Capture fromTextSelection(Component source,Component focus){
        Component candidate=focus instanceof JTextComponent?focus:source;
        if(!(candidate instanceof JTextComponent text)||GlobalHotkeys.capturingShortcut(candidate))return blank();
        String selected=text.getSelectedText();
        if(selected==null||selected.isEmpty())return blank();
        try{
            if(selected.length()>Codecs.LIMIT)throw new IllegalArgumentException();
            byte[] bytes=Codecs.encode(selected,"UTF-8");
            if(bytes.length>Codecs.LIMIT)throw new IllegalArgumentException();
            return new Capture(bytes,"Text selection",null);
        }catch(IllegalArgumentException e){return new Capture(new byte[0],"","선택 영역은 최대 1 MiB입니다. 더 작은 텍스트를 선택하세요.");}
    }

    static Capture fromWorkbench(Component focus){
        try{
            var value=com.burpworkbench.platform.WorkbenchInput.capture(focus,true);
            return value==null?blank():new Capture(value.bytes(),value.source(),null);
        }catch(RuntimeException error){return new Capture(new byte[0],"","선택 영역을 읽을 수 없거나 1 MiB를 초과했습니다.");}
    }

    static Capture fromEditor(MessageEditorHttpRequestResponse editor){
        try{
            if(editor==null)return blank();
            var selected=editor.selectionOffsets();
            if(selected==null||selected.isEmpty())return blank();
            var range=selected.get();int start=range.startIndexInclusive(),end=range.endIndexExclusive();
            if(start==end)return blank();
            if(start<0||end<start)return new Capture(new byte[0],"","선택 범위를 읽을 수 없습니다.");
            if(end-start>Codecs.LIMIT)return new Capture(new byte[0],"","선택 영역은 최대 1 MiB입니다. 더 작은 영역을 선택하세요.");
            var exchange=editor.requestResponse();var context=editor.selectionContext();
            if(exchange==null||context==null)return blank();
            boolean response=context==MessageEditorHttpRequestResponse.SelectionContext.RESPONSE;
            if(response&&exchange.response()==null||!response&&exchange.request()==null)return blank();
            var bytes=response?exchange.response().toByteArray():exchange.request().toByteArray();
            if(end>bytes.length())return new Capture(new byte[0],"","선택 범위를 읽을 수 없습니다.");
            String label=response?"Response selection":"Request selection";
            return new Capture(bytes.subArray(start,end).getBytes(),label,null);
        }catch(RuntimeException e){return new Capture(new byte[0],"","선택 범위를 읽을 수 없습니다.");}
    }
}
