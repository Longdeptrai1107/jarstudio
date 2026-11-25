package dev.jarstudio.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;

public final class Ui {
    public static final Color ACCENT=new Color(0,120,215);
    public static final Font FONT=new Font("Segoe UI",Font.PLAIN,12);
    public static final Font CODE=new Font("Consolas",Font.PLAIN,14);
    private Ui(){}
    public static void install() {
        try {UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception ignored){}
        var defaults=UIManager.getDefaults();
        for(Object key:java.util.Collections.list(defaults.keys()))if(key.toString().endsWith(".font"))UIManager.put(key,new javax.swing.plaf.FontUIResource(FONT));
        UIManager.put("SplitPane.dividerSize",5);UIManager.put("Tree.rowHeight",23);UIManager.put("TabbedPane.contentBorderInsets",new java.awt.Insets(1,0,0,0));
        UIManager.put("ToolTip.font",FONT);UIManager.put("Table.rowHeight",23);
    }
    public static JButton button(String text,String icon,Runnable action) {
        JButton button=new JButton(text,icon(icon));button.setFont(FONT);button.setFocusable(false);button.addActionListener(event->action.run());return button;
    }
    public static JComponent header(String text) {
        javax.swing.JLabel label=new javax.swing.JLabel(text);label.setFont(FONT.deriveFont(Font.BOLD));label.setOpaque(true);label.setBackground(new Color(238,241,245));
        label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(198,203,211)),BorderFactory.createEmptyBorder(5,8,5,8)));return label;
    }
    public static Icon icon(String kind){return new SmallIcon(kind);}
    public static BufferedImage appImage() {
        BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);graphics.setColor(new Color(0,96,170));graphics.fillRoundRect(2,2,60,60,12,12);
        graphics.setColor(Color.WHITE);graphics.setFont(new Font("Segoe UI",Font.BOLD,42));graphics.drawString("J",21,48);graphics.dispose();return image;
    }
    private static final class SmallIcon implements Icon {
        final String kind;SmallIcon(String kind){this.kind=kind==null?"file":kind;}
        public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}
        public void paintIcon(Component component,Graphics graphics,int x,int y) {
            Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setStroke(new BasicStroke(1.3f));
            Color blue=new Color(36,111,175),gray=new Color(90,101,115),gold=new Color(208,156,31),green=new Color(32,142,70),purple=new Color(133,76,165);
            switch(kind) {
                case "open","package" -> {g.setColor(gold);g.fillRoundRect(1,6,16,10,2,2);g.fillRect(2,3,7,4);g.setColor(new Color(245,208,113));g.fillPolygon(new int[]{1,5,18,14},new int[]{8,6,6,16},4);}
                case "archive","library" -> {g.setColor(gold);g.fillRoundRect(2,2,14,14,3,3);g.setColor(new Color(255,235,169));g.fillRect(5,3,8,12);g.setColor(gray);g.drawLine(9,4,9,14);for(int i=5;i<15;i+=3)g.drawLine(8,i,10,i);}
                case "class","method","field" -> {g.setColor(kind.equals("class")?new Color(202,155,25):kind.equals("method")?purple:blue);g.fillRoundRect(2,2,14,14,3,3);g.setColor(Color.WHITE);g.setFont(new Font("Segoe UI",Font.BOLD,11));g.drawString(kind.equals("class")?"C":kind.equals("method")?"M":"F",5,13);}
                case "save","saveall" -> {g.setColor(blue);g.fillRoundRect(2,2,14,14,2,2);g.setColor(Color.WHITE);g.fillRect(5,3,7,4);g.fillRect(5,10,8,5);g.setColor(blue);g.fillRect(10,3,2,3);if(kind.equals("saveall")){g.setColor(gold);g.drawRect(0,0,13,13);}}
                case "start","continue" -> {g.setColor(green);g.fillPolygon(new int[]{5,15,5},new int[]{2,9,16},3);}
                case "pause" -> {g.setColor(blue);g.fillRect(4,3,4,12);g.fillRect(11,3,4,12);}
                case "stop","breakpoint" -> {g.setColor(new Color(190,47,49));if(kind.equals("stop"))g.fillRect(4,4,11,11);else g.fillOval(2,2,14,14);}
                case "step","into","out" -> {g.setColor(blue);g.drawArc(2,2,12,9,20,170);g.fillPolygon(new int[]{10,16,14},new int[]{8,7,2},3);g.setColor(gray);g.drawLine(3,14,15,14);g.drawLine(8,12,8,16);}
                case "compile" -> {g.setColor(gold);g.fillPolygon(new int[]{8,15,11,5,8,3},new int[]{1,1,7,17,10,10},6);g.setColor(new Color(156,111,12));g.drawLine(8,1,5,9);}
                case "search","analyze" -> {g.setColor(blue);g.drawOval(2,2,10,10);g.setStroke(new BasicStroke(2));g.drawLine(10,11,16,17);}
                case "undo","redo","refresh" -> {g.setColor(blue);g.drawArc(3,4,12,10,kind.equals("redo")?-80:20,250);g.fillPolygon(new int[]{2,8,3},new int[]{3,3,9},3);}
                case "attach" -> {g.setColor(blue);g.drawRoundRect(2,4,7,10,3,3);g.drawRoundRect(9,4,7,10,3,3);g.drawLine(6,9,12,9);}
                case "close" -> {g.setColor(gray);g.drawLine(5,5,13,13);g.drawLine(13,5,5,13);}
                case "export" -> {g.setColor(blue);g.drawRect(2,7,10,9);g.drawLine(8,10,16,2);g.drawLine(10,2,16,2);g.drawLine(16,2,16,8);}
                default -> {g.setColor(gray);g.drawRect(4,2,11,14);g.drawLine(6,6,12,6);g.drawLine(6,9,12,9);g.drawLine(6,12,11,12);}
            }
            g.dispose();
        }
    }
}
