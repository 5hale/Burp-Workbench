package com.burpworkbench.platform;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.List;

@FunctionalInterface
public interface ExtractionHandler {
    boolean extract(List<HttpRequestResponse> requestResponses);
}
