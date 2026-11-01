// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import java.util.Objects;

/**
 * One token of a line and where it sits: Indra's {@code (token, x)} and
 * {@code (char, x, content index)} tuples.
 *
 * <p>Three kinds, as in Indra: an id of the base recognizer's vocabulary; a character (or a
 * math reading) from a group recognizer, in visual order; and a character from a PDF text layer,
 * with its place in the content stream.</p>
 *
 * @param text  The token's characters, or null for a vocabulary id
 * @param id    The vocabulary id, or -1 for characters
 * @param x     Its x on the page, in pixels
 * @param index Its place in the PDF content stream, or {@link #NO_INDEX}
 */
public record Token(String text, int id, double x, int index) {

    /** The index of a token that does not come from a text layer. */
    public static final int NO_INDEX = Integer.MIN_VALUE;

    public Token {
        if ((text == null) == (id < 0)) {
            throw new IllegalArgumentException("a token is either characters or an id");
        }
        if (text == null && index != NO_INDEX) {
            throw new IllegalArgumentException("an id token has no content index");
        }
    }

    /**
     * A token of the base recognizer.
     *
     * @param id Its vocabulary id
     * @param x  Its x on the page
     * @return The token
     */
    public static Token ofId(int id, double x) {
        return new Token(null, id, x, NO_INDEX);
    }

    /**
     * Characters from a group recognizer or a math reading.
     *
     * @param text The characters
     * @param x    Their x on the page
     * @return The token
     */
    public static Token ofText(String text, double x) {
        return new Token(Objects.requireNonNull(text, "text must not be null"), -1, x, NO_INDEX);
    }

    /**
     * Characters from a PDF text layer.
     *
     * @param text  The characters
     * @param x     Their x on the page
     * @param index Their place in the content stream
     * @return The token
     */
    public static Token ofLayer(String text, double x, int index) {
        if (index == NO_INDEX) {
            throw new IllegalArgumentException("a text-layer token has a content index");
        }
        return new Token(Objects.requireNonNull(text, "text must not be null"), -1, x, index);
    }

    /**
     * Whether this is characters rather than a vocabulary id: Python's
     * {@code isinstance(tok[0], str)}.
     *
     * @return Whether it holds characters
     */
    public boolean isText() {
        return text != null;
    }

    /**
     * Whether this comes from a PDF text layer: Python's {@code len(tok) > 2}.
     *
     * @return Whether it has a content index
     */
    public boolean fromLayer() {
        return index != NO_INDEX;
    }

    /**
     * This token at another x, of the same kind.
     *
     * @param newX The new x
     * @return The moved token
     */
    public Token at(double newX) {
        return new Token(text, id, newX, index);
    }
}
