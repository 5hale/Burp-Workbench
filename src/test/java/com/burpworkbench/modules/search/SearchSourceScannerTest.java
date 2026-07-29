package com.burpworkbench.modules.search;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHistoryFilter;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.sitemap.SiteMap;
import burp.api.montoya.sitemap.SiteMapFilter;
import burp.api.montoya.sitemap.SiteMapNode;
import com.burpworkbench.core.selection.SelectionScope;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchSourceScannerTest {
    @Test
    void targetCandidatesArePartitionedAndEachMessageIsMatchedOnce() {
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicInteger nativeMatches = new AtomicInteger();
        AtomicInteger largestBatch = new AtomicInteger();
        List<SiteMapNode> nodes = new ArrayList<>();
        for (int index = 0; index < 96; index++) {
            String url = "https://example.com/item/" + index;
            nodes.add(node(url, requestResponse(url, nativeMatches)));
        }
        String duplicateUrl = "https://example.com/item/0";
        nodes.add(node(duplicateUrl, requestResponse(duplicateUrl, nativeMatches)));
        SiteMap siteMap = siteMap(nodes, sourceCalls, largestBatch);
        SearchSourceScanner scanner = scanner(siteMap);
        SearchEngine.PreparedSearch preparedSearch =
                new SearchEngine().prepare(targetOptions("needle"));
        List<String> matchedUrls = new ArrayList<>();

        scanner.scan(
                targetOptions("needle"),
                preparedSearch,
                () -> false,
                exchange -> {
                    matchedUrls.add(exchange.url());
                    return true;
                }
        );

        assertEquals(SearchSourceScanner.PARTITION_COUNT, sourceCalls.get());
        assertEquals(nodes.size(), nativeMatches.get());
        assertEquals(96, matchedUrls.size());
        assertEquals(96, new HashSet<>(matchedUrls).size());
        assertTrue(largestBatch.get() < nodes.size());
        assertTrue(SearchSourceScanner.partitionFor(Long.MIN_VALUE) >= 0);
    }

    @Test
    void cancellationStopsBeforeTheNextPartition() {
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicInteger nativeMatches = new AtomicInteger();
        AtomicInteger largestBatch = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        String url = urlInPartition(0);
        SiteMap siteMap = siteMap(
                List.of(node(url, requestResponse(url, nativeMatches))),
                sourceCalls,
                largestBatch
        );
        SearchSourceScanner scanner = scanner(siteMap);
        SearchEngine.PreparedSearch preparedSearch =
                new SearchEngine().prepare(targetOptions("needle"));

        scanner.scan(
                targetOptions("needle"),
                preparedSearch,
                cancelled::get,
                exchange -> {
                    cancelled.set(true);
                    return true;
                }
        );

        assertEquals(1, sourceCalls.get());
        assertEquals(1, nativeMatches.get());
    }

    @Test
    void proxyUsesAllPartitionsIncludingNegativeIdsAndMatchesEachItemOnce() {
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicInteger nativeMatches = new AtomicInteger();
        AtomicInteger convertedItems = new AtomicInteger();
        AtomicInteger largestBatch = new AtomicInteger();
        List<ProxyHttpRequestResponse> items = new ArrayList<>();
        Map<ProxyHttpRequestResponse, Long> historyIds = new IdentityHashMap<>();
        for (int id = -48; id < 48; id++) {
            String url = "https://example.com/proxy/" + (id + 48);
            ProxyHttpRequestResponse item = proxyItem(id, url, nativeMatches);
            items.add(item);
            historyIds.put(item, (long) id);
        }
        ProxyHttpRequestResponse duplicate =
                proxyItem(-49, "https://example.com/proxy/0", nativeMatches);
        items.add(duplicate);
        historyIds.put(duplicate, -49L);

        burp.api.montoya.proxy.Proxy proxyApi =
                proxyHistory(items, sourceCalls, largestBatch);
        Function<ProxyHttpRequestResponse, HttpRequestResponse> converter = item -> {
            convertedItems.incrementAndGet();
            return requestResponse(item.finalRequest().url(), new AtomicInteger());
        };
        ProxyHistoryPartitioner partitioner =
                ProxyHistoryPartitioner.historyIdForTesting(historyIds::get);
        SearchSourceScanner scanner =
                scanner(null, proxyApi, List.of(), converter, partitioner);
        SearchEngine.PreparedSearch preparedSearch =
                new SearchEngine().prepare(proxyOptions("needle"));
        List<String> matchedUrls = new ArrayList<>();

        scanner.scan(
                proxyOptions("needle"),
                preparedSearch,
                () -> false,
                exchange -> {
                    matchedUrls.add(exchange.url());
                    return true;
                }
        );

        assertEquals(SearchSourceScanner.PARTITION_COUNT, sourceCalls.get());
        assertEquals(items.size(), nativeMatches.get());
        assertEquals(items.size(), convertedItems.get());
        assertEquals(96, matchedUrls.size());
        assertEquals(96, new HashSet<>(matchedUrls).size());
        assertTrue(largestBatch.get() < items.size());
    }

    @Test
    void historyIdFailureIsolatesOnlyThatItemAndDoesNotChangeMode() {
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicInteger nativeMatches = new AtomicInteger();
        ProxyHttpRequestResponse malformed =
                proxyItem(1, "https://example.com/proxy/bad", nativeMatches);
        ProxyHttpRequestResponse valid =
                proxyItem(2, "https://example.com/proxy/good", nativeMatches);
        List<ProxyHttpRequestResponse> items = List.of(malformed, valid);
        ProxyHistoryPartitioner partitioner =
                ProxyHistoryPartitioner.historyIdForTesting(item -> {
                    if (item == malformed) {
                        throw new IllegalStateException("bad history id");
                    }
                    return -2L;
                });
        SearchSourceScanner scanner = scanner(
                null,
                proxyHistory(items, sourceCalls, new AtomicInteger()),
                List.of(),
                item -> requestResponse(item.finalRequest().url(), new AtomicInteger()),
                partitioner
        );
        SearchOptions options = proxyOptions("needle");
        SearchEngine.PreparedSearch preparedSearch = new SearchEngine().prepare(options);
        List<String> matchedUrls = new ArrayList<>();

        SearchSourceScanner.ScanStatistics statistics = scanner.scan(
                options,
                preparedSearch,
                () -> false,
                exchange -> {
                    matchedUrls.add(exchange.url());
                    return true;
                }
        );

        assertEquals(SearchSourceScanner.PARTITION_COUNT, sourceCalls.get());
        assertEquals(SearchSourceScanner.PARTITION_COUNT, statistics.malformedItems());
        assertEquals(List.of("https://example.com/proxy/good"), matchedUrls);
        assertEquals(ProxyHistoryPartitioner.Mode.HISTORY_ID, partitioner.mode());
    }

    @Test
    void collidingPartitionKeysDoNotRemoveDistinctProxyResults() {
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicInteger nativeMatches = new AtomicInteger();
        List<ProxyHttpRequestResponse> items = List.of(
                proxyItem(1, "https://example.com/proxy/one", nativeMatches),
                proxyItem(2, "https://example.com/proxy/two", nativeMatches)
        );
        ProxyHistoryPartitioner partitioner =
                ProxyHistoryPartitioner.historyIdForTesting(item -> 7L);
        SearchSourceScanner scanner = scanner(
                null,
                proxyHistory(items, sourceCalls, new AtomicInteger()),
                List.of(),
                item -> requestResponse(item.finalRequest().url(), new AtomicInteger()),
                partitioner
        );
        SearchOptions options = proxyOptions("needle");
        List<String> matchedUrls = new ArrayList<>();

        scanner.scan(
                options,
                new SearchEngine().prepare(options),
                () -> false,
                exchange -> {
                    matchedUrls.add(exchange.url());
                    return true;
                }
        );

        assertEquals(SearchSourceScanner.PARTITION_COUNT, sourceCalls.get());
        assertEquals(
                Set.of(
                        "https://example.com/proxy/one",
                        "https://example.com/proxy/two"
                ),
                new HashSet<>(matchedUrls)
        );
        assertEquals(2, matchedUrls.size());
    }

    @Test
    void targetAndProxySourcesAreBothVisitedInOneRun() {
        AtomicInteger targetCalls = new AtomicInteger();
        AtomicInteger proxyCalls = new AtomicInteger();
        AtomicInteger nativeMatches = new AtomicInteger();
        String targetUrl = "https://example.com/target/one";
        String proxyUrl = "https://example.com/proxy/one";
        SiteMap siteMap = siteMap(
                List.of(node(targetUrl, requestResponse(targetUrl, nativeMatches))),
                targetCalls,
                new AtomicInteger()
        );
        ProxyHttpRequestResponse proxyItem =
                proxyItem(-3, proxyUrl, nativeMatches);
        SearchSourceScanner scanner = scanner(
                siteMap,
                proxyHistory(
                        List.of(proxyItem),
                        proxyCalls,
                        new AtomicInteger()
                ),
                List.of(),
                item -> requestResponse(item.finalRequest().url(), new AtomicInteger()),
                ProxyHistoryPartitioner.historyIdForTesting(item -> -3L)
        );
        SearchOptions options = targetAndProxyOptions("needle");
        List<HttpExchange> matches = new ArrayList<>();

        scanner.scan(
                options,
                new SearchEngine().prepare(options),
                () -> false,
                exchange -> {
                    matches.add(exchange);
                    return true;
                }
        );

        assertEquals(SearchSourceScanner.PARTITION_COUNT, targetCalls.get());
        assertEquals(SearchSourceScanner.PARTITION_COUNT, proxyCalls.get());
        assertEquals(2, nativeMatches.get());
        assertEquals(Set.of("Target", "Proxy"), matches.stream()
                .map(HttpExchange::source)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(targetUrl, proxyUrl), matches.stream()
                .map(HttpExchange::url)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void contextScopeRestrictsBothTargetAndProxySources() {
        AtomicInteger nativeMatches = new AtomicInteger();
        String targetInside = "https://example.com/allowed/target";
        String targetOutside = "https://example.com/outside/target";
        String proxyInside = "https://example.com/allowed/proxy";
        String proxyOutside = "https://other.example/allowed/proxy";
        SiteMap siteMap = siteMap(
                List.of(
                        node(targetInside, requestResponse(targetInside, nativeMatches)),
                        node(targetOutside, requestResponse(targetOutside, nativeMatches))
                ),
                new AtomicInteger(),
                new AtomicInteger()
        );
        burp.api.montoya.proxy.Proxy proxyApi = proxyHistory(
                List.of(
                        proxyItem(1, proxyInside, nativeMatches),
                        proxyItem(2, proxyOutside, nativeMatches)
                ),
                new AtomicInteger(),
                new AtomicInteger()
        );
        MontoyaApi api = proxy(MontoyaApi.class, (method, args) -> switch (method.getName()) {
            case "siteMap" -> siteMap;
            case "proxy" -> proxyApi;
            default -> defaultValue(method.getReturnType());
        });
        SearchSourceScanner scanner = new SearchSourceScanner(
                api,
                List.of(),
                List.of(SelectionScope.fromUrl(
                        "https://example.com/allowed/"
                ).orElseThrow()),
                null,
                item -> requestResponse(item.finalRequest().url(), new AtomicInteger()),
                ProxyHistoryPartitioner.historyIdForTesting(item -> 1L)
        );
        SearchOptions options = targetAndProxyOptions("needle");
        List<String> matches = new ArrayList<>();

        scanner.scan(
                options,
                new SearchEngine().prepare(options),
                () -> false,
                exchange -> {
                    matches.add(exchange.url());
                    return true;
                }
        );

        assertEquals(Set.of(targetInside, proxyInside), new HashSet<>(matches));
        assertEquals(2, nativeMatches.get());
    }

    @Test
    void rawSourcePreservesFirstOccurrenceDeduplicationBeforeMatching() {
        String url = "https://example.com/same";
        List<HttpExchange> context = List.of(
                new HttpExchange("Context", requestResponse(url, 404), null),
                new HttpExchange("Context", requestResponse(url, 200), null)
        );
        SearchSourceScanner scanner = scanner(
                null,
                null,
                context,
                item -> null
        );
        SearchOptions options = contextStatusOptions();
        SearchEngine.PreparedSearch preparedSearch = new SearchEngine().prepare(options);
        List<Integer> statuses = new ArrayList<>();

        scanner.scan(
                options,
                preparedSearch,
                () -> false,
                exchange -> {
                    statuses.add(exchange.statusCode());
                    return true;
                }
        );

        assertTrue(statuses.isEmpty());
    }

    @Test
    void sourceReadFailureIsReportedInsteadOfLookingComplete() {
        SiteMap brokenSiteMap = proxy(SiteMap.class, (method, args) -> {
            if ("requestResponses".equals(method.getName())) {
                throw new IllegalStateException("boom");
            }
            return defaultValue(method.getReturnType());
        });
        SearchSourceScanner scanner = scanner(brokenSiteMap);
        SearchOptions options = targetOptions("needle");
        SearchEngine.PreparedSearch preparedSearch = new SearchEngine().prepare(options);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> scanner.scan(options, preparedSearch, () -> false, exchange -> true)
        );

        assertTrue(failure.getMessage().contains("Target"));
        assertEquals("Target partition 1/32", scanner.activePhase());
    }

    @Test
    void malformedCandidateChecksAreCounted() {
        SiteMapNode malformedNode = proxy(SiteMapNode.class, (method, args) -> {
            if ("url".equals(method.getName())) {
                throw new IllegalStateException("bad URL");
            }
            return defaultValue(method.getReturnType());
        });
        SearchSourceScanner scanner = scanner(siteMap(
                List.of(malformedNode),
                new AtomicInteger(),
                new AtomicInteger()
        ));
        SearchOptions options = targetOptions("needle");
        SearchEngine.PreparedSearch preparedSearch = new SearchEngine().prepare(options);

        SearchSourceScanner.ScanStatistics scanStatistics = scanner.scan(
                options,
                preparedSearch,
                () -> false,
                exchange -> true
        );

        assertEquals(
                SearchSourceScanner.PARTITION_COUNT,
                scanStatistics.malformedItems()
        );
    }

    @Test
    void regexTimeoutIsIncompleteButTheNextItemStillMatches() {
        String slowBody = "a".repeat(16_384) + "!";
        List<HttpExchange> context = List.of(
                new HttpExchange(
                        "Context",
                        regexRequestResponse("https://example.com/slow", slowBody),
                        null
                ),
                new HttpExchange(
                        "Context",
                        regexRequestResponse("https://example.com/next", "needle"),
                        null
                )
        );
        SearchSourceScanner scanner = scanner(null, null, context, item -> null);
        SearchOptions options = contextRegexOptions("(a+)+$|needle");
        AtomicLong clock = new AtomicLong();
        SearchEngine.PreparedSearch preparedSearch = new SearchEngine(
                Duration.ofNanos(100),
                clock::getAndIncrement
        ).prepare(options);
        List<String> matches = new ArrayList<>();

        SearchSourceScanner.ScanStatistics statistics = scanner.scan(
                options,
                preparedSearch,
                () -> false,
                exchange -> {
                    matches.add(exchange.url());
                    return true;
                }
        );

        assertEquals(1, statistics.regexTimeoutItems());
        assertEquals(2, statistics.scannedItems());
        assertEquals(1, statistics.matchedItems());
        assertTrue(statistics.incomplete());
        assertEquals(List.of("https://example.com/next"), matches);
    }

    private SearchSourceScanner scanner(SiteMap siteMap) {
        return scanner(
                siteMap,
                null,
                List.of(),
                item -> null
        );
    }

    private SearchSourceScanner scanner(
            SiteMap siteMap,
            burp.api.montoya.proxy.Proxy proxyApi,
            List<HttpExchange> context,
            Function<ProxyHttpRequestResponse, HttpRequestResponse> converter
    ) {
        return scanner(
                siteMap,
                proxyApi,
                context,
                converter,
                ProxyHistoryPartitioner.runtime()
        );
    }

    private SearchSourceScanner scanner(
            SiteMap siteMap,
            burp.api.montoya.proxy.Proxy proxyApi,
            List<HttpExchange> context,
            Function<ProxyHttpRequestResponse, HttpRequestResponse> converter,
            ProxyHistoryPartitioner partitioner
    ) {
        MontoyaApi api = proxy(MontoyaApi.class, (method, args) -> switch (method.getName()) {
            case "siteMap" -> siteMap;
            case "proxy" -> proxyApi;
            default -> defaultValue(method.getReturnType());
        });
        return new SearchSourceScanner(
                api,
                context,
                List.of(),
                null,
                converter,
                partitioner
        );
    }

    private SiteMap siteMap(
            List<SiteMapNode> nodes,
            AtomicInteger sourceCalls,
            AtomicInteger largestBatch
    ) {
        return proxy(SiteMap.class, (method, args) -> {
            if ("requestResponses".equals(method.getName()) && args.length == 1) {
                sourceCalls.incrementAndGet();
                SiteMapFilter filter = (SiteMapFilter) args[0];
                List<HttpRequestResponse> matches = new ArrayList<>();
                for (SiteMapNode node : nodes) {
                    if (filter.matches(node)) {
                        matches.add(node.requestResponse());
                    }
                }
                largestBatch.accumulateAndGet(matches.size(), Math::max);
                return matches;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private burp.api.montoya.proxy.Proxy proxyHistory(
            List<ProxyHttpRequestResponse> items,
            AtomicInteger sourceCalls,
            AtomicInteger largestBatch
    ) {
        return proxy(burp.api.montoya.proxy.Proxy.class, (method, args) -> {
            if ("history".equals(method.getName()) && args.length == 1) {
                sourceCalls.incrementAndGet();
                ProxyHistoryFilter filter = (ProxyHistoryFilter) args[0];
                List<ProxyHttpRequestResponse> matches = new ArrayList<>();
                for (ProxyHttpRequestResponse item : items) {
                    if (filter.matches(item)) {
                        matches.add(item);
                    }
                }
                largestBatch.accumulateAndGet(matches.size(), Math::max);
                return matches;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private SiteMapNode node(String url, HttpRequestResponse requestResponse) {
        return proxy(SiteMapNode.class, (method, args) -> switch (method.getName()) {
            case "url" -> url;
            case "requestResponse" -> requestResponse;
            case "issues" -> List.of();
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequestResponse requestResponse(String url, AtomicInteger nativeMatches) {
        HttpRequest request = request(url);
        return proxy(HttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "hasResponse" -> false;
            case "contains" -> {
                nativeMatches.incrementAndGet();
                yield true;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private ProxyHttpRequestResponse proxyItem(int id, String url, AtomicInteger nativeMatches) {
        HttpRequest request = request(url);
        return proxy(ProxyHttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "id" -> id;
            case "url" -> url;
            case "request", "finalRequest" -> request;
            case "hasResponse" -> false;
            case "contains" -> {
                nativeMatches.incrementAndGet();
                yield true;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequestResponse requestResponse(String url, int statusCode) {
        HttpRequest request = request(url);
        HttpResponse response = proxy(HttpResponse.class, (method, args) -> switch (method.getName()) {
            case "statusCode" -> (short) statusCode;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "url" -> url;
            case "request" -> request;
            case "response" -> response;
            case "hasResponse" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequestResponse regexRequestResponse(String url, String text) {
        HttpRequest request = request(url);
        byte[] bodyBytes = text.getBytes(StandardCharsets.UTF_8);
        ByteArray body = proxy(ByteArray.class, (method, args) -> switch (method.getName()) {
            case "length" -> bodyBytes.length;
            case "getByte" -> bodyBytes[(int) args[0]];
            default -> defaultValue(method.getReturnType());
        });
        HttpResponse response = proxy(HttpResponse.class, (method, args) -> switch (method.getName()) {
            case "body" -> body;
            case "headerValue" -> "Content-Type".equals(args[0])
                    ? "text/plain; charset=utf-8"
                    : null;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(HttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "request" -> request;
            case "response" -> response;
            case "hasResponse" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequest request(String url) {
        return proxy(HttpRequest.class, (method, args) -> switch (method.getName()) {
            case "url" -> url;
            case "method" -> "GET";
            default -> defaultValue(method.getReturnType());
        });
    }

    private SearchOptions targetOptions(String query) {
        return new SearchOptions(
                query,
                SearchMode.TEXT,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private SearchOptions proxyOptions(String query) {
        return new SearchOptions(
                query,
                SearchMode.TEXT,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                false,
                true,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private SearchOptions targetAndProxyOptions(String query) {
        return new SearchOptions(
                query,
                SearchMode.TEXT,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private SearchOptions contextStatusOptions() {
        return new SearchOptions(
                "",
                SearchMode.TEXT,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                Set.of("2xx"),
                Set.of(),
                Set.of()
        );
    }

    private SearchOptions contextRegexOptions(String query) {
        return new SearchOptions(
                query,
                SearchMode.TEXT,
                true,
                true,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private String urlInPartition(int partition) {
        for (int index = 0; ; index++) {
            String url = "https://example.com/cancel/" + index;
            if (SearchSourceScanner.partitionFor(url.hashCode()) == partition) {
                return url;
            }
        }
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
