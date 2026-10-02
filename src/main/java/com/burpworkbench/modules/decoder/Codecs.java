package com.burpworkbench.modules.decoder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.*;

/** Pure local transforms. Never replace malformed bytes or unrepresentable characters silently. */
final class Codecs {
    static final int LIMIT = 1024 * 1024;
    static final String[] CHARSETS = {"UTF-8", "EUC-KR", "MS949", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE", "ISO-8859-1"};
    static final String[] BASES = {"Base64", "Base64 URL-safe", "Base64 MIME", "Base32", "Base32 Hex", "Base16", "Base58 Bitcoin"};
    static final String[] HASHES = {"MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512", "SHA-512/224", "SHA-512/256", "SHA3-224", "SHA3-256", "SHA3-384", "SHA3-512"};
    static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567", B32HEX = "0123456789ABCDEFGHIJKLMNOPQRSTUV";
    static final String B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    enum Kind { TEXT, URL, BASE, HEX, HTML, UNICODE, UTF8, UTF16, UTF32, DECIMAL, HASH }
    record Options(String charset, String notation, boolean spaces, boolean littleEndian,
                   String unicodeUnit, String base, boolean padding, String hash, boolean formUrl) {
        static Options defaults() { return new Options("UTF-8", "\\u", true, false, "UTF-16", "Base64", true, "SHA-256", false); }
        Options notation(String value) { return new Options(charset,value,spaces,littleEndian,unicodeUnit,base,padding,hash,formUrl); }
        Options charset(String value) { return new Options(value,notation,spaces,littleEndian,unicodeUnit,base,padding,hash,formUrl); }
        Options base(String value) { return new Options(charset,notation,spaces,littleEndian,unicodeUnit,value,padding,hash,formUrl); }
    }
    static String convert(String input, Kind kind, boolean encode, Options o) {
        Objects.requireNonNull(input);check(input.length());
        String result;
        if (kind == Kind.TEXT) result = valid(input);
        else if (kind == Kind.HASH) {
            if (!encode) throw new IllegalArgumentException("Hash는 되돌릴 수 없습니다. Calculate를 사용하세요.");
            try { result = HexFormat.of().formatHex(MessageDigest.getInstance(o.hash).digest(encode(input,o.charset))); }
            catch (NoSuchAlgorithmException e) { throw new IllegalArgumentException("지원하지 않는 Hash: " + o.hash); }
        } else if (kind == Kind.BASE) result = encode ? baseEncode(encode(input,o.charset),o) : decode(baseDecode(input,o),o.charset);
        else if (kind == Kind.HEX) result = encode ? renderBytes(encode(input,o.charset),o.notation,o.spaces) : decode(parseHex(input),o.charset);
        else if (kind == Kind.URL) result = encode ? percent(encode(input,o.charset),o.formUrl,false) : decode(parsePercent(input,o.charset,o.formUrl),o.charset);
        else if (kind == Kind.HTML) result = encode ? htmlEncode(input) : htmlDecode(input);
        else if (kind == Kind.DECIMAL) result = encode ? decimalEncode(input) : decimalDecode(input);
        else result = unicode(input,kind,encode,o);
        // Bounded input + bounded output; long running work is cancellable off the EDT.
        if (result.length() > 16 * LIMIT) throw new IllegalArgumentException("Decoder 출력 한도(16,777,216 UTF-16 units)를 초과했습니다.");
        check(0); return result;
    }
    static void check(int size) { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); if (size > LIMIT) throw new IllegalArgumentException("Decoder 입력 한도는 1,048,576 UTF-16 units입니다. 작은 영역을 선택하세요."); }
    static String valid(String text) {
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))throw new IllegalArgumentException("짝이 없는 Unicode surrogate입니다.");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("짝이 없는 Unicode surrogate입니다.");}
        return text;
    }
    static Charset charset(String name) { return Charset.forName("MS949".equals(name)?"x-windows-949":name); }
    static byte[] encode(String text, String codec) {
        valid(text);
        try { ByteBuffer b=charset(codec).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));byte[] out=new byte[b.remaining()];b.get(out);return out; }
        catch(CharacterCodingException e){throw new IllegalArgumentException(codec+"로 표현할 수 없는 문자가 있습니다.");}
    }
    static String decode(byte[] bytes,String codec) {
        try { return valid(charset(codec).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()); }
        catch(CharacterCodingException e){throw new IllegalArgumentException(codec+"로 읽을 수 없는 바이트입니다. Charset을 확인하세요.");}
    }
    static String hex(long value,int width) { String s=Long.toHexString(value).toUpperCase(Locale.ROOT);return "0".repeat(Math.max(0,width-s.length()))+s; }
    static String renderBytes(byte[] bytes,String notation,boolean spaces) {
        String prefix=switch(notation){case "%"->"%";case "0x"->"0x";case "\\x"->"\\x";case "\\u"->"\\u";case "U+"->"U+";default->"";};
        StringBuilder b=new StringBuilder();for(int i=0;i<bytes.length;i++){if((i&4095)==0)check(0);if(i>0&&spaces)b.append(' ');b.append(prefix).append(hex(bytes[i]&255,2));}return b.toString();
    }
    /** Accept raw pairs, %HH, 0xHH and backslash-x pairs without requiring a prefix selector. */
    static byte[] parseHex(String s) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<s.length();){if((i&4095)==0)check(0);if(Character.isWhitespace(s.charAt(i))){i++;continue;}
            if(s.charAt(i)=='%')i++;else if(s.regionMatches(true,i,"0x",0,2)||s.regionMatches(true,i,"\\x",0,2))i+=2;
            if(i+2>s.length()||Character.digit(s.charAt(i),16)<0||Character.digit(s.charAt(i+1),16)<0)throw new IllegalArgumentException("Hex는 EA B9 80 / %EA%B9%80 / 0xEA 0xB9 0x80 형식으로 입력하세요.");
            out.write((Character.digit(s.charAt(i),16)<<4)|Character.digit(s.charAt(i+1),16));i+=2;
        }return out.toByteArray();
    }
    static String percent(byte[] data,boolean form,boolean all) {
        StringBuilder b=new StringBuilder();for(int i=0;i<data.length;i++){if((i&4095)==0)check(0);int c=data[i]&255;if(!all&&((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||"-._~".indexOf(c)>=0))b.append((char)c);else if(form&&c==32)b.append('+');else b.append('%').append(hex(c,2));}return b.toString();
    }
    static byte[] parsePercent(String s,String codec,boolean form) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<s.length();){if((i&4095)==0)check(0);int c=s.codePointAt(i);if(c=='%'){if(i+3>s.length()||Character.digit(s.charAt(i+1),16)<0||Character.digit(s.charAt(i+2),16)<0)throw new IllegalArgumentException("% 뒤에는 16진수 두 자리가 필요합니다.");out.write(Integer.parseInt(s.substring(i+1,i+3),16));i+=3;}else{if(form&&c=='+')c=32;if(c<128)out.write(c);else out.writeBytes(encode(new String(Character.toChars(c)),codec));i+=Character.charCount(c);}}return out.toByteArray();
    }
    static String unicode(String s,Kind kind,boolean enc,Options o) {
        String unit=switch(kind){case UTF8->"UTF-8";case UTF16->"UTF-16";case UTF32->"UTF-32";default->o.unicodeUnit;};
        String codec=unit.equals("UTF-8")?"UTF-8":unit+(o.littleEndian?"LE":"BE");
        if(o.notation.equals("%"))return enc?percent(encode(s,codec),false,true):decode(parseHex(s),codec);
        if(unit.equals("UTF-8"))return enc?renderBytes(encode(s,codec),o.notation,o.spaces):decode(parseByteNotation(s),codec);
        int width=unit.equals("UTF-16")?4:8;
        if(enc){valid(s);StringBuilder out=new StringBuilder();for(int i=0;i<s.length();){check(0);int n=width==4?s.charAt(i):s.codePointAt(i);i+=width==4?1:Character.charCount(n);
                if(out.length()>0&&o.spaces)out.append(' ');String p=switch(o.notation){case "0x"->"0x";case "U+"->"U+";case "\\u", "\\U"->width==4?"\\u":"\\U";default->"";};out.append(p).append(hex(Integer.toUnsignedLong(n),width));}return out.toString();}
        List<Long> values=parseUnits(s,width);StringBuilder out=new StringBuilder();for(long value:values){check(0);int n=(int)value;if(width==4)out.append((char)n);else{if(n<0||n>0x10FFFF||(n>=0xD800&&n<=0xDFFF))throw new IllegalArgumentException("Unicode 코드 포인트 범위를 벗어났습니다.");out.appendCodePoint(n);}}return valid(out.toString());
    }
    static byte[] parseByteNotation(String s) { return parseHex(s.replace("\\u","\\x").replace("U+","0x")); }
    static List<Long> parseUnits(String s,int width) {
        List<Long> result=new ArrayList<>();for(int i=0;i<s.length();){if(Character.isWhitespace(s.charAt(i))){i++;continue;}check(0);
            if(s.regionMatches(true,i,"0x",0,2)||s.startsWith("U+",i)||s.startsWith("\\u",i)||s.startsWith("\\U",i))i+=2;
            if(i+width>s.length())throw new IllegalArgumentException("선택한 Unicode 단위는 "+width+"자리 Hex가 필요합니다.");
            String value=s.substring(i,i+width);if(!value.matches("[0-9A-Fa-f]+"))throw new IllegalArgumentException("Unicode 표기 또는 단위를 확인하세요.");result.add(Long.parseLong(value,16));i+=width;
        }return result;
    }
    static String decimalEncode(String s) { valid(s);StringJoiner out=new StringJoiner(" ");s.codePoints().forEach(cp->{check(0);out.add(Integer.toString(cp));});return out.toString(); }
    static String decimalDecode(String s) { if(s.isBlank())return "";StringBuilder out=new StringBuilder();for(String part:s.trim().split("\\s+")){check(0);int n;try{n=Integer.parseInt(part);}catch(NumberFormatException e){throw new IllegalArgumentException("Decimal 코드 포인트를 공백으로 구분하세요.");}if(n<0||n>0x10FFFF||(n>=0xD800&&n<=0xDFFF))throw new IllegalArgumentException("유효하지 않은 Unicode 코드 포인트입니다.");out.appendCodePoint(n);}return out.toString(); }
    static String htmlEncode(String s) { valid(s);return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
    static String htmlDecode(String s) {
        Matcher m=Pattern.compile("&(#x[0-9a-fA-F]+|#X[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").matcher(s);StringBuilder b=new StringBuilder();while(m.find()){check(0);String key=m.group(1),v;if(key.startsWith("#")){int cp;try{cp=Integer.parseInt(key.substring(key.length()>1&&(key.charAt(1)=='x'||key.charAt(1)=='X')?2:1),key.toLowerCase(Locale.ROOT).startsWith("#x")?16:10);}catch(NumberFormatException e){throw new IllegalArgumentException("HTML 문자 참조 범위를 확인하세요.");}if(cp<0||cp>0x10FFFF||(cp>=0xD800&&cp<=0xDFFF))throw new IllegalArgumentException("잘못된 HTML 문자 참조입니다.");v=new String(Character.toChars(cp));}else v=switch(key){case "amp"->"&";case "lt"->"<";case "gt"->">";case "quot"->"\"";case "apos"->"'";default->"\u00a0";};m.appendReplacement(b,Matcher.quoteReplacement(v));}m.appendTail(b);return valid(b.toString());
    }
    static String baseEncode(byte[] data,Options o) {
        return switch(o.base){case "Base16"->HexFormat.of().withUpperCase().formatHex(data);case "Base32"->base32Encode(data,B32,o.padding);case "Base32 Hex"->base32Encode(data,B32HEX,o.padding);case "Base58 Bitcoin"->base58Encode(data);default->{Base64.Encoder encoder=switch(o.base){case "Base64 URL-safe"->Base64.getUrlEncoder();case "Base64 MIME"->Base64.getMimeEncoder();default->Base64.getEncoder();};yield(o.padding?encoder:encoder.withoutPadding()).encodeToString(data);}};
    }
    static byte[] baseDecode(String input,Options o) {
        String s=input.replaceAll("[\\r\\n\\t ]","");
        if(o.base.equals("Base16")){if(!s.matches("(?:[0-9a-fA-F]{2})*"))throw new IllegalArgumentException("Base16 형식이 아닙니다.");return HexFormat.of().parseHex(s);}
        if(o.base.startsWith("Base32"))return base32Decode(s,o.base.equals("Base32")?B32:B32HEX);
        if(o.base.equals("Base58 Bitcoin"))return base58Decode(s);
        boolean url=o.base.equals("Base64 URL-safe");if(!s.matches(url?"[A-Za-z0-9_-]*={0,2}":"[A-Za-z0-9+/]*={0,2}"))throw new IllegalArgumentException("선택한 Base64 alphabet과 입력이 다릅니다.");
        try{byte[] data=(url?Base64.getUrlDecoder():Base64.getDecoder()).decode(s);String canonical=(url?Base64.getUrlEncoder():Base64.getEncoder()).withoutPadding().encodeToString(data);if(!canonical.equals(s.replaceAll("=+$","")))throw new IllegalArgumentException("Base64 padding bits가 올바르지 않습니다.");return data;}catch(IllegalArgumentException e){throw new IllegalArgumentException("Base64 길이·alphabet·padding을 확인하세요.");}
    }
    static String base32Encode(byte[] data,String alphabet,boolean padding) {
        StringBuilder out=new StringBuilder();int acc=0,bits=0;for(int i=0;i<data.length;i++){if((i&4095)==0)check(0);acc=(acc<<8)|(data[i]&255);bits+=8;while(bits>=5){bits-=5;out.append(alphabet.charAt((acc>>>bits)&31));}acc&=(1<<bits)-1;}if(bits>0)out.append(alphabet.charAt((acc<<(5-bits))&31));if(padding)while(out.length()%8!=0)out.append('=');return out.toString();
    }
    static byte[] base32Decode(String s,String alphabet) {
        String bare=s.replaceAll("=+$","").toUpperCase(Locale.ROOT);int remainder=bare.length()%8;if(!(remainder==0||remainder==2||remainder==4||remainder==5||remainder==7))throw new IllegalArgumentException("Base32 길이가 올바르지 않습니다.");
        if(s.indexOf('=')>=0&&(s.length()%8!=0||!s.substring(bare.length()).matches("={1,6}")))throw new IllegalArgumentException("Base32 padding이 올바르지 않습니다.");
        ByteArrayOutputStream out=new ByteArrayOutputStream();int acc=0,bits=0;for(int i=0;i<bare.length();i++){if((i&4095)==0)check(0);int v=alphabet.indexOf(bare.charAt(i));if(v<0)throw new IllegalArgumentException("Base32 alphabet을 확인하세요.");acc=(acc<<5)|v;bits+=5;if(bits>=8){bits-=8;out.write((acc>>>bits)&255);}acc&=(1<<bits)-1;}if(acc!=0)throw new IllegalArgumentException("Base32 padding bits가 올바르지 않습니다.");return out.toByteArray();
    }
    static String base58Encode(byte[] data) {
        if(data.length>8192)throw new IllegalArgumentException("Base58 처리 한도는 8 KiB입니다.");if(data.length==0)return "";int zero=0;while(zero<data.length&&data[zero]==0)zero++;BigInteger value=new BigInteger(1,data),base=BigInteger.valueOf(58);StringBuilder b=new StringBuilder();while(value.signum()>0){check(0);BigInteger[] qr=value.divideAndRemainder(base);b.append(B58.charAt(qr[1].intValue()));value=qr[0];}b.append("1".repeat(zero));return b.reverse().toString();
    }
    static byte[] base58Decode(String s) {
        if(s.length()>11200)throw new IllegalArgumentException("Base58 입력 한도는 11,200자입니다.");if(s.isEmpty())return new byte[0];BigInteger value=BigInteger.ZERO;for(int i=0;i<s.length();i++){check(0);int digit=B58.indexOf(s.charAt(i));if(digit<0)throw new IllegalArgumentException("Base58 Bitcoin alphabet을 확인하세요.");value=value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));}byte[] data=value.toByteArray();int skip=data.length>0&&data[0]==0?1:0,zeros=0;while(zeros<s.length()&&s.charAt(zeros)=='1')zeros++;byte[] out=new byte[zeros+data.length-skip];System.arraycopy(data,skip,out,zeros,data.length-skip);if(out.length>8192)throw new IllegalArgumentException("Base58 처리 한도는 8 KiB입니다.");return out;
    }
}
