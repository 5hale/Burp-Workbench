package com.burpworkbench.modules.search;

import java.util.Set;

final class SearchOptionsMapper {
    SearchOptions queryOptions(SearchOptionsSnapshot snapshot) {
        return new SearchOptions(
                snapshot.query(),
                snapshot.mode(),
                snapshot.regex(),
                snapshot.caseSensitive(),
                false,
                snapshot.requestHeaders(),
                snapshot.requestBody(),
                snapshot.responseHeaders(),
                snapshot.responseBody(),
                snapshot.includeTarget(),
                snapshot.includeProxy(),
                snapshot.includeRepeater(),
                snapshot.includeOrganizer(),
                true,
                Set.of(),
                false,
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    SearchOptions filterOptions(SearchOptionsSnapshot snapshot) {
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
                snapshot.statusPatterns(),
                true,
                snapshot.mimeCategories(),
                snapshot.extensions(),
                snapshot.excludedExtensions()
        );
    }

    SearchOptions negativeFilterOptions(SearchOptionsSnapshot snapshot, String query) {
        return new SearchOptions(
                query,
                snapshot.mode(),
                snapshot.regex(),
                snapshot.caseSensitive(),
                false,
                snapshot.requestHeaders(),
                snapshot.requestBody(),
                snapshot.responseHeaders(),
                snapshot.responseBody(),
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
}
