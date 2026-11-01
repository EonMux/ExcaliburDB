// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

/**
 * A 2x3 affine map in double precision: Indra's {@code _device_affine} array
 * {@code [[a00, a01, a02], [a10, a11, a12]]}, which takes a PDF-space point {@code (x, y)} to
 * {@code (a00 x + a01 y + a02, a10 x + a11 y + a12)}.
 *
 * @param a00 x from x
 * @param a01 x from y
 * @param a02 x offset
 * @param a10 y from x
 * @param a11 y from y
 * @param a12 y offset
 */
public record Affine(double a00, double a01, double a02, double a10, double a11, double a12) {

    /**
     * The image of x, numpy's {@code A[0, 0] * x + A[0, 1] * y + A[0, 2]}.
     *
     * @param x Abscissa
     * @param y Ordinate
     * @return The mapped abscissa
     */
    public double x(double x, double y) {
        return a00 * x + a01 * y + a02;
    }

    /**
     * The image of y, numpy's {@code A[1, 0] * x + A[1, 1] * y + A[1, 2]}.
     *
     * @param x Abscissa
     * @param y Ordinate
     * @return The mapped ordinate
     */
    public double y(double x, double y) {
        return a10 * x + a11 * y + a12;
    }
}
