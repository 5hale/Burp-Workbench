package com.burpworkbench.modules.search;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RepeaterCacheTest {
    @Test
    void sameMethodAndUrlKeepsOnlyLatestTransaction() {
        HttpRequestResponse first = requestResponse("GET", "https://example.com/api/items");
        HttpRequestResponse latest = requestResponse("GET", "https://example.com/api/items");
        RepeaterCache cache = new RepeaterCache();

        cache.add(first);
        cache.add(latest);

        List<HttpRequestResponse> snapshot = cache.snapshot();
        assertEquals(1, snapshot.size());
        assertSame(latest, snapshot.get(0));
    }

    private HttpRequestResponse requestResponse(String requestMethod, String url) {
        HttpRequest request = proxy(HttpRequest.class, (method, arguments) -> switch (method.getName()) {
            case "method" -> requestMethod;
            case "url" -> url;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "request" -> request;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, ProxyHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> handler.invoke(
                        method,
                        arguments == null ? new Object[0] : arguments
                )
        );
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private interface ProxyHandler {
        Object invoke(java.lang.reflect.Method method, Object[] arguments);
    }
}
