package com.eonmux.excalibur.ocr.array;

import java.util.Arrays;
import java.util.Objects;

/**
 * numpy's reductions, orderings and searches over one-dimensional arrays, rounding and
 * breaking ties as numpy 2.5 does, for ports of Indra code that applies them to {@code float32}
 * box coordinates and logits.
 *
 * <p>The rounding matters: a float32 sum is numpy's pairwise sum, not a running one, a float32
 * mean divides that sum by the count in float64, and a float32 median is a float32 mean of the
 * two middle values. Indra compares all three with thresholds.</p>
 */
public final class NumpyOps {

    /** Below this many values numpy sums one after another; above it, eight running sums. */
    private static final int PAIRWISE_UNROLL = 8;
    /** Above this many values numpy splits the sum into two halves. */
    private static final int PAIRWISE_BLOCK = 128;

    private NumpyOps() {
        throw new AssertionError("Static methods only");
    }

    /**
     * {@code np.sum} of a float32 array: numpy's pairwise summation, in float32.
     *
     * @param values The values
     * @return Their sum, 0 for none
     */
    public static float sum(float[] values) {
        Objects.requireNonNull(values, "values must not be null");
        return sum(values, 0, values.length);
    }

    /**
     * {@code np.sum} of a contiguous float32 slice, such as one row of a matrix
     * ({@code m.sum(1)}).
     *
     * @param values The array
     * @param from   First index, inclusive
     * @param to     Last index, exclusive
     * @return The slice's sum
     */
    public static float sum(float[] values, int from, int to) {
        Objects.requireNonNull(values, "values must not be null");
        Objects.checkFromToIndex(from, to, values.length);
        return pairwise(values, from, to - from);
    }

    private static float pairwise(float[] a, int start, int n) {
        if (n < PAIRWISE_UNROLL) {
            float res = 0f;
            for (int i = 0; i < n; i++) {
                res += a[start + i];
            }
            return res;
        }
        if (n <= PAIRWISE_BLOCK) {
            float[] r = new float[PAIRWISE_UNROLL];
            System.arraycopy(a, start, r, 0, PAIRWISE_UNROLL);
            int i = PAIRWISE_UNROLL;
            for (; i < n - (n % PAIRWISE_UNROLL); i += PAIRWISE_UNROLL) {
                for (int j = 0; j < PAIRWISE_UNROLL; j++) {
                    r[j] += a[start + i + j];
                }
            }
            float res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
            for (; i < n; i++) {
                res += a[start + i];
            }
            return res;
        }
        int half = n / 2;
        half -= half % PAIRWISE_UNROLL;
        return pairwise(a, start, half) + pairwise(a, start + half, n - half);
    }

    /**
     * {@code np.sum} of a float64 slice, as of a list of Python floats: numpy's pairwise sum.
     *
     * @param values The array
     * @param from   First index, inclusive
     * @param to     Last index, exclusive
     * @return The slice's sum
     */
    public static double sum(double[] values, int from, int to) {
        Objects.requireNonNull(values, "values must not be null");
        Objects.checkFromToIndex(from, to, values.length);
        return pairwise(values, from, to - from);
    }

    private static double pairwise(double[] a, int start, int n) {
        if (n < PAIRWISE_UNROLL) {
            double res = 0.0;
            for (int i = 0; i < n; i++) {
                res += a[start + i];
            }
            return res;
        }
        if (n <= PAIRWISE_BLOCK) {
            double[] r = new double[PAIRWISE_UNROLL];
            System.arraycopy(a, start, r, 0, PAIRWISE_UNROLL);
            int i = PAIRWISE_UNROLL;
            for (; i < n - (n % PAIRWISE_UNROLL); i += PAIRWISE_UNROLL) {
                for (int j = 0; j < PAIRWISE_UNROLL; j++) {
                    r[j] += a[start + i + j];
                }
            }
            double res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
            for (; i < n; i++) {
                res += a[start + i];
            }
            return res;
        }
        int half = n / 2;
        half -= half % PAIRWISE_UNROLL;
        return pairwise(a, start, half) + pairwise(a, start + half, n - half);
    }

    /**
     * {@code np.mean} of float64 values, as of a list of Python floats.
     *
     * @param values The values, at least one
     * @return Their mean
     */
    public static double mean(double[] values) {
        requireNonEmpty(values == null ? -1 : values.length, "mean");
        return sum(values, 0, values.length) / values.length;
    }

