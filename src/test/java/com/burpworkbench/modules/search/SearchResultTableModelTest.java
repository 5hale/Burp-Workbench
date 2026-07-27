package com.burpworkbench.modules.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchResultTableModelTest {
    @Test
    void modelOwnsOneUnboundedResultListWithoutRowVectors() {
        SearchResultTableModel model = new SearchResultTableModel();
        SearchResult first = new SearchResult(null, "Text", 10);
        SearchResult second = new SearchResult(null, "JSON", 20);
        List<SearchResult> canonical = List.of(first, second);
        SearchResultView view = new SearchResultView(canonical);

        model.bind(view);
        model.addVisibleIndices(new int[]{0, 1});

        assertEquals(2, model.getRowCount());
        assertSame(first, model.resultAt(0));
        assertSame(second, model.resultAt(1));
        assertThrows(UnsupportedOperationException.class, () -> model.results().clear());

        model.clear();
        assertEquals(0, model.getRowCount());
    }

    @Test
    void replaceKeepsEveryResult() {
        SearchResultTableModel model = new SearchResultTableModel();
        List<SearchResult> results = java.util.stream.IntStream.range(0, 1_000)
                .mapToObj(index -> new SearchResult(null, "", index))
                .toList();
        SearchResultView view = new SearchResultView(results);

        model.bind(view);
        model.replaceVisibleIndices(
                java.util.stream.IntStream.range(0, 1_000).toArray()
        );

        assertEquals(1_000, model.resultCount());
    }
}
