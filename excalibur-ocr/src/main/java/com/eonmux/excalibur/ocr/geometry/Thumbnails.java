// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.geometry;

import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;
import com.eonmux.excalibur.ocr.image.Interpolation;
import com.eonmux.excalibur.ocr.image.Resize;

import java.util.Objects;

/**
 * Small versions of a page for the skew search and the orientation model; Indra's
 * {@code preview} and {@code orient_input}.
 *
 * <p>Sizes are {@code round(side * scale)} with Python's {@code round}, half to even.</p>
 */
public final class Thumbnails {

    /** The preview's longest side; Indra's {@code PREVIEW_SIDE}. */
    public static final int PREVIEW_SIDE = 1600;
    /** The orientation model's square input side; Indra's {@code ORIENT_SIDE}. */
    public static final int ORIENT_SIDE = 768;

    private Thumbnails() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The page shrunk once to at most {@value #PREVIEW_SIDE} pixels a side.
     *
     * @param gray The page
     * @return The preview, or the page itself when it is no larger
     */
    public static GrayImage preview(GrayImage gray) {
        return preview(gray, PREVIEW_SIDE);
    }

    /**
     * The page shrunk once to at most {@code side} pixels a side by area averaging; Indra's
     * {@code preview}.
     *
     * @param gray The page
     * @param side The longest side allowed, at least 1
     * @return The preview, or the page itself when it is no larger
     */
    public static GrayImage preview(GrayImage gray, int side) {
        Objects.requireNonNull(gray, "gray must not be null");
        requirePositive(side);
        double s = scale(gray, side);
        if (s >= 1) {
            return gray;
        }
        return Resize.resize(gray, scaled(gray.width(), s), scaled(gray.height(), s),
            Interpolation.AREA);
    }

    /**
     * The orientation model's input: {@code [768, 768]} ink, the page scaled so its long side
     * fills it, the rest paper.
     *
     * @param gray The page
     * @return The input
     */
    public static InkMap orientInput(GrayImage gray) {
        return orientInput(gray, ORIENT_SIDE);
    }

    /**
     * The orientation model's input; Indra's {@code orient_input}: the page scaled by area
     * averaging (enlarged too, when small) so its long side is {@code side}, as ink
     * {@code 1 - g/255} in single precision, at the top left of a {@code side x side} square of
     * paper (0).
     *
     * @param gray The page
     * @param side The square's side, at least 1
     * @return The input
     */
    public static InkMap orientInput(GrayImage gray, int side) {
        Objects.requireNonNull(gray, "gray must not be null");
        requirePositive(side);
        double s = scale(gray, side);
        GrayImage small = Resize.resize(gray, scaled(gray.width(), s), scaled(gray.height(), s),
            Interpolation.AREA);
        float[] out = new float[side * side];
        for (int y = 0; y < Math.min(side, small.height()); y++) {
            for (int x = 0; x < Math.min(side, small.width()); x++) {
                out[y * side + x] = 1f - small.get(x, y) / 255f;
            }
        }
        return InkMap.adopt(side, side, out);
    }

    private static double scale(GrayImage gray, int side) {
        return (double) side / Math.max(gray.height(), gray.width());
    }

    private static int scaled(int size, double s) {
        return Math.max(1, (int) Math.rint(size * s));
    }

    private static void requirePositive(int side) {
        if (side < 1) {
            throw new IllegalArgumentException("side must be positive, got " + side);
        }
    }
}
