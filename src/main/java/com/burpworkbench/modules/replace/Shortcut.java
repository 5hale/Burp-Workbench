package com.burpworkbench.modules.replace;

import java.awt.event.KeyEvent;

/** A deliberately small set of shortcuts accepted by Replace++. */
record Shortcut(String value) {
    Shortcut {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Choose a shortcut.");
    }

    static Shortcut defaultShortcut() {
        return new Shortcut("Ctrl+Shift+9");
    }

    static Shortcut fromKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_META
                || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_ALT) {
            throw new IllegalArgumentException("Press a key together with Ctrl or Cmd.");
        }

        boolean ctrl = event.isControlDown();
        boolean cmd = event.isMetaDown();
        if (!ctrl && !cmd) throw new IllegalArgumentException("A shortcut must include Ctrl or Cmd.");
        if (ctrl && cmd) throw new IllegalArgumentException("Choose either Ctrl or Cmd, not both.");
        if (event.isAltDown() || event.isAltGraphDown()) {
            throw new IllegalArgumentException("Alt combinations are not supported (AltGr may enter text).");
        }

        String key = keyName(code);
        StringBuilder result = new StringBuilder(ctrl ? "Ctrl" : "Cmd");
        if (event.isShiftDown()) result.append("+Shift");
        result.append('+').append(key);
        return new Shortcut(result.toString());
    }

    private static String keyName(int code) {
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
            return String.valueOf((char) ('A' + code - KeyEvent.VK_A));
        }
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
            return String.valueOf((char) ('0' + code - KeyEvent.VK_0));
        }
        if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) {
            return "F" + (code - KeyEvent.VK_F1 + 1);
        }
        throw new IllegalArgumentException("Use a letter, number, or F1–F12 key.");
    }
}
