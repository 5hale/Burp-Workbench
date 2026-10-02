package com.burpworkbench.modules.replace;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Resizes its own editor; siblings keep their heights and the outer pane scrolls. */
final class ResizableTextEditor extends JPanel {
    private final JScrollPane scroll;
    private int editorHeight = 70;

    ResizableTextEditor(String title, String name, JTextComponent editor) {
        super(new BorderLayout(0, 4));
        setName(name);
        add(new JLabel(title), BorderLayout.NORTH);
        scroll = new JScrollPane(editor);
        scroll.setMinimumSize(new Dimension(100, 70));
        add(scroll, BorderLayout.CENTER);
        JComponent handle = new JComponent() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                graphics.setColor(UIManager.getColor("Label.disabledForeground") == null
                        ? Color.GRAY : UIManager.getColor("Label.disabledForeground"));
                int middle = getWidth() / 2;
                for (int x = middle - 6; x <= middle + 6; x += 6) graphics.fillOval(x, 3, 3, 3);
            }
        };
        handle.setName(name + ".resize");
        handle.setPreferredSize(new Dimension(20, 10));
        handle.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        handle.setToolTipText("Drag to resize " + title);
        MouseAdapter resize = new MouseAdapter() {
            private int startY, startHeight;
            @Override public void mousePressed(MouseEvent event) {
                startY = event.getYOnScreen();
                startHeight = editorHeight;
            }
            @Override public void mouseDragged(MouseEvent event) {
                setEditorHeight(startHeight + event.getYOnScreen() - startY);
            }
        };
        handle.addMouseListener(resize);
        handle.addMouseMotionListener(resize);
        add(handle, BorderLayout.SOUTH);
        setEditorHeight(editorHeight);
    }

    void setEditorHeight(int height) {
        editorHeight = Math.max(70, Math.min(8192, height));
        scroll.setPreferredSize(new Dimension(430, editorHeight));
        // BoxLayout caches child sizes, including when a section is currently collapsed.
        for (Container ancestor = this; ancestor != null; ancestor = ancestor.getParent()) ancestor.invalidate();
        revalidate();
        repaint();
    }

    int editorHeight() { return editorHeight; }

    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override public Dimension getMinimumSize() {
        return new Dimension(120, getPreferredSize().height);
    }
}
