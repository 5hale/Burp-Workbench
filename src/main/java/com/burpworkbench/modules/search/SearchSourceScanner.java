package com.burpworkbench.modules.search;

import com.burpworkbench.core.selection.SelectionScope;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Reads Burp data sources without retaining one combined in-memory collection.
 *
 * <p>Montoya returns Site Map and Proxy filter results as lists rather than streams.
 * Large sources are therefore divided into stable partitions, and each returned list
 * is consumed before the next partition is requested.</p>
 */
final class SearchSourceScanner {
    static final int PARTITION_COUNT = 32;

    private final MontoyaApi api;
    private volatile List<HttpExchange> contextExchanges;
    private volatile List<SelectionScope> contextScopes;
    private final RepeaterCache repeaterCache;
    private final Function<ProxyHttpRequestResponse, HttpRequestResponse> proxyRequestResponseFactory;
    private final ProxyHistoryPartitioner proxyHistoryPartitioner;
    private final AtomicLong scannedItems = new AtomicLong();
    private final AtomicLong matchedItems = new AtomicLong();
    private final AtomicInteger malformedItems = new AtomicInteger();
    private final AtomicInteger regexTimeoutItems = new AtomicInteger();
    private volatile String activePhase = "idle";

    SearchSourceScanner(
            MontoyaApi api,
            List<HttpExchange> contextExchanges,
            List<SelectionScope> contextScopes,
            RepeaterCache repeaterCache
    ) {
        this(
                api,
                contextExchanges,
                contextScopes,
                repeaterCache,
                HttpExchangeFactory::requestResponseFromProxyItem,
                ProxyHistoryPartitioner.runtime()
        );
    }

    SearchSourceScanner(
            MontoyaApi api,
            List<HttpExchange> contextExchanges,
            List<SelectionScope> contextScopes,
            RepeaterCache repeaterCache,
            Function<ProxyHttpRequestResponse, HttpRequestResponse> proxyRequestResponseFactory
    ) {
        this(
                api,
                contextExchanges,
                contextScopes,
                repeaterCache,
                proxyRequestResponseFactory,
                ProxyHistoryPartitioner.runtime()
        );
    }

    SearchSourceScanner(
            MontoyaApi api,
            List<HttpExchange> contextExchanges,
            List<SelectionScope> contextScopes,
            RepeaterCache repeaterCache,
            Function<ProxyHttpRequestResponse, HttpRequestResponse> proxyRequestResponseFactory,
            ProxyHistoryPartitioner proxyHistoryPartitioner
    ) {
        this.api = api;
        this.contextExchanges = contextExchanges == null ? List.of() : List.copyOf(contextExchanges);
        this.contextScopes = contextScopes == null ? List.of() : List.copyOf(contextScopes);
        this.repeaterCache = repeaterCache;
        this.proxyRequestResponseFactory = Objects.requireNonNull(
                proxyRequestResponseFactory,
                "proxyRequestResponseFactory"
        );
        this.proxyHistoryPartitioner = Objects.requireNonNull(
                proxyHistoryPartitioner,
                "proxyHistoryPartitioner"
        );
    }

    ScanStatistics scan(
            SearchOptions options,
            SearchEngine.PreparedSearch preparedSearch,
            BooleanSupplier cancellationRequested,
            Predicate<HttpExchange> matchVisitor
    ) {
        BooleanSupplier cancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        scannedItems.set(0);
        matchedItems.set(0);
        malformedItems.set(0);
        regexTimeoutItems.set(0);
        boolean completed = scanSources(options, preparedSearch, cancellation, matchVisitor);
        if (completed) {
            activePhase = "complete";
        }
        return currentStatistics();
    }

    void clearRetainedContext() {
        contextExchanges = List.of();
        contextScopes = List.of();
    }

    String activePhase() {
        return activePhase;
    }

    ScanStatistics currentStatistics() {
        return new ScanStatistics(
                scannedItems.get(),
                matchedItems.get(),
                malformedItems.get(),
                regexTimeoutItems.get()
        );
    }

