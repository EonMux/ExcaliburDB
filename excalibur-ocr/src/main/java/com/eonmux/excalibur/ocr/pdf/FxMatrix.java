package com.eonmux.excalibur.ocr.pdf;

/**
 * An affine map in single precision, with the conventions and arithmetic of pdfium's
 * {@code CFX_Matrix}: a point maps to {@code (a x + c y + e, b x + d y + f)}, and
 * {@code m.times(n)} applies {@code m} first, then {@code n}.
 *
 * <p>Indra OCR reads text through pdfium, whose text-page code decides run boundaries and spaces
 * from float comparisons. Repeating its arithmetic operation for operation in {@code float} keeps
 * the comparisons that fall near a threshold on the same side.</p>
 *
 * @param a Scale of x into x
 * @param b Share of x in y
 * @param c Share of y in x
 * @param d Scale of y into y
 * @param e Translation in x
 * @param f Translation in y
 */
record FxMatrix(float a, float b, float c, float d, float e, float f) {

    /** The identity map. */
    static final FxMatrix IDENTITY = new FxMatrix(1, 0, 0, 1, 0, 0);

    /**
     * This map followed by another: pdfium's {@code operator*}.
     *
     * @param r The map applied second
     * @return The composition
     */
    FxMatrix times(FxMatrix r) {
        return new FxMatrix(a * r.a + b * r.c, a * r.b + b * r.d,
            c * r.a + d * r.c, c * r.b + d * r.d,
            e * r.a + f * r.c + r.e, e * r.b + f * r.d + r.f);
    }

    /**
     * The inverse map, or the identity when this one is singular: {@code GetInverse}.
     *
     * @return The inverse
     */
    FxMatrix inverse() {
        float i = a * d - b * c;
        if (Math.abs(i) == 0) {
            return IDENTITY;
        }
        float j = -i;
        return new FxMatrix(d / i, b / j, c / j, a / i, (c * f - d * e) / i, (a * f - b * e) / j);
    }

    /**
     * Maps a point: {@code Transform}.
     *
     * @param p The point
     * @return Its image
     */
    FxPoint apply(FxPoint p) {
        return new FxPoint(a * p.x() + c * p.y() + e, b * p.x() + d * p.y() + f);
    }

    /**
     * The axis-aligned bounds of a rectangle's image: {@code TransformRect}.
     *
     * @param r The rectangle
     * @return The bounds of its four mapped corners
     */
    FxRect apply(FxRect r) {
        FxPoint p0 = apply(new FxPoint(r.left(), r.top()));
        FxPoint p1 = apply(new FxPoint(r.left(), r.bottom()));
        FxPoint p2 = apply(new FxPoint(r.right(), r.top()));
        FxPoint p3 = apply(new FxPoint(r.right(), r.bottom()));
        float right = Math.max(Math.max(Math.max(p0.x(), p1.x()), p2.x()), p3.x());
        float left = Math.min(Math.min(Math.min(p0.x(), p1.x()), p2.x()), p3.x());
        float top = Math.max(Math.max(Math.max(p0.y(), p1.y()), p2.y()), p3.y());
        float bottom = Math.min(Math.min(Math.min(p0.y(), p1.y()), p2.y()), p3.y());
        return new FxRect(left, bottom, right, top);
    }

    /**
     * The length one unit along x maps to: {@code GetXUnit}.
     *
     * @return The x unit
     */
    float xUnit() {
        if (b == 0) {
            return Math.abs(a);
        }
        if (a == 0) {
            return Math.abs(b);
        }
        return (float) Math.hypot(a, b);
    }

    /**
     * The length one unit along y maps to: {@code GetYUnit}.
     *
     * @return The y unit
     */
    float yUnit() {
        if (c == 0) {
            return Math.abs(d);
        }
        if (d == 0) {
            return Math.abs(c);
        }
        return (float) Math.hypot(c, d);
    }

    /**
     * A distance scaled by the mean of the two units: {@code TransformDistance}.
     *
     * @param distance The distance
     * @return The mapped distance
     */
    float distance(float distance) {
        return distance * (xUnit() + yUnit()) / 2;
    }

    /**
     * The length a horizontal distance maps to: {@code TransformXDistance}.
     *
     * @param dx The distance along x
     * @return Its mapped length
     */
    float xDistance(float dx) {
        return (float) Math.hypot(a * dx, b * dx);
    }
}
