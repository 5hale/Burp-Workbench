package com.burpworkbench.modules.compare;

import java.nio.charset.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Immutable presentation built away from Swing. Original bytes are never rewritten. */
final class Comparison {
    enum Kind { Modified, Deleted, Added }
    record Mark(int a0, int a1, int b0, int b1, Kind kind, String location) {}
    record PaintRange(int start, int end, Kind kind) {}
    record Side(String text, byte[] syntax, int[] lineStarts, List<String> lineLabels, List<PaintRange> ranges) {
        static Side plain(String text) { return new Side(text,new byte[text.length()],Comparison.lineStarts(text),labels(text),List.of()); }
        private static List<String> labels(String text) {List<String> labels=new ArrayList<>();int count=Comparison.lineStarts(text).length;for(int i=0;i<count;i++)labels.add(Integer.toString(i+1));return List.copyOf(labels);}
    }
    record View(String left, String right, List<Mark> marks, String note, Side a, Side b, String problem) {
        View(String left,String right,List<Mark> marks,String note){this(left,right,marks,note,Side.plain(left),Side.plain(right),null);}
    }
    record Options(DiffEngine.Mode mode, boolean hex, boolean differencesOnly, String charset) {}
    private static final Pattern CHARSET = Pattern.compile("(?im)^Content-Type:[^\\r\\n]*charset\\s*=\\s*[\"']?([^\\s;\"'\\r\\n]+)");

    static View prepare(byte[] a,byte[] b,Options options){return withFallback(a,b,options,()->build(a,b,options));}
    static View withFallback(byte[] a,byte[] b,Options options,Supplier<View> compute){
        return withFallback(a,b,options,options,compute);
    }
    static View withFallback(byte[] a,byte[] b,Options options,Options other,Supplier<View> compute){
        try{return compute.get();}
        catch(CancellationException cancelled){throw cancelled;}
        catch(RuntimeException error){
            DiffEngine.check();
            String problem=error instanceof DiffEngine.Limit?error.getMessage():"Comparison failed: "+error.getClass().getSimpleName();
            // Failure is not an empty/equal diff. Keep both sources, even in Differences only.
            Options full=new Options(options.mode,options.hex,false,options.charset);
            Side left=render(decode(a,options.charset),List.of(),true,full).side;
            Side right=render(decode(b,other.charset),List.of(),false,new Options(other.mode,other.hex,false,other.charset)).side;
            return new View(left.text,right.text,List.of(),"Original data shown; differences not computed",left,right,problem);
        }
    }

