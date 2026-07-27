package com.burpworkbench.modules.search;

import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Immutable, background-safe view-filter plan for one UI criteria version.
 *
 * <p>Each plan is consumed by one background execution path. UI changes publish
 * a new plan instead of mutating a matcher already in use.</p>
 */
final class SearchFilterPlan {
    private final long version;
    private final SearchEngine searchEngine;
    private final SearchOptions filterOptions;
    private final SearchEngine.PreparedSearch negativeSearch;

    SearchFilterPlan(
            long version,
            SearchEngine searchEngine,
            SearchOptions filterOptions,
            SearchOptions negativeFilterOptions
    ) {
        this.version = version;
        this.searchEngine = searchEngine;
        this.filterOptions = filterOptions;
        this.negativeSearch = negativeFilterOptions == null
                ? null
                : searchEngine.prepare(negativeFilterOptions);
    }

    long version() {
        return version;
    }

    Evaluation evaluate(SearchResult result) {
        if (!searchEngine.matchesFilters(result.exchange(), filterOptions)) {
            return Evaluation.HIDDEN;
        }
        if (negativeSearch == null) {
            return Evaluation.VISIBLE;
        }
        try {
            return negativeSearch.matches(result.exchange())
                    ? Evaluation.HIDDEN
                    : Evaluation.VISIBLE;
        } catch (SearchItemTimeoutException exception) {
            // A timed-out negative check cannot safely hide the result.
            return Evaluation.VISIBLE_AFTER_TIMEOUT;
        }
    }

    Outcome evaluateAll(List<SearchResult> results) {
        SearchResultView.IndexBuffer visibleIndices =
                new SearchResultView.IndexBuffer();
        int regexTimeoutItems = 0;
        for (int index = 0; index < results.size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            Evaluation evaluation = evaluate(results.get(index));
            if (evaluation.visible()) {
                visibleIndices.add(index);
            }
            regexTimeoutItems += evaluation.regexTimeoutItems();
        }
        return new Outcome(visibleIndices.toArray(), regexTimeoutItems);
    }

    record Evaluation(boolean visible, int regexTimeoutItems) {
        private static final Evaluation HIDDEN = new Evaluation(false, 0);
        private static final Evaluation VISIBLE = new Evaluation(true, 0);
        private static final Evaluation VISIBLE_AFTER_TIMEOUT = new Evaluation(true, 1);
    }

    record Outcome(int[] visibleIndices, int regexTimeoutItems) {
    }
}
