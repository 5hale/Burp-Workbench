package com.burpworkbench.modules.search;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyHistoryPartitionerTest {
    private static final ZonedDateTime FIXED_TIME =
            ZonedDateTime.of(2026, 7, 29, 10, 15, 30, 123_456_789, ZoneOffset.UTC);

    @Test
    void runtimeModeMatchesTheAvailableMontoyaCapability() {
        boolean historyIdAvailable;
        try {
            Class.forName(
                    "burp.api.montoya.proxy.ProxyHttpRequestResponse",
                    false,
                    ProxyHistoryPartitionerTest.class.getClassLoader()
            ).getMethod("id");
            historyIdAvailable = true;
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            historyIdAvailable = false;
        }

        assertEquals(
                historyIdAvailable
                        ? ProxyHistoryPartitioner.Mode.HISTORY_ID
                        : ProxyHistoryPartitioner.Mode.LEGACY_METADATA,
                ProxyHistoryPartitioner.runtime().mode()
        );

        ProxyHttpRequestResponse item = proxy(
                ProxyHttpRequestResponse.class,
                (method, args) -> switch (method.getName()) {
                    case "id" -> -17;
                    case "time" -> FIXED_TIME;
                    case "listenerPort" -> 8080;
                    case "finalRequest", "request" ->
                            request("GET", "https://a.test/path");
                    default -> defaultValue(method.getReturnType());
                }
        );
        if (historyIdAvailable) {
            assertEquals(
                    Math.floorMod(-17L, SearchSourceScanner.PARTITION_COUNT),
                    ProxyHistoryPartitioner.runtime().partition(
                            item,
                            SearchSourceScanner.PARTITION_COUNT
                    )
            );
        } else {
            assertEquals(
                    ProxyHistoryPartitioner.legacyMetadata().stableKey(item),
                    ProxyHistoryPartitioner.runtime().stableKey(item)
            );
        }
    }

    @Test
    void runtimeHistoryIdLinkageFailureIsAnItemFailureNotAModeSwitch() {
        boolean historyIdAvailable;
        try {
            Class.forName(
                    "burp.api.montoya.proxy.ProxyHttpRequestResponse",
                    false,
                    ProxyHistoryPartitionerTest.class.getClassLoader()
            ).getMethod("id");
            historyIdAvailable = true;
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            historyIdAvailable = false;
        }
        if (!historyIdAvailable) {
            assertEquals(
                    ProxyHistoryPartitioner.Mode.LEGACY_METADATA,
                    ProxyHistoryPartitioner.runtime().mode()
            );
            return;
        }

        ProxyHttpRequestResponse item = proxy(
                ProxyHttpRequestResponse.class,
                (method, args) -> {
                    if ("id".equals(method.getName())) {
                        throw new AbstractMethodError("broken runtime implementation");
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ProxyHistoryPartitioner.runtime().stableKey(item)
        );

        assertTrue(failure.getCause() instanceof AbstractMethodError);
        assertEquals(
                ProxyHistoryPartitioner.Mode.HISTORY_ID,
                ProxyHistoryPartitioner.runtime().mode()
        );
    }

    @Test
    void negativeHistoryIdsMapToOneValidPartition() {
        ProxyHistoryPartitioner partitioner =
                ProxyHistoryPartitioner.historyIdForTesting(item -> -17L);
        ProxyHttpRequestResponse item = item(FIXED_TIME, 8080, "GET", "https://a.test/");

        assertEquals(
                Math.floorMod(-17L, SearchSourceScanner.PARTITION_COUNT),
                partitioner.partition(item, SearchSourceScanner.PARTITION_COUNT)
        );
        assertEquals(
                Set.of(partitioner.partition(item, SearchSourceScanner.PARTITION_COUNT)),
                partitionsObserved(partitioner, item, 32)
        );
    }

    @Test
    void historyIdReaderFailureDoesNotChangeTheSelectedMode() {
        AtomicInteger calls = new AtomicInteger();
        ProxyHistoryPartitioner partitioner =
                ProxyHistoryPartitioner.historyIdForTesting(item -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("broken id");
                });
        ProxyHttpRequestResponse item = item(FIXED_TIME, 8080, "GET", "https://a.test/");

        assertThrows(
                IllegalStateException.class,
                () -> partitioner.partition(item, SearchSourceScanner.PARTITION_COUNT)
        );
        assertEquals(1, calls.get());
        assertEquals(ProxyHistoryPartitioner.Mode.HISTORY_ID, partitioner.mode());
    }

    @Test
    void legacyKeyIsStableAndDoesNotUseBodyOrObjectHashCode() {
        AtomicInteger forbiddenAccesses = new AtomicInteger();
        ProxyHistoryPartitioner partitioner = ProxyHistoryPartitioner.legacyMetadata();
        ProxyHttpRequestResponse first = guardedItem(forbiddenAccesses);
        ProxyHttpRequestResponse second = guardedItem(forbiddenAccesses);

        long firstKey = partitioner.stableKey(first);
        long secondKey = partitioner.stableKey(second);

        assertEquals(firstKey, secondKey);
        assertEquals(0, forbiddenAccesses.get());
        assertEquals(
                Set.of(partitioner.partition(first, SearchSourceScanner.PARTITION_COUNT)),
                partitionsObserved(partitioner, first, 32)
        );
    }

    @Test
    void legacyUsesRequestWhenFinalRequestIsMissing() {
        ProxyHistoryPartitioner partitioner = ProxyHistoryPartitioner.legacyMetadata();
        HttpRequest request = request("POST", "https://fallback.test/path");
        ProxyHttpRequestResponse withFinal =
                item(FIXED_TIME, 8443, request, request, false, false, false);
        ProxyHttpRequestResponse withoutFinal =
                item(FIXED_TIME, 8443, null, request, false, false, false);

        assertEquals(
                partitioner.stableKey(withFinal),
                partitioner.stableKey(withoutFinal)
        );
    }

    @Test
    void eachUnavailableMetadataFieldUsesItsDefaultWithoutFailing() {
        ProxyHistoryPartitioner partitioner = ProxyHistoryPartitioner.legacyMetadata();
        ProxyHttpRequestResponse missing =
                item(null, 0, null, null, false, false, false);
        ProxyHttpRequestResponse failing =
                item(null, 0, null, null, true, true, true);
        HttpRequest emptyRequest = proxy(HttpRequest.class, (method, args) -> switch (method.getName()) {
            case "method", "url" -> throw new IllegalStateException("unavailable request metadata");
            default -> defaultValue(method.getReturnType());
        });
        ProxyHttpRequestResponse failingRequestFields =
                item(null, 0, emptyRequest, null, false, false, false);

        assertEquals(partitioner.stableKey(missing), partitioner.stableKey(failing));
        assertEquals(
                partitioner.stableKey(missing),
                partitioner.stableKey(failingRequestFields)
        );
    }

    @Test
    void oneFailingLegacyAccessorDoesNotDiscardTheOtherMetadata() {
        ProxyHistoryPartitioner partitioner = ProxyHistoryPartitioner.legacyMetadata();
        HttpRequest completeRequest = request("GET", "https://a.test/path");
        ProxyHttpRequestResponse failedTime = failingItem(
                FIXED_TIME,
                8080,
                completeRequest,
                completeRequest,
                Set.of("time")
        );
        ProxyHttpRequestResponse defaultTime =
                item(null, 8080, completeRequest, completeRequest, false, false, false);
        ProxyHttpRequestResponse failedListener = failingItem(
                FIXED_TIME,
                8080,
                completeRequest,
                completeRequest,
                Set.of("listenerPort")
        );
        ProxyHttpRequestResponse defaultListener =
                item(FIXED_TIME, 0, completeRequest, completeRequest, false, false, false);
        ProxyHttpRequestResponse failedFinalRequest = failingItem(
                FIXED_TIME,
                8080,
                completeRequest,
                completeRequest,
                Set.of("finalRequest")
        );
        ProxyHttpRequestResponse missingFinalRequest =
                item(FIXED_TIME, 8080, null, completeRequest, false, false, false);

        HttpRequest failedMethod = failingRequest(
                "GET",
                "https://a.test/path",
                Set.of("method")
        );
        HttpRequest defaultMethod = request("", "https://a.test/path");
        HttpRequest failedUrl = failingRequest(
                "GET",
                "https://a.test/path",
                Set.of("url")
        );
        HttpRequest defaultUrl = request("GET", "");

        assertEquals(partitioner.stableKey(defaultTime), partitioner.stableKey(failedTime));
        assertEquals(
                partitioner.stableKey(defaultListener),
                partitioner.stableKey(failedListener)
        );
        assertEquals(
                partitioner.stableKey(missingFinalRequest),
                partitioner.stableKey(failedFinalRequest)
        );
        assertEquals(
                partitioner.stableKey(item(
                        FIXED_TIME,
                        8080,
                        defaultMethod,
                        defaultMethod,
                        false,
                        false,
                        false
                )),
                partitioner.stableKey(item(
                        FIXED_TIME,
                        8080,
                        failedMethod,
                        failedMethod,
                        false,
                        false,
                        false
                ))
        );
        assertEquals(
                partitioner.stableKey(item(
                        FIXED_TIME,
                        8080,
                        defaultUrl,
                        defaultUrl,
                        false,
                        false,
                        false
                )),
                partitioner.stableKey(item(
                        FIXED_TIME,
                        8080,
                        failedUrl,
                        failedUrl,
                        false,
                        false,
                        false
                ))
        );
    }

    @Test
    void sameUrlAndSameTimestampHistoriesStillDistributeWhenOtherMetadataVaries() {
        ProxyHistoryPartitioner partitioner = ProxyHistoryPartitioner.legacyMetadata();
        Set<Integer> sameUrlPartitions = new HashSet<>();
        Set<Integer> sameTimePartitions = new HashSet<>();

        for (int index = 0; index < 512; index++) {
            ZonedDateTime varyingNano = FIXED_TIME.withNano(index);
            sameUrlPartitions.add(partitioner.partition(
                    item(varyingNano, 8080, "GET", "https://same.test/path"),
                    SearchSourceScanner.PARTITION_COUNT
            ));
            sameTimePartitions.add(partitioner.partition(
                    item(FIXED_TIME, 8080, "GET", "https://same.test/" + index),
                    SearchSourceScanner.PARTITION_COUNT
            ));
        }

        assertTrue(sameUrlPartitions.size() > 1);
        assertTrue(sameTimePartitions.size() > 1);
    }

    @Test
    void legacyHashCollisionOnlySharesAPartition() {
        ProxyHistoryPartitioner partitioner = ProxyHistoryPartitioner.legacyMetadata();
        ProxyHttpRequestResponse first = item(FIXED_TIME, 8080, "GET", "Aa");
        ProxyHttpRequestResponse second = item(FIXED_TIME, 8080, "GET", "BB");

        assertEquals(partitioner.stableKey(first), partitioner.stableKey(second));
        assertEquals(
                partitioner.partition(first, SearchSourceScanner.PARTITION_COUNT),
                partitioner.partition(second, SearchSourceScanner.PARTITION_COUNT)
        );
    }

    @Test
    void changingEachLegacyMetadataComponentChangesTheKey() {
        ProxyHistoryPartitioner partitioner = ProxyHistoryPartitioner.legacyMetadata();
        long baseline = partitioner.stableKey(
                item(FIXED_TIME, 8080, "GET", "https://a.test/path")
        );

        assertNotEquals(
                baseline,
                partitioner.stableKey(item(
                        FIXED_TIME.plusSeconds(1),
                        8080,
                        "GET",
                        "https://a.test/path"
                ))
        );
        assertNotEquals(
                baseline,
                partitioner.stableKey(item(
                        FIXED_TIME.plusNanos(1),
                        8080,
                        "GET",
                        "https://a.test/path"
                ))
        );
        assertNotEquals(
                baseline,
                partitioner.stableKey(item(
                        FIXED_TIME,
                        8081,
                        "GET",
                        "https://a.test/path"
                ))
        );
        assertNotEquals(
                baseline,
                partitioner.stableKey(item(
                        FIXED_TIME,
                        8080,
                        "POST",
                        "https://a.test/path"
                ))
        );
        assertNotEquals(
                baseline,
                partitioner.stableKey(item(
                        FIXED_TIME,
                        8080,
                        "GET",
                        "https://b.test/path"
                ))
        );
    }

    private Set<Integer> partitionsObserved(
            ProxyHistoryPartitioner partitioner,
            ProxyHttpRequestResponse item,
            int calls
    ) {
        Set<Integer> partitions = new HashSet<>();
        for (int index = 0; index < calls; index++) {
            partitions.add(partitioner.partition(item, SearchSourceScanner.PARTITION_COUNT));
        }
        return partitions;
    }

    private ProxyHttpRequestResponse guardedItem(AtomicInteger forbiddenAccesses) {
        HttpRequest request = proxy(HttpRequest.class, (method, args) -> switch (method.getName()) {
            case "method" -> "GET";
            case "url" -> "https://a.test/path";
            case "body", "bodyToString", "toByteArray", "hashCode" -> {
                forbiddenAccesses.incrementAndGet();
                throw new AssertionError("body or object hashCode must not be used");
            }
            default -> defaultValue(method.getReturnType());
        });
        return proxy(ProxyHttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "time" -> FIXED_TIME;
            case "listenerPort" -> 8080;
            case "finalRequest", "request" -> request;
            case "requestBody", "hashCode" -> {
                forbiddenAccesses.incrementAndGet();
                throw new AssertionError("body or object hashCode must not be used");
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private ProxyHttpRequestResponse item(
            ZonedDateTime time,
            int listenerPort,
            String method,
            String url
    ) {
        HttpRequest request = request(method, url);
        return item(time, listenerPort, request, request, false, false, false);
    }

    private ProxyHttpRequestResponse item(
            ZonedDateTime time,
            int listenerPort,
            HttpRequest finalRequest,
            HttpRequest request,
            boolean failTime,
            boolean failListenerPort,
            boolean failRequests
    ) {
        return proxy(ProxyHttpRequestResponse.class, (method, args) -> switch (method.getName()) {
            case "time" -> {
                if (failTime) {
                    throw new IllegalStateException("unavailable time");
                }
                yield time;
            }
            case "listenerPort" -> {
                if (failListenerPort) {
                    throw new IllegalStateException("unavailable listener");
                }
                yield listenerPort;
            }
            case "finalRequest" -> {
                if (failRequests) {
                    throw new IllegalStateException("unavailable final request");
                }
                yield finalRequest;
            }
            case "request" -> {
                if (failRequests) {
                    throw new IllegalStateException("unavailable request");
                }
                yield request;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private HttpRequest request(String methodValue, String urlValue) {
        return failingRequest(methodValue, urlValue, Set.of());
    }

    private HttpRequest failingRequest(
            String methodValue,
            String urlValue,
            Set<String> failingMethods
    ) {
        return proxy(HttpRequest.class, (method, args) -> switch (method.getName()) {
            case "method" -> {
                if (failingMethods.contains("method")) {
                    throw new IllegalStateException("unavailable method");
                }
                yield methodValue;
            }
            case "url" -> {
                if (failingMethods.contains("url")) {
                    throw new IllegalStateException("unavailable URL");
                }
                yield urlValue;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private ProxyHttpRequestResponse failingItem(
            ZonedDateTime time,
            int listenerPort,
            HttpRequest finalRequest,
            HttpRequest request,
            Set<String> failingMethods
    ) {
        return proxy(ProxyHttpRequestResponse.class, (method, args) -> {
            if (failingMethods.contains(method.getName())) {
                throw new IllegalStateException("unavailable " + method.getName());
            }
            return switch (method.getName()) {
                case "time" -> time;
                case "listenerPort" -> listenerPort;
                case "finalRequest" -> finalRequest;
                case "request" -> request;
                default -> defaultValue(method.getReturnType());
            };
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, ProxyHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(
                        method,
                        args == null ? new Object[0] : args
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
        Object invoke(java.lang.reflect.Method method, Object[] args);
    }
}
