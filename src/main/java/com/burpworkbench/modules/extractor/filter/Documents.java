package com.burpworkbench.modules.extractor.filter;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.brotli.dec.BrotliInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.xml.sax.InputSource;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.zip.*;

/** Local-only decoders. No parser may resolve a URL or external entity. */
public final class Documents {
    public static final int MAX_BYTES = 8 * 1024 * 1024, MAX_SLOTS = 100_000;
    public static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(MAX_BYTES).build()).build());
    public enum Format { AUTO, JSON, HTML, XML, CSV, FORM, TEXT, HTTP, HEADERS }
    public record Input(String name, byte[] bytes, String contentType, String encoding, Format format) {
        public Input { bytes = bytes.clone(); }
        public static Input text(String name, String value, Format format) {
            return new Input(name, value.getBytes(StandardCharsets.UTF_8), "", "", format);
        }
    }
    public static final class Slot {
        public final int id;
        public final String field, context, text, originalType;
        Slot(int id, String field, String context, String text, String originalType) {
            this.id=id; this.field=field; this.context=context; this.text=text; this.originalType=originalType;
        }
    }
    public static final class Doc {
        public final String source;
        public final List<Slot> slots = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public Format format;
        private Function<Map<Integer,String>,String> renderer;
        Doc(String source, Format format) { this.source=source; this.format=format; }
        Slot add(String field, String context, String text, String type) {
            check();
            if(slots.size() >= MAX_SLOTS) throw new IllegalArgumentException("TOO_MANY_FIELDS");
            Slot s=new Slot(slots.size(),field,context,text,type); slots.add(s); return s;
        }
        public String render(Map<Integer,String> values) { check(); return values.isEmpty()?source:renderer.apply(values); }
    }
    private record Edit(int start, int end, Slot slot, Function<String,String> encode) {}
    public static Input file(Path path, Format format, String charset) throws IOException {
        if(!Files.isRegularFile(path) || Files.size(path)>MAX_BYTES) throw new IOException("FILE_LIMIT_OR_NOT_REGULAR");
        byte[] bytes;
        try(InputStream in=Files.newInputStream(path)) { bytes=limited(in); }
        String name=path.getFileName().toString();
        return new Input(name,bytes,"; charset="+charset,"",format==Format.AUTO?extension(name):format);
    }
    public static Doc parse(Input input) throws Exception {
        check();
        byte[] bytes=decode(input.bytes,input.encoding);
        String text=decodeText(bytes,input.contentType);
        Format format=input.format==Format.AUTO?detect(input.contentType,text):input.format;
        Doc d=new Doc(text,format);
        switch(format) {
            case JSON -> json(d);
            case HTML -> html(d);
            case XML -> xml(d);
            case CSV -> csv(d);
            case FORM -> form(d);
            case HTTP -> http(d);
            case HEADERS -> { Slot s=d.add("","http/header",d.source,"string"); d.renderer=v->v.getOrDefault(s.id,s.text); }
            case TEXT -> plain(d);
            default -> throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
        }
        return d;
    }
    public static Format extension(String name) {
        String n=name.toLowerCase(Locale.ROOT);
        if(n.endsWith(".json")) return Format.JSON;
        if(n.endsWith(".html")||n.endsWith(".htm")) return Format.HTML;
        if(n.endsWith(".xml")) return Format.XML;
        if(n.endsWith(".csv")) return Format.CSV;
        if(n.endsWith(".http")) return Format.HTTP;
        if(n.matches(".*\\.(txt|log|js|css|md|yaml|yml|properties)$")) return Format.TEXT;
        return Format.AUTO;
    }
    static Format detect(String ct, String text) {
        String type=ct.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if(type.contains("json")) return Format.JSON;
        if(type.contains("html")) return Format.HTML;
        if(type.contains("xml")) return Format.XML;
        if(type.equals("text/csv")) return Format.CSV;
        if(type.equals("application/x-www-form-urlencoded")) return Format.FORM;
        if(!type.isEmpty() && !type.startsWith("text/") && !type.contains("javascript")) throw new IllegalArgumentException("UNSUPPORTED_CONTENT_TYPE");
        String t=text.stripLeading();
        if(t.startsWith("{")||t.startsWith("[")) return Format.JSON;
        if(t.startsWith("HTTP/") || t.matches("(?s)^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) [^\r\n]+ HTTP/.*")) return Format.HTTP;
        if(t.toLowerCase(Locale.ROOT).startsWith("<!doctype html")||t.toLowerCase(Locale.ROOT).startsWith("<html")) return Format.HTML;
        if(t.startsWith("<?xml")) return Format.XML;
        return Format.TEXT;
    }
    static byte[] decode(byte[] bytes, String enc) throws IOException {
        if(bytes.length>MAX_BYTES) throw new IOException("INPUT_LIMIT");
        String[] parts=enc.toLowerCase(Locale.ROOT).split(",");
        if(parts.length>3) throw new IOException("ENCODING_LAYERS");
        for(int i=parts.length-1;i>=0;i--) {
            String e=parts[i].trim(); if(e.isEmpty()||e.equals("identity")) continue;
            InputStream in=new ByteArrayInputStream(bytes);
            in=switch(e) {case "gzip"->new GZIPInputStream(in);case "deflate"->new InflaterInputStream(in);case "br"->new BrotliInputStream(in);default->throw new IOException("UNSUPPORTED_ENCODING");};
            try(InputStream decoded=in) { bytes=limited(decoded); }
        }
        return bytes;
    }
    static byte[] limited(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n;
        while((n=in.read(buffer))!=-1) { check(); if(out.size()+n>MAX_BYTES) throw new IOException("DECODED_LIMIT"); out.write(buffer,0,n); }
        return out.toByteArray();
    }
    static String decodeText(byte[] bytes,String ct) throws CharacterCodingException {
        Matcher m=Pattern.compile("(?i)charset\\s*=\\s*[\"']?([^;\\s\"']+)").matcher(ct);
        Charset charset=m.find()?Charset.forName(m.group(1)):StandardCharsets.UTF_8;
        int start=0;
        if(bytes.length>=3 && (bytes[0]&255)==239 && (bytes[1]&255)==187 && (bytes[2]&255)==191) {charset=StandardCharsets.UTF_8;start=3;}
        else if(bytes.length>=2 && (bytes[0]&255)==255 && (bytes[1]&255)==254) {charset=StandardCharsets.UTF_16LE;start=2;}
        else if(bytes.length>=2 && (bytes[0]&255)==254 && (bytes[1]&255)==255) {charset=StandardCharsets.UTF_16BE;start=2;}
        String text=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,start,bytes.length-start)).toString();
        for(int i=0;i<text.length();i++) if(text.charAt(i)<32 && "\t\r\n".indexOf(text.charAt(i))<0) throw new IllegalArgumentException("BINARY_OR_CONTROL_DATA");
        return text;
    }
    static void json(Doc d) throws IOException {
        List<Edit> edits=new ArrayList<>();
        try(JsonParser p=JSON.getFactory().createParser(d.source)) {
            if(p.nextToken()==null) throw new IOException("EMPTY_JSON");
            jsonValue(d,p,"","",edits,0);
            if(p.nextToken()!=null) throw new IOException("TRAILING_JSON");
        }
        d.renderer=values->edit(d.source,edits,values);
    }
    static void jsonValue(Doc d,JsonParser p,String field,String context,List<Edit> edits,int depth) throws IOException {
        check(); if(depth>100) throw new IOException("DEPTH_LIMIT");
        if(p.currentToken()==JsonToken.START_OBJECT) {
            while(p.nextToken()!=JsonToken.END_OBJECT) {
                if(p.currentToken()!=JsonToken.FIELD_NAME) throw new IOException("INVALID_OBJECT");
                String key=p.currentName();
                int start=(int)p.currentTokenLocation().getCharOffset();
                // FIELD_NAME parser lookahead may pass the colon; locate its quoted token explicitly.
                int end=quotedEnd(d.source,start);
                Slot ks=d.add("","key",key,"key"); edits.add(new Edit(start,end,ks,Documents::quote));
                p.nextToken(); jsonValue(d,p,key,context+"/"+field,edits,depth+1);
            }
        } else if(p.currentToken()==JsonToken.START_ARRAY) {
            while(p.nextToken()!=JsonToken.END_ARRAY) jsonValue(d,p,field,context,edits,depth+1);
        } else if(p.currentToken()==JsonToken.VALUE_STRING || p.currentToken().isNumeric()) {
            int start=(int)p.currentTokenLocation().getCharOffset(); String value=p.getText();
            int end=p.currentToken()==JsonToken.VALUE_STRING?quotedEnd(d.source,start):(int)p.currentLocation().getCharOffset();
            Slot s=d.add(field,context,value,p.currentToken()==JsonToken.VALUE_STRING?"string":"number");
            edits.add(new Edit(start,end,s,Documents::quote));
        } else if(p.currentToken()!=JsonToken.VALUE_NULL && p.currentToken()!=JsonToken.VALUE_TRUE && p.currentToken()!=JsonToken.VALUE_FALSE) throw new IOException("INVALID_JSON_VALUE");
    }
    static int quotedEnd(String text,int start) {
        boolean escape=false;
        for(int i=start+1;i<text.length();i++) {char c=text.charAt(i);if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')return i+1;}
        throw new IllegalArgumentException("UNTERMINATED_STRING");
    }
    static String edit(String text,List<Edit> edits,Map<Integer,String> values) {
        StringBuilder out=new StringBuilder(text);
        edits.stream().sorted(Comparator.comparingInt(Edit::start).reversed()).forEach(e->{String v=values.get(e.slot.id);if(v!=null&&!v.equals(e.slot.text))out.replace(e.start,e.end,e.encode.apply(v));});
        return out.toString();
    }
    static void plain(Doc d) {
        Slot s=d.add("","text",d.source,"string");
        d.renderer=values->values.getOrDefault(s.id,s.text);
        d.warnings.add("TEXT_PATTERNS_ONLY: free-text names, addresses and code semantics need manual review");
    }
    static void form(Doc d) {
        List<Edit> edits=new ArrayList<>(); int offset=0;
        for(String pair:d.source.split("&",-1)) {
            int eq=pair.indexOf('='); String key=URLDecoder.decode(eq<0?pair:pair.substring(0,eq),StandardCharsets.UTF_8);
            Slot k=d.add("","key",key,"key"); edits.add(new Edit(offset,offset+(eq<0?pair.length():eq),k,Documents::urlEncode));
            if(eq>=0) {String value=URLDecoder.decode(pair.substring(eq+1),StandardCharsets.UTF_8);Slot s=d.add(key,"form",value,"string");edits.add(new Edit(offset+eq+1,offset+pair.length(),s,Documents::urlEncode));}
            offset+=pair.length()+1;
        }
        d.renderer=values->edit(d.source,edits,values);
    }
    static String urlEncode(String s) {return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static void html(Doc d) {
        org.jsoup.nodes.Document dom=Jsoup.parse(d.source);
        dom.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8);
        List<Consumer<Map<Integer,String>>> setters=new ArrayList<>();
        dom.forEachNode(node->{
            check();
            if(node instanceof Element e) {
                for(Attribute a:new ArrayList<>(e.attributes().asList())) {
                    String key=a.getKey(); String field=key;
                    if(key.equals("value")) {
                        field=!e.attr("name").isEmpty()?e.attr("name"):e.id();
                        if(!Rules.FIELDS.containsKey(Rules.key(field))) {
                            String label="";
                            for(Element l:dom.select("label"))if(!e.id().isEmpty()&&l.attr("for").equals(e.id()))label=l.ownText().trim();
                            if(label.isEmpty()&&e.parent()!=null&&e.parent().normalName().equals("label"))label=e.parent().ownText().trim();
                            if(Rules.FIELDS.containsKey(Rules.key(label)))field=label;
                        }
                        if(e.attr("type").equalsIgnoreCase("password"))field="password";
                        if(e.attr("type").equalsIgnoreCase("tel"))field="phoneNumber";
                        if(e.attr("type").equalsIgnoreCase("email"))field="email";
                    }
                    Slot s=d.add(field,"html/"+e.normalName(),a.getValue(),"attribute");
                    setters.add(v->e.attr(key,v.getOrDefault(s.id,s.text)));
                }
            }
            if(node instanceof TextNode t) {
                String field=""; Node parent=t.parent();
                if(parent instanceof Element e) {
                    if(e.normalName().equals("title")&&e.parent()!=null&&e.parent().normalName().equals("head")) field="__html_title";
                    else if(e.normalName().equals("textarea"))field=e.attr("name");
                }
                Slot s=d.add(field,"html/text",t.getWholeText(),"string");setters.add(v->t.text(v.getOrDefault(s.id,s.text)));
            } else if(node instanceof DataNode n) {
                Slot s=d.add("","html/script",n.getWholeData(),"string");setters.add(v->n.setWholeData(v.getOrDefault(s.id,s.text)));
                d.warnings.add("SCRIPT_PATTERNS_ONLY");
            } else if(node instanceof Comment c) {
                Slot s=d.add("","html/comment",c.getData(),"string");setters.add(v->c.setData(v.getOrDefault(s.id,s.text)));
            }
        });
        d.renderer=v->{setters.forEach(s->s.accept(v));return dom.outerHtml();};
        d.warnings.add("HTML_RESERIALIZED; images and external resources are not inspected or fetched");
    }
    static void xml(Doc d) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        var builder=f.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});
        var dom=builder.parse(new InputSource(new StringReader(d.source)));
        List<Consumer<Map<Integer,String>>> setters=new ArrayList<>();
        xmlNode(d,dom,"",setters,0);
        d.renderer=v->{
            setters.forEach(s->s.accept(v));
            try {TransformerFactory tf=TransformerFactory.newInstance();tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");Transformer t=tf.newTransformer();t.setOutputProperty(OutputKeys.ENCODING,"UTF-8");StringWriter out=new StringWriter();t.transform(new DOMSource(dom),new StreamResult(out));return out.toString();}catch(Exception e){throw new IllegalArgumentException("XML_RENDER_FAILED");}
        };
        d.warnings.add("XML_RESERIALIZED; element/attribute names and processing instructions require manual review");
    }
    static void xmlNode(Doc d,org.w3c.dom.Node n,String context,List<Consumer<Map<Integer,String>>> setters,int depth) {
        if(depth>100)throw new IllegalArgumentException("DEPTH_LIMIT"); check();
        if(n.hasAttributes()) for(int i=0;i<n.getAttributes().getLength();i++) {var a=n.getAttributes().item(i);Slot s=d.add(a.getNodeName(),context,a.getNodeValue(),"attribute");setters.add(v->a.setNodeValue(v.getOrDefault(s.id,s.text)));}
        if(n.getNodeType()==org.w3c.dom.Node.TEXT_NODE || n.getNodeType()==org.w3c.dom.Node.CDATA_SECTION_NODE || n.getNodeType()==org.w3c.dom.Node.COMMENT_NODE) {
            Slot s=d.add(n.getParentNode().getNodeName(),context,n.getNodeValue(),"string");setters.add(v->n.setNodeValue(v.getOrDefault(s.id,s.text)));
        }
        for(var child=n.getFirstChild();child!=null;child=child.getNextSibling())xmlNode(d,child,context+"/"+n.getNodeName(),setters,depth+1);
    }
    static void csv(Doc d) {
        List<List<String>> rows=csvRows(d.source);
        if(rows.isEmpty())throw new IllegalArgumentException("EMPTY_CSV");
        List<List<Slot>> table=new ArrayList<>(); List<String> headers=rows.get(0);
        for(int r=0;r<rows.size();r++) {
            if(rows.get(r).size()!=headers.size())throw new IllegalArgumentException("CSV_COLUMN_MISMATCH");
            List<Slot> row=new ArrayList<>();for(int c=0;c<headers.size();c++)row.add(d.add(r==0?"":headers.get(c),r==0?"key":"csv",rows.get(r).get(c),r==0?"key":"string"));table.add(row);
        }
        d.renderer=v->{StringBuilder out=new StringBuilder();for(var row:table){for(int i=0;i<row.size();i++){if(i>0)out.append(',');Slot s=row.get(i);out.append('"').append(v.getOrDefault(s.id,s.text).replace("\"","\"\"")).append('"');}out.append("\r\n");}return out.toString();};
        d.warnings.add("CSV quoted UTF-8 output; saved as .txt to avoid spreadsheet formula execution");
    }
    static List<List<String>> csvRows(String text) {
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quote=false,closed=false;
        for(int i=0;i<text.length();i++) {check();char c=text.charAt(i);
            if(quote){if(c=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){value.append('"');i++;}else{quote=false;closed=true;}}else value.append(c);}
            else if(c=='"'){if(value.length()>0||closed)throw new IllegalArgumentException("CSV_QUOTE");quote=true;}
            else if(c==','||c=='\n'||c=='\r'){row.add(value.toString());value.setLength(0);closed=false;if(c!=','){rows.add(row);row=new ArrayList<>();if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}}
            else{if(closed)throw new IllegalArgumentException("CSV_AFTER_QUOTE");value.append(c);}
            if(rows.size()>MAX_SLOTS)throw new IllegalArgumentException("CSV_LIMIT");
        }
        if(quote)throw new IllegalArgumentException("CSV_UNTERMINATED");
        if(value.length()>0||closed||!row.isEmpty()){row.add(value.toString());rows.add(row);}return rows;
    }
    static void http(Doc d) throws Exception {
        int split=d.source.indexOf("\r\n\r\n"),sep=4;if(split<0){split=d.source.indexOf("\n\n");sep=2;}if(split<0)throw new IllegalArgumentException("HTTP_HEADERS");
        String[] headers=d.source.substring(0,split).split("\r?\n");String ct="",ce="";
        List<Slot> hs=new ArrayList<>();List<String> names=new ArrayList<>();
        hs.add(d.add("","http/start-line",headers[0],"string"));names.add("");
        for(int i=1;i<headers.length;i++) {int colon=headers[i].indexOf(':');if(colon<1)throw new IllegalArgumentException("HTTP_HEADER");String name=headers[i].substring(0,colon);String value=headers[i].substring(colon+1).trim();
            if(name.equalsIgnoreCase("content-type"))ct=value;
            if(name.equalsIgnoreCase("content-encoding"))ce=value;
            if(name.equalsIgnoreCase("transfer-encoding"))throw new IllegalArgumentException("RAW_HTTP_TRANSFER_ENCODING_UNSUPPORTED");
            if(name.equalsIgnoreCase("content-length"))continue;
            hs.add(d.add(name,"http/header",value,"string"));names.add(name);
        }
        if(!ce.isBlank()&&!ce.equalsIgnoreCase("identity"))throw new IllegalArgumentException("RAW_HTTP_COMPRESSED_USE_BURP_BODY");
        Doc body=parse(new Input("body",d.source.substring(split+sep).getBytes(StandardCharsets.UTF_8),ct.replaceAll("(?i)charset\\s*=\\s*[^;]+","charset=UTF-8"),"",Format.AUTO));
        Map<Integer,Integer> mapping=new HashMap<>();
        for(Slot s:body.slots){Slot added=d.add(s.field,s.context,s.text,s.originalType);mapping.put(s.id,added.id);}
        d.warnings.addAll(body.warnings);d.warnings.add("HTTP_ANALYSIS_ONLY: Content-Length removed; not replayable");
        d.renderer=v->{StringBuilder out=new StringBuilder();for(int i=0;i<hs.size();i++){Slot s=hs.get(i);if(i>0)out.append(names.get(i)).append(": ");out.append(v.getOrDefault(s.id,s.text)).append("\r\n");}Map<Integer,String> bv=new HashMap<>();mapping.forEach((a,b)->{if(v.containsKey(b))bv.put(a,v.get(b));});return out.append("\r\n").append(body.render(bv)).toString();};
    }
    static String quote(String text) {try{return JSON.writeValueAsString(text);}catch(Exception e){throw new IllegalArgumentException("JSON_RENDER");}}
    static void check() {if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
    private Documents() {}
}
