package com.eonmux.excalibur.ocr.array;

import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sums and means against numpy's own ({@code tools/fixtures/gen_pairwise.py}). */
@DisplayName("Pairwise sums, rounded as numpy rounds them")
class NumpyPairwiseTest {

    @Test
    @DisplayName("match numpy's float32 and float64 sums and means bit for bit")
    void matchesNumpy() {
        Map<String, NdArray> arrays = Fixtures.npz("array/pairwise.npz");
        int cases = 0;
        for (int k = 0; arrays.containsKey("f" + k); k++, cases++) {
            float[] f = arrays.get("f" + k).floats();
            float[] fWant = arrays.get("fsum" + k).floats();
            double[] d = arrays.get("d" + k).doubles();
            double[] dWant = arrays.get("dsum" + k).doubles();

            assertThat(NumpyOps.sum(f, 0, f.length)).as("float32 sum %d", k).isEqualTo(fWant[0]);
            assertThat(NumpyOps.mean(f)).as("float32 mean %d", k).isEqualTo(fWant[1]);
            assertThat(NumpyOps.sum(d, 0, d.length)).as("float64 sum %d", k).isEqualTo(dWant[0]);
            assertThat(NumpyOps.mean(d)).as("float64 mean %d", k).isEqualTo(dWant[1]);
        }
        assertThat(cases).isEqualTo(32);
    }
}
