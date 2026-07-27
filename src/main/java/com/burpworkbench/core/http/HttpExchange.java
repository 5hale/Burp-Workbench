package com.burpworkbench.core.http;

import burp.api.montoya.http.message.HttpRequestResponse;

import java.time.ZonedDateTime;

public record HttpExchange(
        String source,
        HttpRequestResponse requestResponse,
        ZonedDateTime time
) {
    public HttpExchange copyToTempFile() {
        HttpRequestResponse stored = requestResponse.copyToTempFile();
        if (stored == null) {
            throw new IllegalStateException("Montoya did not create temporary-file storage");
        }
        return new HttpExchange(source, stored, time);
    }

    public String host() {
        try {
            return requestResponse.httpService().host();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    public String method() {
        try {
            return requestResponse.request().method();
        } catch (RuntimeException exception) {
            return "GET";
        }
    }

    public String url() {
        try {
            return requestResponse.request().url();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    public int statusCode() {
        try {
            return requestResponse.hasResponse() && requestResponse.response() != null
                    ? requestResponse.response().statusCode()
                    : -1;
        } catch (RuntimeException exception) {
            return -1;
        }
    }
}
