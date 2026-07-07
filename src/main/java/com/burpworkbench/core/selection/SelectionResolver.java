package com.burpworkbench.core.selection;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SelectionResolver {
    private final MontoyaApi api;

    public SelectionResolver(MontoyaApi api) {
        this.api = api;
    }

    public List<HttpRequestResponse> resolve(List<HttpRequestResponse> selectedItems, boolean includeSubtree) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return List.of();
        }
        if (!includeSubtree) {
            return deduplicate(selectedItems);
        }

        List<HttpRequestResponse> siteMapItems;
        try {
            siteMapItems = api.siteMap().requestResponses();
        } catch (RuntimeException exception) {
            if (api != null) {
                api.logging().logToError("Unable to read full Site Map; falling back to selected items: " + exception);
            }
            return deduplicate(selectedItems);
        }
        return resolveFromSiteMap(selectedItems, siteMapItems);
    }

    public static List<HttpRequestResponse> resolveFromSiteMap(
            List<HttpRequestResponse> selectedItems,
            List<HttpRequestResponse> siteMapItems
    ) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return List.of();
        }
        if (siteMapItems == null || siteMapItems.isEmpty()) {
            return deduplicate(selectedItems);
        }

        List<SelectionScope> scopes = scopesFor(selectedItems);
        if (scopes.isEmpty()) {
            return deduplicate(selectedItems);
        }

        List<HttpRequestResponse> candidates = new ArrayList<>();
        for (HttpRequestResponse item : siteMapItems) {
            for (SelectionScope scope : scopes) {
                if (scope.matches(item)) {
                    candidates.add(item);
                    break;
                }
            }
        }
        candidates.addAll(selectedItems);
        return deduplicate(candidates);
    }

    public static List<SelectionScope> scopesFor(List<HttpRequestResponse> selectedItems) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return List.of();
        }
        return selectedItems.stream()
                .map(SelectionScope::from)
                .flatMap(Optional::stream)
                .toList();
    }

    private static List<HttpRequestResponse> deduplicate(List<HttpRequestResponse> items) {
        Map<String, HttpRequestResponse> deduped = new LinkedHashMap<>();
        for (HttpRequestResponse item : items) {
            if (item != null) {
                deduped.putIfAbsent(deduplicationKey(item), item);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private static String deduplicationKey(HttpRequestResponse item) {
        try {
            return item.request().method() + " " + item.request().url();
        } catch (RuntimeException exception) {
            return "malformed-" + System.identityHashCode(item);
        }
    }
}
