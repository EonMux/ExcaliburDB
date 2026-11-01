package com.eonmux.excalibur.ocr.pdf;

/**
 * A rectangle in PDF orientation (y up) and single precision, as pdfium's {@code CFX_FloatRect}.
 *
 * @param left   Left edge
 * @param bottom Bottom edge
 * @param right  Right edge
 * @param top    Top edge
 */
record FxRect(float left, float bottom, float right, float top) {

    /** The empty rectangle at the origin. */
    static final FxRect EMPTY = new FxRect(0, 0, 0, 0);

    float width() {
        return right - left;
    }

    float height() {
        return top - bottom;
    }

    /**
     * Whether the rectangle has no area: {@code IsEmpty}.
     *
     * @return True when {@code left >= right} or {@code bottom >= top}
     */
    boolean isEmpty() {
        return left >= right || bottom >= top;
    }

    /**
     * The rectangle with its edges in order: {@code Normalize}.
     *
     * @return The ordered rectangle
     */
    FxRect normalized() {
        return new FxRect(Math.min(left, right), Math.min(bottom, top), Math.max(left, right),
            Math.max(bottom, top));
    }

    /**
     * The overlap with another rectangle, or {@link #EMPTY}: {@code Intersect}.
     *
     * @param o The other rectangle
     * @return The overlap
     */
    FxRect intersect(FxRect o) {
        FxRect n = normalized();
        FxRect m = o.normalized();
        FxRect r = new FxRect(Math.max(n.left, m.left), Math.max(n.bottom, m.bottom),
            Math.min(n.right, m.right), Math.min(n.top, m.top));
        return r.left > r.right || r.bottom > r.top ? EMPTY : r;
    }

    /**
     * The bounds of both rectangles: {@code Union}.
     *
     * @param o The other rectangle
     * @return The union's bounds
     */
    FxRect union(FxRect o) {
        FxRect n = normalized();
        FxRect m = o.normalized();
        return new FxRect(Math.min(n.left, m.left), Math.min(n.bottom, m.bottom),
            Math.max(n.right, m.right), Math.max(n.top, m.top));
    }

    /**
     * Whether a point lies inside or on the edge: {@code Contains}.
     *
     * @param p The point
     * @return True when inside
     */
    boolean contains(FxPoint p) {
        FxRect n = normalized();
        return p.x() <= n.right && p.x() >= n.left && p.y() <= n.top && p.y() >= n.bottom;
    }
}
