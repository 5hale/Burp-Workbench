package com.burpworkbench.core.selection;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class SelectionResolver {
    private final MontoyaApi api;

    public SelectionResolver(MontoyaApi api) {
        this.api = api;
    }

    public List<HttpRequestResponse> resolve(List<HttpRequestResponse> selectedItems, boolean includeSubtree) {
        return resolve(selectedItems, includeSubtree, () -> false);
    }

    public List<HttpRequestResponse> resolve(
            List<HttpRequestResponse> selectedItems,
            boolean includeSubtree,
            BooleanSupplier cancelled
    ) {
        checkCancelled(cancelled);
        if (selectedItems == null || selectedItems.isEmpty()) {
            return List.of();
        }
        if (!includeSubtree) {
            return deduplicate(selectedItems, List.of(), cancelled);
        }

        List<SelectionScope> scopes = scopesFor(selectedItems, cancelled);
        if (scopes.isEmpty()) {
            return deduplicate(selectedItems, List.of(), cancelled);
        }
        List<HttpRequestResponse> siteMapItems;
        try {
            siteMapItems = api.siteMap().requestResponses(node -> {
                checkCancelled(cancelled);
                String url;
                try {
                    url = node.url();
                } catch (RuntimeException exception) {
                    return false;
                }
                for (SelectionScope scope : scopes) {
                    checkCancelled(cancelled);
                    if (scope.matchesUrl(url)) {
                        return true;
                    }
                }
                return false;
            });
            checkCancelled(cancelled);
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (api != null) {
                api.logging().logToError("Unable to read filtered Site Map; falling back to selected items: " + exception);
            }
            return deduplicate(selectedItems, List.of(), cancelled);
        }
        List<HttpRequestResponse> filteredBatch = siteMapItems == null ? List.of() : siteMapItems;
        return stableFilteredView(filteredBatch, selectedItems, cancelled);
    }

    static List<HttpRequestResponse> resolveFromSiteMap(
            List<HttpRequestResponse> selectedItems,
            List<HttpRequestResponse> siteMapItems
    ) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return List.of();
        }
        if (siteMapItems == null || siteMapItems.isEmpty()) {
            return deduplicate(selectedItems, List.of(), () -> false);
        }

        List<SelectionScope> scopes = scopesFor(selectedItems);
        if (scopes.isEmpty()) {
            return deduplicate(selectedItems, List.of(), () -> false);
        }

        List<HttpRequestResponse> matchingSiteMapItems = new ArrayList<>();
        for (HttpRequestResponse item : siteMapItems) {
            for (SelectionScope scope : scopes) {
                if (scope.matches(item)) {
                    matchingSiteMapItems.add(item);
                    break;
                }
            }
        }
        return deduplicate(matchingSiteMapItems, selectedItems, () -> false);
    }

    public static List<SelectionScope> scopesFor(List<HttpRequestResponse> selectedItems) {
        return scopesFor(selectedItems, () -> false);
    }

    private static List<SelectionScope> scopesFor(
            List<HttpRequestResponse> selectedItems,
            BooleanSupplier cancelled
    ) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return List.of();
        }
        List<SelectionScope> scopes = new ArrayList<>();
        for (HttpRequestResponse selectedItem : selectedItems) {
            checkCancelled(cancelled);
            Optional<SelectionScope> scope = SelectionScope.from(selectedItem);
            scope.ifPresent(scopes::add);
        }
        return scopes;
    }

    private static List<HttpRequestResponse> deduplicate(
            List<HttpRequestResponse> first,
            List<HttpRequestResponse> second,
            BooleanSupplier cancelled
    ) {
        int expectedSize = safeSize(first) + safeSize(second);
        List<HttpRequestResponse> deduped = new ArrayList<>(expectedSize);
        Set<DeduplicationKey> seen = new HashSet<>(expectedSize);
        appendDistinct(first, deduped, seen, cancelled);
        appendDistinct(second, deduped, seen, cancelled);
        return deduped;
    }

    private static List<HttpRequestResponse> stableFilteredView(
            List<HttpRequestResponse> filteredBatch,
            List<HttpRequestResponse> selectedItems,
            BooleanSupplier cancelled
    ) {
        Set<DeduplicationKey> seen = new HashSet<>(safeSize(filteredBatch));
        int[] acceptedIndices = new int[filteredBatch.size()];
        int acceptedCount = 0;
        for (int index = 0; index < filteredBatch.size(); index++) {
            checkCancelled(cancelled);
            HttpRequestResponse item = filteredBatch.get(index);
            if (item != null && seen.add(deduplicationKey(item))) {
                acceptedIndices[acceptedCount++] = index;
            }
        }

        List<HttpRequestResponse> selectedExtras = new ArrayList<>(safeSize(selectedItems));
        appendDistinct(selectedItems, selectedExtras, seen, cancelled);
        return new StableFilteredView(
                filteredBatch,
                acceptedIndices,
                acceptedCount,
                selectedExtras
        );
    }

    private static void appendDistinct(
            List<HttpRequestResponse> items,
            List<HttpRequestResponse> deduped,
            Set<DeduplicationKey> seen,
            BooleanSupplier cancelled
    ) {
        if (items == null) {
            return;
        }
        for (HttpRequestResponse item : items) {
            checkCancelled(cancelled);
            if (item != null && seen.add(deduplicationKey(item))) {
                deduped.add(item);
            }
        }
    }

    private static int safeSize(List<?> items) {
        if (items == null) {
            return 0;
        }
        return Math.min(items.size(), 1_000_000);
    }

    private static DeduplicationKey deduplicationKey(HttpRequestResponse item) {
        try {
            return new RequestKey(item.request().method(), item.request().url());
        } catch (RuntimeException exception) {
            return new IdentityKey(item);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled by user");
        }
    }

    private static final class StableFilteredView extends AbstractList<HttpRequestResponse> {
        private final List<HttpRequestResponse> filteredBatch;
        private final int[] acceptedIndices;
        private final int acceptedCount;
        private final List<HttpRequestResponse> selectedExtras;

        private StableFilteredView(
                List<HttpRequestResponse> filteredBatch,
                int[] acceptedIndices,
                int acceptedCount,
                List<HttpRequestResponse> selectedExtras
        ) {
            this.filteredBatch = filteredBatch;
            this.acceptedIndices = acceptedIndices;
            this.acceptedCount = acceptedCount;
            this.selectedExtras = selectedExtras;
        }

        @Override
        public HttpRequestResponse get(int index) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException(index);
            }
            return index < acceptedCount
                    ? filteredBatch.get(acceptedIndices[index])
                    : selectedExtras.get(index - acceptedCount);
        }

        @Override
        public int size() {
            return Math.addExact(acceptedCount, selectedExtras.size());
        }
    }

    private interface DeduplicationKey {
    }

    private record RequestKey(String method, String url) implements DeduplicationKey {
    }

    private static final class IdentityKey implements DeduplicationKey {
        private final HttpRequestResponse item;

        private IdentityKey(HttpRequestResponse item) {
            this.item = item;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey key && item == key.item;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(item);
        }
    }
}
