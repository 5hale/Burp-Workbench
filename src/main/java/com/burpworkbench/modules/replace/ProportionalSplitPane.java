package com.burpworkbench.modules.replace;

import javax.swing.JSplitPane;
import java.awt.Component;
import java.awt.Insets;

/** Apply proportions at layout time, not before native editors have a real width. */
final class ProportionalSplitPane extends JSplitPane {
    private double fraction = 0.5;
    private boolean layingOut;

    ProportionalSplitPane(Component left, Component right) {
        super(HORIZONTAL_SPLIT, left, right);
        setResizeWeight(0.5);
        setContinuousLayout(true);
    }

    double dividerFraction() { return fraction; }

    private int availableWidth() {
        Insets insets = getInsets();
        return getWidth() - insets.left - insets.right - getDividerSize();
    }

    @Override public void setDividerLocation(double value) {
        if (value < 0 || value > 1) throw new IllegalArgumentException("Divider proportion must be 0..1");
        fraction = value;
        boolean previous = layingOut;
        layingOut = true;
        try { super.setDividerLocation(value); } finally { layingOut = previous; }
    }

    @Override public void setDividerLocation(int location) {
        super.setDividerLocation(location);
        if (!layingOut && location >= 0 && availableWidth() > 0) {
            fraction = Math.max(0, Math.min(1, (double) (location - getInsets().left) / availableWidth()));
        }
    }

    @Override public void doLayout() {
        layingOut = true;
        try {
            if (availableWidth() > 0 && getLeftComponent() != null && getRightComponent() != null) {
                int location = getInsets().left + (int) Math.round(availableWidth() * fraction);
                if (getDividerLocation() != location) super.setDividerLocation(location);
            }
            super.doLayout();
        } finally { layingOut = false; }
    }
}
