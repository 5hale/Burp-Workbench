package com.burpworkbench.modules.extractor.filter;

import java.util.Locale;
import java.util.Map;

/** Parser label hints only. No name, account, IP or other broad detectors. */
final class Rules {
    static final Map<String,String> FIELDS=Map.of("phone","PHONE","phonenumber","PHONE","tel","PHONE","mobile","PHONE","휴대폰","PHONE","전화번호","PHONE","email","EMAIL","이메일","EMAIL");
    static String key(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]","");}
}
