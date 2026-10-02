package com.burpworkbench.modules.extractor.filter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import static com.burpworkbench.modules.extractor.filter.FilterSettings.*;

/** Ordered user rules only. Tags protected; no automatic phone/email branch. */
public final class FilterSession {
    private static final Pattern TAG=Pattern.compile("§[^§\\r\\n]{1,100}§");
    private record Prepared(Rule rule,Pattern pattern,Pattern mime,Pattern field,Pattern exclude,int index){}
    private final List<Prepared> prepared=new ArrayList<>();private final BooleanSupplier cancelled;
    private final Set<String> reserved=new HashSet<>(),generated=new HashSet<>();
    private final Map<String,String> identities=new HashMap<>();private final Map<String,Integer> sequence=new HashMap<>();
    private final Map<String,Map<String,Object>> attributes=new LinkedHashMap<>();private final List<Hit> locations=new ArrayList<>();private int hits;
    public record Hit(String ruleId,String ruleName,String tag,int start,int end,String context){}
    public record Result(String original,String text,String format,List<String> warnings,int matches){public byte[] bytes(){return text.getBytes(StandardCharsets.UTF_8);}}
    public FilterSession(FilterSettings settings){this(settings,()->false);}
    public FilterSession(FilterSettings settings,BooleanSupplier cancelled){
        this.cancelled=cancelled;int index=0;
        for(Rule r:settings.rules()){
            index++;if(!r.enabled())continue;
            String regex=switch(r.match()){case REGEX->r.source();case LITERAL->Pattern.quote(r.source());case HOST->"(?i)(?<![A-Za-z0-9_.@-])"+Pattern.quote(r.source())+"(?![A-Za-z0-9_.-])";};
            prepared.add(new Prepared(r,Pattern.compile(regex),optional(r.contentType()),optional(r.field()),optional(r.exclude()),index));reserved.add(r.tag());
        }
    }
    private static Pattern optional(String pattern){return pattern.isEmpty()?null:Pattern.compile(pattern);}
    private void check(){Documents.check();if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();}
    public int hits(){return hits;}
    public List<Map<String,Object>> attributes(){return attributes.values().stream().map(Map::copyOf).toList();}
    public List<Hit> locations(){return List.copyOf(locations);}
    public Result filter(Documents.Input input)throws Exception{
        Snapshot snapshot=new Snapshot();
        try{return filterInput(input);}catch(Exception|StackOverflowError e){snapshot.restore();throw e;}
    }
    private Result filterInput(Documents.Input input)throws Exception{
        check();Documents.Doc doc=Documents.parse(input);reserve(doc.source);int before=hits;String original=doc.source;Map<Integer,String> values=new HashMap<>();
        String mime=input.contentType().split(";",2)[0].trim();
        if(doc.format==Documents.Format.HTTP){Matcher header=Pattern.compile("(?im)^Content-Type:\\s*([^;\\r\\n]+)").matcher(doc.source);if(header.find())mime=header.group(1).trim();}
        if(mime.isEmpty())mime=switch(doc.format){case JSON->"application/json";case HTML->"text/html";case XML->"application/xml";default->"text/plain";};
        for(Prepared p:prepared){
            check();RegexBudget budget=new RegexBudget(cancelled);if(p.mime!=null&&!p.mime.matcher(budget.wrap(mime)).find())continue;
            if(p.rule.target()==Target.VALUES){
                for(var slot:doc.slots){
                    Scope scope=slot.context.startsWith("http/")?Scope.METADATA:Scope.BODY;if(!applies(p.rule,scope))continue;
                    if(p.field!=null&&!p.field.matcher(budget.wrap(slot.field)).find())continue;
                    String value=values.getOrDefault(slot.id,slot.text),changed=replace(value,p,slot.originalType,slot.context,budget);if(!changed.equals(value))values.put(slot.id,changed);
                }
            }else{
                String rendered=doc.render(values),changed;
                if(doc.format==Documents.Format.HTTP){
                    int boundary=rendered.indexOf("\r\n\r\n"),separator=4;if(boundary<0){boundary=rendered.indexOf("\n\n");separator=2;}if(boundary<0)throw new IllegalArgumentException("HTTP header boundary missing");
                    String head=rendered.substring(0,boundary),body=rendered.substring(boundary+separator);
                    if(applies(p.rule,Scope.METADATA))head=replace(head,p,"raw","http/source",budget);
                    if(applies(p.rule,Scope.BODY))body=replace(body,p,"raw","body/source",budget);
                    changed=head+rendered.substring(boundary,boundary+separator)+body;
                }else{Scope scope=doc.format==Documents.Format.HEADERS?Scope.METADATA:Scope.BODY;changed=applies(p.rule,scope)?replace(rendered,p,"raw","document",budget):rendered;}
                if(!changed.equals(rendered)){doc=Documents.parse(new Documents.Input(input.name(),changed.getBytes(StandardCharsets.UTF_8),"; charset=UTF-8","",doc.format));values.clear();}
            }
        }
        return new Result(original,doc.render(values),doc.format.name(),List.copyOf(doc.warnings),hits-before);
    }
    private static boolean applies(Rule rule,Scope scope){return rule.scope()==Scope.ALL||rule.scope()==scope;}
    public String metadata(String value){check();reserve(value);for(Prepared p:prepared)if(applies(p.rule,Scope.METADATA)&&p.mime==null&&p.field==null)value=replace(value,p,"string","metadata",new RegexBudget(cancelled));return value;}
    public String filename(String value){String out=metadata(value).replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","_").replaceAll("[. ]+$","");return out.isBlank()?"item":out;}
    public Path path(Path path){Path out=Path.of("");int index=0;for(Path segment:path){String value=segment.toString();Matcher port=Pattern.compile("^(.*)(_\\d+)$").matcher(value);if(index++==0&&port.matches())value=filename(port.group(1))+port.group(2);else value=filename(value);out=out.resolve(value);}return out;}
    private final class Snapshot {
        final Set<String> oldReserved=new HashSet<>(reserved),oldGenerated=new HashSet<>(generated);
        final Map<String,String> oldIdentities=new HashMap<>(identities);final Map<String,Integer> oldSequence=new HashMap<>(sequence);
        final Map<String,Map<String,Object>> oldAttributes=new LinkedHashMap<>();final int oldHits=hits,oldLocations=locations.size();
        Snapshot(){attributes.forEach((k,v)->oldAttributes.put(k,new LinkedHashMap<>(v)));}
        void restore(){reserved.clear();reserved.addAll(oldReserved);generated.clear();generated.addAll(oldGenerated);identities.clear();identities.putAll(oldIdentities);sequence.clear();sequence.putAll(oldSequence);attributes.clear();attributes.putAll(oldAttributes);hits=oldHits;locations.subList(oldLocations,locations.size()).clear();}
    }
    private void reserve(String value){Matcher m=TAG.matcher(value);while(m.find()){if(generated.contains(m.group()))throw new IllegalArgumentException("Input collides with a generated tag; not saved");reserved.add(m.group());}}
    private String replace(String value,Prepared p,String type,String context,RegexBudget budget){
        List<int[]> protectedSpans=new ArrayList<>();Matcher tags=TAG.matcher(value);while(tags.find())protectedSpans.add(new int[]{tags.start(),tags.end()});
        Matcher matcher=p.pattern.matcher(budget.wrap(value));StringBuilder out=new StringBuilder();int cursor=0;
        try{while(matcher.find()){
            check();int start=matcher.start(p.rule.group()),end=matcher.end(p.rule.group());if(start<cursor||end<=start)continue;
            // Binary search avoids a match-count x tag-count scan on already filtered files.
            int low=0,high=protectedSpans.size();while(low<high){int mid=(low+high)>>>1;if(protectedSpans.get(mid)[1]<=start)low=mid+1;else high=mid;}
            if(low<protectedSpans.size()&&protectedSpans.get(low)[0]<end)continue;
            String found=value.substring(start,end);if(p.exclude!=null&&p.exclude.matcher(budget.wrap(found)).find())continue;
            if(hits>=100_000)throw new IllegalArgumentException("Match limit exceeded");
            String tag=p.rule.numbered()?numbered(p.rule.tag(),found):p.rule.tag();out.append(value,cursor,start).append(tag);cursor=end;hits++;
            locations.add(new Hit(p.rule.id(),p.rule.name(),tag,start,end,context));
            String key=p.rule.id()+"\0"+tag+"\0"+type;
            Map<String,Object> row=attributes.computeIfAbsent(key,k->{Map<String,Object> a=new LinkedHashMap<>();a.put("tag",tag);a.put("type",p.rule.category());a.put("originalType",type);a.put("ruleId",p.rule.id());a.put("rule",p.index);a.put("occurrences",0);return a;});row.put("occurrences",(int)row.get("occurrences")+1);
        }}catch(StackOverflowError e){throw new IllegalArgumentException("Regex recursion limit exceeded; simplify the pattern");}
        return cursor==0?value:out.append(value,cursor,value.length()).toString();
    }
    private String numbered(String base,String value){return identities.computeIfAbsent(base+"\0"+value,k->{String tag,name=base.substring(1,base.length()-1);do{tag="§"+name+"_"+sequence.merge(base,1,Integer::sum)+"§";}while(reserved.contains(tag));reserved.add(tag);generated.add(tag);return tag;});}
    public void writeAttributes(Path directory)throws java.io.IOException{Documents.JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("filter_attributes.json").toFile(),Map.of("schema",3,"scope","User-selected rules only; not complete anonymization","matches",hits,"attributes",attributes()));}
}
