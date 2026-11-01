// Ported from Indra OCR 1.1.2, det_post.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.detect;

import com.eonmux.excalibur.ocr.image.Box;

import java.util.Objects;

/**
 * A detected text line: one row of the {@code [N, 5]} array Indra's {@code decode} returns.
 *
 * @param box   The line's ink box in detector input pixels
 * @param score The mean line probability over the line's core pixels, 0 to 1
 */
public record ScoredBox(Box box, float score) {

    public ScoredBox {
        Objects.requireNonNull(box, "box must not be null");
    }
}
