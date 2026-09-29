package com.burpworkbench.modules.replace;

import java.util.List;

/** Optional starting rules for a project with no saved Replace++ state. All start disabled. */
final class DefaultRules {
    private static final List<RuleDraft> RULES = List.of(
            removal("Remove If-Modified-Since", "(?im)^If-Modified-Since.*$"),
            removal("Remove If-None-Match", "(?im)^If-None-Match.*$"),
            removal("Remove Sec-CH headers", "(?im)(s|S)ec-(c|C)h.*"),
            removal("Remove Sec-Fetch headers", "(?im)(s|S)ec-(f|F)etch.*"),
            removal("Remove Cache-Control (optional)", "(?im)^Cache-Control:.*$"),
            removal("Remove Pragma (optional)", "(?im)^Pragma:.*$")
    );

    private DefaultRules() {}

    static List<RuleDraft> rules() { return RULES; }

    private static RuleDraft removal(String name, String match) {
        return new RuleDraft(false, name, RuleTypes.REQUEST_HEADER, "", "", match, "", true);
    }
}
