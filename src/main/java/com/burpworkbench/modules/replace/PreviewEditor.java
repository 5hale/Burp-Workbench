package com.burpworkbench.modules.replace;

import javax.swing.JComponent;

/** Sample editor boundary; all methods are called from Swing's event dispatch thread. */
interface PreviewEditor extends AutoCloseable {
    JComponent component();
    void setMessage(boolean request, String text);
    String text();
    boolean isModified();
    void setEnabled(boolean enabled);
    void setCaretPosition(int position);
    @Override void close();
}
