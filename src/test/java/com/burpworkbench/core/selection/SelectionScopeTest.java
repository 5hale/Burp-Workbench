package com.burpworkbench.core.selection;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.sitemap.SiteMap;
import burp.api.montoya.sitemap.SiteMapFilter;
import burp.api.montoya.sitemap.SiteMapNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void subtreeUsesFilteredSiteMapBatchAndPreservesFirstOccurrenceOrder() {
        HttpRequestResponse selected = requestResponse("https://example.com/");
        HttpRequestResponse first = requestResponse("https://example.com/a.js");
        HttpRequestResponse duplicate = requestResponse("https://example.com/a.js");
        HttpRequestResponse outside = requestResponse("https://outside.example/b.js");
        AtomicInteger filteredCalls = new AtomicInteger();
        AtomicInteger unfilteredCalls = new AtomicInteger();
        SiteMap siteMap = proxy(SiteMap.class, (method, args) -> {
            if (!"requestResponses".equals(method.getName())) {
                return defaultValue(method.getReturnType());
            }
            if (args.length == 0) {
                unfilteredCalls.incrementAndGet();
                return List.of(first, duplicate, outside);
            }
            filteredCalls.incrementAndGet();
            SiteMapFilter filter = (SiteMapFilter) args[0];
            List<HttpRequestResponse> result = new ArrayList<>();
            assertTrue(filter.matches(siteMapNode(first)));
            for (HttpRequestResponse item : List.of(first, duplicate, outside)) {
                if (filter.matches(siteMapNode(item))) {
                    result.add(item);
                }
            }
            return result;
        });
        MontoyaApi api = proxy(MontoyaApi.class, (method, args) ->
                "siteMap".equals(method.getName()) ? siteMap : defaultValue(method.getReturnType())
        );

        List<HttpRequestResponse> resolved = new SelectionResolver(api).resolve(
                List.of(selected),
                true,
                () -> false
        );

        assertEquals(List.of(first, selected), resolved);
        assertEquals(1, filteredCalls.get());
        assertEquals(0, unfilteredCalls.get());
    }

    @Test
    void deduplicationUsesMethodAndUrlWithoutConflatingDifferentMethods() {
        HttpRequestResponse firstGet = requestResponse("GET", "https://example.com/very/long/path");
        HttpRequestResponse post = requestResponse("POST", "https://example.com/very/long/path");
        HttpRequestResponse duplicateGet = requestResponse("GET", "https://example.com/very/long/path");

        List<HttpRequestResponse> resolved = new SelectionResolver(null).resolve(
                List.of(firstGet, post, duplicateGet),
                false
        );

        assertEquals(List.of(firstGet, post), resolved);
    }

    @Test
    void subtreePredicateHonorsCancellationBetweenNodes() {
        HttpRequestResponse selected = requestResponse("https://example.com/");
        HttpRequestResponse first = requestResponse("https://example.com/a.js");
        HttpRequestResponse second = requestResponse("https://example.com/b.js");
        AtomicBoolean cancelled = new AtomicBoolean();
        SiteMap siteMap = proxy(SiteMap.class, (method, args) -> {
            if ("requestResponses".equals(method.getName()) && args.length == 1) {
                SiteMapFilter filter = (SiteMapFilter) args[0];
                filter.matches(siteMapNode(first));
                cancelled.set(true);
                filter.matches(siteMapNode(second));
                return List.of(first, second);
            }
            return defaultValue(method.getReturnType());
        });
        MontoyaApi api = proxy(MontoyaApi.class, (method, args) ->
                "siteMap".equals(method.getName()) ? siteMap : defaultValue(method.getReturnType())
        );

        assertThrows(
                CancellationException.class,
                () -> new SelectionResolver(api).resolve(List.of(selected), true, cancelled::get)
        );
    }

    private SiteMapNode siteMapNode(HttpRequestResponse item) {
        return proxy(SiteMapNode.class, (method, args) -> switch (method.getName()) {
            case "url" -> item.request().url();
            case "requestResponse" -> item;
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequestResponse requestResponse(String url) {
        return requestResponse("GET", url);
    }

    private HttpRequestResponse requestResponse(String requestMethod, String url) {
        HttpRequest request = proxy(HttpRequest.class, (reflectedMethod, args) -> switch (reflectedMethod.getName()) {
            case "method" -> requestMethod;
            case "url" -> url;
            default -> defaultValue(reflectedMethod.getReturnType());
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
