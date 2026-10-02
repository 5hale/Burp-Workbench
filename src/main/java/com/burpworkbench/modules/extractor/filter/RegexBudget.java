package com.burpworkbench.modules.extractor.filter;
import java.util.function.BooleanSupplier;
/** In-thread budget; no abandoned timeout workers. */
final class RegexBudget {
    private long reads;private final long deadline=System.nanoTime()+2_000_000_000L;private final BooleanSupplier cancelled;
    RegexBudget(BooleanSupplier cancelled){this.cancelled=cancelled;}
    void check(){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();if(++reads>30_000_000L||((reads&1023)==0&&System.nanoTime()>deadline))throw new IllegalArgumentException("Regex budget exceeded; simplify the pattern");}
    CharSequence wrap(String text){return new Guard(text,0,text.length());}
    private final class Guard implements CharSequence {
        final String text;final int start,end;Guard(String text,int start,int end){this.text=text;this.start=start;this.end=end;}
        public int length(){return end-start;}
        public char charAt(int i){check();if(i<0||i>=length())throw new IndexOutOfBoundsException();return text.charAt(start+i);}
        public CharSequence subSequence(int a,int b){if(a<0||b<a||b>length())throw new IndexOutOfBoundsException();return new Guard(text,start+a,start+b);}
        public String toString(){return text.substring(start,end);}
    }
}
