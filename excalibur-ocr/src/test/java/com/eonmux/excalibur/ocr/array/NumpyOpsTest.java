package com.eonmux.excalibur.ocr.array;

import com.eonmux.excalibur.ocr.testing.Fixtures;
import com.eonmux.excalibur.ocr.layout.FixtureJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("numpy's reductions, orders and searches round and break ties as numpy does")
class NumpyOpsTest {

    /** {@code ((i * 7919) % 2001 - 1000) * 0.37} as float32, i = 0..299. */
    private static float[] spread(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = (float) ((i * 7919) % 2001 - 1000) * 0.37f;
        }
        return a;
    }

    @Test
    @DisplayName("float32 sums are numpy's pairwise sums, not running sums")
    void sum() {
        float[] twelve = {-651.8f, -174.7f, 1663.7f, 659.1f, -1641.4f, -5.2f, -623.5f, 148.6f,
            -1608.2f, 241.8f, 235.4f, 1575.6f};
        assertThat(NumpyOps.sum(twelve)).isEqualTo((float) -0x1.69334p+7);
        float[] spread = spread(300);
        assertThat(NumpyOps.sum(spread)).as("split into halves above 128")
            .isEqualTo((float) 0x1.6025c4p+10);
        assertThat(NumpyOps.sum(spread, 0, 100)).isEqualTo((float) 0x1.1788f4p+9);
        assertThat(NumpyOps.sum(new float[] {0.1f, 0.2f, 0.3f, 0.4f, 0.5f})).isEqualTo(1.5f);
        assertThat(NumpyOps.sum(new float[0])).isZero();
    }

    @Test
    @DisplayName("means divide in float64 and medians average the middle pair in float32")
    void meanAndMedian() {
        assertThat(NumpyOps.mean(new float[] {0.1f, 0.2f, 0.7f})).isEqualTo(0.33333334f);
        assertThat(NumpyOps.median(new float[] {1.1f, 2.3f, 0.2f, 5.9f})).isEqualTo(1.7f);
        assertThat(NumpyOps.median(new float[] {3f, 1f, 2f})).isEqualTo(2f);
        assertThat(NumpyOps.median(new float[] {1f, Float.NaN})).isNaN();
        assertThat(NumpyOps.median(new double[] {1.0, 4.0, 2.0, 3.0})).isEqualTo(2.5);
        assertThat(NumpyOps.median(new double[] {5.0})).isEqualTo(5.0);
        assertThat(NumpyOps.median(new double[] {Double.NaN, 1.0})).isNaN();

        Map<String, NdArray> arrays = Fixtures.npz("layout/synthetic.npz");
        Map<?, ?> doc = (Map<?, ?>) FixtureJson.parse(Fixtures.text("layout/synthetic.json"));
        List<?> medians = (List<?>) ((Map<?, ?>) doc.get("numeric")).get("medians");
        for (int k = 0; k < medians.size(); k++) {
            assertThat(NumpyOps.median(arrays.get("med" + k).floats())).as("case %d", k)
                .isEqualTo(((Number) medians.get(k)).floatValue());
        }
    }

    @Test
    @DisplayName("argsort is stable with NaN last, argmax takes the first maximum or NaN")
    void orders() {
        assertThat(NumpyOps.argsort(new float[] {2f, 1f, 2f, -0f, 0f, Float.NaN, 1f}))
            .containsExactly(3, 4, 1, 6, 0, 2, 5);
        assertThat(NumpyOps.argsort(new float[0])).isEmpty();
        assertThat(NumpyOps.argmax(new float[] {1f, 3f, 3f})).isEqualTo(1);
        assertThat(NumpyOps.argmax(new float[] {1f, Float.NaN, 3f, Float.NaN})).isEqualTo(1);
        assertThat(NumpyOps.argmax(new double[] {Double.NEGATIVE_INFINITY, -1, -1})).isEqualTo(1);
        assertThat(NumpyOps.argmax(new double[] {0, Double.NaN})).isEqualTo(1);
    }

    @Test
    @DisplayName("searchsorted bisects as numpy does, on sorted and unsorted values")
    void searchsorted() {
        assertThat(NumpyOps.searchsortedRight(new double[] {1, 2, 2, 5}, 2.0)).isEqualTo(3);
        assertThat(NumpyOps.searchsortedRight(new double[] {3, 1, 2, 5}, 2.0)).isEqualTo(3);
        assertThat(NumpyOps.searchsortedRight(new double[] {1, Double.NaN}, 5.0)).isEqualTo(1);
        assertThat(NumpyOps.searchsortedRight(
            new double[] {Double.NEGATIVE_INFINITY, 10, Double.POSITIVE_INFINITY}, -3)).isEqualTo(1);
        assertThat(NumpyOps.searchsortedRight(new double[0], 1)).isZero();
    }

    @Test
    @DisplayName("empty and missing arrays are refused")
    void refusals() {
        assertThatThrownBy(() -> NumpyOps.mean(new float[0]))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mean of no values");
        assertThatThrownBy(() -> NumpyOps.median(new double[0])).hasMessageContaining("median");
        assertThatThrownBy(() -> NumpyOps.argmax(new float[0])).hasMessageContaining("argmax");
        assertThatThrownBy(() -> NumpyOps.argmax((double[]) null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NumpyOps.sum(new float[2], 1, 3))
            .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> NumpyOps.searchsortedRight(null, 0))
            .isInstanceOf(NullPointerException.class);
    }
}
