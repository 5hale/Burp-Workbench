package com.burpworkbench.modules.extractor.filter;

import burp.api.montoya.persistence.PersistedObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;

/** Project-local configuration, including unfinished drafts. Never imports demo state. */
final class FilterRuleStore {
    static final String KEY="burpworkbench.extractor.filter.v1.state";
    static final String BACKUP="burpworkbench.extractor.filter.v1.backup";
    static final int MAX_CHARS=8*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper();
    record Draft(String source,String tag,FilterSettings.Scope scope,FilterSettings.Match match){
        Draft {
            Objects.requireNonNull(source);Objects.requireNonNull(tag);
            Objects.requireNonNull(scope);Objects.requireNonNull(match);
            if(source.length()>4096||tag.length()>128)throw new IllegalArgumentException("Rule storage limit exceeded");
        }
    }
    record State(int schema,boolean phone,boolean email,List<Draft> rules){
        State {
            if(schema!=1||rules==null||rules.size()>200)throw new IllegalArgumentException("Unsupported filter configuration");
            rules=List.copyOf(rules);
        }
        static State empty(){return new State(1,true,true,List.of());}
    }
    private final PersistedObject data;
    private String loadedRaw;
    private boolean loaded;
    FilterRuleStore(PersistedObject data){this.data=Objects.requireNonNull(data);}
    synchronized State load(){
        loaded=false;
        try{
            String raw=data.getString(KEY);
            if(raw!=null&&(raw.isEmpty()||raw.length()>MAX_CHARS))throw new IllegalArgumentException();
            State state=raw==null?State.empty():JSON.readValue(raw,State.class);
            if(state==null)throw new IllegalArgumentException();
            loadedRaw=raw;loaded=true;return state;
        }catch(Exception failure){throw new IllegalStateException("Filter rules could not be loaded; stored data was left unchanged.");}
    }
    synchronized void save(State state){
        if(!loaded)throw new IllegalStateException("Filter rules are not loaded; reload before saving.");
        final String encoded;
        try{encoded=JSON.writeValueAsString(Objects.requireNonNull(state));}
        catch(Exception failure){throw new IllegalStateException("Filter rules could not be encoded.");}
        if(encoded.length()>MAX_CHARS)throw new IllegalStateException("Filter rule storage limit exceeded.");
        try{
            String current=data.getString(KEY);
            if(!Objects.equals(current,loadedRaw))throw new IllegalStateException();
            if(encoded.equals(current))return;
            if(current!=null)data.setString(BACKUP,current);
            data.setString(KEY,encoded);loadedRaw=encoded;
        }catch(RuntimeException failure){
            loaded=false;
            throw new IllegalStateException("Filter rules could not be saved; reload before retrying.");
        }
    }
}
