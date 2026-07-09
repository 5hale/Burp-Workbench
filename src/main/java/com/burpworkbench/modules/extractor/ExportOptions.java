package com.burpworkbench.modules.extractor;

import com.burpworkbench.core.filter.ExtensionFilters;
import com.burpworkbench.core.filter.MimeCategory;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record ExportOptions(
        StatusFilterMode statusFilterMode,
        Set<String> statusPatterns,
        Set<MimeCategory> mimeCategories,
        Set<String> extensions,
        boolean beautify
) {
    public ExportOptions {
        statusFilterMode = statusFilterMode == null ? StatusFilterMode.ALL : statusFilterMode;
        statusPatterns = normalizeStatusPatterns(statusPatterns == null ? Set.of() : statusPatterns);
        mimeCategories = mimeCategories == null || mimeCategories.isEmpty()
                ? Set.of()
                : Set.copyOf(mimeCategories);
        extensions = normalizeExtensions(extensions == null ? Set.of() : extensions);
    }

    public static ExportOptions defaults() {
        return new ExportOptions(
                StatusFilterMode.ALL,
                Set.of(),
                EnumSet.allOf(MimeCategory.class),
                Set.of(),
                false
        );
    }

    public ExportOptions(
            StatusFilterMode statusFilterMode,
            Set<String> statusPatterns,
            Set<MimeCategory> mimeCategories,
            Set<String> extensions
    ) {
        this(statusFilterMode, statusPatterns, mimeCategories, extensions, false);
    }

    public ExportOptions withSaveBeautifiedJavascriptCopy(boolean enabled) {
        return new ExportOptions(statusFilterMode, statusPatterns, mimeCategories, extensions, enabled);
    }

    public ExportOptions withBeautify(boolean enabled) {
        return new ExportOptions(statusFilterMode, statusPatterns, mimeCategories, extensions, enabled);
    }

    public boolean saveBeautifiedJavascriptCopy() {
        return beautify;
    }

    public static Set<String> parseExtensions(String value) {
        return ExtensionFilters.parseExtensions(value);
    }

    public static Set<String> parseStatusPatterns(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new TreeSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                if (!trimmed.matches("\\d{3}") && !trimmed.matches("\\dxx")) {
                    throw new IllegalArgumentException("status must be 3 digits or Nxx: " + trimmed);
                }
                parsed.add(trimmed);
            }
        }
        return parsed;
    }

    public boolean matchesFilter(ExportCandidate candidate) {
        return matchesStatus(candidate.statusCode()) && matchesFileKind(candidate);
    }

    public Optional<String> skipReason(ExportCandidate candidate) {
        if (!matchesStatus(candidate.statusCode())) {
            return Optional.of("filtered by status");
        }
        if (!matchesFileKind(candidate)) {
            return Optional.of("filtered by file kind");
        }
        return Optional.empty();
    }

    private boolean matchesStatus(int statusCode) {
        return switch (statusFilterMode) {
            case ALL -> true;
            case TWO_XX -> statusCode >= 200 && statusCode <= 299;
            case ONLY_200 -> statusCode == 200;
            case CUSTOM -> matchesCustomStatus(statusCode);
        };
    }

    private boolean matchesCustomStatus(int statusCode) {
        if (statusPatterns.isEmpty()) {
            return false;
        }

        String code = Integer.toString(statusCode);
        if (code.length() != 3) {
            return false;
        }

        for (String pattern : statusPatterns) {
            if (pattern.endsWith("xx")) {
                if (code.charAt(0) == pattern.charAt(0)) {
                    return true;
                }
            } else if (pattern.equals(code)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesFileKind(ExportCandidate candidate) {
        boolean hasMimeFilter = !mimeCategories.isEmpty();
        boolean hasExtensionFilter = !extensions.isEmpty();
        if (!hasMimeFilter && !hasExtensionFilter) {
            return true;
        }

        boolean mimeMatches = !hasMimeFilter || mimeCategories.contains(candidate.mimeCategory());
        boolean extensionMatches = !hasExtensionFilter || extensions.contains(candidate.extension());
        return mimeMatches && extensionMatches;
    }
    private static Set<String> normalizeExtensions(Set<String> values) {
        return ExtensionFilters.normalizeExtensions(values);
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
