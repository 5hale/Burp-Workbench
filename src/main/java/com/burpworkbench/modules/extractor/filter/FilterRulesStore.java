package com.burpworkbench.modules.extractor.filter;

import burp.api.montoya.persistence.PersistedObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.burpworkbench.modules.extractor.filter.FilterSettings.*;

/** Product rule schema. Reads legacy product data only; demo state is never imported. */
final class FilterRulesStore {
    static final String KEY="burpworkbench.extractor.rules.v1.state",BACKUP=KEY+".backup";
    static final int MAX=4*1024*1024;
    record State(int schema,List<Rule> rules){
        State {if(schema!=1)throw new IllegalArgumentException("Unsupported rules schema");rules=new FilterSettings(false,false,rules).rules();}
    }
    static final ObjectMapper JSON=new ObjectMapper();
    private final PersistedObject data;
    private String loaded,legacyRaw;
    private boolean ready;
    FilterRulesStore(PersistedObject data){this.data=Objects.requireNonNull(data);}
    List<Rule> load(){
        ready=false;
        try{
            String raw=data.getString(KEY);legacyRaw=null;
            List<Rule> rules;
            if(raw!=null)rules=decode(raw);
            else{
                legacyRaw=data.getString(FilterRuleStore.KEY);
                rules=legacyRaw==null?List.of():legacy(new FilterRuleStore(data).load());
            }
            loaded=raw;ready=true;return rules;
        }catch(Exception e){throw new IllegalStateException("Rules could not be loaded; saved data preserved",e);}
    }
    void save(List<Rule> rules){
        if(!ready)throw new IllegalStateException("Reload saved rules before saving");
        try{
            String now=data.getString(KEY);
            if(!Objects.equals(now,loaded)||loaded==null&&!Objects.equals(legacyRaw,data.getString(FilterRuleStore.KEY)))throw new IllegalStateException("Saved rules changed outside this editor");
            String encoded=encode(rules);if(encoded.equals(now))return;
            if(now!=null)data.setString(BACKUP,now);
            data.setString(KEY,encoded);loaded=encoded;legacyRaw=null;
        }catch(Exception e){ready=false;throw new IllegalStateException("Rules could not be saved; saved data preserved",e);}
    }
    private static List<Rule> legacy(FilterRuleStore.State old){
        List<Rule> rules=new ArrayList<>();int index=0;
        for(var draft:old.rules()){
            String id=UUID.nameUUIDFromBytes(("extractor-legacy-"+index).getBytes(StandardCharsets.UTF_8)).toString();
            boolean valid=!draft.source().isEmpty();
            try{normalizeTag(draft.tag());if(draft.match()==Match.HOST&&!draft.source().matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))valid=false;}catch(IllegalArgumentException e){valid=false;}
            rules.add(new Rule(draft.source(),draft.tag(),draft.scope(),draft.match(),id,"Imported rule "+(++index),"Custom",0,Target.VALUES,"","","",valid,false,"Imported from 0.5.0. Incomplete rules remain OFF."));
        }
        // Existing saved choices become visible editable rules, not hidden detector branches.
        if(old.phone())for(String key:List.of("kr-mobile","kr-mobile-raw","adjacent-tel"))rules.add(legacyTemplate(key));
        if(old.email())rules.add(legacyTemplate("email"));
        return List.copyOf(rules);
    }
    private static Rule legacyTemplate(String key){
        Rule r=RuleCatalog.entries().stream().filter(e->e.key().equals(key)).findFirst().orElseThrow().template();
        return new Rule(r.source(),r.tag(),Scope.ALL,r.match(),"imported-"+key,r.name(),r.category(),r.group(),r.target(),r.contentType(),r.field(),r.exclude(),true,r.numbered(),"Imported 0.5.0 detector choice. Regex template; review coverage before sharing.");
    }
    static String encode(List<Rule> rules)throws Exception{String json=JSON.writerWithDefaultPrettyPrinter().writeValueAsString(new State(1,rules));if(json.length()>MAX)throw new IllegalArgumentException("Rules file exceeds 4 MiB");return json;}
    static List<Rule> decode(String json)throws Exception{if(json==null||json.isBlank()||json.length()>MAX)throw new IllegalArgumentException("Invalid rules file size");State state=JSON.readValue(json,State.class);if(state==null)throw new IllegalArgumentException("Null rules state");return state.rules();}
    static List<Rule> read(Path file)throws Exception{if(Files.size(file)>MAX)throw new IllegalArgumentException("Rules file exceeds 4 MiB");return decode(Files.readString(file));}
}
