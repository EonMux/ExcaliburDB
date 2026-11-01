// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.geometry;

import com.eonmux.excalibur.ocr.image.AffineTransform;
import com.eonmux.excalibur.ocr.image.BorderMode;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.Interpolation;
import com.eonmux.excalibur.ocr.image.Resize;
import com.eonmux.excalibur.ocr.image.WarpAffine;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Measures how far a page's text rows are tilted; Indra's {@code estimate_skew}.
 *
 * <p>Text rows are level when the page's horizontal ink profile changes most sharply from row
 * to row, so the search scores each candidate angle by rotating an ink thumbnail and summing
 * the squared differences of its row sums. It runs coarse to fine: every 2 degrees over
 * +-{@value #DESKEW_MAX} on a half-size thumbnail, then every 0.5 degrees within 2 degrees of 0
 * and of the two best coarse angles, then every 0.1 degrees within 0.5 degrees of the best, on
 * the full thumbnail. Ties are broken as Indra's numpy and Python code breaks them; see
 * {@link NumpyOrder}. Indra may score angles on a thread pool; the result does not depend on
 * it, and this class scores them in turn.</p>
 */
public final class SkewEstimator {

    /** Degrees of tilt searched each way; quarter turns cover the rest. */
    public static final double DESKEW_MAX = 45.0;
    /** Smaller tilts are left alone by Indra's pipeline (the search step is 0.1). */
    public static final double DESKEW_MIN = 0.3;
    /** The full thumbnail's long side; Indra's default {@code side}. */
    public static final int THUMBNAIL_SIDE = 800;
    /** Below this mean ink (0 to 255) a page counts as blank and is not searched. */
    private static final double BLANK_MEAN = 0.003 * 255;
    /** Above this thumbnail side the row-profile sums could round, which numpy would order. */
    private static final int MAX_SIDE = 4096;

    private SkewEstimator() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The skew of a page with Indra's {@value #THUMBNAIL_SIDE}-pixel thumbnail.
     *
     * @param gray The page
     * @return Degrees, counter-clockwise, that straighten its text rows; 0 for a blank page
     */
    public static double estimate(GrayImage gray) {
        return estimate(gray, THUMBNAIL_SIDE);
    }

    /**
     * The skew of a page; Indra's {@code estimate_skew(gray, side)}.
     *
     * @param gray The page
     * @param side The full thumbnail's long side, 2 to {@value #MAX_SIDE}
     * @return Degrees, counter-clockwise, that straighten its text rows; 0 for a blank page
     * @throws IllegalArgumentException if {@code side} is out of range
     */
    public static double estimate(GrayImage gray, int side) {
        Objects.requireNonNull(gray, "gray must not be null");
        if (side < 2 || side > MAX_SIDE) {
            throw new IllegalArgumentException("side must be 2 to " + MAX_SIDE + ", got " + side);
        }
        GrayImage fine = inkThumb(gray, side);
        if (fine.mean() < BLANK_MEAN) {
            return 0.0;
        }
        GrayImage coarse = inkThumb(gray, side / 2);
        double[] grid = NumpyOrder.arange(-DESKEW_MAX, DESKEW_MAX + 1e-6, 2.0);
        double[] scores = new double[grid.length];
        for (int i = 0; i < grid.length; i++) {
            scores[i] = rowSharpness(coarse, grid[i]);
        }
        int[] order = NumpyOrder.argsort(scores);
        double[] starts = NumpyOrder.setIterationOrder(0.0,
            grid[order[order.length - 2]], grid[order[order.length - 1]]);

        Map<Double, Double> seen = new HashMap<>();
        double best = Double.NaN;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (double a0 : starts) {
            for (double a : NumpyOrder.arange(a0 - 2.0, a0 + 2.0 + 1e-6, 0.5)) {
                double score = seen.computeIfAbsent(a + 0.0, k -> rowSharpness(fine, a));
                if (score > bestScore) {
                    bestScore = score;
                    best = a;
                }
            }
        }
        double[] last = NumpyOrder.arange(best - 0.5, best + 0.5 + 1e-6, 0.1);
        double angle = last[0];
        double angleScore = rowSharpness(fine, angle);
        for (int i = 1; i < last.length; i++) {
            double score = rowSharpness(fine, last[i]);
            if (score > angleScore) {
                angleScore = score;
                angle = last[i];
            }
        }
        return angle;
    }

    /**
     * The page shrunk to {@code side} pixels on its long side as ink, 255 for black; Indra's
     * {@code _ink_thumb}. Eight-bit, as Indra keeps it, because 8-bit images rotate faster.
     *
     * @param gray The page
     * @param side The thumbnail's long side
     * @return The inverted thumbnail
     */
    static GrayImage inkThumb(GrayImage gray, int side) {
        double s = (double) side / Math.max(gray.height(), gray.width());
        return Resize.resize(gray, Math.max(1, (int) Math.rint(gray.width() * s)),
            Math.max(1, (int) Math.rint(gray.height() * s)), Interpolation.AREA).inverted();
    }

    /**
     * How sharply an ink thumbnail's row profile changes with the page turned by an angle;
     * Indra's {@code _row_sharpness}: the sum over rows of the squared change in row ink.
     *
     * <p>The thumbnail is rotated about its centre with a black border, so ink leaving the
     * frame is lost, as in Indra. Row sums and their squared differences are integers that
     * fit a double exactly at the sizes allowed, so the order of numpy's summation does not
     * matter.</p>
     *
     * @param ink     The ink thumbnail
     * @param degrees The rotation, counter-clockwise
     * @return The score; higher means more level rows
     */
    static double rowSharpness(GrayImage ink, double degrees) {
        int w = ink.width();
        int h = ink.height();
        AffineTransform m = AffineTransform.rotation(w / 2.0, h / 2.0, degrees, 1.0);
        byte[] rotated = WarpAffine.warp(ink, m, w, h, BorderMode.CONSTANT).pixels();
        long total = 0;
        long previous = 0;
        for (int y = 0; y < h; y++) {
            long row = 0;
            for (int x = 0; x < w; x++) {
                row += rotated[y * w + x] & 0xFF;
            }
            if (y > 0) {
                long d = row - previous;
                total += d * d;
            }
            previous = row;
        }
        return total;
    }
}
