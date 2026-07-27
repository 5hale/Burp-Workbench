package com.burpworkbench.modules.search;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class ExtensionFilters {
    private ExtensionFilters() {
    }

    static Set<String> parseExtensions(String value) {
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

    private static String normalizeExtension(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized : "." + normalized;
    }
}
