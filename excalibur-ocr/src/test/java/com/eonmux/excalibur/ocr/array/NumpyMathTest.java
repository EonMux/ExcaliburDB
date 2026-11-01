package com.eonmux.excalibur.ocr.array;

import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("float32 logarithms and exponentials round as numpy and glibc round them")
class NumpyMathTest {

    @Test
    @DisplayName("np.logaddexp(0, -a) matches numpy bit for bit, ties and extremes included")
    void logaddexp() {
        Map<String, NdArray> a = Fixtures.npz("layout/synthetic.npz");
        float[] in = a.get("lae_in").floats();
        float[] out = a.get("lae_out").floats();
        for (int i = 0; i < in.length; i++) {
            assertThat(NumpyMath.logaddexp(0f, -in[i])).as("a = %s", in[i]).isEqualTo(out[i]);
        }
        assertThat(NumpyMath.logaddexp(Float.NaN, 1f)).isNaN();
        assertThat(NumpyMath.logaddexp(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY))
            .isEqualTo(Float.NEGATIVE_INFINITY);
    }

    @Test
    @DisplayName("np.log on float32 is numpy's vector kernel, not the correctly rounded value")
    void log() {
        Map<String, NdArray> a = Fixtures.npz("layout/synthetic.npz");
        float[] in = a.get("log_in").floats();
        float[] out = a.get("log_out").floats();
        for (int i = 0; i < in.length; i++) {
            assertThat(Float.floatToRawIntBits(NumpyMath.log(in[i]))).as("log %s", in[i])
                .isEqualTo(Float.floatToRawIntBits(out[i]));
        }
        assertThat(NumpyMath.log(1e-45f)).isEqualTo(-103.27893f);
        assertThat(NumpyMath.log(0f)).isEqualTo(Float.NEGATIVE_INFINITY);
        assertThat(NumpyMath.log(-1f)).isNaN();
        assertThat(NumpyMath.log(Float.NaN)).isNaN();
        assertThat(NumpyMath.log(Float.POSITIVE_INFINITY)).isEqualTo(Float.POSITIVE_INFINITY);
    }

    @Test
    @DisplayName("expf takes glibc's paths at the edges of its range")
    void expf() {
        assertThat(NumpyMath.expf(Float.NEGATIVE_INFINITY)).isZero();
        assertThat(NumpyMath.expf(Float.POSITIVE_INFINITY)).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(NumpyMath.expf(Float.NaN)).isNaN();
        assertThat(NumpyMath.expf(89f)).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(NumpyMath.expf(-105f)).isZero();
        assertThat(NumpyMath.expf(-100f)).isEqualTo((float) Math.exp(-100.0));
        assertThat(NumpyMath.expf(0f)).isEqualTo(1f);
    }

    @Test
    @DisplayName("log1pf rounds the inputs whose double result is a float midpoint as glibc does")
    void log1pfMidpoints() {
        assertThat(NumpyMath.log1pf(0x1.800006p-21f)).isEqualTo(0x1.7ffffep-21f);
        assertThat(NumpyMath.log1pf(0x1.200036p-17f)).isEqualTo(0x1.1fffe6p-17f);
        assertThat(NumpyMath.log1pf(0x1.fb035ap-2f)).isEqualTo(0x1.9bddc2p-2f);
        assertThat(NumpyMath.log1pf(1f)).isEqualTo((float) Math.log(2));
    }
}
