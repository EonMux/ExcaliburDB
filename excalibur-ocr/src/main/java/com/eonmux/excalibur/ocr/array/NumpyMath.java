package com.eonmux.excalibur.ocr.array;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Single-precision functions that numpy evaluates through the C library or through its own
 * vector kernels, reproduced bit for bit so ported code rounds as Indra does on x86-64.
 *
 * <p>Correctly rounded results are not enough. numpy's {@code float32} {@code np.log} is a
 * vector polynomial that is up to 3 ulp off the true value, and {@code np.logaddexp} calls
 * glibc's {@code expf}, which rounds the wrong way in about one input in 1,500. A logit that
 * decides an order or a block boundary can sit on either side of such an ulp. The kernels are
 * those of numpy 2.5 with AVX2 and FMA, and of glibc 2.44 on a CPU with FMA. Each was checked
 * against the original over every finite {@code float32} input of its domain.</p>
 *
 * <p>Origins: {@link #log} follows numpy's {@code loops_exponent_log.dispatch.c.src} (BSD-3-Clause);
 * {@link #expf} follows glibc's {@code e_expf.c}, which comes from Arm's Optimized Routines
 * (MIT or Apache-2.0 with LLVM exception), and its table holds the bits of 2^(i/32).</p>
 */
public final class NumpyMath {

    private static final int EXP_TABLE_BITS = 5;
    private static final int EXP_N = 1 << EXP_TABLE_BITS;
    private static final double EXP_SHIFT = 0x1.8p+52;
    private static final double EXP_INV_LN2_N = 0x1.71547652b82fep+0 * EXP_N;
    private static final double EXP_C0 = 0x1.c6af84b912394p-5 / EXP_N / EXP_N / EXP_N;
    private static final double EXP_C1 = 0x1.ebfce50fac4f3p-3 / EXP_N / EXP_N;
    private static final double EXP_C2 = 0x1.62e42ff0c52d6p-1 / EXP_N;
    /** glibc's {@code __exp2f_data.tab}: bits of 2^(i/32) less i << 47. */
    private static final long[] EXP_TABLE = {
        0x3ff0000000000000L, 0x3fefd9b0d3158574L, 0x3fefb5586cf9890fL, 0x3fef9301d0125b51L,
        0x3fef72b83c7d517bL, 0x3fef54873168b9aaL, 0x3fef387a6e756238L, 0x3fef1e9df51fdee1L,
        0x3fef06fe0a31b715L, 0x3feef1a7373aa9cbL, 0x3feedea64c123422L, 0x3feece086061892dL,
        0x3feebfdad5362a27L, 0x3feeb42b569d4f82L, 0x3feeab07dd485429L, 0x3feea47eb03a5585L,
        0x3feea09e667f3bcdL, 0x3fee9f75e8ec5f74L, 0x3feea11473eb0187L, 0x3feea589994cce13L,
        0x3feeace5422aa0dbL, 0x3feeb737b0cdc5e5L, 0x3feec49182a3f090L, 0x3feed503b23e255dL,
        0x3feee89f995ad3adL, 0x3feeff76f2fb5e47L, 0x3fef199bdd85529cL, 0x3fef3720dcef9069L,
        0x3fef5818dcfba487L, 0x3fef7c97337b9b5fL, 0x3fefa4afa2a490daL, 0x3fefd0765b6e4540L,
    };
    private static final float EXP_OVERFLOW = 0x1.62e42ep6f;
    private static final float EXP_UNDERFLOW = -0x1.9fe368p6f;

    private static final float LOG_P1 = 9.999999999999998702752e-01f;
    private static final float LOG_P2 = 2.112677543073053063722e+00f;
    private static final float LOG_P3 = 1.480000633576506585156e+00f;
    private static final float LOG_P4 = 3.808837741388407920751e-01f;
    private static final float LOG_P5 = 2.589979117907922693523e-02f;
    private static final float LOG_Q1 = 2.612677543073109236779e+00f;
    private static final float LOG_Q2 = 2.453006071784736363091e+00f;
    private static final float LOG_Q3 = 9.864942958519418960339e-01f;
    private static final float LOG_Q4 = 1.546476374983906719538e-01f;
    private static final float LOG_Q5 = 5.875095403124574342950e-03f;
    private static final float LN2 = 0.693147180559945309417232121458176568f;
    private static final float SQRT1_2 = 0.707106781186547524400844362104849039f;
    /** Precision of the series that decides a log1p rounding on a float midpoint. */
    private static final MathContext SERIES = new MathContext(60);
    /** 2^100, which lifts a subnormal input into the normal range. */
    private static final float TWO_POW_100 = 0x1p100f;

    private NumpyMath() {
        throw new AssertionError("Static methods only");
    }

    /**
     * glibc's {@code expf}: e^x rounded to {@code float}, as glibc 2.44 computes it on a CPU
     * with FMA (its {@code __expf_fma} variant).
     *
     * <p>x·32/ln 2 is split into an integer k and a remainder r, 2^(k/32) is read from a
     * 32-entry table and 2^(r/32) from a cubic, all in double. The FMA build fuses the
     * multiply-adds of the range reduction and of the polynomial; unfused, two inputs in
     * 2.2 billion round differently.</p>
     *
     * @param x The exponent
     * @return e^x as glibc's {@code expf} returns it
     */
    public static float expf(float x) {
        int bits = Float.floatToRawIntBits(x);
        int abstop = (bits >>> 20) & 0x7ff;
        if (abstop >= (Float.floatToRawIntBits(88.0f) >>> 20)) {
            if (x == Float.NEGATIVE_INFINITY) {
                return 0.0f;
            }
            if (abstop >= (Float.floatToRawIntBits(Float.POSITIVE_INFINITY) >>> 20)) {
                return x + x;
            }
            if (x > EXP_OVERFLOW) {
                return Float.POSITIVE_INFINITY;
            }
            if (x < EXP_UNDERFLOW) {
                return 0.0f;
            }
        }
        double xd = x;
        double kd = Math.fma(EXP_INV_LN2_N, xd, EXP_SHIFT);
        long ki = Double.doubleToRawLongBits(kd);
        kd -= EXP_SHIFT;
        double r = Math.fma(EXP_INV_LN2_N, xd, -kd);
        long t = EXP_TABLE[(int) (ki & (EXP_N - 1))] + (ki << (52 - EXP_TABLE_BITS));
        double s = Double.longBitsToDouble(t);
        double z = Math.fma(EXP_C0, r, EXP_C1);
        double r2 = r * r;
        double y = Math.fma(EXP_C2, r, 1.0);
        y = Math.fma(z, r2, y);
        return (float) (y * s);
    }

    /**
     * glibc's {@code log1pf}: log(1 + x) correctly rounded to {@code float}, as glibc 2.41 and
     * later compute it (the CORE-MATH implementation).
     *
     * <p>{@link StrictMath#log1p(double)} is within one double ulp of the true value, so its
     * float rounding is correct unless the double lands exactly halfway between two floats,
     * where rounding half to even can pick the wrong one (three inputs of [0, 1] do). Those
     * are decided by the true value's side of the midpoint, from a 60-digit series.</p>
     *
     * @param x The argument, greater than -1
     * @return log(1 + x), correctly rounded
     */
    public static float log1pf(float x) {
        double y = StrictMath.log1p(x);
        float f = (float) y;
        if (!isFloatMidpoint(y)) {
            return f;
        }
        float lo = f > y ? Math.nextDown(f) : f;
        float hi = f > y ? f : Math.nextUp(f);
        return exactLog1p(x).compareTo(new BigDecimal(y)) > 0 ? hi : lo;
    }

    /** Whether a double in the normal float range lies exactly halfway between two floats. */
    private static boolean isFloatMidpoint(double y) {
        double magnitude = Math.abs(y);
        if (!(magnitude >= Float.MIN_NORMAL && magnitude <= Float.MAX_VALUE)) {
            return false;
        }
        long lowBits = Double.doubleToRawLongBits(y) & ((1L << 29) - 1);
        return lowBits == 1L << 28;
    }

    /** log(1 + x) to 60 digits, as 2 atanh(x / (2 + x)): a series in u² with |u| < 1. */
    private static BigDecimal exactLog1p(float x) {
        BigDecimal bx = new BigDecimal(x);
        BigDecimal u = bx.divide(bx.add(BigDecimal.TWO), SERIES);
        BigDecimal u2 = u.multiply(u, SERIES);
        BigDecimal power = u;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal limit = BigDecimal.ONE.movePointLeft(SERIES.getPrecision() + 10);
        for (int k = 1; power.abs().compareTo(limit) > 0; k += 2) {
            sum = sum.add(power.divide(BigDecimal.valueOf(k), SERIES), SERIES);
            power = power.multiply(u2, SERIES);
        }
        return sum.multiply(BigDecimal.TWO, SERIES);
    }

    /**
     * numpy's {@code np.logaddexp} on {@code float32}: log(e^x + e^y), as
     * {@code npy_logaddexpf} computes it with glibc's {@code expf} and {@code log1pf}.
     *
     * @param x One exponent
     * @param y The other
     * @return log(e^x + e^y)
     */
    public static float logaddexp(float x, float y) {
        if (x == y) {
            return x + LN2;
        }
        float tmp = x - y;
        if (tmp > 0) {
            return x + log1pf(expf(-tmp));
        }
        if (tmp <= 0) {
            return y + log1pf(expf(tmp));
        }
        return tmp;
    }

    /**
     * numpy's {@code np.log} on {@code float32}, as numpy 2.x computes it on x86-64 with AVX2
     * and FMA ({@code simd_log_FLOAT}).
     *
     * <p>The input is split into an exponent and a mantissa in [√½, √2), and log(mantissa) is
     * a 5th-over-5th order rational function in fused multiply-adds. The result is up to 3 ulp
     * from the correctly rounded value; numpy's AVX-512 kernel is the same algorithm.</p>
     *
     * @param x The argument
     * @return The natural logarithm as numpy returns it: NaN below zero, -infinity at zero
     */
    public static float log(float x) {
        if (Float.isNaN(x) || x < 0) {
            return Float.NaN;
        }
        if (x == 0) {
            return Float.NEGATIVE_INFINITY;
        }
        if (x == Float.POSITIVE_INFINITY) {
            return x;
        }
        boolean subnormal = x < Float.MIN_NORMAL;
        int bits = Float.floatToRawIntBits(subnormal ? x * TWO_POW_100 : x);
        float exponent = (bits >>> 23) - 0x7E;
        if (subnormal) {
            exponent -= 100f;
        }
        float m = Float.intBitsToFloat((bits & 0x7fffff) | (126 << 23));
        if (m <= SQRT1_2) {
            m = m + m;
            exponent = exponent - 1f;
        }
        m = m - 1f;
        float num = Math.fma(LOG_P5, m, LOG_P4);
        num = Math.fma(num, m, LOG_P3);
        num = Math.fma(num, m, LOG_P2);
        num = Math.fma(num, m, LOG_P1);
        num = Math.fma(num, m, 0f);
        float den = Math.fma(LOG_Q5, m, LOG_Q4);
        den = Math.fma(den, m, LOG_Q3);
        den = Math.fma(den, m, LOG_Q2);
        den = Math.fma(den, m, LOG_Q1);
        den = Math.fma(den, m, 1f);
        return Math.fma(exponent, LN2, num / den);
    }
}
