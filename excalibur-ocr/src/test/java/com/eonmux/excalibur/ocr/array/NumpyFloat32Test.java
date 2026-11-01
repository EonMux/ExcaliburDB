package com.eonmux.excalibur.ocr.array;

import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("numpy's float32 functions")
class NumpyFloat32Test {

    @Test
    @DisplayName("compute exp bit for bit as np.exp does, through the subnormal and overflow ends")
    void expMatchesNumpy() {
        Map<String, NdArray> f = Fixtures.npz("imaging/numpy_order.npz");
        float[] x = f.get("exp_x").floats();
        float[] y = f.get("exp_y").floats();
        int correctlyRoundedMisses = 0;
        for (int i = 0; i < x.length; i++) {
            float got = NumpyFloat32.exp(x[i]);
            assertThat(Float.floatToIntBits(got)).as("exp(%s)", x[i])
                .isEqualTo(Float.floatToIntBits(y[i]));
            if ((float) Math.exp(x[i]) != y[i]) {
                correctlyRoundedMisses++;
            }
        }
        assertThat(x.length).isGreaterThan(5000);
        assertThat(correctlyRoundedMisses).as("inputs where numpy is not correctly rounded")
            .isGreaterThan(100);
    }

    @Test
    @DisplayName("return infinity, zero and NaN at the ends of the range")
    void specialValues() {
        assertThat(NumpyFloat32.exp(Float.NaN)).isNaN();
        assertThat(NumpyFloat32.exp(89f)).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(NumpyFloat32.exp(-104f)).isZero();
        assertThat(NumpyFloat32.exp(0f)).isEqualTo(1f);
        assertThat(NumpyFloat32.exp(-100f)).isPositive().isLessThan(Float.MIN_NORMAL);
    }
}
