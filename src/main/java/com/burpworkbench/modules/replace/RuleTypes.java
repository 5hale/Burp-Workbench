package com.burpworkbench.modules.replace;

final class RuleTypes {
    static final String REQUEST_HEADER = "Request header";
    static final String REQUEST_BODY = "Request body";
    static final String RESPONSE_HEADER = "Response header";
    static final String RESPONSE_BODY = "Response body";
    static final String REQUEST_PARAM_NAME = "Request param name";
    static final String REQUEST_PARAM_VALUE = "Request param value";
    static final String REQUEST_FIRST_LINE = "Request first line";
    static final String RESPONSE_FIRST_LINE = "Response first line";

    private RuleTypes() {}

    static String[] labels() {
        return new String[] { REQUEST_HEADER, REQUEST_BODY, RESPONSE_HEADER, RESPONSE_BODY,
                REQUEST_PARAM_NAME, REQUEST_PARAM_VALUE, REQUEST_FIRST_LINE, RESPONSE_FIRST_LINE };
    }

    static boolean isParameter(String type) {
        return REQUEST_PARAM_NAME.equals(type) || REQUEST_PARAM_VALUE.equals(type);
    }
}