    static View build(byte[] a, byte[] b, Options o) {
        return build(a,b,o,o);
    }
    static View build(byte[] a, byte[] b, Options o, Options other) {
        Decoded left=decode(a,o.charset), right=decode(b,other.charset);
        boolean bytes=o.mode==DiffEngine.Mode.Bytes;
        var sa=bytes ? new DiffEngine.Sequence(a) : new DiffEngine.Sequence(left.text,o.mode);
        var sb=bytes ? new DiffEngine.Sequence(b) : new DiffEngine.Sequence(right.text,o.mode);
        List<DiffEngine.Span> spans=DiffEngine.compare(sa,sb);
        List<Change> changes=new ArrayList<>();
        for (DiffEngine.Span s:spans) {
            DiffEngine.check();
            if(!s.equal())changes.add(new Change(range(left,s.a0(),s.a1(),bytes),range(right,s.b0(),s.b1(),bytes),
                    s.a0()==s.a1()?Kind.Added:s.b0()==s.b1()?Kind.Deleted:Kind.Modified));
        }
        Rendered la=render(left,changes,true,o),rb=render(right,changes,false,other);
        List<Mark> marks=new ArrayList<>();
        for(int i=0;i<changes.size();i++){Change c=changes.get(i);marks.add(new Mark(la.starts[i],la.ends[i],rb.starts[i],rb.ends[i],c.kind,
                "A "+left.location(c.a)+"  /  B "+right.location(c.b)));}
        String note=left.charset.name()+" / "+right.charset.name();
        if (!left.exact || !right.exact) note+=" · decoding loss: use Bytes + Hex for exact comparison";
        if (!bytes && !Arrays.equals(a,b) && marks.isEmpty()) note+=" · decoded text equal; original bytes differ";
        return new View(la.side.text,rb.side.text,List.copyOf(marks),note,la.side,rb.side,null);
    }
    record SourceRange(int charStart,int charEnd,int byteStart,int byteEnd) {}
    record Change(SourceRange a,SourceRange b,Kind kind) {}
    record Rendered(Side side,int[] starts,int[] ends) {}
    private static SourceRange range(Decoded d,int start,int end,boolean bytes){
        int cs=bytes?d.charAtByte(start,false):start,ce=start==end?cs:bytes?d.charAtByte(end,true):end;
        return new SourceRange(cs,ce,bytes?start:d.byteAtChar[start],bytes?end:d.byteAtChar[end]);
    }
    static int[] lineStarts(String text){
        int count=1;for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')count++;
        int[] starts=new int[count];int n=1;for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')starts[n++]=i+1;return starts;
    }
    static int lineAt(int[] starts,int position){int p=Arrays.binarySearch(starts,position);return p>=0?p:Math.max(0,-p-2);}
    private static Rendered render(Decoded d,List<Change> changes,boolean left,Options o){
        return o.hex?hex(d,changes,left,o.differencesOnly):text(d,changes,left,o.differencesOnly);
    }
    private static Rendered text(Decoded d,List<Change> changes,boolean left,boolean only){
        byte[] syntax=SyntaxColors.scan(d.text);StringBuilder out=new StringBuilder();
        List<String> labels=new ArrayList<>();List<PaintRange> paints=new ArrayList<>();
        int[] starts=new int[changes.size()],ends=new int[changes.size()];
        if(!only){out.append(d.text);for(int i=0;i<d.lines.length;i++)labels.add(Integer.toString(i+1));}
        List<byte[]> fragments=new ArrayList<>();
        for(int i=0;i<changes.size();i++){
            DiffEngine.check();Change c=changes.get(i);SourceRange s=left?c.a:c.b;
            if(only){
                if(i>0){out.append("\n⋯\n");labels.add("…");}
                labels.add(Integer.toString(lineAt(d.lines,s.charStart)+1));starts[i]=out.length();
                out.append(d.text,s.charStart,s.charEnd);ends[i]=out.length();
                for(int p=s.charStart;p<s.charEnd;p++)if(d.text.charAt(p)=='\n')labels.add(Integer.toString(lineAt(d.lines,p+1)+1));
                fragments.add(Arrays.copyOfRange(syntax,s.charStart,s.charEnd));
            }else{starts[i]=s.charStart;ends[i]=s.charEnd;}
            if(ends[i]>starts[i])paints.add(new PaintRange(starts[i],ends[i],c.kind));
        }
        if(only){byte[] compact=new byte[out.length()];for(int i=0;i<fragments.size();i++)System.arraycopy(fragments.get(i),0,compact,starts[i],fragments.get(i).length);syntax=compact;}
        if(labels.isEmpty())labels.add(" ");String content=out.toString();
        return new Rendered(new Side(content,syntax,lineStarts(content),List.copyOf(labels),List.copyOf(paints)),starts,ends);
    }
    private static Rendered hex(Decoded d,List<Change> changes,boolean left,boolean only){
        byte[] kinds=new byte[d.raw.length];BitSet rows=new BitSet();int rowCount=(d.raw.length+15)/16;
        if(!only)rows.set(0,Math.max(1,rowCount));
        for(Change c:changes){SourceRange s=left?c.a:c.b;Arrays.fill(kinds,s.byteStart,s.byteEnd,(byte)(c.kind.ordinal()+1));
            rows.set(s.byteStart/16,Math.max(s.byteStart/16+1,(s.byteEnd+15)/16));}
        StringBuilder out=new StringBuilder();List<String> labels=new ArrayList<>();List<PaintRange> paints=new ArrayList<>();
        int[] rowPositions=new int[Math.max(1,rowCount+1)];Arrays.fill(rowPositions,-1);char[] digits="0123456789ABCDEF".toCharArray();int previous=-1;
        for(int row=rows.nextSetBit(0);row>=0;row=rows.nextSetBit(row+1)){
            DiffEngine.check();if(previous>=0){out.append('\n');if(row>previous+1){out.append("⋯\n");labels.add("…");}}
            int offset=row*16,base=out.length(),end=Math.min(offset+16,d.raw.length);rowPositions[row]=base;
            labels.add((row+1)+"  "+String.format(Locale.ROOT,"%08X",offset));
            for(int p=offset;p<offset+16;p++){
                if(p<end&&(!only||kinds[p]!=0)){int value=d.raw[p]&255;out.append(digits[value>>>4]).append(digits[value&15]);}else out.append("  ");
                out.append(' ');
            }
            out.append(" | ");
            for(int p=offset;p<end;p++){int value=d.raw[p]&255;out.append(only&&kinds[p]==0?' ':value>=32&&value<=126?(char)value:'.');}
            // Two sorted paint runs per changed segment: hex bytes and matching character column.
            for(int column=0;column<2;column++)for(int p=offset;p<end;){byte kind=kinds[p];int q=p+1;while(q<end&&kinds[q]==kind)q++;
                if(kind!=0){int start=column==0?base+(p-offset)*3:base+51+p-offset;int stop=column==0?base+(q-offset)*3-1:base+51+q-offset;
                    paints.add(new PaintRange(start,stop,Kind.values()[kind-1]));}p=q;}
            previous=row;
        }
        int[] starts=new int[changes.size()],ends=new int[changes.size()];
        for(int i=0;i<changes.size();i++){SourceRange s=left?changes.get(i).a:changes.get(i).b;
            starts[i]=rowPositions[s.byteStart/16]+(s.byteStart%16)*3;
            ends[i]=s.byteStart==s.byteEnd?starts[i]:rowPositions[(s.byteEnd-1)/16]+((s.byteEnd-1)%16)*3+2;}
        if(labels.isEmpty())labels.add(" ");String content=out.toString();
        return new Rendered(new Side(content,new byte[content.length()],lineStarts(content),List.copyOf(labels),List.copyOf(paints)),starts,ends);
    }
    static final class Decoded {
        final byte[] raw; final String text; final Charset charset; final boolean exact; final int[] byteAtChar;final int[] lines;
        Decoded(byte[] raw, Charset charset) {
            this.raw=raw; this.charset=charset; text=new String(raw,charset);
            exact=Arrays.equals(raw,text.getBytes(charset));lines=lineStarts(text);
            byteAtChar=new int[text.length()+1]; int pos=0;
            CharsetEncoder encoder=charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            ByteBuffer encoded=ByteBuffer.allocate(64);
            for(int i=0;i<text.length();) {
                if ((i&1023)==0) DiffEngine.check();
                int cp=text.codePointAt(i), count=Character.charCount(cp); byteAtChar[i]=Math.min(pos,raw.length);
                if(count==2)byteAtChar[i+1]=Math.min(pos,raw.length);
                int len;
                if(charset.equals(StandardCharsets.UTF_8))len=cp<128?1:cp<2048?2:cp<65536?3:4;
                else {encoded.clear();encoder.encode(CharBuffer.wrap(text,i,i+count),encoded,false);len=encoded.position();}
                pos+=len; i+=count; byteAtChar[i]=Math.min(pos,raw.length);
            }
            byteAtChar[text.length()]=raw.length;
        }
        int charAtByte(int offset,boolean end) {
            int idx=Arrays.binarySearch(byteAtChar,offset);
            if(idx>=0){ while(idx>0&&byteAtChar[idx-1]==offset)idx--; return idx; }
            int insertion=-idx-1; int found=end?insertion:Math.max(0,insertion-1);
            if(found>0&&found<text.length()&&Character.isLowSurrogate(text.charAt(found)))found+=end?1:-1;
            return Math.min(text.length(),found);
        }
        String location(SourceRange r){int line=lineAt(lines,r.charStart),column=text.codePointCount(lines[line],r.charStart)+1;
            return "L"+(line+1)+":"+column+" · bytes "+r.byteStart+"–"+r.byteEnd;}
    }
    static Decoded decode(byte[] raw,String option) {
        Charset charset=StandardCharsets.UTF_8;
        if (!"Auto".equals(option)) charset=Charset.forName(option);
        else {
            String headers=new String(raw,0,Math.min(raw.length,8192),StandardCharsets.ISO_8859_1);
            int split=headers.indexOf("\r\n\r\n"); if(split<0)split=headers.indexOf("\n\n");
            if(split>=0) { Matcher m=CHARSET.matcher(headers.substring(0,split));
                if(m.find())try{charset=Charset.forName(m.group(1));}catch(IllegalArgumentException ignored){} }
        }
        return new Decoded(raw,charset);
    }
}
