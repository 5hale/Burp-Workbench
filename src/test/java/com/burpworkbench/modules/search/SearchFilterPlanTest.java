package com.burpworkbench.modules.search;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.burpworkbench.core.filter.MimeCategory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchFilterPlanTest {
    @Test
    void defaultFilterKeepsResultVisible() {
        SearchFilterPlan plan = new SearchFilterPlan(
                1,
                new SearchEngine(),
                filterOptions(true, Set.of()),
                null
        );

        SearchFilterPlan.Evaluation evaluation =
                plan.evaluate(result(200, "body"));

        assertTrue(evaluation.visible());
        assertEquals(0, evaluation.regexTimeoutItems());
    }

    @Test
    void exchangeFilterCanHideResult() {
        SearchFilterPlan plan = new SearchFilterPlan(
                2,
                new SearchEngine(),
                filterOptions(false, Set.of("2xx")),
                null
        );

        assertFalse(plan.evaluate(result(404, "body")).visible());
    }

    @Test
    void sameEndpointTransactionsRefilterIndependentlyWithoutRescan() {
        List<SearchResult> canonicalResults = List.of(
                result(200, "{\"secret\":true}", "application/json"),
                result(404, "<html>public</html>", "text/html"),
                noResponseResult("request-only")
        );

        assertArrayEquals(
                new int[]{0, 1, 2},
                evaluateAll(canonicalResults, filterOptions(true, Set.of()), null)
        );
        assertArrayEquals(
                new int[]{0},
                evaluateAll(canonicalResults, filterOptions(false, Set.of("2xx")), null)
        );
        assertArrayEquals(
                new int[]{1},
                evaluateAll(canonicalResults, filterOptions(false, Set.of("4xx")), null)
        );
        assertArrayEquals(
                new int[]{0, 1, 2},
                evaluateAll(canonicalResults, filterOptions(true, Set.of()), null)
        );
        assertArrayEquals(
                new int[]{0},
                evaluateAll(
                        canonicalResults,
                        filterOptions(true, Set.of(), true, Set.of(MimeCategory.JSON)),
                        null
                )
        );
        assertArrayEquals(
                new int[]{1},
                evaluateAll(
                        canonicalResults,
                        filterOptions(true, Set.of(), true, Set.of(MimeCategory.HTML)),
                        null
                )
        );
        assertArrayEquals(
                new int[]{1, 2},
                evaluateAll(
                        canonicalResults,
                        filterOptions(true, Set.of()),
                        negativeTextOptions("secret")
                )
        );
        assertArrayEquals(
                new int[]{0},
                evaluateAll(
                        canonicalResults,
                        filterOptions(
                                false,
                                Set.of("2xx"),
                                true,
                                Set.of(MimeCategory.JSON)
                        ),
                        null
                )
        );
        assertArrayEquals(
                new int[0],
                evaluateAll(
                        canonicalResults,
                        filterOptions(
                                false,
                                Set.of("2xx"),
                                true,
                                Set.of(MimeCategory.JSON)
                        ),
                        negativeTextOptions("secret")
                )
        );
    }

    @Test
    void negativeRegexTimeoutKeepsResultVisibleAndCountsIncompleteItem() {
        AtomicLong clock = new AtomicLong();
        SearchEngine timedEngine = new SearchEngine(
                Duration.ofNanos(1),
                clock::getAndIncrement
        );
        SearchFilterPlan plan = new SearchFilterPlan(
                3,
                timedEngine,
                filterOptions(true, Set.of()),
                negativeRegexOptions("a+")
        );

        SearchFilterPlan.Evaluation evaluation =
                plan.evaluate(result(200, "aaaa"));

        assertTrue(evaluation.visible());
        assertEquals(1, evaluation.regexTimeoutItems());
    }

    @Test
    void evaluateAllHonorsThreadInterruption() {
        SearchFilterPlan plan = new SearchFilterPlan(
                4,
                new SearchEngine(),
                filterOptions(true, Set.of()),
                null
        );

        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    CancellationException.class,
                    () -> plan.evaluateAll(List.of(result(200, "body")))
            );
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancelledRefreshCanRecoverEveryCanonicalResultOnRefilter() {
        SearchFilterPlan plan = new SearchFilterPlan(
                5,
                new SearchEngine(),
                filterOptions(true, Set.of()),
                null
        );
        List<SearchResult> canonicalResults = new ArrayList<>(
                List.of(result(200, "first"), result(200, "second"))
        );
        SearchResultView visibleView =
                new SearchResultView(canonicalResults);
        visibleView.replaceVisibleIndices(new int[]{0});

        // A refresh snapshot can be cancelled while a newly scanned result is
        // already canonical. Discarding that task must not discard the result:
        // a dirty-tab refilter derives the complete view from canonical state.
        List<SearchResult> cancelledSnapshot =
                List.copyOf(canonicalResults);
        canonicalResults.add(result(200, "third"));
        plan.evaluateAll(cancelledSnapshot);

        SearchFilterPlan.Outcome recovered =
                plan.evaluateAll(List.copyOf(canonicalResults));
        visibleView.replaceVisibleIndices(recovered.visibleIndices());

        assertEquals(3, visibleView.size());
        assertEquals("first", bodyText(visibleView.get(0)));
        assertEquals("third", bodyText(visibleView.get(2)));
    }

    private SearchOptions filterOptions(
            boolean allStatus,
            Set<String> statuses
    ) {
        return filterOptions(allStatus, statuses, false, Set.of());
    }

    private SearchOptions filterOptions(
            boolean allStatus,
            Set<String> statuses,
            boolean mimeFilterEnabled,
            Set<MimeCategory> mimeCategories
    ) {
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
                allStatus,
                statuses,
                mimeFilterEnabled,
                mimeCategories,
                Set.of(),
                Set.of()
        );
    }

    private SearchOptions negativeTextOptions(String query) {
        return new SearchOptions(
                query,
                SearchMode.TEXT,
                false,
                false,
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
                false,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private SearchOptions negativeRegexOptions(String query) {
        return new SearchOptions(
                query,
                SearchMode.TEXT,
                true,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                Set.of(),
                false,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private SearchResult result(int status, String bodyText) {
        return result(status, bodyText, "text/plain; charset=utf-8", true);
    }

    private SearchResult result(int status, String bodyText, String contentType) {
        return result(status, bodyText, contentType, true);
    }

    private SearchResult noResponseResult(String bodyText) {
        return result(0, bodyText, "", false);
    }

    private SearchResult result(
            int status,
            String bodyText,
            String contentType,
            boolean hasResponse
    ) {
        byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
        ByteArray bodyBytes = proxy(ByteArray.class, (method, args) ->
                switch (method.getName()) {
                    case "length" -> body.length;
                    case "getByte" -> body[(int) args[0]];
                    case "getBytes" -> body.clone();
                    case "indexOf" -> byteIndexOf(body, args);
                    default -> defaultValue(method.getReturnType());
                });
        HttpRequest request = proxy(HttpRequest.class, (method, args) ->
                switch (method.getName()) {
                    case "body", "toByteArray" -> bodyBytes;
                    case "bodyOffset" -> 0;
                    case "method" -> "GET";
                    case "url" -> "https://example.test/item";
                    case "headerValue" -> "text/plain; charset=utf-8";
                    default -> defaultValue(method.getReturnType());
                });
        HttpResponse response = proxy(HttpResponse.class, (method, args) ->
                switch (method.getName()) {
                    case "statusCode" -> (short) status;
                    case "body", "toByteArray" -> bodyBytes;
                    case "bodyOffset" -> 0;
                    case "headerValue" -> contentType;
                    default -> defaultValue(method.getReturnType());
                });
        HttpRequestResponse requestResponse = proxy(
                HttpRequestResponse.class,
                (method, args) -> switch (method.getName()) {
                    case "request" -> request;
                    case "response" -> hasResponse ? response : null;
                    case "hasResponse" -> hasResponse;
                    default -> defaultValue(method.getReturnType());
                }
        );
        return new SearchResult(
                new HttpExchange("Test", requestResponse, null),
                "Text",
                body.length
        );
    }

    private int[] evaluateAll(
            List<SearchResult> results,
            SearchOptions filterOptions,
            SearchOptions negativeOptions
    ) {
        return new SearchFilterPlan(
                99,
                new SearchEngine(),
                filterOptions,
                negativeOptions
        ).evaluateAll(results).visibleIndices();
    }

    private int byteIndexOf(byte[] haystack, Object[] arguments) {
        byte[] needle = String.valueOf(arguments[0]).getBytes(StandardCharsets.UTF_8);
        boolean caseSensitive = (boolean) arguments[1];
        int start = Math.max(0, (int) arguments[2]);
        int end = Math.min(haystack.length, (int) arguments[3]);
        int lastStart = end - needle.length;
        for (int index = start; index <= lastStart; index++) {
            boolean matched = true;
            for (int offset = 0; offset < needle.length; offset++) {
                byte actual = haystack[index + offset];
                byte expected = needle[offset];
                if (!caseSensitive) {
                    actual = asciiLowercase(actual);
                    expected = asciiLowercase(expected);
                }
                if (actual != expected) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return index;
            }
        }
        return -1;
    }

    private byte asciiLowercase(byte value) {
        return value >= 'A' && value <= 'Z'
                ? (byte) (value + ('a' - 'A'))
                : value;
    }

    private String bodyText(SearchResult result) {
        return new String(
                result.exchange()
                        .requestResponse()
                        .request()
                        .body()
                        .getBytes(),
                StandardCharsets.UTF_8
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (instance, method, arguments) ->
                        invocation.invoke(
                                method,
                                arguments == null ? new Object[0] : arguments
                        )
        );
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
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
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments)
                throws Throwable;
    }
}
