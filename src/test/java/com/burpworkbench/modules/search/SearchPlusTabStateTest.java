package com.burpworkbench.modules.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchPlusTabStateTest {
    @Test
    void initialTitlesUseNumbersOnly() {
        assertEquals("1", SearchPlusTabState.initial(1).title);
        assertEquals("2", SearchPlusTabState.initial(2).title);
        assertEquals("3", SearchPlusTabState.initial(3).title);
    }

    @Test
    void renameRejectsBlankTitleAndTrimsValidTitle() {
        SearchPlusTabState state = SearchPlusTabState.initial(1);

        assertThrows(IllegalArgumentException.class, () -> state.rename("  "));
        state.rename("  scoped search  ");

        assertEquals("scoped search", state.title);
    }

    @Test
    void previewSearchExpressionUsesTextQueriesOnly() {
        assertEquals("admin", SearchPlusTabState.previewSearchExpression(SearchMode.TEXT, "admin"));
        assertEquals("", SearchPlusTabState.previewSearchExpression(SearchMode.TEXT, "  "));
        assertEquals("", SearchPlusTabState.previewSearchExpression(SearchMode.HEX, "61 64 6d 69 6e"));
    }

    @Test
    void tabOwnsCanonicalResultsAndPrimitiveVisibleView() {
        SearchPlusTabState state = SearchPlusTabState.initial(1);
        SearchResult result = new SearchResult(null, "text", 4);
        SearchResultTableModel model = new SearchResultTableModel();

        state.allResults.add(result);
        state.visibleResults.addVisibleIndices(new int[]{0});
        model.bind(state.visibleResults);

        assertEquals(1, state.allResults.size());
        assertEquals(1, state.visibleResults.size());
        assertEquals(1, model.resultCount());
    }
}
