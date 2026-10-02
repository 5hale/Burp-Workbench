package com.burpworkbench.modules.compare;

import java.util.concurrent.CancellationException;

/** Independent display choices. Hex always renders original bytes, never formatted copies. */
final class PaneComparison {
    private record Source(byte[] bytes, String charset, String basis) {}

    static Comparison.View prepare(CompareItem a, CompareItem b, int aView, int bView, Comparison.Options options) {
        if(a==null||b==null){
            Source one=a==null?null:source(a,aView,options.charset()),two=b==null?null:source(b,bView,options.charset());
            var left=one==null?Comparison.Side.plain(""):Comparison.preview(one.bytes(),aView==2,one.charset());
            var right=two==null?Comparison.Side.plain(""):Comparison.preview(two.bytes(),bView==2,two.charset());
            return new Comparison.View(left.text(),right.text(),java.util.List.of(),"Single pane",left,right,null);
        }
        Source left=source(a,aView,options.charset()), right=source(b,bView,options.charset());
        var ao=new Comparison.Options(options.mode(),aView==2,options.differencesOnly(),left.charset());
        var bo=new Comparison.Options(options.mode(),bView==2,options.differencesOnly(),right.charset());
        var value=Comparison.withFallback(left.bytes(),right.bytes(),ao,bo,
                ()->Comparison.build(left.bytes(),right.bytes(),ao,bo));
        String basis="A: "+left.basis()+" / B: "+right.basis();
        String note=basis+" · "+value.note();
        if(aView==0||bView==0)note+=" · comparing displayed representations; formatting may change differences; original bytes and HTTP headers preserved";
        return new Comparison.View(value.left(),value.right(),value.marks(),note,value.a(),value.b(),value.problem());
    }
    private static Source source(CompareItem item,int view,String charset) {
        if(view==0) {
            try {
                var formatted=PrettyComparison.format(item,charset);
                return new Source(formatted.bytes(),"UTF-8","Pretty (Formatted UTF-8)");
            } catch(CancellationException cancelled) { throw cancelled;
            } catch(RuntimeException unavailable) {
                DiffEngine.check();
                return new Source(item.bytes,charset,"Pretty unavailable → Raw ("+unavailable.getMessage()+")");
            }
        }
        return new Source(item.bytes,charset,view==2?"Hex (original bytes)":"Raw (original)");
    }
}
