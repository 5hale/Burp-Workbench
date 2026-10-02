package com.burpworkbench.modules.decoder;

import java.awt.event.KeyEvent;

record Shortcut(String value) {
    Shortcut { if(value==null||!value.matches("(Ctrl|Cmd)(\\+Shift)?\\+([A-Z0-9]|F([1-9]|1[0-2]))"))throw new IllegalArgumentException("Ctrl/Cmd + 문자·숫자·F1–F12를 사용하세요."); }
    static Shortcut from(KeyEvent e){
        if(e.isControlDown()==e.isMetaDown()||e.isAltDown()||e.isAltGraphDown())throw new IllegalArgumentException("Ctrl 또는 Cmd를 포함하세요. Alt 조합은 지원하지 않습니다.");
        int code=e.getKeyCode();String key;
        if(code>=KeyEvent.VK_A&&code<=KeyEvent.VK_Z)key=String.valueOf((char)code);
        else if(code>=KeyEvent.VK_0&&code<=KeyEvent.VK_9)key=String.valueOf((char)code);
        else if(code>=KeyEvent.VK_F1&&code<=KeyEvent.VK_F12)key="F"+(code-KeyEvent.VK_F1+1);
        else throw new IllegalArgumentException("문자·숫자·F1–F12를 함께 누르세요.");
        return new Shortcut((e.isControlDown()?"Ctrl":"Cmd")+(e.isShiftDown()?"+Shift":"")+"+"+key);
    }
}
