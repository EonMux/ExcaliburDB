package com.eonmux.excalibur.ocr.image;

/**
 * An axis-aligned box in pixels: left, top, right and bottom edges.
 *
 * <p>Single precision, as the boxes of Indra OCR are numpy {@code float32} arrays: arithmetic
 * that Indra does on those arrays is done here in {@code float}, and arithmetic it does on Python
 * floats taken from them is done in {@code double}, so the two round alike.</p>
 *
 * @param x0 Left edge
 * @param y0 Top edge
 * @param x1 Right edge
 * @param y1 Bottom edge
 */
public record Box(float x0, float y0, float x1, float y1) {

    /**
     * The box's width, {@code x1 - x0}.
     *
     * @return Its width, negative for a box whose edges are crossed
     */
    public float width() {
        return x1 - x0;
    }

    /**
     * The box's height, {@code y1 - y0}.
     *
     * @return Its height, negative for a box whose edges are crossed
     */
    public float height() {
        return y1 - y0;
    }
}
