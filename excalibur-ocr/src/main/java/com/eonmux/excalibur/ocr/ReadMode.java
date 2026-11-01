// Ported from Indra OCR 1.1.2, document.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr;

import java.util.Locale;

/** Where a PDF page's text is read from: Indra's {@code mode}. */
public enum ReadMode {

    /** The text layer where it passes the checks, OCR elsewhere. */
    AUTO,

    /** OCR on every page; the text layer is never read. */
    OCR,

    /** The text layer even where the checks fail; OCR only on pages with no text at all. */
    TEXT;

    /**
     * The mode a setting names.
     *
     * @param name {@code auto}, {@code ocr} or {@code text}, in any case
     * @return The mode
     * @throws IllegalArgumentException for any other name
     */
    public static ReadMode of(String name) {
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("a read mode is auto, ocr or text, got: " + name,
                e);
        }
    }

    /**
     * The mode's name as Indra writes it.
     *
     * @return {@code auto}, {@code ocr} or {@code text}
     */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
