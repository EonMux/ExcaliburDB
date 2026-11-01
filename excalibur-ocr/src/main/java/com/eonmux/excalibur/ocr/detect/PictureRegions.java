// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.detect;

import com.eonmux.excalibur.ocr.image.Box;
import com.eonmux.excalibur.ocr.image.Components;
import com.eonmux.excalibur.ocr.image.ConnectedComponents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Picture regions from the detector's picture channel; Indra's {@code picture_boxes}.
 *
 * <p>A picture is an 8-connected region whose picture probability exceeds
 * {@value #PICTURE_MIN}, kept when its box covers at least {@value #PICTURE_MIN_AREA} of the
 * page. Boxes are mapped from the channel's half-input resolution back to page pixels and
 * clipped at the page's right and bottom edges.</p>
 */
public final class PictureRegions {

    /** A pixel is picture above this probability; Indra's {@code PICTURE_MIN}. */
    public static final double PICTURE_MIN = 0.5;
    /** A region is kept from this share of the page area; Indra's {@code PICTURE_MIN_AREA}. */
    public static final double PICTURE_MIN_AREA = 0.004;

    private PictureRegions() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The picture boxes of a page.
     *
     * @param logit      The picture channel's logits, {@code [height, width]} row by row
     * @param height     Rows of the channel
     * @param width      Columns of the channel
     * @param scale      The detector input's scale, input pixels per page pixel
     * @param pageWidth  The page's width in pixels
     * @param pageHeight The page's height in pixels
     * @return The boxes in page pixels, in the order of their regions' labels; Indra returns
     *         them as {@code float32}
     */
    public static List<Box> find(float[] logit, int height, int width, double scale,
                                 int pageWidth, int pageHeight) {
        Objects.requireNonNull(logit, "logit must not be null");
        if (height < 1 || width < 1 || (long) height * width != logit.length) {
            throw new IllegalArgumentException("a " + width + "x" + height + " channel needs "
                + ((long) height * width) + " logits, got " + logit.length);
        }
        if (!(scale > 0)) {
            throw new IllegalArgumentException("scale must be positive, got " + scale);
        }
        double threshold = Math.log(PICTURE_MIN / (1 - PICTURE_MIN));
        boolean[] mask = new boolean[logit.length];
        for (int i = 0; i < logit.length; i++) {
            mask[i] = logit[i] > threshold;
        }
        Components cc = ConnectedComponents.label(mask, width, height,
            ConnectedComponents.Connectivity.EIGHT);
        double f = 2.0 / scale;
        double minArea = PICTURE_MIN_AREA * pageHeight * pageWidth;
        List<Box> boxes = new ArrayList<>();
        for (int i = 1; i < cc.count(); i++) {
            int x = cc.left(i);
            int y = cc.top(i);
            double x0 = x * f;
            double y0 = y * f;
            double x1 = Math.min((x + cc.boxWidth(i)) * f, pageWidth);
            double y1 = Math.min((y + cc.boxHeight(i)) * f, pageHeight);
            if ((x1 - x0) * (y1 - y0) >= minArea) {
                boxes.add(new Box((float) x0, (float) y0, (float) x1, (float) y1));
            }
        }
        return List.copyOf(boxes);
    }
}
