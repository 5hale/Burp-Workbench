package com.burpworkbench.modules.replace;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Gives both native preview editors one shared height without borrowing space from rule fields. */
final class ResizablePreview extends JPanel {
    private final JComponent content;
    private int contentHeight = 320;

    ResizablePreview(JComponent content) {
        super(new BorderLayout(0, 4));
        this.content = content;
        setName("preview.editor");
        content.setMinimumSize(new Dimension(100, 0));
        add(content, BorderLayout.CENTER);
        JComponent handle = new JComponent() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Color color = UIManager.getColor("Label.disabledForeground");
                graphics.setColor(color == null ? Color.GRAY : color);
                int middle = getWidth() / 2;
                for (int x = middle - 6; x <= middle + 6; x += 6) graphics.fillOval(x, 3, 3, 3);
            }
        };
        handle.setName("preview.editor.resize");
        handle.setPreferredSize(new Dimension(20, 10));
        handle.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        handle.setToolTipText("Drag to resize both Test editors");
        MouseAdapter resize = new MouseAdapter() {
            private int startY, startHeight;
            @Override public void mousePressed(MouseEvent event) {
                startY = event.getYOnScreen();
                startHeight = contentHeight;
            }
            @Override public void mouseDragged(MouseEvent event) {
                setContentHeight(startHeight + event.getYOnScreen() - startY);
            }
        };
        handle.addMouseListener(resize);
        handle.addMouseMotionListener(resize);
        add(handle, BorderLayout.SOUTH);
        setContentHeight(contentHeight);
    }

    void setContentHeight(int height) {
        contentHeight = Math.max(160, Math.min(8192, height));
        content.setPreferredSize(new Dimension(430, contentHeight));
        // Invalidate all cached BoxLayout sizes, including folded sections.
        for (Container ancestor = this; ancestor != null; ancestor = ancestor.getParent()) ancestor.invalidate();
        revalidate();
        repaint();
    }

    int contentHeight() { return contentHeight; }

    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override public Dimension getMinimumSize() {
        return new Dimension(120, getPreferredSize().height);
    }
}
