package com.burpworkbench.platform;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/** Theme-colored management icons with text tooltips and accessible names. */
public final class ActionIcons {
    private static final Map<String,Integer> KINDS = Map.of("Add",0,"Copy",1,"Remove",2,"Up",3,"Down",4,"Paste",5,"Load",6,"Clear",7,"⇄",8);
    public static JButton button(String label) {
        Integer kind = KINDS.get(label);
        if (kind == null) return new JButton(label);
        JButton button = new JButton(new Glyph(kind));
        button.setPreferredSize(new Dimension(34,30));
        button.setToolTipText(label.equals("⇄") ? "Swap A / B" : label);
        button.getAccessibleContext().setAccessibleName(button.getToolTipText());
        return button;
    }
    private record Glyph(int kind) implements Icon {
        public int getIconWidth(){return 18;}
        public int getIconHeight(){return 18;}
        public void paintIcon(Component c,Graphics graphics,int x,int y) {
            Graphics2D g=(Graphics2D)graphics.create();
            try {
                g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                Color color=c.isEnabled()?c.getForeground():UIManager.getColor("Label.disabledForeground");
                g.setColor(color==null?Color.GRAY:color);g.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                switch(kind) {
                    case 0->{g.drawLine(9,3,9,15);g.drawLine(3,9,15,9);}
                    case 1->{g.drawRect(3,3,9,10);g.drawRect(6,6,9,10);}
                    case 2->g.drawLine(3,9,15,9);
                    case 3->{g.drawLine(9,15,9,3);g.drawLine(9,3,4,8);g.drawLine(9,3,14,8);}
                    case 4->{g.drawLine(9,3,9,15);g.drawLine(9,15,4,10);g.drawLine(9,15,14,10);}
                    case 5->{g.drawRect(4,4,10,12);g.drawRect(6,2,6,3);g.drawLine(7,8,11,8);g.drawLine(7,11,11,11);}
                    case 6->{g.drawPolyline(new int[]{2,2,7,9,16,16},new int[]{14,4,4,6,6,14},6);g.drawPolyline(new int[]{2,5,16,13,2},new int[]{14,8,8,15,15},5);}
                    case 7->{g.drawLine(3,4,15,4);g.drawRect(7,2,4,2);g.drawLine(5,4,6,16);g.drawLine(13,4,12,16);g.drawLine(6,16,12,16);g.drawLine(8,7,8,13);g.drawLine(10,7,10,13);}
                    default->{g.drawLine(2,6,16,6);g.drawLine(16,6,12,2);g.drawLine(16,6,12,10);g.drawLine(16,13,2,13);g.drawLine(2,13,6,9);g.drawLine(2,13,6,17);}
                }
            } finally {g.dispose();}
        }
    }
    private ActionIcons(){}
}
