// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.geometry;

import com.eonmux.excalibur.ocr.image.AffineTransform;
import com.eonmux.excalibur.ocr.image.BorderMode;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.WarpAffine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Straightens a skewed page and maps boxes found on it back; Indra's {@code rotate_page} and
 * {@code unrotate_box}.
 */
public final class PageRotation {

    private PageRotation() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The page rotated about its centre, same size, edges extended by replicating the border
     * pixels; Indra's {@code rotate_page}.
     *
     * @param gray    The page
     * @param degrees Counter-clockwise degrees, as {@link SkewEstimator} measures them
     * @return The rotated page
     */
    public static GrayImage rotate(GrayImage gray, double degrees) {
        Objects.requireNonNull(gray, "gray must not be null");
        int w = gray.width();
        int h = gray.height();
        AffineTransform m = AffineTransform.rotation(w / 2.0, h / 2.0, degrees, 1.0);
        return WarpAffine.warp(gray, m, w, h, BorderMode.REPLICATE);
    }

    /**
     * A box on a page that {@link #rotate} produced, mapped back to the page before the
     * rotation; Indra's {@code unrotate_box}.
     *
     * <p>The result is the bounding box of the four mapped corners, clipped to the page and
     * rounded to one decimal as Python's {@code round(v, 1)} rounds: the exact binary value
     * to the nearest tenth, ties to even. The corners are mapped as numpy's matrix product
     * computes them, {@code fma(m01, y, m00 x) + m02}.</p>
     *
     * @param box     At least four values: left, top, right, bottom
     * @param degrees The rotation {@link #rotate} applied
     * @param width   The page's width
     * @param height  The page's height
     * @return {@code {x0, y0, x1, y1}} on the page before the rotation
     */
    public static double[] unrotateBox(double[] box, double degrees, int width, int height) {
        Objects.requireNonNull(box, "box must not be null");
        if (box.length < 4) {
            throw new IllegalArgumentException("a box has 4 values, got " + box.length);
        }
        AffineTransform m = AffineTransform.rotation(width / 2.0, height / 2.0, -degrees, 1.0);
        double[] xs = {box[0], box[2], box[0], box[2]};
        double[] ys = {box[1], box[1], box[3], box[3]};
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 4; k++) {
            double cx = Math.fma(m.m01(), ys[k], m.m00() * xs[k]) + m.m02();
            double cy = Math.fma(m.m11(), ys[k], m.m10() * xs[k]) + m.m12();
            minX = Math.min(minX, cx);
            minY = Math.min(minY, cy);
            maxX = Math.max(maxX, cx);
            maxY = Math.max(maxY, cy);
        }
        return new double[] {
            roundTenth(clip(minX, width)), roundTenth(clip(minY, height)),
            roundTenth(clip(maxX, width)), roundTenth(clip(maxY, height)),
        };
    }

    /** {@code np.clip(v, 0, hi)} as numpy evaluates it: {@code min(max(v, 0), hi)}, each
     *  taking the bound unless the value is strictly beyond it. */
    private static double clip(double v, double hi) {
        double low = v > 0 ? v : 0.0;
        return low < hi ? low : hi;
    }

    /** Python's {@code round(v, 1)}. */
    private static double roundTenth(double v) {
        if (v == 0 || !Double.isFinite(v)) {
            return v;
        }
        return new BigDecimal(v).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }
}
