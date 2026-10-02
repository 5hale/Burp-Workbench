package com.burpworkbench.modules.compare;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;

/** Exact diff: bounded Myers trace for small edits, linear-space LCS for larger edits. */
final class DiffEngine {
    enum Mode { Words, Characters, Bytes }
    record Span(boolean equal, int a0, int a1, int b0, int b1) {}
    private static final int TRACE_DISTANCE = 1024, TOKEN_LIMIT = 1024 * 1024;
    static final class Limit extends RuntimeException { Limit(String message) { super(message); } }
    static final class Sequence {
        final byte[] bytes; final String text; final int[] bounds;
        Sequence(byte[] bytes) { this.bytes = bytes; text = null; bounds = null; }
        Sequence(String text, Mode mode) {
            this.text = text; bytes = null;
            int[] work = new int[Math.min(text.length() + 1, TOKEN_LIMIT + 2)];
            int count = 0, i = 0; work[count++] = 0;
            while (i < text.length()) {
                if ((count & 255) == 0) check();
                int cp = text.codePointAt(i); boolean whitespace = Character.isWhitespace(cp);
                i += Character.charCount(cp);
                if (mode == Mode.Words) {
                    while (i < text.length() && Character.isWhitespace(text.codePointAt(i)) == whitespace) {
                        if ((i & 1023) == 0) check();
                        i += Character.charCount(text.codePointAt(i));
                    }
                }
                if (count > TOKEN_LIMIT) throw new Limit("Compare token limit reached (1,048,576). Select a smaller region.");
                work[count++] = i;
            }
            bounds = Arrays.copyOf(work, count);
        }
        int size() { return bytes == null ? bounds.length - 1 : bytes.length; }
        int offset(int index) { return bytes == null ? bounds[index] : index; }
        boolean same(int a, Sequence other, int b) {
            if (bytes != null) return bytes[a] == other.bytes[b];
            int len = bounds[a+1] - bounds[a];
            return len == other.bounds[b+1] - other.bounds[b] && text.regionMatches(bounds[a], other.text, other.bounds[b], len);
        }
    }
    static final class Budget {
        private final LongSupplier clock;
        private final long start, nanos;
        Budget(long nanos, LongSupplier clock) { this.clock=clock; this.nanos=nanos; start=clock.getAsLong(); }
        void check() {
            DiffEngine.check();
            if (clock.getAsLong()-start >= nanos)
                throw new Limit("Comparison exceeded the 4-second comparison budget. Select a smaller region or use Words.");
        }
    }
    static List<Span> compare(Sequence a, Sequence b) {
        return compare(a,b,TRACE_DISTANCE,new Budget(4_000_000_000L,System::nanoTime));
    }
    static List<Span> compare(Sequence a, Sequence b, int traceDistance, Budget budget) {
        budget.check();
        int prefix = 0, suffix = 0;
        while (prefix < a.size() && prefix < b.size() && a.same(prefix, b, prefix)) { if ((prefix & 1023) == 0) budget.check(); prefix++; }
        while (suffix < a.size()-prefix && suffix < b.size()-prefix && a.same(a.size()-1-suffix, b, b.size()-1-suffix)) { if ((suffix & 1023) == 0) budget.check(); suffix++; }
        int n = a.size()-prefix-suffix, m = b.size()-prefix-suffix;
        List<Span> tokens = new ArrayList<>();
        if (prefix > 0) tokens.add(new Span(true,0,prefix,0,prefix));
        if (n == 0 || m == 0) {
            if (n+m > 0) tokens.add(new Span(false,prefix,prefix+n,prefix,prefix+m));
        } else {
            // A length difference alone can exceed the trace budget; do not waste a trace first.
            List<Span> middle = Math.abs(n-m)>traceDistance ? null : myers(a,b,prefix,n,m,traceDistance,budget);
            if(middle!=null) tokens.addAll(middle);
            else if(n>=m) linear(a,prefix,prefix+n,b,prefix,prefix+m,budget,tokens,false);
            else linear(b,prefix,prefix+m,a,prefix,prefix+n,budget,tokens,true);
        }
        if (suffix>0) tokens.add(new Span(true,a.size()-suffix,a.size(),b.size()-suffix,b.size()));
        List<Span> result = new ArrayList<>();
        for (Span s: tokens) {
            budget.check();
            append(result,new Span(s.equal,a.offset(s.a0),a.offset(s.a1),b.offset(s.b0),b.offset(s.b1)));
        }
        return List.copyOf(result);
    }
    private static List<Span> myers(Sequence a,Sequence b,int prefix,int n,int m,int traceDistance,Budget budget) {
            int bound = Math.min(traceDistance, n+m), shift = bound+1;
            int[] v = new int[2*bound+3];
            List<int[]> trace = new ArrayList<>();
            int distance = -1;
            outer: for (int d=0; d<=bound; d++) {
                budget.check();
                trace.add(v.clone());
                for (int k=-d; k<=d; k+=2) {
                    int x = (k == -d || (k != d && v[shift+k-1] < v[shift+k+1])) ? v[shift+k+1] : v[shift+k-1]+1;
                    int y = x-k;
                    while (x<n && y<m && a.same(prefix+x, b, prefix+y)) { if ((x & 1023)==0) budget.check(); x++; y++; }
                    v[shift+k] = x;
                    if (x>=n && y>=m) { distance=d; break outer; }
                }
            }
            if (distance < 0) return null;
            List<Span> reverse = new ArrayList<>(); int x=n, y=m;
            for (int d=distance; d>0; d--) {
                budget.check(); int[] old=trace.get(d); int k=x-y;
                int pk=(k == -d || (k != d && old[shift+k-1] < old[shift+k+1])) ? k+1 : k-1;
                int px=old[shift+pk], py=px-pk;
                int sx = pk == k+1 ? px : px+1, sy = pk == k+1 ? py+1 : py;
                if (x>sx) reverse.add(new Span(true,prefix+sx,prefix+x,prefix+sy,prefix+y));
                reverse.add(new Span(false,prefix+px,prefix+sx,prefix+py,prefix+sy));
                x=px; y=py;
            }
            if (x>0) reverse.add(new Span(true,prefix,prefix+x,prefix,prefix+y));
            Collections.reverse(reverse); return reverse;
    }
    /** Hirschberg split: only two score rows, never an n*m matrix or an unbounded trace. */
    private static void linear(Sequence a,int a0,int a1,Sequence b,int b0,int b1,Budget budget,List<Span> out,boolean swapped) {
        budget.check();
        int firstA=a0,firstB=b0;
        while(a0<a1&&b0<b1&&a.same(a0,b,b0)){if((a0&1023)==0)budget.check();a0++;b0++;}
        emit(out,true,firstA,a0,firstB,b0,swapped);
        int lastA=a1,lastB=b1;
        while(a0<a1&&b0<b1&&a.same(a1-1,b,b1-1)){if((a1&1023)==0)budget.check();a1--;b1--;}
        if(a0==a1||b0==b1) emit(out,false,a0,a1,b0,b1,swapped);
        else if(a1-a0==1){
            int match=-1;
            for(int j=b0;j<b1;j++){if((j&1023)==0)budget.check();if(a.same(a0,b,j)){match=j;break;}}
            if(match<0)emit(out,false,a0,a1,b0,b1,swapped);
            else{emit(out,false,a0,a0,b0,match,swapped);emit(out,true,a0,a1,match,match+1,swapped);emit(out,false,a1,a1,match+1,b1,swapped);}
        }else{
            int middle=(a0+a1)>>>1;
            int split=split(a,a0,middle,a1,b,b0,b1,budget);
            linear(a,a0,middle,b,b0,split,budget,out,swapped);
            linear(a,middle,a1,b,split,b1,budget,out,swapped);
        }
        emit(out,true,a1,lastA,b1,lastB,swapped);
    }
    private static int split(Sequence a,int a0,int middle,int a1,Sequence b,int b0,int b1,Budget budget){
        int[] forward=scores(a,a0,middle,b,b0,b1,false,budget);
        int[] backward=scores(a,middle,a1,b,b0,b1,true,budget);
        int best=-1,split=0,m=b1-b0;
        for(int j=0;j<=m;j++){if((j&1023)==0)budget.check();int score=forward[j]+backward[m-j];if(score>best){best=score;split=j;}}
        return b0+split;
    }
    private static int[] scores(Sequence a,int a0,int a1,Sequence b,int b0,int b1,boolean reverse,Budget budget){
        int n=a1-a0,m=b1-b0;int[] row=new int[m+1];
        for(int i=0;i<n;i++){
            budget.check();int diagonal=0,ai=reverse?a1-1-i:a0+i;
            for(int j=1;j<=m;j++){
                if((j&1023)==0)budget.check();
                int old=row[j];
                row[j]=a.same(ai,b,reverse?b1-j:b0+j-1)?diagonal+1:Math.max(row[j],row[j-1]);
                diagonal=old;
            }
        }
        return row;
    }
    private static void emit(List<Span> out,boolean equal,int a0,int a1,int b0,int b1,boolean swapped){
        append(out,swapped?new Span(equal,b0,b1,a0,a1):new Span(equal,a0,a1,b0,b1));
    }
    private static void append(List<Span> out,Span next){
        if(next.a0==next.a1&&next.b0==next.b1)return;
        if(!out.isEmpty()&&out.get(out.size()-1).equal==next.equal){
            Span previous=out.remove(out.size()-1);
            next=new Span(next.equal,previous.a0,next.a1,previous.b0,next.b1);
        }
        out.add(next);
    }
    static void check() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }
}
