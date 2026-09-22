package com.eonmux.excalibur.graph.store;

/**
 * Stable sort of an {@code int[]} of indices under a caller-supplied comparison.
 *
 * <p>{@code Arrays.sort} cannot do this without boxing every index into an {@code Integer} to reach
 * a {@code Comparator}, which for a hundred million edges is a hundred million objects the
 * collector then has to walk. This sorts the primitive array directly.
 *
 * <p>Stability is not a nicety here. The edge store is ordered by (subject, relation) jointly, and
 * the build reaches that order by sorting on one component and then stably sorting on the other —
 * which only produces the intended order if the second sort preserves the first. Supersession
 * likewise depends on edges with equal keys keeping the order they were given.
 */
final class IndexSort {

    /**
     * Runs below this length are sorted by insertion, which beats merging for small inputs and
     * removes the recursion's base-case overhead.
     */
    private static final int INSERTION_THRESHOLD = 32;

    /** Comparison over two positions of the caller's data, by index. */
    @FunctionalInterface
    interface IndexComparator {
        /** Negative, zero or positive as {@code a} sorts before, with, or after {@code b}. */
        int compare(int a, int b);
    }

    private IndexSort() {
    }

    /**
     * Sorts {@code indices} in place, stably.
     *
     * <p>Bottom-up merge sort: one scratch array, no recursion, and a guaranteed O(n log n)
     * regardless of input order — which matters because edge input is very often already grouped by
     * subject, exactly the pattern that makes a naive quicksort quadratic.
     */
    static void sort(int[] indices, IndexComparator comparator) {
        if (indices == null) {
            throw new IllegalArgumentException("indices must not be null");
        }
        if (comparator == null) {
            throw new IllegalArgumentException("comparator must not be null");
        }
        int n = indices.length;
        if (n < 2) {
            return;
        }

        int[] scratch = new int[n];
        int[] source = indices;
        int[] target = scratch;

        for (int start = 0; start < n; start += INSERTION_THRESHOLD) {
            insertionSort(source, start, Math.min(start + INSERTION_THRESHOLD, n), comparator);
        }

        for (int width = INSERTION_THRESHOLD; width < n; width <<= 1) {
            for (int start = 0; start < n; start += width << 1) {
                int mid = Math.min(start + width, n);
                int end = Math.min(start + (width << 1), n);
                merge(source, target, start, mid, end, comparator);
            }
            int[] swap = source;
            source = target;
            target = swap;
        }

        if (source != indices) {
            System.arraycopy(source, 0, indices, 0, n);
        }
    }

    private static void insertionSort(int[] a, int from, int to, IndexComparator comparator) {
        for (int i = from + 1; i < to; i++) {
            int value = a[i];
            int j = i - 1;
            // Strictly greater, so equal elements never move past one another: this is what keeps
            // the sort stable.
            while (j >= from && comparator.compare(a[j], value) > 0) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = value;
        }
    }

    private static void merge(int[] source, int[] target, int start, int mid, int end,
                              IndexComparator comparator) {
        int left = start;
        int right = mid;
        for (int i = start; i < end; i++) {
            // Take from the left run on a tie, which preserves the earlier element's position.
            if (left < mid && (right >= end || comparator.compare(source[left], source[right]) <= 0)) {
                target[i] = source[left++];
            } else {
                target[i] = source[right++];
            }
        }
    }
}
