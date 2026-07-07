package com.burpworkbench.core.filter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class ExtensionFilters {
    private ExtensionFilters() {
    }

    public static Set<String> parseExtensions(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new TreeSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(ExtensionFilters::normalizeExtension)
                .forEach(parsed::add);
        return Set.copyOf(parsed);
    }

    public static Set<String> normalizeExtensions(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(normalizeExtension(value));
            }
        }
        return Set.copyOf(normalized);
    }

    public static String normalizeExtension(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized : "." + normalized;
    }
}
