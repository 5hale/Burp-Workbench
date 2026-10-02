package com.burpworkbench.modules.replace;

import burp.api.montoya.persistence.PersistedObject;
import java.util.List;

/** Independent project namespace; never migrates or overwrites Replace rules. */
final class ForwardSession {
    static final String KEY="burpworkbench.forward.v1.state";
    static final String BACKUP="burpworkbench.forward.v1.backup";
    record State(List<ForwardRule> rules, boolean enabled) {
        State { rules=List.copyOf(rules); }
        static State empty(){return new State(List.of(),false);}
    }
    private final RuleSession storage;
    private volatile State state;
    private boolean invalid;
    ForwardSession(PersistedObject project) {
        storage=new RuleSession(new RuleStore(project,KEY,BACKUP,new RuleStore.State(List.of(),false)));
        try { state=new State(storage.snapshot().rules().stream().map(ForwardRule::from).toList(),storage.snapshot().enabled()); }
        catch(IllegalArgumentException failure){state=State.empty();invalid=true;}
    }
    State snapshot(){return state;}
    void rulesChanged(List<ForwardRule> rules){
        state=new State(rules,state.enabled());storage.rulesChanged(rules.stream().map(ForwardRule::stored).toList());
    }
    void enabledChanged(boolean enabled){state=new State(state.rules(),enabled);storage.enabledChanged(enabled);}
    void flush(){if(!invalid)storage.flush();}
    String status(){return invalid?"Load failed · stored data preserved":storage.status();}
}