    private boolean scanSources(
            SearchOptions options,
            SearchEngine.PreparedSearch preparedSearch,
            BooleanSupplier cancellation,
            Predicate<HttpExchange> matchVisitor
    ) {
        if (contextScopes.isEmpty()
                && !visitSource(
                "Context",
                () -> contextExchanges,
                Function.identity(),
                CandidateState.RAW,
                preparedSearch,
                cancellation,
                matchVisitor
        )) {
            return false;
        }
        if (options.includeTarget()
                && !visitPartitionedSource(
                "Target",
                partition -> targetRequestResponses(preparedSearch, partition, cancellation),
                item -> new HttpExchange("Target", item, null),
                CandidateState.MATCHED_AND_SCOPED,
                preparedSearch,
                cancellation,
                matchVisitor
        )) {
            return false;
        }
        if (options.includeProxy()
                && !visitPartitionedSource(
                "Proxy",
                partition -> proxyHistory(preparedSearch, partition, cancellation),
                item -> {
                    HttpRequestResponse requestResponse =
                            proxyRequestResponseFactory.apply(item);
                    return requestResponse == null
                            ? null
                            : new HttpExchange("Proxy", requestResponse, safeProxyTime(item));
                },
                CandidateState.MATCHED_AND_SCOPED,
                preparedSearch,
                cancellation,
                matchVisitor
        )) {
            return false;
        }
        if (options.includeRepeater() && repeaterCache != null
                && !visitSource(
                "Repeater",
                repeaterCache::snapshot,
                item -> new HttpExchange("Repeater", item, null),
                CandidateState.RAW,
                preparedSearch,
                cancellation,
                matchVisitor
        )) {
            return false;
        }
        if (options.includeOrganizer()) {
            return visitSource(
                    "Organizer",
                    () -> api.organizer().items(),
                    item -> new HttpExchange("Organizer", item, null),
                    CandidateState.RAW,
                    preparedSearch,
                    cancellation,
                    matchVisitor
            );
        }
        return true;
    }

    static int partitionFor(long stableValue) {
        return Math.floorMod(stableValue, PARTITION_COUNT);
    }

    private List<HttpRequestResponse> targetRequestResponses(
            SearchEngine.PreparedSearch preparedSearch,
            int partition,
            BooleanSupplier cancellation
    ) {
        return api.siteMap().requestResponses(node -> {
            try {
                ensureNotCancelled(cancellation);
                String url = node.url();
                if (partitionFor(url == null ? 0 : url.hashCode()) != partition
                        || !matchesContextScope(url)) {
                    return false;
                }
                HttpRequestResponse requestResponse = node.requestResponse();
                if (requestResponse == null) {
                    return false;
                }
                scannedItems.incrementAndGet();
                HttpExchange exchange = new HttpExchange("Target", requestResponse, null);
                return preparedSearch.supportsNativeWholeMessageSearch()
                        ? preparedSearch.matchesFilters(exchange)
                        && preparedSearch.matchesNativeQuery(requestResponse)
                        : preparedSearch.matches(exchange);
            } catch (SearchItemTimeoutException exception) {
                recordRegexTimeout(exception);
                return false;
            } catch (CancellationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                recordMalformedItem(exception);
                return false;
            }
        });
    }

    private List<ProxyHttpRequestResponse> proxyHistory(
            SearchEngine.PreparedSearch preparedSearch,
            int partition,
            BooleanSupplier cancellation
    ) {
        return api.proxy().history(item -> {
            try {
                ensureNotCancelled(cancellation);
                if (item == null
                        || proxyHistoryPartitioner.partition(item, PARTITION_COUNT) != partition
                        || (!contextScopes.isEmpty() && !matchesContextScope(proxyItemUrl(item)))) {
                    return false;
                }
                scannedItems.incrementAndGet();
                boolean nativeSearch = preparedSearch.supportsNativeWholeMessageSearch();
                if (nativeSearch && !preparedSearch.hasExchangeFilters()) {
                    return preparedSearch.matchesNativeQuery(item);
                }

                HttpRequestResponse requestResponse = proxyRequestResponseFactory.apply(item);
                if (requestResponse == null) {
                    return false;
                }
                HttpExchange exchange = new HttpExchange("Proxy", requestResponse, safeProxyTime(item));
                return nativeSearch
                        ? preparedSearch.matchesFilters(exchange)
                        && preparedSearch.matchesNativeQuery(item)
                        : preparedSearch.matches(exchange);
            } catch (SearchItemTimeoutException exception) {
                recordRegexTimeout(exception);
                return false;
            } catch (CancellationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                recordMalformedItem(exception);
                return false;
            }
        });
    }

    private <T> boolean visitSource(
            String source,
            Supplier<List<T>> supplier,
            Function<T, HttpExchange> mapper,
            CandidateState candidateState,
            SearchEngine.PreparedSearch preparedSearch,
            BooleanSupplier cancellation,
            Predicate<HttpExchange> visitor
    ) {
        activePhase = source;
        try {
            ensureNotCancelled(cancellation);
            List<T> items = supplier.get();
            return items == null
                    || items.isEmpty()
                    || visitItems(
                    items,
                    mapper,
                    candidateState,
                    preparedSearch,
                    cancellation,
                    visitor
            );
        } catch (CancellationException exception) {
            return false;
        } catch (RuntimeException exception) {
            throw sourceReadFailure(source, exception);
        }
    }

