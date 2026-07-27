package com.burpworkbench.modules.search;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager2;
import java.awt.RenderingHints;
import java.util.LinkedHashMap;
import java.util.Map;

final class SearchUiSupport {
    private static final int CONTROL_GRID_COLUMNS = 4;
    private static final int CONTROL_GRID_GAP = 8;
    private static final int CONTROL_GRID_ROW_GAP = 6;
    private static final int CONTROL_GRID_MIN_WIDTH = 1080;
    private static final Icon SEARCH_ICON = new SearchIcon();
    private static final Icon CANCEL_ICON = new CancelIcon();
    private static final Icon FILTER_ICON = new FilterIcon();

    private SearchUiSupport() {
    }

    static Icon searchIcon() {
        return SEARCH_ICON;
    }

    static Icon cancelIcon() {
        return CANCEL_ICON;
    }

    static Icon filterIcon() {
        return FILTER_ICON;
    }

    static FlowLayout wrapFlowLayout(int align, int horizontalGap, int verticalGap) {
        return new WrapFlowLayout(align, horizontalGap, verticalGap);
    }

    static LayoutManager2 controlGridLayout() {
        return new ControlGridLayout();
    }

    static Object controlCell(int row, int column, int span) {
        return new ControlCell(row, column, span);
    }

    private record ControlCell(int row, int column, int span) {
        private ControlCell {
            if (row < 0
                    || column < 0
                    || column >= CONTROL_GRID_COLUMNS
                    || span <= 0
                    || column + span > CONTROL_GRID_COLUMNS) {
                throw new IllegalArgumentException("Invalid Search++ control grid cell");
            }
        }
    }

    private static final class WrapFlowLayout extends FlowLayout {
        private WrapFlowLayout(int align, int horizontalGap, int verticalGap) {
            super(align, horizontalGap, verticalGap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, false);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        private Dimension layoutSize(Container target, boolean minimum) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                int maxWidth = availableWidth(target, insets);
                int rowWidth = 0;
                int rowHeight = 0;
                int preferredWidth = 0;
                int preferredHeight = 0;
                int visibleCount = 0;

                for (Component component : target.getComponents()) {
                    if (!component.isVisible()) {
                        continue;
                    }
                    Dimension size = minimum ? component.getMinimumSize() : component.getPreferredSize();
                    int nextRowWidth = rowWidth == 0 ? size.width : rowWidth + getHgap() + size.width;
                    if (rowWidth > 0 && nextRowWidth > maxWidth) {
                        preferredWidth = Math.max(preferredWidth, rowWidth);
                        preferredHeight += rowHeight + getVgap();
                        rowWidth = size.width;
                        rowHeight = size.height;
                    } else {
                        rowWidth = nextRowWidth;
                        rowHeight = Math.max(rowHeight, size.height);
                    }
                    visibleCount++;
                }

                if (visibleCount > 0) {
                    preferredWidth = Math.max(preferredWidth, rowWidth);
                    preferredHeight += rowHeight;
                }

                return new Dimension(
                        preferredWidth + insets.left + insets.right + getHgap() * 2,
                        preferredHeight + insets.top + insets.bottom + getVgap() * 2
                );
            }
        }

