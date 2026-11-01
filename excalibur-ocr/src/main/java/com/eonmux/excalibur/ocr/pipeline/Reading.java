// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import java.util.List;
import java.util.Objects;

/**
 * What one line says: Indra's {@code (text, confidence, tokens)}.
 *
 * @param text   The line's text
 * @param conf   How sure the recognizer is: its mean peak probability, 1 for a text layer
 * @param tokens The tokens, with their x on the page
 */
public record Reading(String text, double conf, List<Token> tokens) {

    /** A line with nothing read. */
    public static final Reading EMPTY = new Reading("", 0.0, List.of());

    public Reading {
        Objects.requireNonNull(text, "text must not be null");
        tokens = List.copyOf(tokens);
    }
}
