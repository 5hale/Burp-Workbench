package com.burpworkbench.modules.replace;

import java.util.ArrayList;
import java.util.List;

/** Local collection backing the table; explicit row operations make ordering easy to verify. */
final class RuleList {
    private final List<RuleDraft> rules = new ArrayList<>();

    RuleList(List<RuleDraft> initial) { rules.addAll(List.copyOf(initial)); }

    List<RuleDraft> snapshot() { return List.copyOf(rules); }

    int size() { return rules.size(); }
    RuleDraft get(int row) { return rules.get(row); }
    int add(RuleDraft rule) { rules.add(rule); return rules.size() - 1; }
    void set(int row, RuleDraft rule) { rules.set(row, rule); }
    RuleDraft remove(int row) { return rules.remove(row); }
    int duplicate(int row) {
        RuleDraft original = get(row);
        rules.add(row + 1, original.renamed(original.name() + " (copy)"));
        return row + 1;
    }
    int move(int row, int direction) {
        int destination = row + direction;
        if (row < 0 || row >= rules.size() || destination < 0 || destination >= rules.size()) return row;
        RuleDraft rule = rules.remove(row);
        rules.add(destination, rule);
        return destination;
    }
}
