package com.burpworkbench.tests;

import com.burpworkbench.core.http.HttpExchangeFactory;

import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertNull;

class HttpExchangeFactoryTest {
    @Test
    void returnsNullForMissingProxyItem() {
        assertNull(HttpExchangeFactory.requestResponseFromProxyItem(null));
    }

    @Test
    void returnsNullWhenProxyItemHasNoUsableRequest() {
        ProxyHttpRequestResponse item = proxy(ProxyHttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "finalRequest" -> null;
            case "request" -> null;
            case "hasResponse" -> false;
            default -> defaultValue(method.getReturnType());
        });

        assertNull(HttpExchangeFactory.requestResponseFromProxyItem(item));
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
