// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Python's number rounding and formatting as Indra's text layer uses them, where Java's own would
 * round differently.
 */
final class PyFormat {

    private PyFormat() {
        throw new AssertionError("Static methods only");
    }

    /** Python's {@code round(x, digits)}: the nearest, ties to even, on the exact value. */
    static double roundHalfEven(double x, int digits) {
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python's {@code f"{x:.0%}"}. */
    static String percent(double x) {
        return new BigDecimal(x * 100).setScale(0, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }
}
