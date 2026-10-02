package com.burpworkbench.modules.compare;

import javax.swing.*;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.List;

/** Read-only viewer. Colors only the visible lines; no StyledDocument or per-byte Swing tags. */
final class SyntaxEditor extends JTextArea {
    private Comparison.Side presentation;
    final JComponent gutter=new Gutter();

    SyntaxEditor(){
        addPropertyChangeListener("font",e->{gutter.revalidate();gutter.repaint();});
    }
    @Override public void updateUI(){
        setUI(new BasicTextAreaUI(){
            @Override public View create(Element element){
                if(getLineWrap())return new WrappedPlainView(element,getWrapStyleWord()){
                    @Override protected float drawUnselectedText(Graphics2D g,float x,float y,int p0,int p1)throws BadLocationException{return drawColors(g,x,y,p0,p1,this);}
                };
                return new PlainView(element){
                    @Override protected float drawUnselectedText(Graphics2D g,float x,float y,int p0,int p1)throws BadLocationException{return drawColors(g,x,y,p0,p1,this);}
                };
            }
        });
    }
    void show(Comparison.Side value){
        presentation=null;getHighlighter().removeAllHighlights();setText(value.text());presentation=value;
        if(!value.text().isEmpty()&&!value.ranges().isEmpty())try{getHighlighter().addHighlight(0,value.text().length(),new DifferencePainter());}
        catch(BadLocationException impossible){throw new IllegalStateException(impossible);}
        setCaretPosition(0);gutter.revalidate();gutter.repaint();repaint();
    }
    void clear(){show(Comparison.Side.plain(""));}
    Comparison.Side presentation(){return presentation;}
    private float drawColors(Graphics2D g,float x,float y,int start,int end,TabExpander tabs)throws BadLocationException{
        byte[] codes=presentation==null?null:presentation.syntax();Segment segment=new Segment();
        for(int p=start;p<end;){byte code=codes!=null&&p<codes.length?codes[p]:SyntaxColors.PLAIN;int q=p+1;
            while(q<end&&(codes!=null&&q<codes.length?codes[q]:SyntaxColors.PLAIN)==code)q++;
            getDocument().getText(p,q-p,segment);g.setColor(syntaxColor(code));x=Utilities.drawTabbedText(segment,x,y,g,tabs,p);p=q;}
        return x;
    }
    private boolean dark(){Color c=getBackground();return c.getRed()+c.getGreen()+c.getBlue()<380;}
    Color syntaxColor(byte code){boolean dark=dark();return switch(code){
        case SyntaxColors.NAME->dark?new Color(137,183,255):new Color(0,60,200);
        case SyntaxColors.STRING->dark?new Color(153,214,148):new Color(0,116,30);
        case SyntaxColors.LITERAL->dark?new Color(212,166,255):new Color(123,42,165);
        case SyntaxColors.COMMENT->dark?new Color(175,183,186):new Color(100,112,119);
        case SyntaxColors.VALUE->dark?new Color(255,161,163):new Color(187,29,40);
        default->getForeground();};}
    Color differenceColor(Comparison.Kind kind){boolean dark=dark();return switch(kind){
        case Modified->dark?new Color(92,65,25):new Color(255,226,174);
        case Deleted->dark?new Color(91,39,45):new Color(255,208,211);
        case Added->dark?new Color(29,77,53):new Color(194,236,213);};}
    private final class DifferencePainter extends LayeredHighlighter.LayerPainter {
        public void paint(Graphics g,int p0,int p1,Shape bounds,JTextComponent component){}
        public Shape paintLayer(Graphics g,int p0,int p1,Shape bounds,JTextComponent component,View view){
            if(presentation==null)return null;List<Comparison.PaintRange> ranges=presentation.ranges();
            int low=0,high=ranges.size();while(low<high){int mid=(low+high)>>>1;if(ranges.get(mid).end()<=p0)low=mid+1;else high=mid;}
            Rectangle union=null;
            for(int i=low;i<ranges.size();i++){var range=ranges.get(i);if(range.start()>=p1)break;
                int a=Math.max(p0,range.start()),b=Math.min(p1,range.end());if(a>=b)continue;
                try{Shape shape=view.modelToView(a,Position.Bias.Forward,b,Position.Bias.Backward,bounds);Rectangle r=shape.getBounds();
                    g.setColor(differenceColor(range.kind()));g.fillRect(r.x,r.y,Math.max(1,r.width),r.height);if(union==null)union=r;else union.add(r);
                }catch(BadLocationException ignored){}
            }
            return union;
        }
    }
    private final class Gutter extends JComponent {
        @Override public Dimension getPreferredSize(){
            FontMetrics fm=getFontMetrics(SyntaxEditor.this.getFont());int chars=3;
            if(presentation!=null)for(String label:presentation.lineLabels())chars=Math.max(chars,label.length());
            return new Dimension(fm.charWidth('0')*chars+18,SyntaxEditor.this.getPreferredSize().height);
        }
        @Override protected void paintComponent(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();try{
                Color bg=SyntaxEditor.this.getBackground();g.setColor(bg);g.fillRect(0,0,getWidth(),getHeight());
                g.setColor(dark()?new Color(80,80,80):new Color(216,216,216));g.drawLine(getWidth()-1,0,getWidth()-1,getHeight());
                if(presentation==null)return;g.setFont(SyntaxEditor.this.getFont());FontMetrics fm=g.getFontMetrics();
                g.setColor(dark()?new Color(170,174,182):new Color(104,108,118));Rectangle clip=g.getClipBounds();
                int offset=viewToModel2D(new Point(0,clip.y)),line=Comparison.lineAt(presentation.lineStarts(),Math.max(0,offset));
                for(int i=line;i<presentation.lineStarts().length;i++){
                    Rectangle2D r=modelToView2D(presentation.lineStarts()[i]);if(r==null||r.getY()>clip.y+clip.height)break;
                    String label=presentation.lineLabels().get(i);g.drawString(label,getWidth()-9-fm.stringWidth(label),(int)r.getY()+fm.getAscent());
                }
            }catch(BadLocationException ignored){}finally{g.dispose();}
        }
    }
}
