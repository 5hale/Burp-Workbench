package com.burpworkbench.modules.search;

import com.burpworkbench.core.filter.MimeCategory;

import java.util.Set;

record SearchOptionsSnapshot(
        String query,
        SearchMode mode,
        boolean regex,
        boolean caseSensitive,
        boolean requestHeaders,
        boolean requestBody,
        boolean responseHeaders,
        boolean responseBody,
        boolean includeTarget,
        boolean includeProxy,
        boolean includeRepeater,
        boolean includeOrganizer,
        Set<String> statusPatterns,
        Set<MimeCategory> mimeCategories,
        Set<String> extensions,
        Set<String> excludedExtensions
) {
    SearchOptionsSnapshot {
        query = query == null ? "" : query;
        mode = mode == null ? SearchMode.TEXT : mode;
        statusPatterns = Set.copyOf(statusPatterns == null ? Set.of() : statusPatterns);
        mimeCategories = Set.copyOf(mimeCategories == null ? Set.of() : mimeCategories);
        extensions = Set.copyOf(extensions == null ? Set.of() : extensions);
        excludedExtensions = Set.copyOf(excludedExtensions == null ? Set.of() : excludedExtensions);
    }
}
