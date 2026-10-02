package com.burpworkbench.modules.extractor.filter;

import java.util.*;

public record FilterSettings(boolean phone, boolean email, List<Rule> rules) {
    public enum Scope { ALL, BODY, METADATA }
    public enum Match { LITERAL, HOST }
    public record Rule(String source, String tag, Scope scope, Match match) {
        public Rule {
            if(source==null||source.isEmpty()||source.length()>4096||source.indexOf('§')>=0)
                throw new IllegalArgumentException("원문은 1~4096자이며 § 문자는 사용할 수 없습니다.");
            tag=normalizeTag(tag);
            Objects.requireNonNull(scope);Objects.requireNonNull(match);
            if(match==Match.HOST&&!source.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))
                throw new IllegalArgumentException("HOST 규칙에는 scheme/port/path 없이 호스트만 입력하세요.");
        }
    }
    public FilterSettings {
        rules=List.copyOf(rules);
        if(rules.size()>200)throw new IllegalArgumentException("규칙은 최대 200개입니다.");
    }
    public static FilterSettings defaults(){return new FilterSettings(true,true,List.of());}
    public static String normalizeTag(String value){
        String tag=Objects.requireNonNull(value).trim();
        if(!tag.startsWith("§"))tag="§"+tag+"§";
        if(!tag.matches("§[\\p{L}\\p{N}_-]{1,64}§"))
            throw new IllegalArgumentException("태그는 문자/숫자/_/- 1~64자입니다. 예: §회사§");
        return tag;
    }
}
