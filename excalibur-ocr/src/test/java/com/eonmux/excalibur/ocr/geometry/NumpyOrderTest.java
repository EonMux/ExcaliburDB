package com.eonmux.excalibur.ocr.geometry;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("numpy and CPython orderings")
class NumpyOrderTest {

    private static Map<String, NdArray> f;

    @BeforeAll
    static void load() {
        f = Fixtures.npz("imaging/numpy_order.npz");
    }

    @Test
    @DisplayName("order tied keys as np.argsort does on AVX2 hardware")
    void argsort() {
        long[] sizes = f.get("argsort_sizes").longs();
        double[] keys = f.get("argsort_keys").doubles();
        long[] orders = f.get("argsort_orders").longs();
        int offset = 0;
        int unstable = 0;
        for (long size : sizes) {
            int n = (int) size;
            double[] k = Arrays.copyOfRange(keys, offset, offset + n);
            int[] expected = Arrays.stream(orders, offset, offset + n).mapToInt(v -> (int) v)
                .toArray();
            int[] got = NumpyOrder.argsort(k);

            assertThat(got).as("keys %s", Arrays.toString(k)).containsExactly(expected);
            Integer[] stable = new Integer[n];
            Arrays.setAll(stable, i -> i);
            Arrays.sort(stable, (a, b) -> Double.compare(k[a], k[b]));
            if (!Arrays.equals(Arrays.stream(stable).mapToInt(i -> i).toArray(), expected)) {
                unstable++;
            }
            offset += n;
        }
        assertThat(offset).isEqualTo(keys.length);
        assertThat(unstable).as("cases where a stable sort would differ").isGreaterThan(50);
    }

    @Test
    @DisplayName("fill float ranges as np.arange does")
    void arange() {
        double[] params = f.get("arange_params").doubles();
        long[] sizes = f.get("arange_sizes").longs();
        double[] values = f.get("arange_values").doubles();
        int offset = 0;
        for (int i = 0; i < sizes.length; i++) {
            int n = (int) sizes[i];
            double[] got = NumpyOrder.arange(params[i * 3], params[i * 3 + 1], params[i * 3 + 2]);

            assertThat(got).as("arange %s", Arrays.toString(Arrays.copyOfRange(params, i * 3,
                i * 3 + 3))).containsExactly(Arrays.copyOfRange(values, offset, offset + n));
            offset += n;
        }
        assertThat(offset).isEqualTo(values.length);
    }

    @Test
    @DisplayName("iterate {0.0, a, b} in CPython's order for every pair of coarse skew angles")
    void setOrder() {
        double[] pairs = f.get("set_pairs").doubles();
        double[] orders = f.get("set_orders").doubles();
        for (int i = 0; i < pairs.length / 2; i++) {
            double a = pairs[i * 2];
            double b = pairs[i * 2 + 1];

            assertThat(NumpyOrder.setIterationOrder(0.0, a, b)).as("{0.0, %s, %s}", a, b)
                .containsExactly(Arrays.copyOfRange(orders, i * 3, i * 3 + 3));
        }
        assertThat(pairs.length / 2).isEqualTo(46 * 45);
    }

    @Test
    @DisplayName("reject what they do not reproduce")
    void validates() {
        assertThatThrownBy(() -> NumpyOrder.argsort(new double[257]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumpyOrder.argsort(new double[] {1, Double.NaN}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumpyOrder.arange(0, 1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumpyOrder.setIterationOrder(0.5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumpyOrder.setIterationOrder(1, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumpyOrder.setIterationOrder(1, 2, 3, 4, 5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(NumpyOrder.setIterationOrder(-1.0, 7.0, 15.0)).containsExactly(15.0, -1.0, 7.0);
        assertThat(NumpyOrder.argsort(new double[] {2, 1})).containsExactly(1, 0);
        assertThat(NumpyOrder.arange(1, 1.5, 1)).containsExactly(1.0);
    }
}
