package com.burpworkbench.modules.compare;

import java.util.Arrays;
import java.util.Set;

/** Lightweight lexical colors only: never pretty-prints or changes the compared text. */
final class SyntaxColors {
    static final byte PLAIN=0, NAME=1, STRING=2, LITERAL=3, COMMENT=4, VALUE=5;
    private static final Set<String> KEYWORDS=Set.of("true","false","null","undefined","return","function","var","let","const","if","else","for","while","new","class","import","export","async","await");
    static byte[] scan(String text) {
        byte[] colors=new byte[text.length()];int body=0;
        int firstEnd=lineEnd(text,0);
        String first=text.substring(0,Math.min(firstEnd,2048));
        boolean http=first.startsWith("HTTP/") || first.matches("[A-Z]{1,20} .+ HTTP/[^ ]+");
        if(http){
            int firstSpace=first.indexOf(' ');if(firstSpace>0)paint(colors,0,firstSpace,NAME);
            int p=nextLine(text,firstEnd);
            while(p<text.length()){
                DiffEngine.check();int end=lineEnd(text,p);if(end==p){body=nextLine(text,end);break;}
                int colon=find(text,':',p,end);
                if(colon>=p&&colon<end){paint(colors,p,colon+1,NAME);String header=text.substring(p,colon);
                    if(header.equalsIgnoreCase("Cookie")||header.equalsIgnoreCase("Set-Cookie"))pairs(text,colors,colon+1,end,';');}
                p=nextLine(text,end);body=p;
            }
            if(body==0)body=text.length();
        }
        String prefix=text.substring(body,Math.min(text.length(),body+512)).stripLeading();
        boolean structured=prefix.startsWith("{")||prefix.startsWith("[")||prefix.startsWith("<")
                ||prefix.startsWith("//")||prefix.startsWith("function")||prefix.startsWith("const ")||prefix.startsWith("var ")||prefix.startsWith("let ");
        if(!structured){
            if(http&&text.substring(0,Math.min(body,text.length())).toLowerCase(java.util.Locale.ROOT).contains("application/x-www-form-urlencoded"))pairs(text,colors,body,text.length(),'&');
            return colors;
        }
        for(int i=body;i<text.length();){
            if((i&1023)==0)DiffEngine.check();char c=text.charAt(i);int start=i++;
            if(c=='"'||c=='\''||c=='`'){
                while(i<text.length()){if((i&1023)==0)DiffEngine.check();char q=text.charAt(i++);if(q=='\\'&&i<text.length())i++;else if(q==c)break;}
                int after=i;while(after<text.length()&&Character.isWhitespace(text.charAt(after)))after++;
                paint(colors,start,i,after<text.length()&&text.charAt(after)==':'?NAME:STRING);
            }else if(c=='/'&&i<text.length()&&text.charAt(i)=='/'){
                while(i<text.length()&&text.charAt(i)!='\n'&&text.charAt(i)!='\r')i++;paint(colors,start,i,COMMENT);
            }else if(c=='/'&&i<text.length()&&text.charAt(i)=='*'){
                int end=text.indexOf("*/",i+1);i=end<0?text.length():end+2;paint(colors,start,i,COMMENT);
            }else if(c=='<'&&text.startsWith("<!--",start)){
                int end=text.indexOf("-->",i);i=end<0?text.length():end+3;paint(colors,start,i,COMMENT);
            }else if(c=='<'){
                if(i<text.length()&&(text.charAt(i)=='/'||text.charAt(i)=='!'))i++;
                while(i<text.length()&&(Character.isLetterOrDigit(text.charAt(i))||text.charAt(i)=='-'||text.charAt(i)==':'))i++;
                paint(colors,start,i,NAME);
            }else if(Character.isDigit(c)||(c=='-'&&i<text.length()&&Character.isDigit(text.charAt(i)))){
                while(i<text.length()&&"0123456789.eExXaAbBcCdDfF+-".indexOf(text.charAt(i))>=0)i++;paint(colors,start,i,NAME);
            }else if(Character.isJavaIdentifierStart(c)){
                while(i<text.length()&&Character.isJavaIdentifierPart(text.charAt(i)))i++;
                if(KEYWORDS.contains(text.substring(start,i)))paint(colors,start,i,LITERAL);
            }
        }
        return colors;
    }
    private static void pairs(String text,byte[] colors,int from,int end,char delimiter){
        for(int p=from;p<end;){DiffEngine.check();int stop=find(text,delimiter,p,end);if(stop<0)stop=end;int eq=find(text,'=',p,stop);
            if(eq>=p&&eq<stop){paint(colors,p,eq,NAME);paint(colors,eq+1,stop,VALUE);}p=stop+1;}
    }
    private static int find(String text,char c,int from,int end){for(int i=from;i<end;i++){if((i&4095)==0)DiffEngine.check();if(text.charAt(i)==c)return i;}return -1;}
    private static void paint(byte[] colors,int a,int b,byte code){Arrays.fill(colors,a,b,code);}
    private static int lineEnd(String s,int start){int p=start;while(p<s.length()&&s.charAt(p)!='\n'&&s.charAt(p)!='\r')p++;return p;}
    private static int nextLine(String s,int end){if(end<s.length()&&s.charAt(end)=='\r')end++;if(end<s.length()&&s.charAt(end)=='\n')end++;return end;}
}
