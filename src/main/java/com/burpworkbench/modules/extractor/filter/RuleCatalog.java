package com.burpworkbench.modules.extractor.filter;
import java.util.*;
import static com.burpworkbench.modules.extractor.filter.FilterSettings.*;

/** Curated adaptations, not a claim of equivalence with full upstream detectors. */
public final class RuleCatalog {
    public record Entry(String key,Rule template,String example,String notes){
        public Rule instantiate(){Rule r=template;return new Rule(r.source(),r.tag(),r.scope(),r.match(),UUID.randomUUID().toString(),r.name(),r.category(),r.group(),r.target(),r.contentType(),r.field(),r.exclude(),true,r.numbered(),"");}
    }
    private static final String GL="Gitleaks b58d3f1 · MIT · config/gitleaks.toml · regex adaptation; entropy/allowlists not imported";
    private static final String SD="Sensitive Discoverer 08fe769 · Apache-2.0 · regexes/regex_general.jsonc · capture-group adaptation";
    private static Entry rule(String key,String name,String category,String regex,String tag,int group,Target target,String field,String example,String source,String notes){
        return new Entry(key,new Rule(regex,tag,Scope.BODY,Match.REGEX,key,name,category,group,target,"",field,"",true,true,source),example,notes);
    }
    public static List<Entry> entries(){return ENTRIES;}
    private static final List<Entry> ENTRIES=List.of(
        rule("email","Email","Personal", "(?<![A-Za-z0-9.!#$%&'*+/=?^_`{|}~@-])[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+(?![A-Za-z0-9@-])","§EMAIL§",0,Target.VALUES,"","contact: analyst@example.test","Presidio d4ccc6b · MIT · generic/email_recognizer.py · ASCII/boundary adaptation; no TLD validation","Shape only; user review required. Unicode email/TLD validation is not provided."),
        rule("kr-mobile","KR mobile · labeled field","Personal","(?<![A-Za-z0-9])(?:010[- .]?[0-9]{4}[- .]?[0-9]{4}|\\+82[- .]?10[- .]?[0-9]{4}[- .]?[0-9]{4})(?![A-Za-z0-9])","§PHONE§",0,Target.VALUES,"(?i)^(?:phone|phone_?number|phone_?no|tel|tel_?no|mobile|mobile_?no|휴대폰|전화번호|연락처)$","{\"tel\":\"010-1234-5678\",\"orderId\":\"01012345678\"}","Burp Workbench 0.5.0 pattern; libphonenumber KR metadata research · pattern only","Requires a matching parsed field. No subscriber or library validation in this regex rule."),
        rule("kr-mobile-raw","KR mobile · key + value","Personal","(?i)[\"'](?:tel|phone|mobile|전화번호|휴대폰)[\"']\\s*:\\s*[\"']((?:010[- .]?[0-9]{4}[- .]?[0-9]{4}|\\+82[- .]?10[- .]?[0-9]{4}[- .]?[0-9]{4}))[\"']","§PHONE§",1,Target.DOCUMENT,"","{\"tel\":\"010-1234-5678\"}","Burp Workbench · original example inspired by phone context research","Source match; group 1 changes the value only. Key/quotes are kept."),
        rule("generic-api-key","Generic API key · quoted value","Account","(?i)api.{0,5}key[^&|;?,]{0,32}?['\"]([a-zA-Z0-9_\\-+=/\\\\]{10,})['\"]","§APIKEY§",1,Target.DOCUMENT,"","{\"backend_api_key\":\"DEMOabcdefgh123456\"}",SD,"Broad key-context regex, not a validated credential."),
        rule("generic-secret","Generic secret · quoted value","Account","(?i)secret[^&|;?,]{0,32}?['\"]([a-zA-Z0-9_\\-+=/\\\\]{10,})['\"]","§SECRET§",1,Target.DOCUMENT,"","{\"client_secret\":\"DEMOabcdefgh123456\"}",SD,"Broad secret-context regex. Short passwords are not covered."),
        rule("aws-access","AWS access key ID","Account","\\b((?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16})\\b","§AWSKEY§",1,Target.VALUES,"","AKIAABCDEFGHIJKLMNOP",GL,"Format only; original entropy threshold not applied."),
        rule("github-pat","GitHub PAT","Account","ghp_[0-9a-zA-Z]{36}","§GITHUB§",0,Target.VALUES,"","ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",GL,"Format only; does not verify token validity."),
        rule("github-fine","GitHub fine-grained PAT","Account","github_pat_\\w{82}","§GITHUB§",0,Target.VALUES,"","github_pat_"+"A".repeat(82),GL,"Upstream regex only. Entropy/exclusions omitted."),
        rule("slack-bot","Slack bot token","Account","xoxb-[0-9]{10,13}-[0-9]{10,13}[a-zA-Z0-9-]*","§SLACK§",0,Target.VALUES,"","xoxb-123456789012-123456789012-DEMOabc",GL,"Shape does not prove a live credential."),
        rule("stripe-key","Stripe access key","Financial","\\b((?:sk|rk)_(?:test|live|prod)_[a-zA-Z0-9]{10,99})(?:[\\x60'\"\\s;]|\\\\[nr]|$)","§STRIPE§",1,Target.DOCUMENT,"","sk_test_DEMOabcdefgh123456",GL,"Only capture group 1 is replaced; delimiters kept."),
        rule("private-key","PEM private key block","Account","(?i)-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----[\\s\\S-]{64,}?KEY(?: BLOCK)?-----","§PRIVATEKEY§",0,Target.VALUES,"","-----BEGIN PRIVATE KEY-----\n"+"A".repeat(80)+"\n-----END PRIVATE KEY-----",GL,"Block shape only. For JSON use VALUES to preserve escapes."),
        rule("private-ip","Private IPv4 · strict octets","Environment","(?<![0-9.])(?:10\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){2}|192\\.168(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){2}|172\\.(?:1[6-9]|2[0-9]|3[01])(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){2})(?![0-9.])","§IP§",0,Target.VALUES,"","10.2.20.200 public=8.8.8.8",SD,"Adapted ranges/octets, intentionally opt-in. IP-like versions may still match."),
        rule("quoted-12","Quoted 12 digits · value only","Examples","(?<!\\\\)\"([0-9]{12})\"","§NUMBER§",1,Target.DOCUMENT,"","{\"value\":\"123456789012\",\"long\":\"1234567890123\"}","Burp Workbench · original user-request example","Exact quoted 12-digit string. Group 1 preserves quotes; not a personal-data classifier."),
        rule("adjacent-tel","Adjacent tel label","Examples","(?i)\\btel\\s*[:=]\\s*([0-9]{3}[- ]?[0-9]{4}[- ]?[0-9]{4})(?![0-9])","§PHONE§",1,Target.DOCUMENT,"","tel: 010-1234-5678; orderId: 01012345678","Burp Workbench · original context/capture example","Regex adjacency example; customize allowed whitespace/delimiters."),
        rule("json-password","Password · parsed field","Account","(?s).+","§PASSWORD§",0,Target.VALUES,"(?i)^(?:password|passwd|pwd)$","{\"password\":\"demo-only\",\"description\":\"password policy\"}","Burp Workbench · original field-scoped example inspired by research","Any nonempty value of the selected field. No credential validation.")
    );
    private RuleCatalog(){}
}
