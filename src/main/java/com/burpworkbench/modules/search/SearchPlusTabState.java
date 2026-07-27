package com.burpworkbench.modules.search;

import com.burpworkbench.core.filter.MimeCategory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class SearchPlusTabState {
    String title;
    String query = "";
    SearchMode mode = SearchMode.TEXT;
    boolean regex;
    boolean caseSensitive;
    boolean negativeAuto;
    String negativeFilter = "";
    String activeNegativeFilter = "";
    boolean requestHeaders = true;
    boolean requestBody = true;
    boolean responseHeaders = true;
    boolean responseBody = true;
    boolean includeTarget = true;
    boolean includeProxy = true;
    boolean includeRepeater = true;
    boolean includeOrganizer = true;
    boolean status2xx = true;
    boolean status3xx = true;
    boolean status4xx = true;
    boolean status5xx = true;
    boolean showExtension;
    String showExtensionText = "";
    boolean hideExtension;
    String hideExtensionText = "";
    final Map<MimeCategory, Boolean> mimeSelections = defaultMimeSelections();
    List<SearchResult> allResults = List.of();
    List<SearchResult> currentResults = List.of();
    int[] selectedModelRows = new int[0];
    String countText = "0 results";
    long searchRunId;
    boolean searchCancelled;
    boolean searchFailed;
    int skippedResults;
    int malformedItems;

    private SearchPlusTabState(String title) {
        rename(title);
    }

    static SearchPlusTabState initial(int number) {
        return new SearchPlusTabState(Integer.toString(number));
    }

    static boolean isValidTitle(String title) {
        return title != null && !title.trim().isEmpty();
    }

    void rename(String newTitle) {
        if (!isValidTitle(newTitle)) {
            throw new IllegalArgumentException("tab name must not be blank");
        }
        title = newTitle.trim();
    }

    String previewSearchExpression() {
        return previewSearchExpression(mode, query);
    }

    static String previewSearchExpression(SearchMode mode, String query) {
        String safeQuery = query == null ? "" : query;
        return mode == SearchMode.TEXT && !safeQuery.isBlank() ? safeQuery : "";
    }

    void setResults(List<SearchResult> allResults, List<SearchResult> currentResults) {
        this.allResults = copyResults(allResults);
        this.currentResults = copyResults(currentResults);
    }

    private static List<SearchResult> copyResults(List<SearchResult> results) {
        return results == null || results.isEmpty() ? List.of() : new ArrayList<>(results);
    }

    private static Map<MimeCategory, Boolean> defaultMimeSelections() {
        Map<MimeCategory, Boolean> selections = new EnumMap<>(MimeCategory.class);
        selections.put(MimeCategory.HTML, true);
        selections.put(MimeCategory.JAVASCRIPT, true);
        selections.put(MimeCategory.CSS, false);
        selections.put(MimeCategory.IMAGE, false);
        selections.put(MimeCategory.JSON, true);
        selections.put(MimeCategory.TEXT, true);
        selections.put(MimeCategory.OTHER, true);
        return selections;
    }
}
