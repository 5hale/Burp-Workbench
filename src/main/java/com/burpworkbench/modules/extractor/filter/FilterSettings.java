package com.burpworkbench.modules.extractor.filter;
import java.util.*;
import java.util.regex.Pattern;

/** Legacy booleans retained for source compatibility, not used as hidden detectors. */
public record FilterSettings(boolean phone, boolean email, List<Rule> rules) {
    public enum Scope { ALL, BODY, METADATA }
    public enum Match { REGEX, LITERAL, HOST }
    public enum Target { VALUES, DOCUMENT }
    public record Rule(String source,String tag,Scope scope,Match match,String id,String name,
        String category,int group,Target target,String contentType,String field,String exclude,
        boolean enabled,boolean numbered,String provenance) {
        public Rule {
            source=bounded(source,4096,"Pattern");tag=bounded(tag,128,"Tag");name=bounded(name,100,"Name");
            category=bounded(category,80,"Category");contentType=bounded(contentType,512,"Content-Type");
            field=bounded(field,512,"Field");exclude=bounded(exclude,1024,"Exclude");provenance=bounded(provenance,2000,"Description");
            Objects.requireNonNull(scope);Objects.requireNonNull(match);Objects.requireNonNull(target);
            if(id==null||!id.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid rule ID");
            if(group<0||group>100)throw new IllegalArgumentException("Group must be 0–100");
            if(enabled){
                if(source.isEmpty()||name.isBlank())throw new IllegalArgumentException("Name and pattern are required");
                tag=normalizeTag(tag);
                if(match==Match.REGEX){if(group>Pattern.compile(source).matcher("").groupCount())throw new IllegalArgumentException("Capture group does not exist");}
                else if(group!=0)throw new IllegalArgumentException("Capture group is only used by Regex");
                if(match==Match.HOST&&!source.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))throw new IllegalArgumentException("HOST needs a host without scheme/port/path");
                for(String condition:List.of(contentType,field,exclude))if(!condition.isEmpty())Pattern.compile(condition);
                if(target==Target.DOCUMENT&&!field.isEmpty())throw new IllegalArgumentException("Field condition requires VALUES mode");
            }
        }
        public Rule(String source,String tag,Scope scope,Match match){this(source,tag,scope,match,UUID.randomUUID().toString(),"Custom rule","Custom",0,Target.VALUES,"","","",true,false,"");}
        public Rule copy(){return new Rule(source,tag,scope,match,UUID.randomUUID().toString(),name.length()>94?name:name+" copy",category,group,target,contentType,field,exclude,enabled,numbered,provenance);}
        public static Rule draft(){return new Rule("","§VALUE§",Scope.BODY,Match.REGEX,UUID.randomUUID().toString(),"New rule","Custom",0,Target.VALUES,"","","",false,true,"");}
    }
    public FilterSettings {rules=List.copyOf(rules);if(rules.size()>500)throw new IllegalArgumentException("Maximum 500 rules");if(rules.stream().map(Rule::id).distinct().count()!=rules.size())throw new IllegalArgumentException("Duplicate rule IDs");}
    public static FilterSettings defaults(){return new FilterSettings(false,false,List.of());}
    static String bounded(String value,int limit,String label){Objects.requireNonNull(value,label);if(value.length()>limit)throw new IllegalArgumentException(label+" is too long");return value;}
    public static String normalizeTag(String value){String tag=Objects.requireNonNull(value).trim();if(!tag.startsWith("§"))tag="§"+tag+"§";if(!tag.matches("§[\\p{L}\\p{N}_-]{1,64}§"))throw new IllegalArgumentException("Tag: 1–64 letters/digits/_/-; e.g. §PHONE§");return tag;}
}
