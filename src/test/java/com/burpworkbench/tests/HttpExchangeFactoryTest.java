package com.burpworkbench.tests;

import com.burpworkbench.core.http.HttpExchange;
import com.burpworkbench.core.http.HttpExchangeFactory;

import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpExchangeFactoryTest {
    @Test
    void proxyHistorySkipsMalformedItemsWithoutThrowing() {
        ProxyHttpRequestResponse malformed = proxy(ProxyHttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "finalRequest" -> throwIllegalUrl();
            case "request" -> null;
            case "hasResponse" -> false;
            default -> defaultValue(method.getReturnType());
        });

        List<HttpExchange> exchanges = HttpExchangeFactory.fromProxyHistory(List.of(malformed));

        assertEquals(0, exchanges.size());
    }

    private Object throwIllegalUrl() {
        throw new IllegalArgumentException("Illegal char <:> at index 24: z5N6o6SqQ_yxbzUGR-eUgw==:HbTYZKT3jDOIR6OGjTmFkXe");
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, ProxyHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(method, args == null ? new Object[0] : args)
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
        Object invoke(java.lang.reflect.Method method, Object[] args);
    }
}
