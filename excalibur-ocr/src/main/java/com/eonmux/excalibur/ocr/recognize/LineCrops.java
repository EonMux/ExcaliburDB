// Ported from Indra OCR 1.1.2, rec_preproc.py and line_mask.py (Apache License 2.0); see
// excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.recognize;

import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;
import com.eonmux.excalibur.ocr.image.Interpolation;
import com.eonmux.excalibur.ocr.image.Resize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Recognizer inputs from line boxes: Indra's {@code crop_line}, {@code crop_lines},
 * {@code is_dark_line} and {@code line_darkness}.
 *
 * <p>A line box is expanded by {@link #PAD_Y} of its height above and below and {@link #PAD_X}
 * left and right, cut from the page (paper outside it), and scaled to the recognizer's height,
 * keeping its aspect ratio, as an ink map: 0 paper, 1 ink. A tall window keeps the text's scale
 * and adds the page above and below the line. Indra masks a line with {@code mask.onnx} first
 * when the model directory has it; the pinned revision does not, so neither does this.</p>
 */
public final class LineCrops {

    /** Of the box height, added above and below. */
    public static final double PAD_Y = 0.2;

    /** Of the box height, added left and right. */
    public static final double PAD_X = 0.3;

    /** Widths are at least this, and batches padded to a multiple of it: the timestep stride. */
    public static final int WIDTH_MULTIPLE = 8;

    /** The widest crop. */
    public static final int MAX_WIDTH = 4096;

    private LineCrops() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The recognizer input of each line of a page.
     *
     * @param gray   The page
     * @param boxes  Line boxes, {@code [x0, y0, x1, y1, ...]} pixels
     * @param height The recognizer's line height
     * @param window The window's height in lines: 1, or more to read the page around the line
     * @param dark   Whether each line is light on dark, when known; null to measure it
     * @return The ink maps, one per box
     */
    public static List<InkMap> cropLines(GrayImage gray, float[][] boxes, int height,
                                         int window, boolean[] dark) {
        List<InkMap> crops = new ArrayList<>(boxes.length);
        for (int i = 0; i < boxes.length; i++) {
            crops.add(crop(gray, boxes[i], height, window,
                dark == null ? isDarkLine(gray, boxes[i]) : dark[i]));
        }
        return crops;
    }

    /**
     * Whether each line is light text on a dark ground.
     *
     * @param gray  The page
     * @param boxes Line boxes
     * @return Per box, {@link #isDarkLine}
     */
    public static boolean[] lineDarkness(GrayImage gray, float[][] boxes) {
        boolean[] dark = new boolean[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            dark[i] = isDarkLine(gray, boxes[i]);
        }
        return dark;
    }

    /**
     * Whether a line is light text on a dark ground - a banner, a dark table head, a slide: the
     * ground, the median of the box, lies nearer the dark end than the text does, so the bright
     * tail is the longer one. The recognizers read dark text on light paper, so such a line is
     * read inverted.
     *
     * @param gray The page
     * @param box  The line box
     * @return Whether it is light on dark
     */
    public static boolean isDarkLine(GrayImage gray, float[] box) {
        GrayImage b = gray.region((int) (double) box[0], (int) (double) box[1],
            (int) (double) box[2] + 1, (int) (double) box[3] + 1);
        if (b == null) {
            return false;
        }
        double[] p = percentiles(b);
        return p[2] - p[1] > 2 * (p[1] - p[0]) + 8;
    }

    /**
     * numpy's linear {@code percentile} at 2, 50 and 98 of an image's pixels, from its
     * histogram.
     *
     * @param image The pixels
     * @return The three percentiles
     */
    static double[] percentiles(GrayImage image) {
        byte[] pixels = image.pixels();
        long n = pixels.length;
        long[] cum = new long[256];
        for (byte v : pixels) {
            cum[v & 0xFF]++;
        }
        for (int i = 1; i < 256; i++) {
            cum[i] += cum[i - 1];
        }
        double[] qs = {0.02, 0.5, 0.98};
        double[] out = new double[qs.length];
        for (int k = 0; k < qs.length; k++) {
            double x = qs[k] * (n - 1);
            long i = (long) Math.floor(x);
            double g = x - i;
            double lo = searchRight(cum, i);
            double hi = searchRight(cum, Math.min(i + 1, n - 1));
            double d = hi - lo;
            // numpy's lerp, from the nearer end
            out[k] = g >= 0.5 ? hi - d * (1 - g) : lo + d * g;
        }
        return out;
    }

    /** numpy's {@code searchsorted(cum, v, side="right")}: how many entries are at most v. */
    private static int searchRight(long[] cum, long v) {
        int i = 0;
        while (i < cum.length && cum[i] <= v) {
            i++;
        }
        return i;
    }

    /**
     * One line's recognizer input.
     *
     * @param gray   The page
     * @param box    The line box
     * @param height The recognizer's line height
     * @param window The window's height in lines
     * @param dark   Whether the line is light on dark
     * @return The ink map, {@code window * height} rows
     */
    public static InkMap crop(GrayImage gray, float[] box, int height, int window,
                              boolean dark) {
        double padY = (window * (1 + 2 * PAD_Y) - 1) / 2;
        int outH = height * window;
        double x0 = box[0];
        double y0 = box[1];
        double x1 = box[2];
        double y1 = box[3];
        double h = Math.max(1.0, y1 - y0);
        int ix0 = (int) Math.floor(x0 - PAD_X * h);
        int iy0 = (int) Math.floor(y0 - padY * h);
        int ix1 = (int) Math.ceil(x1 + PAD_X * h);
        int iy1 = (int) Math.ceil(y1 + padY * h);
        int cw = Math.max(1, ix1 - ix0);
        int ch = Math.max(1, iy1 - iy0);
        byte[] crop = new byte[cw * ch];
        Arrays.fill(crop, (byte) (dark ? 0 : 255));
        GrayImage inside = gray.region(ix0, iy0, ix1, iy1);
        if (inside != null) {
            int sx0 = Math.max(0, ix0);
            int sy0 = Math.max(0, iy0);
            byte[] src = inside.pixels();
            for (int y = 0; y < inside.height(); y++) {
                System.arraycopy(src, y * inside.width(), crop,
                    (sy0 - iy0 + y) * cw + (sx0 - ix0), inside.width());
            }
        }
        int outW = Math.max(WIDTH_MULTIPLE,
            (int) Math.min(MAX_WIDTH, Math.rint((double) cw * outH / ch)));
        GrayImage resized = Resize.resize(GrayImage.of(cw, ch, crop), outW, outH,
            ch > outH ? Interpolation.AREA : Interpolation.LINEAR);
        byte[] px = resized.pixels();
        float[] ink = new float[px.length];
        for (int i = 0; i < px.length; i++) {
            float v = (px[i] & 0xFF) / 255f;
            ink[i] = dark ? v : 1f - v;
        }
        return InkMap.adopt(outW, outH, ink);
    }
}