    /**
     * {@code np.mean} of a float32 array: the pairwise float32 sum divided by the count in
     * float64 (numpy divides by an {@code intp} count), rounded back to float32.
     *
     * @param values The values, at least one
     * @return Their mean
     */
    public static float mean(float[] values) {
        requireNonEmpty(values == null ? -1 : values.length, "mean");
        return (float) ((double) sum(values) / values.length);
    }

    /**
     * {@code np.median} of a float32 array, which numpy returns as float32: the middle value,
     * or for an even count the float32 mean of the two middle values.
     *
     * @param values The values, at least one
     * @return Their median; NaN if any value is NaN
     */
    public static float median(float[] values) {
        requireNonEmpty(values == null ? -1 : values.length, "median");
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if (Float.isNaN(sorted[n - 1])) {
            return Float.NaN;
        }
        if (n % 2 == 1) {
            return sorted[n / 2];
        }
        return (float) ((double) (sorted[n / 2 - 1] + sorted[n / 2]) / 2);
    }

    /**
     * {@code np.median} of float64 values, such as a list of Python floats.
     *
     * @param values The values, at least one
     * @return Their median; NaN if any value is NaN
     */
    public static double median(double[] values) {
        requireNonEmpty(values == null ? -1 : values.length, "median");
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if (Double.isNaN(sorted[n - 1])) {
            return Double.NaN;
        }
        if (n % 2 == 1) {
            return sorted[n / 2];
        }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /**
     * {@code np.argsort} as a stable sort ({@code kind="stable"}): the indices that put the
     * values in ascending order, equal values in index order, NaN last.
     *
     * <p>numpy's default sort is not stable on x86-64 with AVX2: it is x86-simd-sort's vector
     * quicksort, whose bitonic networks place equal values by their lanes. Callers whose result
     * depends on that placement only for exactly equal keys get the stable order here.</p>
     *
     * @param values The values
     * @return The sorting permutation
     */
    public static int[] argsort(float[] values) {
        Objects.requireNonNull(values, "values must not be null");
        Integer[] idx = new Integer[values.length];
        Arrays.setAll(idx, i -> i);
        Arrays.sort(idx, (i, j) -> compare(values[i], values[j]));
        return Arrays.stream(idx).mapToInt(Integer::intValue).toArray();
    }

    /** numpy's float order: by value, -0.0 equal to 0.0, NaN after every number. */
    private static int compare(float a, float b) {
        if (a < b) {
            return -1;
        }
        if (a > b) {
            return 1;
        }
        boolean nanA = Float.isNaN(a);
        boolean nanB = Float.isNaN(b);
        return nanA == nanB ? 0 : nanA ? 1 : -1;
    }

    /**
     * {@code np.argmax}: the index of the first largest value, or of the first NaN, which numpy
     * counts as the largest.
     *
     * @param values The values, at least one
     * @return The index
     */
    public static int argmax(float[] values) {
        requireNonEmpty(values == null ? -1 : values.length, "argmax");
        int best = 0;
        for (int i = 0; i < values.length; i++) {
            if (Float.isNaN(values[i])) {
                return i;
            }
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * {@code np.argmax} of float64 values: the index of the first largest, or of the first NaN.
     *
     * @param values The values, at least one
     * @return The index
     */
    public static int argmax(double[] values) {
        requireNonEmpty(values == null ? -1 : values.length, "argmax");
        int best = 0;
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) {
                return i;
            }
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * {@code np.searchsorted(a, key, side="right")}: numpy's binary search for the first index
     * whose value is greater than the key. On sorted input that is the number of values not
     * greater than the key; on unsorted input it is whatever numpy's bisection finds, step for
     * step.
     *
     * @param a   The values, sorted ascending for the result to mean anything
     * @param key The value to place
     * @return The insertion index, from 0 to {@code a.length}
     */
    public static int searchsortedRight(double[] a, double key) {
        Objects.requireNonNull(a, "a must not be null");
        int min = 0;
        int max = a.length;
        while (min < max) {
            int mid = min + ((max - min) >> 1);
            if (less(key, a[mid])) {
                max = mid;
            } else {
                min = mid + 1;
            }
        }
        return min;
    }

    /** numpy's sort comparison: {@code a < b}, with NaN after every number. */
    private static boolean less(double a, double b) {
        return a < b || (Double.isNaN(b) && !Double.isNaN(a));
    }

    private static void requireNonEmpty(int length, String what) {
        if (length < 0) {
            throw new NullPointerException("values must not be null");
        }
        if (length == 0) {
            throw new IllegalArgumentException(what + " of no values");
        }
    }
}
