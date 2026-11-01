// Reproduces documented numpy 2.5 and CPython 3 behaviour that Indra OCR's skew search depends
// on: np.arange with a float step, np.argsort's order among equal keys (x86-simd-sort's AVX2
// argsort network), and the iteration order of a small set of floats.
package com.eonmux.excalibur.ocr.geometry;

/**
 * The orderings that decide ties in Indra's skew search, reproduced from numpy and CPython.
 *
 * <p>{@code estimate_skew} keeps the first of equal scores in three places: the two best coarse
 * angles ({@code np.argsort(scores)[-2:]}), the iteration over the set of start angles, and
 * Python's {@code max}, which keeps the first maximum. Each is reproduced here so a page whose
 * scores tie picks the same angle as Indra.</p>
 */
final class NumpyOrder {

    /** Keys per AVX2 register of 64-bit values. */
    private static final int LANES = 4;
    /** x86-simd-sort sorts arrays up to this length with one bitonic network. */
    static final int MAX_NETWORK_SORT = 256;
    private static final int[] SWAP_PAIRS = {1, 0, 3, 2};
    private static final int[] SWAP_HALVES = {2, 3, 0, 1};
    private static final int[] REVERSE = {3, 2, 1, 0};
    private static final long NO_INDEX = -1L;

    private NumpyOrder() {
        throw new AssertionError("Static methods only");
    }

    /**
     * {@code np.arange(start, stop, step)} for floats: {@code ceil((stop - start) / step)}
     * values, the first {@code start}, the second {@code start + step}, and each later one
     * {@code start + i * delta} with {@code delta} the difference of the first two, as numpy
     * fills them.
     *
     * @param start The first value
     * @param stop  The bound the values stop short of
     * @param step  The step, nonzero
     * @return The values
     */
    static double[] arange(double start, double stop, double step) {
        if (step == 0 || !Double.isFinite(step)) {
            throw new IllegalArgumentException("arange needs a finite nonzero step, got " + step);
        }
        double length = Math.ceil((stop - start) / step);
        if (!(length > 0)) {
            return new double[0];
        }
        int n = Math.toIntExact((long) length);
        double[] values = new double[n];
        values[0] = start;
        if (n > 1) {
            values[1] = start + step;
            double delta = values[1] - start;
            for (int i = 2; i < n; i++) {
                values[i] = start + i * delta;
            }
        }
        return values;
    }

