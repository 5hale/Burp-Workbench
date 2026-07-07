package com.burpworkbench.core.http;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.organizer.OrganizerItem;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.util.ArrayList;
import java.util.List;

public final class HttpExchangeFactory {
    private HttpExchangeFactory() {
    }

    public static List<HttpExchange> fromRequestResponses(String source, List<HttpRequestResponse> items) {
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

    public static List<HttpExchange> fromProxyHistory(List<ProxyHttpRequestResponse> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<HttpExchange> exchanges = new ArrayList<>();
        for (ProxyHttpRequestResponse item : items) {
            try {
                HttpRequestResponse requestResponse = fromProxyItem(item);
                if (requestResponse != null) {
                    exchanges.add(new HttpExchange("Proxy", requestResponse, safeTime(item)));
                }
            } catch (RuntimeException ignored) {
                // Burp can contain malformed or partially parsed history entries. One bad row must not abort Search++.
            }
        }
        return exchanges;
    }

    public static List<HttpExchange> fromOrganizerItems(List<OrganizerItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<HttpExchange> exchanges = new ArrayList<>();
        for (OrganizerItem item : items) {
            if (item != null) {
                exchanges.add(new HttpExchange("Organizer", item, null));
            }
        }
        return exchanges;
    }

    private static HttpRequestResponse fromProxyItem(ProxyHttpRequestResponse item) {
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

    private static java.time.ZonedDateTime safeTime(ProxyHttpRequestResponse item) {
        try {
            return item.time();
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
