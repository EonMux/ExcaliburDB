// Ported from Indra OCR 1.1.2, lm.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.lm;

import com.eonmux.excalibur.ocr.array.NdArray;

import java.util.Map;

/**
 * One of {@link NgramLM}'s merged tables, as {@code lm.py} builds {@code p_keys}/{@code p_vals}
 * and {@code h_keys}/{@code h_vals}: every history length's keys tagged with the length in the
 * top bits ({@link NgramLM#TAG_SHIFT}), ascending as unsigned 64-bit values, and their values as
 * doubles. History length {@code n} occupies {@code [start[n], start[n + 1])}, which is where
 * Python's per-length tables ({@code k[n]}, {@code hk[n]}) are found. Immutable once built.
 */
final class KeyTable {

    private final long[] keys;
    private final double[] values;
    private final int[] start;

    private KeyTable(long[] keys, double[] values, int[] start) {
        this.keys = keys;
        this.values = values;
        this.start = start;
    }

    /**
     * Merges {@code <keyPrefix><n>} and {@code <valuePrefix><n>} for every {@code n} below
     * {@code order}. Each table must be strictly ascending, so the concatenation in length
     * order is the sorted merge Python's {@code argsort} builds.
     */
    static KeyTable merge(Map<String, NdArray> arrays, String keyPrefix, String valuePrefix,
                       int order) {
        long[][] keys = new long[order][];
        double[][] values = new double[order][];
        int[] start = new int[order + 1];
        for (int n = 0; n < order; n++) {
            keys[n] = NgramLM.require(arrays, keyPrefix + n).longs();
            values[n] = NgramLM.require(arrays, valuePrefix + n).doubles();
            if (keys[n].length != values[n].length) {
                throw new IllegalArgumentException(keyPrefix + n + " holds " + keys[n].length
                    + " keys but " + valuePrefix + n + " holds " + values[n].length
                    + " values");
            }
            checkKeys(keys[n], keyPrefix + n);
            start[n + 1] = Math.addExact(start[n], keys[n].length);
        }
        long[] merged = new long[start[order]];
        double[] mergedValues = new double[start[order]];
        for (int n = 0; n < order; n++) {
            long tag = (long) n << NgramLM.TAG_SHIFT;
            for (int i = 0; i < keys[n].length; i++) {
                merged[start[n] + i] = keys[n][i] | tag;
            }
            System.arraycopy(values[n], 0, mergedValues, start[n], values[n].length);
        }
        return new KeyTable(merged, mergedValues, start);
    }

    private static void checkKeys(long[] keys, String name) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] >>> NgramLM.TAG_SHIFT != 0) {
                throw new IllegalArgumentException(name + "[" + i + "] = "
                    + Long.toUnsignedString(keys[i]) + " reaches the tag bits");
            }
            if (i > 0 && Long.compareUnsigned(keys[i - 1], keys[i]) >= 0) {
                throw new IllegalArgumentException(name + " is not strictly ascending at "
                    + i);
            }
        }
    }

    /** The number of keys, every history length together. */
    int length() {
        return keys.length;
    }

    /** The tagged key at an index. */
    long key(int i) {
        return keys[i];
    }

    /** The value at an index. */
    double value(int i) {
        return values[i];
    }

    /** The number of keys of history length {@code n}. */
    int size(int n) {
        return start[n + 1] - start[n];
    }

    /** Where an untagged key of history length {@code n} is, or -1. */
    int find(int n, long key) {
        return find(keys, start[n], start[n + 1], key | ((long) n << NgramLM.TAG_SHIFT));
    }

    /** Where a tagged key is in the whole table, or -1. */
    int search(long taggedKey) {
        return find(keys, 0, keys.length, taggedKey);
    }

    /** The largest value, every history length together. */
    double maxValue() {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    /** The largest value of history length {@code n}. */
    double maxValue(int n) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = start[n]; i < start[n + 1]; i++) {
            m = Math.max(m, values[i]);
        }
        return m;
    }

    /** The leftmost insertion point of {@code key} in {@code [from, to)}, if it is there. */
    static int find(long[] keys, int from, int to, long key) {
        int lo = from;
        int hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Long.compareUnsigned(keys[mid], key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo < to && keys[lo] == key ? lo : -1;
    }
}
