// Reproduces numpy 2.x's documented float32 exp kernel (simd_exp_FLOAT in
// numpy/_core/src/umath/loops_exponent_log.dispatch.c.src, AVX2/FMA3 build).
package com.eonmux.excalibur.ocr.array;

/**
 * numpy's {@code float32} elementwise functions, bit for bit as numpy 2.x computes them on
 * x86-64 with AVX2 and FMA, for ports of Indra code that applies them to {@code float32}
 * arrays: line probabilities, softmax.
 *
 * <p>{@link #exp}: numpy evaluates every {@code float32} exponential with its own vector
 * kernel rather than the C library: Cody-Waite range reduction, then a 5th-over-2nd order
 * rational approximation in fused multiply-adds, then a scale by a power of two. Its results differ from the correctly
 * rounded value in about a third of inputs (by up to 2.5 ulp), so Indra's line probabilities
 * {@code 1 / (1 + exp(-logit))} and softmaxes are reproduced exactly only by the same kernel.</p>
 */
public final class NumpyFloat32 {

    private static final float X_MAX = 88.72283935546875f;
    private static final float X_MIN = -103.97208404541015625f;
    private static final float LOG2E = 1.442695040888963407359924681001892137f;
    private static final float RINT_MAGIC = 0x1.8p23f;
    private static final float LN2_HIGH = -6.93145752e-1f;
    private static final float LN2_LOW = -1.42860677e-6f;
    private static final float P0 = 9.999999999980870924916e-01f;
    private static final float P1 = 7.257664613233124478488e-01f;
    private static final float P2 = 2.473615434895520810817e-01f;
    private static final float P3 = 5.114512081637298353406e-02f;
    private static final float P4 = 6.757896990527504603057e-03f;
    private static final float P5 = 5.082762527590693718096e-04f;
    private static final float Q0 = 1.000000000000000000000e+00f;
    private static final float Q1 = -2.742335390411667452936e-01f;
    private static final float Q2 = 2.159509375685829852307e-02f;
    /** Below this power of two the result is subnormal and is scaled by a division. */
    private static final float MIN_QUADRANT = -125f;

    private NumpyFloat32() {
        throw new AssertionError("Static methods only");
    }

    /**
     * numpy's {@code exp} of one {@code float32}.
     *
     * @param value The exponent
     * @return {@code e^value} as numpy rounds it; infinity above 88.72, zero below -103.97,
     *         NaN for NaN
     */
    public static float exp(float value) {
        if (Float.isNaN(value)) {
            return Float.NaN;
        }
        if (value >= X_MAX) {
            return Float.POSITIVE_INFINITY;
        }
        if (value <= X_MIN) {
            return 0f;
        }
        float quadrant = value * LOG2E;
        quadrant = (quadrant + RINT_MAGIC) - RINT_MAGIC;
        float x = Math.fma(quadrant, LN2_HIGH, value);
        x = Math.fma(quadrant, LN2_LOW, x);
        float num = Math.fma(P5, x, P4);
        num = Math.fma(num, x, P3);
        num = Math.fma(num, x, P2);
        num = Math.fma(num, x, P1);
        num = Math.fma(num, x, P0);
        float den = Math.fma(Q2, x, Q1);
        den = Math.fma(den, x, Q0);
        return scale(num / den, quadrant);
    }

    /** {@code poly * 2^quadrant} by adding to the exponent bits, as numpy's AVX2 scalef. */
    private static float scale(float poly, float quadrant) {
        if (quadrant <= MIN_QUADRANT) {
            int divisor = 1 << (int) Math.rint(-(quadrant - MIN_QUADRANT));
            float scaled = Float.intBitsToFloat(Float.floatToRawIntBits(poly)
                + ((int) MIN_QUADRANT << 23));
            return scaled / divisor;
        }
        return Float.intBitsToFloat(Float.floatToRawIntBits(poly) + ((int) quadrant << 23));
    }
}
