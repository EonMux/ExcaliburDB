// Reproduces the C library's cos and sin as OpenCV's getRotationMatrix2D calls them, by
// computing them correctly rounded.
package com.eonmux.excalibur.ocr.image;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Correctly rounded cosine and sine of a double.
 *
 * <p>OpenCV takes the cosine and sine of a rotation from the C library. glibc's results are
 * correctly rounded almost always, so the correctly rounded value is the best portable
 * reproduction of them: it agreed with glibc on 1,981 of 1,982 random angles, where
 * {@link Math} agreed on 1,978 and {@link StrictMath} on 1,932. Unlike {@link Math}, whose
 * intrinsics vary between JVMs and processors, the result is the same everywhere. glibc picks
 * its implementation at run time (an FMA build on current x86-64), so its rare one-ulp misses
 * cannot be reproduced portably. They seldom reach pixels: of the 2,077 angles Indra's skew
 * search tries, glibc misses at 4, and once {@code warpAffine} narrows the inverted matrix to
 * {@code float}, one of 10 million pairs of those angles and image sizes up to 1600x1600
 * samples differently.</p>
 *
 * <p>Computed by the Taylor series in 60-digit decimal arithmetic, then rounded once to double;
 * the series' own error is far below the spacing of doubles, so the rounding is correct except
 * for values within about 1e-40 of a rounding boundary. Cheap enough for the few dozen
 * rotations a page needs.</p>
 */
final class ExactTrig {

    private static final MathContext PRECISION = new MathContext(60);
    /** Arguments up to this magnitude keep at least 40 digits after the series' cancellation. */
    private static final double MAX_ARGUMENT = 64;

    private ExactTrig() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The cosine, correctly rounded.
     *
     * @param x Radians, at most {@value #MAX_ARGUMENT} in magnitude
     * @return {@code cos(x)}
     */
    static double cos(double x) {
        return series(x, true);
    }

    /**
     * The sine, correctly rounded.
     *
     * @param x Radians, at most {@value #MAX_ARGUMENT} in magnitude
     * @return {@code sin(x)}
     */
    static double sin(double x) {
        return series(x, false);
    }

    private static double series(double x, boolean cosine) {
        if (!(Math.abs(x) <= MAX_ARGUMENT)) {
            throw new IllegalArgumentException("angle " + x + " rad is outside +-"
                + MAX_ARGUMENT + " rad");
        }
        if (x == 0) {
            return cosine ? 1.0 : x;
        }
        BigDecimal arg = new BigDecimal(x);
        BigDecimal square = arg.multiply(arg, PRECISION);
        BigDecimal term = cosine ? BigDecimal.ONE : arg;
        BigDecimal sum = term;
        BigDecimal threshold = BigDecimal.ONE.movePointLeft(PRECISION.getPrecision());
        for (int k = 1; term.abs().compareTo(threshold) > 0 && term.signum() != 0; k++) {
            long a = cosine ? 2L * k - 1 : 2L * k;
            term = term.multiply(square, PRECISION)
                .divide(BigDecimal.valueOf(a * (a + 1)), PRECISION)
                .negate();
            sum = sum.add(term, PRECISION);
        }
        return sum.doubleValue();
    }
}
