package com.burpworkbench.modules.replace;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;

/** Familiar accordion section with one click to expand or collapse. */
final class CollapsibleSection extends JPanel {
    private final JButton header;
    private final JComponent body;
    private final String title;

    CollapsibleSection(String title, JComponent body, boolean expanded) {
        super(new BorderLayout());
        this.title = title; this.body = body;
        setOpaque(false);
        this.header = new SectionHeader();
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setBorder(new SectionBorder());
        header.setContentAreaFilled(false);
        header.setOpaque(false);
        header.addActionListener(event -> setExpanded(!body.isVisible()));
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        setExpanded(expanded);
    }

    void setExpanded(boolean expanded) {
        header.setText(title);
        header.putClientProperty("section.expanded", expanded);
        header.getAccessibleContext().setAccessibleDescription(expanded ? "Expanded" : "Collapsed");
        body.setVisible(expanded);
        for (Container ancestor = this; ancestor != null; ancestor = ancestor.getParent()) ancestor.invalidate();
        revalidate(); repaint();
    }

    boolean expanded() { return body.isVisible(); }

    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private static final class SectionHeader extends JButton {
        @Override public void updateUI() {
            super.updateUI();
            if (getFont() != null) setFont(getFont().deriveFont(Font.PLAIN));
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setColor(getForeground());
                copy.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int x = getWidth() - 21;
                int y = getHeight() / 2;
                boolean expanded = Boolean.TRUE.equals(getClientProperty("section.expanded"));
                int middle = expanded ? y - 3 : y + 3;
                int edge = expanded ? y + 3 : y - 3;
                copy.drawPolyline(new int[]{x, x + 6, x + 12}, new int[]{edge, middle, edge}, 3);
            } finally {
                copy.dispose();
            }
        }
    }

    private static final class SectionBorder extends AbstractBorder {
        @Override public Insets getBorderInsets(Component component) { return new Insets(11, 8, 11, 34); }
        @Override public Insets getBorderInsets(Component component, Insets insets) {
            insets.set(11, 8, 11, 34);
            return insets;
        }
        @Override public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            Color line = UIManager.getColor("Separator.foreground");
            graphics.setColor(line == null ? new Color(155, 155, 155) : line);
            graphics.drawLine(x, y, x + width - 1, y);
            graphics.drawLine(x, y + height - 1, x + width - 1, y + height - 1);
        }
    }
}
