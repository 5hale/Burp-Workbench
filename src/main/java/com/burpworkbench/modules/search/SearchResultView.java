package com.burpworkbench.modules.search;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * A filtered view that stores primitive indices into one canonical result list.
 *
 * <p>The view never owns {@link SearchResult} references. This keeps each
 * result referenced by the tab's canonical session only, while still allowing
 * JTable and extraction actions to use a regular read-only List view.</p>
 */
final class SearchResultView extends AbstractList<SearchResult>
        implements RandomAccess {
    private final List<SearchResult> canonicalResults;
    private int[] visibleIndices = new int[0];
    private int size;

    SearchResultView(List<SearchResult> canonicalResults) {
        this.canonicalResults = Objects.requireNonNull(
                canonicalResults,
                "canonicalResults"
        );
    }

    @Override
    public SearchResult get(int index) {
        Objects.checkIndex(index, size);
        return canonicalResults.get(visibleIndices[index]);
    }

    @Override
    public int size() {
        return size;
    }

    void clearView() {
        size = 0;
        visibleIndices = new int[0];
    }

    void replaceVisibleIndices(int[] replacement) {
        int[] safeReplacement =
                replacement == null ? new int[0] : replacement;
        validateIndices(safeReplacement);
        // The background filter hands off a freshly built primitive array.
        // Taking ownership avoids another full-size allocation at EDT commit.
        visibleIndices = safeReplacement;
        size = safeReplacement.length;
    }

    void addVisibleIndices(int[] additionalIndices) {
        if (additionalIndices == null || additionalIndices.length == 0) {
            return;
        }
        validateIndices(additionalIndices);
        ensureCapacity(size + additionalIndices.length);
        System.arraycopy(
                additionalIndices,
                0,
                visibleIndices,
                size,
                additionalIndices.length
        );
        size += additionalIndices.length;
    }

    private void validateIndices(int[] indices) {
        for (int index : indices) {
            if (index < 0 || index >= canonicalResults.size()) {
                throw new IndexOutOfBoundsException(
                        "result index " + index
                                + " outside canonical size "
                                + canonicalResults.size()
                );
            }
        }
    }

    private void ensureCapacity(int requiredCapacity) {
        if (visibleIndices.length >= requiredCapacity) {
            return;
        }
        int grownCapacity = Math.max(
                requiredCapacity,
                Math.max(16, visibleIndices.length * 2)
        );
        visibleIndices = Arrays.copyOf(visibleIndices, grownCapacity);
    }

    static final class IndexBuffer {
        private int[] values = new int[16];
        private int size;

        void add(int value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        void addAll(int[] additionalValues) {
            if (additionalValues == null || additionalValues.length == 0) {
                return;
            }
            ensureCapacity(size + additionalValues.length);
            System.arraycopy(
                    additionalValues,
                    0,
                    values,
                    size,
                    additionalValues.length
            );
            size += additionalValues.length;
        }

        int size() {
            return size;
        }

        int[] toArray() {
            return size == 0 ? new int[0] : Arrays.copyOf(values, size);
        }

        void clear() {
            size = 0;
            if (values.length > 1_024) {
                values = new int[16];
            }
        }

        private void ensureCapacity(int requiredCapacity) {
            if (values.length >= requiredCapacity) {
                return;
            }
            values = Arrays.copyOf(
                    values,
                    Math.max(requiredCapacity, values.length * 2)
            );
        }
    }
}
