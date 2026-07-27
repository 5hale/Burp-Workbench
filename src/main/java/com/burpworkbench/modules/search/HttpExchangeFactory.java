package com.burpworkbench.modules.search;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.util.ArrayList;
import java.util.List;

final class HttpExchangeFactory {
    private HttpExchangeFactory() {
    }

    static List<HttpExchange> fromRequestResponses(String source, List<HttpRequestResponse> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<HttpExchange> exchanges = new ArrayList<>();
        for (HttpRequestResponse item : items) {
            if (item != null) {
                exchanges.add(new HttpExchange(source, item, null));
            }
        }
        return exchanges;
    }

    static HttpRequestResponse requestResponseFromProxyItem(ProxyHttpRequestResponse item) {
        if (item == null) {
            return null;
        }

        HttpRequest request = item.finalRequest();
        if (request == null) {
            request = item.request();
        }
        if (request == null) {
            return null;
        }

        HttpResponse response = item.hasResponse() ? item.response() : null;
        return HttpRequestResponse.httpRequestResponse(request, response);
    }
}