    /**
     * {@code np.argsort(keys)} for {@code float64} keys without NaN, as numpy 2.5 computes it
     * on AVX2 hardware: x86-simd-sort's {@code avx2_argsort}.
     *
     * <p>An array already in order keeps the identity permutation. Otherwise the keys are
     * loaded four to a register, padded with infinity, each register sorted by a 4-lane
     * network, and the registers merged by bitonic networks; a compare-exchange swaps two
     * entries only when the first key is greater, which fixes where equal keys end up.</p>
     *
     * @param keys The keys, at most {@value #MAX_NETWORK_SORT}, none NaN
     * @return The indices that sort them
     * @throws IllegalArgumentException for more keys, or a NaN key
     */
    static int[] argsort(double[] keys) {
        int n = keys.length;
        if (n > MAX_NETWORK_SORT) {
            throw new IllegalArgumentException("argsort reproduces numpy for at most "
                + MAX_NETWORK_SORT + " keys, got " + n);
        }
        int[] order = new int[n];
        boolean sorted = true;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(keys[i])) {
                throw new IllegalArgumentException("argsort key " + i + " is NaN");
            }
            order[i] = i;
            sorted &= i == 0 || !(keys[i] < keys[i - 1]);
        }
        if (n <= 1 || sorted) {
            return order;
        }
        int registers = MAX_NETWORK_SORT / LANES;
        while (registers > 1 && n * 2 <= registers * LANES) {
            registers /= 2;
        }
        Network net = new Network(keys, registers);
        net.sort();
        for (int i = 0; i < n; i++) {
            order[i] = (int) net.index[i / LANES][i % LANES];
        }
        return order;
    }

    /**
     * The order in which CPython iterates a set built by inserting the given integral floats,
     * such as {@code {0.0, *two_angles}}: the slots of its 8-entry hash table, with
     * {@code hash(float(n)) == n} ({@code -2} for {@code -1}) and CPython's probe sequence.
     *
     * @param values Distinct integral values, at most 4 (a set of up to 4 keeps 8 slots)
     * @return The values in iteration order
     */
    static double[] setIterationOrder(double... values) {
        if (values.length > 4) {
            throw new IllegalArgumentException("only sets of up to 4 keep an 8-slot table, got "
                + values.length);
        }
        final int mask = 7;
        Double[] table = new Double[mask + 1];
        for (double v : values) {
            long hash = integralHash(v);
            long perturb = hash;
            int i = (int) (hash & mask);
            while (table[i] != null) {
                if (table[i] == v) {
                    throw new IllegalArgumentException("set values must be distinct, " + v
                        + " repeats");
                }
                perturb >>>= 5;
                i = (int) ((i * 5L + 1 + perturb) & mask);
            }
            table[i] = v;
        }
        double[] order = new double[values.length];
        int k = 0;
        for (Double v : table) {
            if (v != null) {
                order[k++] = v;
            }
        }
        return order;
    }

    /** CPython's {@code hash} of an integral float. */
    private static long integralHash(double v) {
        if (v != Math.rint(v) || Math.abs(v) >= 0x1p52) {
            throw new IllegalArgumentException("set order is reproduced for integral values, got "
                + v);
        }
        long n = (long) v;
        return n == -1 ? -2 : n;
    }

    /** x86-simd-sort's key-index bitonic network over 4-lane registers. */
    private static final class Network {
        final double[][] key;
        final long[][] index;

        Network(double[] keys, int registers) {
            key = new double[registers][LANES];
            index = new long[registers][LANES];
            for (int r = 0; r < registers; r++) {
                for (int l = 0; l < LANES; l++) {
                    int p = r * LANES + l;
                    key[r][l] = p < keys.length ? keys[p] : Double.POSITIVE_INFINITY;
                    index[r][l] = p < keys.length ? p : NO_INDEX;
                }
            }
        }

        void sort() {
            for (int r = 0; r < key.length; r++) {
                merge(r, SWAP_PAIRS, 0xA);
                merge(r, REVERSE, 0xC);
                merge(r, SWAP_PAIRS, 0xA);
            }
            for (int group = 2; group <= key.length; group *= 2) {
                for (int g = 0; g < key.length / group; g++) {
                    mergeRegisters(g * group, group);
                }
            }
        }

        /** {@code cmp_merge}: lanes in {@code maxLanes} take the larger key of their pair. */
        private void merge(int r, int[] partner, int maxLanes) {
            double[] k = key[r];
            long[] i = index[r];
            double[] nk = new double[LANES];
            long[] ni = new long[LANES];
            for (int l = 0; l < LANES; l++) {
                double other = k[partner[l]];
                double pick = ((maxLanes >> l) & 1) != 0 ? Math.max(k[l], other)
                    : Math.min(k[l], other);
                nk[l] = pick;
                ni[l] = pick == k[l] ? i[l] : i[partner[l]];
            }
            key[r] = nk;
            index[r] = ni;
        }

        /** {@code bitonic_merge_n_vec} over registers {@code first .. first + count - 1}. */
        private void mergeRegisters(int first, int count) {
            for (int i = 0; i < count / 2; i++) {
                int j = first + count - i - 1;
                reverse(j);
                exchange(first + i, j);
                reverse(j);
            }
            for (int num = count / 2; num >= 2; num /= 2) {
                for (int j = 0; j < count; j += num) {
                    for (int i = 0; i < num / 2; i++) {
                        exchange(first + i + j, first + i + j + num / 2);
                    }
                }
            }
            for (int r = first; r < first + count; r++) {
                merge(r, SWAP_HALVES, 0xC);
                merge(r, SWAP_PAIRS, 0xA);
            }
        }

        private void reverse(int r) {
            double[] k = key[r];
            long[] i = index[r];
            key[r] = new double[] {k[REVERSE[0]], k[REVERSE[1]], k[REVERSE[2]], k[REVERSE[3]]};
            index[r] = new long[] {i[REVERSE[0]], i[REVERSE[1]], i[REVERSE[2]], i[REVERSE[3]]};
        }

        /** {@code COEX}: lane-wise compare-exchange, swapping only when {@code a > b}. */
        private void exchange(int a, int b) {
            for (int l = 0; l < LANES; l++) {
                double ka = key[a][l];
                double kb = key[b][l];
                if (Math.min(ka, kb) != ka) {
                    key[a][l] = kb;
                    key[b][l] = ka;
                    long t = index[a][l];
                    index[a][l] = index[b][l];
                    index[b][l] = t;
                }
            }
        }
    }
}
