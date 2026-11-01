// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import java.util.List;
import java.util.Objects;

/**
 * One text-layer run: Indra's {@code TextLine}. A run is pdfium's text between the line breaks
 * it generates, also cut where characters change quarter turn.
 *
 * @param bbox    The bounds of its visible non-space characters
 * @param text    Its text, whitespace runs joined by single spaces
 * @param chars   Its visible characters with their boxes, in PDF order
 * @param quarter Quarter turns from upright in the (turned) frame, 0 to 3
 * @param seq     Position among all the page's runs, in content order; null when unknown, as
 *                Indra's {@code None}, and then a reader falls back to the run's position
 */
public record TextLine(PageBox bbox, String text, List<RunChar> chars, int quarter,
                       Integer seq) {

    public TextLine {
        Objects.requireNonNull(bbox, "bbox must not be null");
        Objects.requireNonNull(text, "text must not be null");
        chars = List.copyOf(chars);
        if (quarter < 0 || quarter > 3) {
            throw new IllegalArgumentException("quarter must be 0 to 3, got " + quarter);
        }
    }
}
