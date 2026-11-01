// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

/**
 * A box on a rendered page, in PDF points with the origin at the top left: left, top, right and
 * bottom edges. Double precision, as Indra keeps text-layer boxes in numpy {@code float64}.
 *
 * @param x0 Left edge
 * @param y0 Top edge
 * @param x1 Right edge
 * @param y1 Bottom edge
 */
public record PageBox(double x0, double y0, double x1, double y1) {
}
