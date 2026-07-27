package com.burpworkbench.modules.search;

import com.burpworkbench.core.filter.MimeCategory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchOptionsMapperTest {
    private final SearchOptionsMapper mapper = new SearchOptionsMapper();

    @Test
    void mapsQueryWithoutApplyingDisplayFilters() {
        SearchOptions options = mapper.queryOptions(snapshot());

        assertEquals("needle", options.query());
        assertEquals(SearchMode.HEX, options.mode());
        assertTrue(options.regex());
        assertTrue(options.caseSensitive());
        assertTrue(options.requestHeaders());
        assertFalse(options.requestBody());
        assertTrue(options.includeTarget());
        assertTrue(options.includeRepeater());
        assertTrue(options.allStatus());
        assertFalse(options.mimeFilterEnabled());
        assertTrue(options.statusPatterns().isEmpty());
        assertTrue(options.extensions().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> mapper.filterOptions(snapshot()).mimeCategories().clear()
        );
    }

    @Test
    void mapsPostSearchAndNegativeFiltersWithExistingSemantics() {
        SearchOptions filter = mapper.filterOptions(snapshot());
        SearchOptions negative = mapper.negativeFilterOptions(snapshot(), "exclude");

        assertFalse(filter.allStatus());
        assertEquals(Set.of("2xx", "4xx"), filter.statusPatterns());
        assertTrue(filter.mimeFilterEnabled());
        assertEquals(Set.of(MimeCategory.JSON), filter.mimeCategories());
        assertEquals(Set.of(".json"), filter.extensions());
        assertEquals(Set.of(".map"), filter.excludedExtensions());
        assertFalse(filter.includeTarget());

        assertEquals("exclude", negative.query());
        assertEquals(SearchMode.HEX, negative.mode());
        assertTrue(negative.regex());
        assertTrue(negative.allStatus());
        assertFalse(negative.includeTarget());
        assertTrue(negative.requestHeaders());
        assertFalse(negative.requestBody());
    }

    private SearchOptionsSnapshot snapshot() {
        return new SearchOptionsSnapshot(
                "needle",
                SearchMode.HEX,
                true,
                true,
                true,
                false,
                true,
                false,
                true,
                false,
                true,
                false,
                Set.of("2xx", "4xx"),
                Set.of(MimeCategory.JSON),
                Set.of(".json"),
                Set.of(".map")
        );
    }
}
