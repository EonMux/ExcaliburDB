// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import java.util.Objects;

/**
 * One page's text: Indra's {@code Page}, as far as a document's markdown needs it.
 *
 * @param markdown The page's markdown, or null when it has none
 * @param text     The page's lines in reading order, one per line
 */
public record PageText(String markdown, String text) {

    public PageText {
        Objects.requireNonNull(text, "text must not be null");
    }

    /**
     * What the page contributes to its document: its markdown, or its text without one.
     *
     * @return The page's part of the document
     */
    public String part() {
        return markdown != null ? markdown : text;
    }
}
