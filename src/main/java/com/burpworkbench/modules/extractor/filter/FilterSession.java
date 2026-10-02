package com.burpworkbench.modules.extractor.filter;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static com.burpworkbench.modules.extractor.filter.FilterSettings.*;

/** One extraction run: stable short tags, no original values in exported attributes. */
public final class FilterSession {
    private static final Pattern TAG=Pattern.compile("§[^§\\r\\n]{1,80}§");
    private static final Pattern EMAIL=Pattern.compile("(?<![\\w.!#$%&'*+/=?^`{|}~@-])[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+(?![\\w@-])");
    private static final Pattern PHONE=Pattern.compile("(?<![\\p{L}\\d])(?:010[- .]?\\d{4}[- .]?\\d{4}|\\+82[- .]?10[- .]?\\d{4}[- .]?\\d{4})(?![\\p{L}\\d])");
    private static final Pattern PHONE_CONTEXT=Pattern.compile("(?i)(?<![\\p{L}\\d_])(?:phone|mobile|tel|휴대폰|휴대전화|전화번호|연락처)[\\s_:-]*$");
    private final FilterSettings settings;
    private final List<PreparedRule> preparedRules;
    private record PreparedRule(Rule rule,int index,Pattern host){}
    private final java.util.function.BooleanSupplier cancelled;
    private final Map<String,String> identities=new HashMap<>();
    private final Map<String,Attribute> attributes=new LinkedHashMap<>();
    private final Set<String> reserved=new HashSet<>(), generated=new HashSet<>();
    private final Map<String,Integer> sequence=new HashMap<>();
    private int hits;
    private record Span(int start,int end,String tag,String type,String originalType,String evidence,int rule){}
    private static final class Attribute {
        final String tag,type,evidence;final int rule;int occurrences;
        final Set<String> originalTypes=new TreeSet<>();
        Attribute(Span s){tag=s.tag;type=s.type;evidence=s.evidence;rule=s.rule;}
        Map<String,Object> row(){return Map.of("tag",tag,"type",type,"originalTypes",originalTypes,"evidence",evidence,"rule",rule,"occurrences",occurrences);}
    }
    public record Result(String original,String text,String format,List<String> warnings,int matches) {
        public byte[] bytes(){return text.getBytes(StandardCharsets.UTF_8);}
    }
    public FilterSession(FilterSettings settings){this(settings,()->false);}
    public FilterSession(FilterSettings settings,java.util.function.BooleanSupplier cancelled){
        this.settings=settings;this.cancelled=cancelled;
        List<PreparedRule> prepared=new ArrayList<>();int index=0;
        for(Rule r:settings.rules()){
            reserved.add(r.tag());
            Pattern host=r.match()==Match.HOST?Pattern.compile("(?<![A-Za-z0-9_.@-])"+Pattern.quote(r.source())+"(?![A-Za-z0-9_.-])",Pattern.CASE_INSENSITIVE):null;
            // Preserve Pattern's code-point handling for supplementary/malformed UTF-16 rules.
            if(host==null&&r.source().chars().anyMatch(c->Character.isSurrogate((char)c)))host=Pattern.compile(Pattern.quote(r.source()));
            prepared.add(new PreparedRule(r,++index,host));
        }
        preparedRules=List.copyOf(prepared);
    }
    private void check(){Documents.check();if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();}
    public int hits(){return hits;}
    public List<Map<String,Object>> attributes(){return attributes.values().stream().map(Attribute::row).toList();}
    public Result filter(Documents.Input input)throws Exception{
        check();Documents.Doc doc=Documents.parse(input);check();reserve(doc.source);
        for(var s:doc.slots)reserve(s.text);
        int before=hits;Map<Integer,String> values=new HashMap<>();
        for(var slot:doc.slots){
            Scope scope=slot.context.startsWith("http/")?Scope.METADATA:Scope.BODY;
            String changed=replace(slot.text,slot.field,slot.originalType,scope,!slot.originalType.equals("key"));
            if(!changed.equals(slot.text))values.put(slot.id,changed);
        }
        return new Result(doc.source,doc.render(values),doc.format.name(),List.copyOf(doc.warnings),hits-before);
    }
    /** Metadata retains request target/version/Host port; never constructs a request line. */
    public String metadata(String value){reserve(value);return replace(value,"","string",Scope.METADATA,true);}
    public String filename(String value){
        String filtered=metadata(value).replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","_").replaceAll("[. ]+$","");
        if(filtered.isBlank()||filtered.equals(".")||filtered.equals(".."))return "item";
        return filtered;
    }
    public Path path(Path path){
        Path out=Path.of("");int index=0;
        for(Path segment:path){String value=segment.toString();
            Matcher port=Pattern.compile("^(.*)(_\\d+)$").matcher(value);
            if(index++==0&&port.matches())value=filename(port.group(1))+port.group(2);
            else value=filename(value);
            out=out.resolve(value);
        }return out;
    }
    private void reserve(String value){
        Matcher m=TAG.matcher(value);
        while(m.find()){
            if(generated.contains(m.group()))throw new IllegalArgumentException("기존 태그와 자동 태그 충돌: 이 항목은 저장하지 않습니다.");
            reserved.add(m.group());
        }
    }
    private String replace(String value,String field,String originalType,Scope scope,boolean auto){
        check();List<Span> spans=new ArrayList<>();
        Matcher existing=TAG.matcher(value);while(existing.find())spans.add(new Span(existing.start(),existing.end(),existing.group(),"EXISTING",originalType,"",0));
        // User order is priority. Match on the ORIGINAL string, never on emitted tags.
        for(PreparedRule prepared:preparedRules){
            Rule rule=prepared.rule();int ruleIndex=prepared.index();
            if(rule.scope()!=Scope.ALL&&rule.scope()!=scope)continue;
            check();
            if(prepared.host()==null){
                int cursor=0,start;
                while((start=value.indexOf(rule.source(),cursor))>=0){
                    int end=start+rule.source().length();limit(spans);
                    if(free(spans,start,end))spans.add(new Span(start,end,rule.tag(),"CUSTOM",originalType,"literal rule "+ruleIndex,ruleIndex));
                    cursor=end;
                }
            }else{
                Matcher m=prepared.host().matcher(value);
                while(m.find()){limit(spans);if(free(spans,m.start(),m.end()))spans.add(new Span(m.start(),m.end(),rule.tag(),"CUSTOM",originalType,"literal rule "+ruleIndex,ruleIndex));}
            }
        }
        if(auto&&settings.email()){
            Matcher m=EMAIL.matcher(value);while(m.find()){
                limit(spans);
                String email=m.group();String local=email.substring(0,email.indexOf('@'));
                if(local.startsWith(".")||local.endsWith(".")||local.contains("..")||local.length()>64||email.length()>254)continue;
                if(free(spans,m.start(),m.end()))spans.add(new Span(m.start(),m.end(),tag("EMAIL",email),"EMAIL",originalType,"email syntax",0));
            }
        }
        if(auto&&settings.phone()){
            Matcher m=PHONE.matcher(value);while(m.find()){
                limit(spans);
                String phone=m.group();boolean formatted=phone.contains("-")||phone.contains(" ")||phone.contains(".")||phone.startsWith("+82");
                boolean context="PHONE".equals(Rules.FIELDS.get(Rules.key(field)))||Set.of("phoneno","telno","telnumber","mobilephone","mobilephonenumber","mobileno","mobilenumber","cellphone","cellphonenumber","contactphone","연락처","휴대전화").contains(Rules.key(field))||PHONE_CONTEXT.matcher(value.substring(Math.max(0,m.start()-32),m.start())).find();
                // Unformatted 11-digit IDs are NOT enough. Numeric/key values are never inferred by shape alone.
                if(!context&&!formatted)continue;
                if(!context&&!field.isEmpty()&&Rules.key(field).matches(".*(id|code|no|number)$"))continue;
                String normalized=mobile(phone);if(normalized==null||!free(spans,m.start(),m.end()))continue;
                spans.add(new Span(m.start(),m.end(),tag("PHONE",normalized),"PHONE",originalType,context?"mobile pattern + phone context":"formatted KR mobile",0));
            }
        }
        if(spans.isEmpty())return value;
        spans.sort(Comparator.comparingInt(Span::start));StringBuilder out=new StringBuilder();int end=0;
        for(Span span:spans){out.append(value,end,span.start).append(span.tag);end=span.end;
            if(!span.type.equals("EXISTING")){hits++;Attribute a=attributes.computeIfAbsent(span.tag+"\0"+span.originalType+"\0"+span.rule,k->new Attribute(span));a.occurrences++;a.originalTypes.add(originalType);}
        }
        return out.append(value,end,value.length()).toString();
    }
    private static boolean free(List<Span> spans,int start,int end){for(Span s:spans)if(start<s.end&&end>s.start)return false;return true;}
    private void limit(List<Span> spans){check();if(spans.size()>=2000||hits>=100000)throw new IllegalArgumentException("FILTER_MATCH_LIMIT");}
    private String tag(String type,String identity){return identities.computeIfAbsent(type+"\0"+identity,k->{String tag;do{tag="§"+type+"_"+sequence.merge(type,1,Integer::sum)+"§";}while(reserved.contains(tag));reserved.add(tag);generated.add(tag);return tag;});}
    private static String mobile(String value){try{var util=PhoneNumberUtil.getInstance();var n=util.parse(value,"KR");if(!util.isValidNumber(n)||util.getNumberType(n)!=PhoneNumberUtil.PhoneNumberType.MOBILE)return null;return util.format(n,PhoneNumberUtil.PhoneNumberFormat.E164);}catch(Exception ex){return null;}}
    public void writeAttributes(Path directory)throws java.io.IOException{
        Documents.JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("filter_attributes.json").toFile(),Map.of("schema",2,"scope","Only phone, email and explicit literal rules; not complete anonymization","matches",hits,"attributes",attributes()));
    }
}
