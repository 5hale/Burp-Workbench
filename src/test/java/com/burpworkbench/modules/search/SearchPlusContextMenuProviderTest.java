package com.burpworkbench.modules.search;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchPlusContextMenuProviderTest {
    @Test
    void exactSelectionPreservesSameEndpointTransactionsInInputOrder() {
        HttpRequestResponse first = requestResponse("GET", "https://example.com/api/items");
        HttpRequestResponse second = requestResponse("GET", "https://example.com/api/items");
        HttpRequestResponse third = requestResponse("POST", "https://example.com/api/items");
        List<HttpRequestResponse> selectedItems = new ArrayList<>(List.of(first, second, third));

        SearchPlusContextMenuProvider.ContextSelection selection =
                SearchPlusContextMenuProvider.contextSelection(selectedItems, false);
        selectedItems.clear();

        assertEquals(3, selection.items().size());
        assertSame(first, selection.items().get(0));
        assertSame(second, selection.items().get(1));
        assertSame(third, selection.items().get(2));
        assertTrue(selection.scopes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> selection.items().add(first));
    }

    @Test
    void siteMapTreeSelectionCarriesScopesWithoutExactItems() {
        HttpRequestResponse selectedHost = requestResponse("GET", "https://example.com/");

        SearchPlusContextMenuProvider.ContextSelection selection =
                SearchPlusContextMenuProvider.contextSelection(List.of(selectedHost), true);

        assertTrue(selection.items().isEmpty());
        assertEquals(1, selection.scopes().size());
        assertTrue(selection.scopes().get(0).matchesUrl("https://example.com/path/in/tree"));
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