        private int availableWidth(Container target, Insets insets) {
            int width = target.getWidth();
            if (width <= 0 && target.getParent() != null) {
                width = target.getParent().getWidth();
            }
            if (width <= 0) {
                width = CONTROL_GRID_MIN_WIDTH;
            }
            return Math.max(1, width - insets.left - insets.right - getHgap() * 2);
        }
    }

    private static final class ControlGridLayout implements LayoutManager2 {
        private final Map<Component, ControlCell> cells = new LinkedHashMap<>();

        @Override
        public void addLayoutComponent(Component component, Object constraints) {
            if (constraints instanceof ControlCell cell) {
                cells.put(component, cell);
                return;
            }
            throw new IllegalArgumentException("Search++ control grid requires ControlCell constraints");
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
            cells.put(component, new ControlCell(0, 0, 1));
        }

        @Override
        public void removeLayoutComponent(Component component) {
            cells.remove(component);
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return layoutSize(parent, false);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return layoutSize(parent, true);
        }

        @Override
        public Dimension maximumLayoutSize(Container target) {
            return new Dimension(Integer.MAX_VALUE, layoutSize(target, false).height);
        }

        @Override
        public float getLayoutAlignmentX(Container target) {
            return 0.5F;
        }

        @Override
        public float getLayoutAlignmentY(Container target) {
            return 0.0F;
        }

        @Override
        public void invalidateLayout(Container target) {
            // Bounds are recalculated from current container width on every layout pass.
        }

        @Override
        public void layoutContainer(Container parent) {
            synchronized (parent.getTreeLock()) {
                Insets insets = parent.getInsets();
                int contentWidth = Math.max(0, parent.getWidth() - insets.left - insets.right);
                int gap = contentWidth >= CONTROL_GRID_GAP * (CONTROL_GRID_COLUMNS - 1)
                        ? CONTROL_GRID_GAP
                        : 0;
                int[] widths = columnWidths(contentWidth, gap);
                int[] xs = columnXs(insets.left, widths, gap);
                int topHeight = rowHeight(0, false);
                int filterHeight = rowHeight(1, false);
                int topY = insets.top;
                int filterY = topY + topHeight + (filterHeight > 0 ? CONTROL_GRID_ROW_GAP : 0);

                for (Map.Entry<Component, ControlCell> entry : cells.entrySet()) {
                    Component component = entry.getKey();
                    ControlCell cell = entry.getValue();
                    if (!component.isVisible()) {
                        component.setBounds(0, 0, 0, 0);
                        continue;
                    }
                    int x = xs[cell.column()];
                    int y = cell.row() == 0 ? topY : filterY;
                    int width = cellWidth(widths, gap, cell.column(), cell.span());
                    int height = cell.row() == 0 ? topHeight : filterHeight;
                    component.setBounds(x, y, Math.max(0, width), Math.max(0, height));
                }
            }
        }

        private Dimension layoutSize(Container parent, boolean minimum) {
            synchronized (parent.getTreeLock()) {
                Insets insets = parent.getInsets();
                int topHeight = rowHeight(0, minimum);
                int filterHeight = rowHeight(1, minimum);
                int height = topHeight + (filterHeight > 0 ? CONTROL_GRID_ROW_GAP + filterHeight : 0);
                return new Dimension(
                        CONTROL_GRID_MIN_WIDTH + insets.left + insets.right,
                        height + insets.top + insets.bottom
                );
            }
        }

        private int rowHeight(int row, boolean minimum) {
            int height = 0;
            for (Map.Entry<Component, ControlCell> entry : cells.entrySet()) {
                Component component = entry.getKey();
                ControlCell cell = entry.getValue();
                if (cell.row() == row && component.isVisible()) {
                    Dimension size = minimum ? component.getMinimumSize() : component.getPreferredSize();
                    height = Math.max(height, size.height);
                }
            }
            return height;
        }

        private int[] columnWidths(int contentWidth, int gap) {
            int totalGap = gap * (CONTROL_GRID_COLUMNS - 1);
            int usableWidth = Math.max(0, contentWidth - totalGap);
            int baseWidth = usableWidth / CONTROL_GRID_COLUMNS;
            int remainder = usableWidth % CONTROL_GRID_COLUMNS;
            int[] widths = new int[CONTROL_GRID_COLUMNS];
            for (int index = 0; index < CONTROL_GRID_COLUMNS; index++) {
                widths[index] = baseWidth + (index < remainder ? 1 : 0);
            }
            return widths;
        }

        private int[] columnXs(int startX, int[] widths, int gap) {
            int[] xs = new int[CONTROL_GRID_COLUMNS];
            int x = startX;
            for (int index = 0; index < CONTROL_GRID_COLUMNS; index++) {
                xs[index] = x;
                x += widths[index] + gap;
            }
            return xs;
        }

        private int cellWidth(int[] widths, int gap, int column, int span) {
            int width = 0;
            for (int index = column; index < column + span; index++) {
                width += widths[index];
            }
            return width + gap * (span - 1);
        }
    }

    private static final class SearchIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(65, 65, 65));
                g.setStroke(new BasicStroke(2.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawOval(x + 2, y + 2, 8, 8);
                g.drawLine(x + 9, y + 9, x + 14, y + 14);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class CancelIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(180, 55, 55));
                g.setStroke(new BasicStroke(2.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(x + 3, y + 3, x + 13, y + 13);
                g.drawLine(x + 13, y + 3, x + 3, y + 13);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class FilterIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(65, 65, 65));
                int[] xs = {x + 2, x + 14, x + 9, x + 9, x + 7, x + 7};
                int[] ys = {y + 3, y + 3, y + 8, y + 13, y + 13, y + 8};
                g.fillPolygon(xs, ys, xs.length);
            } finally {
                g.dispose();
            }
        }
    }
}
