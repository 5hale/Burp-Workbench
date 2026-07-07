package com.burpworkbench.modules.search;

import com.burpworkbench.core.filter.MimeCategory;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public record SearchOptions(
        String query,
        SearchMode mode,
        boolean regex,
        boolean caseSensitive,
        boolean negativeMatch,
        boolean requestHeaders,
        boolean requestBody,
        boolean responseHeaders,
        boolean responseBody,
        boolean includeTarget,
        boolean includeProxy,
        boolean includeRepeater,
        boolean includeOrganizer,
        boolean allStatus,
        Set<String> statusPatterns,
        boolean mimeFilterEnabled,
        Set<MimeCategory> mimeCategories,
        Set<String> extensions,
        Set<String> excludedExtensions
) {
    public SearchOptions(
            String query,
            SearchMode mode,
            boolean regex,
            boolean caseSensitive,
            boolean negativeMatch,
            boolean requestHeaders,
            boolean requestBody,
            boolean responseHeaders,
            boolean responseBody,
            boolean includeTarget,
            boolean includeProxy,
            boolean includeRepeater,
            boolean includeOrganizer,
            boolean allStatus,
            Set<String> statusPatterns,
            Set<MimeCategory> mimeCategories,
            Set<String> extensions
    ) {
        this(query, mode, regex, caseSensitive, negativeMatch, requestHeaders, requestBody, responseHeaders, responseBody,
                includeTarget, includeProxy, includeRepeater, includeOrganizer, allStatus, statusPatterns, mimeCategories,
                extensions, Set.of());
    }

    public SearchOptions(
            String query,
            SearchMode mode,
            boolean regex,
            boolean caseSensitive,
            boolean negativeMatch,
            boolean requestHeaders,
            boolean requestBody,
            boolean responseHeaders,
            boolean responseBody,
            boolean includeTarget,
            boolean includeProxy,
            boolean includeRepeater,
            boolean includeOrganizer,
            boolean allStatus,
            Set<String> statusPatterns,
            Set<MimeCategory> mimeCategories,
            Set<String> extensions,
            Set<String> excludedExtensions
    ) {
        this(query, mode, regex, caseSensitive, negativeMatch, requestHeaders, requestBody, responseHeaders, responseBody,
                includeTarget, includeProxy, includeRepeater, includeOrganizer, allStatus, statusPatterns, false,
                mimeCategories, extensions, excludedExtensions);
    }

    public SearchOptions {
        query = query == null ? "" : query;
        mode = mode == null ? SearchMode.TEXT : mode;
        statusPatterns = normalizeStatusPatterns(statusPatterns == null ? Set.of() : statusPatterns);
        mimeCategories = mimeCategories == null || mimeCategories.isEmpty()
                ? Set.of()
                : EnumSet.copyOf(mimeCategories);
        extensions = Set.copyOf(extensions == null ? Set.of() : extensions);
        excludedExtensions = Set.copyOf(excludedExtensions == null ? Set.of() : excludedExtensions);
    }

    private static Set<String> normalizeStatusPatterns(Set<String> values) {
        Set<String> normalized = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                String pattern = value.trim().toLowerCase(Locale.ROOT);
                if (!pattern.matches("\\d{3}") && !pattern.matches("\\dxx")) {
                    throw new IllegalArgumentException("status must be 3 digits or Nxx: " + pattern);
                }
                normalized.add(pattern);
            }
        }
        return Set.copyOf(normalized);
    }
}
