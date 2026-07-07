package com.burpworkbench.tests;

import com.burpworkbench.core.selection.SelectionResolver;
import com.burpworkbench.core.selection.SelectionScope;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionScopeTest {
    @Test
    void rootScopeMatchesSameOriginOnly() {
        SelectionScope scope = SelectionScope.fromUrl("https://example.com/").orElseThrow();

        assertTrue(scope.matchesUrl("https://example.com/index.html"));
        assertTrue(scope.matchesUrl("https://example.com/static/app.js"));
        assertFalse(scope.matchesUrl("https://other.example/index.html"));
    }

    @Test
    void directoryScopeMatchesChildrenButNotSiblings() {
        SelectionScope scope = SelectionScope.fromUrl("https://example.com/static/").orElseThrow();

        assertTrue(scope.matchesUrl("https://example.com/static/app.js"));
        assertTrue(scope.matchesUrl("https://example.com/static/css/main.css"));
        assertFalse(scope.matchesUrl("https://example.com/static2/app.js"));
    }

    @Test
    void extensionFileScopeMatchesExactPathOnly() {
        SelectionScope scope = SelectionScope.fromUrl("https://example.com/static/app.js").orElseThrow();

        assertTrue(scope.matchesUrl("https://example.com/static/app.js?v=1"));
        assertFalse(scope.matchesUrl("https://example.com/static/app.js/map"));
    }

    @Test
    void extensionlessPathMatchesExactPathAndChildren() {
        SelectionScope scope = SelectionScope.fromUrl("https://example.com/api/users").orElseThrow();

        assertTrue(scope.matchesUrl("https://example.com/api/users"));
        assertTrue(scope.matchesUrl("https://example.com/api/users/1"));
        assertFalse(scope.matchesUrl("https://example.com/api/users2"));
    }

    @Test
    void siteMapTreeSelectionExpandsToMatchingSubtree() {
        HttpRequestResponse selectedHost = requestResponse("https://example.com/");
        List<HttpRequestResponse> expanded = SelectionResolver.resolveFromSiteMap(
                List.of(selectedHost),
                List.of(
                        requestResponse("https://example.com/"),
                        requestResponse("https://example.com/static/app.js"),
                        requestResponse("https://example.com/api/users"),
                        requestResponse("https://other.example/static/app.js")
                )
        );

        assertEquals(3, expanded.size());
    }

    @Test
    void tableSelectionCanRemainExactWithoutSubtreeResolver() {
        HttpRequestResponse selected = requestResponse("https://example.com/static/app.js");
        List<HttpRequestResponse> exact = new SelectionResolver(null).resolve(List.of(selected), false);

        assertEquals(1, exact.size());
    }

    @Test
    void scopesForKeepsSiteMapTreeHostScopeForSearchPlusSources() {
        List<SelectionScope> scopes = SelectionResolver.scopesFor(List.of(requestResponse("https://example.com/")));

        assertEquals(1, scopes.size());
        assertTrue(scopes.get(0).matchesUrl("https://example.com/path/in/scope"));
        assertFalse(scopes.get(0).matchesUrl("https://outside.example/path"));
    }

    private HttpRequestResponse requestResponse(String url) {
        HttpRequest request = proxy(HttpRequest.class, (method, args) -> switch (method.getName()) {
            case "method" -> "GET";
            case "url" -> url;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "request" -> request;
            default -> defaultValue(method.getReturnType());
        });
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