    private <T> boolean visitPartitionedSource(
            String source,
            IntFunction<List<T>> supplier,
            Function<T, HttpExchange> mapper,
            CandidateState candidateState,
            SearchEngine.PreparedSearch preparedSearch,
            BooleanSupplier cancellation,
            Predicate<HttpExchange> visitor
    ) {
        try {
            for (int partition = 0; partition < PARTITION_COUNT; partition++) {
                activePhase = source + " partition " + (partition + 1) + "/" + PARTITION_COUNT;
                ensureNotCancelled(cancellation);
                if (!visitPartition(
                        partition,
                        supplier,
                        mapper,
                        candidateState,
                        preparedSearch,
                        cancellation,
                        visitor
                )) {
                    return false;
                }
            }
            return true;
        } catch (CancellationException exception) {
            return false;
        } catch (RuntimeException exception) {
            throw sourceReadFailure(source, exception);
        }
    }

    private IllegalStateException sourceReadFailure(String source, RuntimeException exception) {
        if (exception instanceof IllegalStateException stateException
                && stateException.getMessage() != null
                && stateException.getMessage().startsWith("Unable to read Search++ source ")) {
            return stateException;
        }
        return new IllegalStateException(
                "Unable to read Search++ source " + source + " at " + activePhase,
                exception
        );
    }

    /**
     * A separate frame ensures the previous Montoya list is unreachable before
     * the next partition supplier starts allocating its result list.
     */
    private <T> boolean visitPartition(
            int partition,
            IntFunction<List<T>> supplier,
            Function<T, HttpExchange> mapper,
            CandidateState candidateState,
            SearchEngine.PreparedSearch preparedSearch,
            BooleanSupplier cancellation,
            Predicate<HttpExchange> visitor
    ) {
        List<T> items = supplier.apply(partition);
        return items == null
                || items.isEmpty()
                || visitItems(
                items,
                mapper,
                candidateState,
                preparedSearch,
                cancellation,
                visitor
        );
    }

    private <T> boolean visitItems(
            List<T> items,
            Function<T, HttpExchange> mapper,
            CandidateState candidateState,
            SearchEngine.PreparedSearch preparedSearch,
            BooleanSupplier cancellation,
            Predicate<HttpExchange> visitor
    ) {
        for (T item : items) {
            try {
                ensureNotCancelled(cancellation);
                if (candidateState == CandidateState.RAW) {
                    scannedItems.incrementAndGet();
                }
                HttpExchange exchange = mapper.apply(item);
                if (exchange == null) {
                    continue;
                }
                if (candidateState == CandidateState.RAW
                        && !matchesContextScope(exchange.url())) {
                    continue;
                }
                if (candidateState == CandidateState.RAW
                        && !preparedSearch.matches(exchange)) {
                    continue;
                }
                matchedItems.incrementAndGet();
                if (!visitor.test(exchange)) {
                    return false;
                }
            } catch (SearchItemTimeoutException exception) {
                recordRegexTimeout(exception);
            } catch (CancellationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                recordMalformedItem(exception);
            }
        }
        return true;
    }

    private void ensureNotCancelled(BooleanSupplier cancellation) {
        if (Thread.currentThread().isInterrupted() || cancellation.getAsBoolean()) {
            throw new CancellationException();
        }
    }

    private boolean matchesContextScope(String url) {
        if (contextScopes.isEmpty()) {
            return true;
        }
        for (SelectionScope scope : contextScopes) {
            try {
                if (scope.matchesUrl(url)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // One malformed scope must not prevent another selected scope from matching.
            }
        }
        return false;
    }

    private ZonedDateTime safeProxyTime(ProxyHttpRequestResponse item) {
        try {
            return item.time();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String proxyItemUrl(ProxyHttpRequestResponse item) {
        try {
            var request = item.finalRequest();
            if (request == null) {
                request = item.request();
            }
            return request == null ? "" : request.url();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private void logWarning(String message, RuntimeException exception) {
        try {
            api.logging().logToError(message + ": " + exception);
        } catch (RuntimeException ignored) {
            // Logging must not interrupt source scanning.
        }
    }

    private void recordMalformedItem(RuntimeException exception) {
        int count = malformedItems.incrementAndGet();
        if (count <= 5) {
            logWarning("Skipped malformed Search++ item at " + activePhase, exception);
        } else if (count == 6) {
            logWarning("Further malformed Search++ item warnings suppressed at " + activePhase, exception);
        }
    }

    private void recordRegexTimeout(SearchItemTimeoutException exception) {
        int count = regexTimeoutItems.incrementAndGet();
        if (count <= 5) {
            logWarning("Skipped Search++ regex item after timeout at " + activePhase, exception);
        } else if (count == 6) {
            logWarning("Further Search++ regex timeout warnings suppressed at " + activePhase, exception);
        }
    }

    record ScanStatistics(
            long scannedItems,
            long matchedItems,
            int malformedItems,
            int regexTimeoutItems
    ) {
        boolean incomplete() {
            return regexTimeoutItems > 0;
        }
    }

    private enum CandidateState {
        RAW,
        MATCHED_AND_SCOPED
    }
}
