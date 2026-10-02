package com.burpworkbench.modules.decoder;

import burp.api.montoya.core.Registration;
import burp.api.montoya.ui.hotkey.HotKeyEvent;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.function.*;

/** Two independent bindings. Retired callbacks are inert even if Burp delays deregistration. */
final class Hotkeys implements AutoCloseable {
    interface Registrar { Registration register(int slot,Shortcut key,Consumer<HotKeyEvent> callback); }
    record Result(boolean success,String message){}
    private final Registrar registrar;private final BiConsumer<Integer,Capture> action;private final Consumer<Runnable> dispatch;private final Consumer<String> log;
    private final Registration[] active=new Registration[2];private final Shortcut[] current=new Shortcut[2];private final Object[] tokens=new Object[2];private final List<Registration> pending=new ArrayList<>();private boolean closed;
    private final Press[] delivered=new Press[2];
    private record Press(long when,int code,int modifiers){}
    Hotkeys(Registrar registrar,BiConsumer<Integer,Capture> action,Consumer<Runnable> dispatch,Consumer<String> log){this.registrar=registrar;this.action=action;this.dispatch=dispatch;this.log=log;}
    synchronized Shortcut current(int slot){return current[slot];}
    private synchronized boolean live(int slot,Object token){return !closed&&tokens[slot]==token;}
    synchronized Result assign(int slot,Shortcut key){
        if(closed)return new Result(false,"Decoder가 종료되었습니다.");
        if(key==null)return new Result(false,"단축키를 먼저 입력하세요.");
        if(key.equals(current[1-slot]))return new Result(false,"다른 변환 창과 같은 단축키는 사용할 수 없습니다.");
        if(key.equals(current[slot]))return new Result(true,"이미 적용된 단축키입니다.");
        retry();Object token=new Object();Registration candidate=null;
        try{
            candidate=registrar.register(slot,key,event->{
                if(!live(slot,token)||GlobalHotkeys.capturingShortcut(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()))return;
                InputEvent input=inputEvent(event);
                if(input!=null&&GlobalHotkeys.capturingShortcut(input.getComponent()))return;
                deliver(slot,token,input,Capture.from(event));
            });
            if(candidate==null||!candidate.isRegistered()){retire(candidate);return new Result(false,"Burp가 키를 등록하지 않았습니다. 기존 키는 유지됩니다.");}
        }catch(RuntimeException e){retire(candidate);log.accept("HOTKEY_REGISTER_FAILED "+e.getClass().getSimpleName());return new Result(false,"키 등록 실패 · 다른 키를 사용하세요. 기존 키는 유지됩니다.");}
        Registration old=active[slot];active[slot]=candidate;current[slot]=key;tokens[slot]=token;delivered[slot]=null;retire(old);return new Result(true,"적용됨: "+key.value());
    }
    synchronized Result clear(int slot){if(closed)return new Result(false,"Decoder가 종료되었습니다.");retry();tokens[slot]=null;current[slot]=null;delivered[slot]=null;Registration old=active[slot];active[slot]=null;retire(old);return new Result(true,"단축키 해제됨");}
    synchronized int matching(KeyEvent event){
        if(closed||event.getID()!=KeyEvent.KEY_PRESSED)return -1;
        try{Shortcut key=Shortcut.from(event);for(int slot=0;slot<2;slot++)if(key.equals(current[slot]))return slot;}catch(IllegalArgumentException ignored){}
        return -1;
    }
    void fromSwing(int slot,KeyEvent event,Capture capture,Consumer<Runnable> afterEvent){
        Object token;synchronized(this){if(closed||current[slot]==null)return;token=tokens[slot];}
        afterEvent.accept(()->deliver(slot,token,event,capture));
    }
    private void deliver(int slot,Object token,InputEvent input,Capture capture){
        synchronized(this){
            if(!live(slot,token))return;
            if(input instanceof KeyEvent key){
                Press press=new Press(key.getWhen(),key.getKeyCode(),key.getModifiersEx());
                if(press.equals(delivered[slot]))return;
                delivered[slot]=press;
            }
        }
        dispatch.accept(()->{if(live(slot,token))action.accept(slot,capture);});
    }
    private static InputEvent inputEvent(HotKeyEvent event){
        try{if(event!=null&&event.inputEvent()!=null)return event.inputEvent();}catch(RuntimeException ignored){}
        return EventQueue.getCurrentEvent() instanceof InputEvent input?input:null;
    }
    synchronized int pendingCount(){return pending.size();}
    private void retry(){List<Registration> copy=new ArrayList<>(pending);pending.clear();copy.forEach(this::retire);}
    private void retire(Registration r){if(r==null)return;try{r.deregister();}catch(RuntimeException e){pending.add(r);log.accept("HOTKEY_DEREGISTER_PENDING");}}
    @Override public synchronized void close(){closed=true;retry();for(int i=0;i<2;i++){tokens[i]=null;current[i]=null;retire(active[i]);active[i]=null;}}
}
