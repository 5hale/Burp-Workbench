package com.burpworkbench.modules.search;

import javax.swing.table.AbstractTableModel;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SearchResultTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            "Source", "Host", "Method", "URL", "Status", "MIME", "Length", "Time"
    };
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SearchResultView results =
            new SearchResultView(new ArrayList<>());
    private List<SearchResult> readOnlyResults = Collections.unmodifiableList(results);

    @Override
    public int getRowCount() {
        return results.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        SearchResult result = results.get(rowIndex);
        HttpExchange exchange = result.exchange();
        return switch (columnIndex) {
            case 0 -> exchange.source();
            case 1 -> exchange.host();
            case 2 -> exchange.method();
            case 3 -> exchange.url();
            case 4 -> exchange.statusCode() < 0 ? "" : exchange.statusCode();
            case 5 -> result.mime();
            case 6 -> result.length();
            case 7 -> exchange.time() == null ? "" : TIME_FORMAT.format(exchange.time());
            default -> throw new IndexOutOfBoundsException("column: " + columnIndex);
        };
    }

    int resultCount() {
        return results.size();
    }

    SearchResult resultAt(int modelRow) {
        return modelRow >= 0 && modelRow < results.size() ? results.get(modelRow) : null;
    }

    List<SearchResult> results() {
        return readOnlyResults;
    }

    void bind(SearchResultView resultView) {
        results = resultView == null
                ? new SearchResultView(new ArrayList<>())
                : resultView;
        readOnlyResults = Collections.unmodifiableList(results);
        fireTableDataChanged();
    }

    void clear() {
        int previousSize = results.size();
        if (previousSize == 0) {
            return;
        }
        results.clearView();
        fireTableRowsDeleted(0, previousSize - 1);
    }

    void replaceVisibleIndices(int[] replacement) {
        results.replaceVisibleIndices(replacement);
        fireTableDataChanged();
    }

    void addVisibleIndices(int[] additionalIndices) {
        if (additionalIndices == null || additionalIndices.length == 0) {
            return;
        }
        int first = results.size();
        results.addVisibleIndices(additionalIndices);
        fireTableRowsInserted(first, results.size() - 1);
    }
}
