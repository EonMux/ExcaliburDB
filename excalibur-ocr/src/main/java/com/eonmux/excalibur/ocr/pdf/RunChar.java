// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

/**
 * One character of a text-layer run and its box: an entry of Indra's {@code TextLine.chars}.
 *
 * @param codePoint The character
 * @param box       Its box in the run's frame
 */
public record RunChar(int codePoint, PageBox box) {

    /**
     * The character as a string.
     *
     * @return One code point
     */
    public String text() {
        return Character.toString(codePoint);
    }
}
