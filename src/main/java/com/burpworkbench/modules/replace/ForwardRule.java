package com.burpworkbench.modules.replace;

import java.util.Objects;

/** Separate UI/traffic model. RuleDraft is used only as the versioned storage envelope. */
record ForwardRule(boolean enabled, String name, String origin, String path, String destination, String destinationPath) {
    ForwardRule {
        Objects.requireNonNull(name); Objects.requireNonNull(origin);
        Objects.requireNonNull(path); Objects.requireNonNull(destination);
        Objects.requireNonNull(destinationPath);
    }
    ForwardRule(boolean enabled,String name,String origin,String path,String destination) {
        this(enabled,name,origin,path,destination,"");
    }
    ForwardRule toggled(boolean on) { return new ForwardRule(on,name,origin,path,destination,destinationPath); }
    RuleDraft stored() { return new RuleDraft(enabled,name,"Forward",origin,path,destinationPath,destination,false); }
    static ForwardRule from(RuleDraft rule) {
        if(!"Forward".equals(rule.target())||rule.regex())
            throw new IllegalArgumentException("Unsupported Forward rule");
        return new ForwardRule(rule.enabled(),rule.name(),rule.url(),rule.path(),rule.replacement(),rule.match());
    }
}
