package com.burpworkbench.modules.replace;

import javax.swing.JComponent;

/** Sample editor boundary; all methods are called from Swing's event dispatch thread. */
interface PreviewEditor extends AutoCloseable {
    JComponent component();
    void setMessage(boolean request, String text);
    String text();
    default void setBytes(boolean request, byte[] bytes) {
        setMessage(request, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }
    default byte[] bytes() { return text().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    default void setSourceUrl(String url) {}
    boolean isModified();
    void setEnabled(boolean enabled);
    void setCaretPosition(int position);
    @Override void close();
}
