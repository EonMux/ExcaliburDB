// Ported from Indra OCR 1.1.2, det_post.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.detect;

import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;
import com.eonmux.excalibur.ocr.image.Interpolation;
import com.eonmux.excalibur.ocr.image.Resize;

import java.util.Objects;

/**
 * The line detector's input for a page: the page scaled so its long side is a set length, as
 * ink, padded with paper to a multiple of {@value #INPUT_MULTIPLE} pixels each way.
 *
 * @param ink   The input, {@code [H, W]} with {@code H} and {@code W} multiples of
 *              {@value #INPUT_MULTIPLE}; 0 is paper and 1 ink
 * @param scale Input pixels per page pixel
 */
public record DetectorInput(InkMap ink, double scale) {

    /** The detector's input sides must divide by this; Indra's {@code INPUT_MULTIPLE}. */
    public static final int INPUT_MULTIPLE = 32;

    public DetectorInput {
        Objects.requireNonNull(ink, "ink must not be null");
    }

    /**
     * The detector input for a page; Indra's {@code page_to_input}.
     *
     * <p>The page is shrunk with {@link Interpolation#AREA} or enlarged with
     * {@link Interpolation#LINEAR} to {@code round(side * scale)} pixels a side, rounded half
     * to even as Python's {@code round} is, and each pixel {@code g} becomes {@code 1 - g/255}
     * in single precision. The padding is 0, paper.</p>
     *
     * @param page     The page
     * @param longSide The length the page's longer side is scaled to, at least 1
     * @return The input and its scale
     * @throws IllegalArgumentException if {@code longSide} is not positive
     */
    public static DetectorInput of(GrayImage page, int longSide) {
        Objects.requireNonNull(page, "page must not be null");
        if (longSide < 1) {
            throw new IllegalArgumentException("longSide must be positive, got " + longSide);
        }
        int h = page.height();
        int w = page.width();
        double scale = (double) longSide / Math.max(h, w);
        int nh = Math.max(1, (int) Math.rint(h * scale));
        int nw = Math.max(1, (int) Math.rint(w * scale));
        GrayImage scaled = Resize.resize(page, nw, nh,
            scale < 1 ? Interpolation.AREA : Interpolation.LINEAR);
        int ph = ceilToMultiple(nh);
        int pw = ceilToMultiple(nw);
        float[] values = new float[ph * pw];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                values[y * pw + x] = 1f - scaled.get(x, y) / 255f;
            }
        }
        return new DetectorInput(InkMap.adopt(pw, ph, values), scale);
    }

    private static int ceilToMultiple(int n) {
        return (n + INPUT_MULTIPLE - 1) / INPUT_MULTIPLE * INPUT_MULTIPLE;
    }
}
