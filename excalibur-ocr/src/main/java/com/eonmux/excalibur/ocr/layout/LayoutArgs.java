package com.eonmux.excalibur.ocr.layout;

import java.util.Objects;

/**
 * Checks of the arrays the reading-order and layout functions take, so a malformed box or a
 * logit matrix of the wrong size fails at the call with a message naming it rather than deep in
 * the arithmetic.
 */
final class LayoutArgs {

    private LayoutArgs() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Requires line boxes: rows of at least four finite values {@code x0, y0, x1, y1}.
     *
     * @param boxes The boxes
     * @throws IllegalArgumentException if a row is short or holds a NaN or an infinity
     */
    static void requireBoxes(float[][] boxes) {
        Objects.requireNonNull(boxes, "boxes must not be null");
        for (int i = 0; i < boxes.length; i++) {
            float[] b = Objects.requireNonNull(boxes[i], "box " + i + " is null");
            if (b.length < 4) {
                throw new IllegalArgumentException("box " + i + " has " + b.length
                    + " values, needs x0, y0, x1, y1");
            }
            for (int k = 0; k < 4; k++) {
                if (!Float.isFinite(b[k])) {
                    throw new IllegalArgumentException("box " + i + " is not finite: " + b[k]);
                }
            }
        }
    }

    /**
     * Requires a row-major {@code [n, cols]} matrix.
     *
     * @param m    The values
     * @param n    Rows
     * @param cols Columns
     * @param name What to call it in messages
     * @throws IllegalArgumentException if it does not hold {@code n * cols} values
     */
    static void requireMatrix(float[] m, int n, int cols, String name) {
        Objects.requireNonNull(m, name + " must not be null");
        if (n < 0) {
            throw new IllegalArgumentException("n must not be negative, got " + n);
        }
        if (m.length != (long) n * cols) {
            throw new IllegalArgumentException(name + " must be [" + n + ", " + cols + "] ("
                + ((long) n * cols) + " values), got " + m.length);
        }
    }
}
