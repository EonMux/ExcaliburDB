// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.ConversionTimeoutException;
import com.eonmux.excalibur.ocr.Deadline;
import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.detect.DetectorInput;
import com.eonmux.excalibur.ocr.detect.LineDecoder;
import com.eonmux.excalibur.ocr.detect.PictureRegions;
import com.eonmux.excalibur.ocr.detect.ScoredBox;
import com.eonmux.excalibur.ocr.geometry.PageRotation;
import com.eonmux.excalibur.ocr.geometry.SkewEstimator;
import com.eonmux.excalibur.ocr.geometry.Thumbnails;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * OCR of one page image: Indra's {@code OrtOCR.__call__}, {@code _read}, {@code _turn},
 * {@code orientation} and {@code detect}.
 *
 * <p>The page is straightened by the angle its text rows are level at, turned upright when the
 * orientation model is sure it is turned, its lines detected - a second time at a higher
 * resolution when they are small - and read, and the lines assembled into markdown.</p>
 */
final class PageOcr {

    /** Median line height, in detector pixels, under which detection runs again larger. */
    static final double ADAPTIVE_MIN_H = 9.0;
    static final double ADAPTIVE_TARGET_H = 12.0;
    static final int ADAPTIVE_MAX_SIDE = 2048;

    /** A turn is applied only above this probability: a wrong turn loses the page. */
    static final double ORIENT_MIN = 0.95;

    /** The orientation class of a page set in vertical columns. */
    static final int VERTICAL = 4;

    private final OcrModels models;
    private final Recognition recognition;
    private final Assembly assembly;

    PageOcr(OcrModels models, Recognition recognition, Assembly assembly) {
        this.models = models;
        this.recognition = recognition;
        this.assembly = assembly;
    }

    /**
     * Reads a page image.
     *
     * @param gray     The page
     * @param turn     Whether it may be turned; false when it is known to be upright
     * @param deadline When the conversion must be done by
     * @return The page's markdown and text
     * @throws ConversionTimeoutException if the deadline passes
     * @throws InterruptedException       if the thread is interrupted
     */
    PageText read(GrayImage gray, boolean turn, Deadline deadline)
            throws ConversionTimeoutException, InterruptedException {
        GrayImage small = Thumbnails.preview(gray);
        double a = SkewEstimator.estimate(small);
        deadline.check();
        // The orientation model reads the page straightened: a tilted page could read as turned.
        int k = turn ? orientation(Math.abs(a) >= SkewEstimator.DESKEW_MIN
            ? PageRotation.rotate(small, a) : small) : 0;
        deadline.check();
        return readTurned(gray, k, a, small, deadline);
    }

    private PageText readTurned(GrayImage gray, int k, double a, GrayImage small,
                                Deadline deadline)
            throws ConversionTimeoutException, InterruptedException {
        GrayImage page = upright(gray, k, a, small);
        deadline.check();
        Detection found = detectAll(page);
        deadline.check();
        boolean vertical = k == VERTICAL;
        List<Reading> rec = recognition.recognize(page, found.boxes(), vertical);
        deadline.check();
        return assembly.assemble(page.width(), page.height(), found.boxes(), rec, vertical,
            vertical ? null : found.pictures());
    }

    /** The page turned by {@code k} quarter turns and straightened: Indra's {@code _turn}. */
    private static GrayImage upright(GrayImage gray, int k, double a, GrayImage small) {
        // A half turn keeps the rows, so the angle stands; after a quarter turn the rows were
        // columns, so the angle is measured again on the turned page.
        int q = k == VERTICAL ? 1 : -k;
        GrayImage page = gray;
        if (k == VERTICAL) {
            // Columns become lines, the first (right) column on top.
            page = gray.rot90(1);
        } else if (k != 0) {
            page = gray.rot90(-k);
        }
        double angle = a;
        if (k == 1 || k == 3 || k == VERTICAL) {
            angle = SkewEstimator.estimate(small != null ? small.rot90(q) : page);
        }
        return Math.abs(angle) >= SkewEstimator.DESKEW_MIN ? PageRotation.rotate(page, angle)
            : page;
    }

    /**
     * Quarter turns, counter-clockwise, the page content is turned by: 0 without the model or
     * when no turn is more likely than {@link #ORIENT_MIN}; {@link #VERTICAL} for a page set in
     * vertical columns.
     */
    private int orientation(GrayImage gray) {
        if (models.orient == null) {
            return 0;
        }
        InkMap thumb = Thumbnails.orientInput(gray);
        float[] p = models.orient.run(Map.of("x", new NdArray("f4",
            new int[] {1, 1, thumb.height(), thumb.width()}, thumb.values()))).getFirst()
            .floats();
        int k = 0;
        for (int i = 1; i < p.length; i++) {
            if (p[i] > p[k]) {
                k = i;
            }
        }
        return p[k] > ORIENT_MIN ? k : 0;
    }

    /**
     * Text-line boxes, and picture regions from the detector's picture channel.
     *
     * @param gray The page
     * @return Line boxes {@code [x0, y0, x1, y1, score]} and picture boxes {@code [x0, y0, x1,
     *         y1]}, page pixels
     */
    Detection detectAll(GrayImage gray) {
        Pass pass = detectAt(gray, models.longSide);
        if (pass.boxes().length >= 8) {
            float med = median(pass.boxes());
            if (med < ADAPTIVE_MIN_H) {
                int ls = (int) Math.min(ADAPTIVE_MAX_SIDE,
                    models.longSide * ADAPTIVE_TARGET_H / Math.max(med, 1.0)) / 32 * 32;
                if (ls > models.longSide) {
                    pass = detectAt(gray, ls);
                }
            }
        }
        float s = (float) pass.scale();
        float w = gray.width();
        float h = gray.height();
        float[][] boxes = pass.boxes();
        for (float[] b : boxes) {
            b[0] = clip(b[0] / s, w);
            b[1] = clip(b[1] / s, h);
            b[2] = clip(b[2] / s, w);
            b[3] = clip(b[3] / s, h);
        }
        float[][] pictures = new float[0][];
        if (pass.picture() != null) {
            pictures = PictureRegions.find(pass.picture(), pass.height(), pass.width(),
                pass.scale(), gray.width(), gray.height()).stream()
                .map(b -> new float[] {b.x0(), b.y0(), b.x1(), b.y1()}).toArray(float[][]::new);
        }
        return new Detection(boxes, pictures);
    }

    /**
     * Text-line boxes only.
     *
     * @param gray The page
     * @return Line boxes {@code [x0, y0, x1, y1, score]}
     */
    float[][] detect(GrayImage gray) {
        return detectAll(gray).boxes();
    }

    private Pass detectAt(GrayImage gray, int longSide) {
        DetectorInput input = DetectorInput.of(gray, longSide);
        InkMap x = input.ink();
        NdArray out = models.det.run(Map.of("x", new NdArray("f4",
            new int[] {1, 1, x.height(), x.width()}, x.values()))).getFirst();
        int channels = out.shape()[1];
        int h = out.shape()[2];
        int w = out.shape()[3];
        float[] values = out.floats();
        List<ScoredBox> decoded = LineDecoder.decode(Arrays.copyOf(values, 3 * h * w), h, w);
        float[][] boxes = decoded.stream().map(b -> new float[] {
            b.box().x0(), b.box().y0(), b.box().x1(), b.box().y1(), b.score()})
            .toArray(float[][]::new);
        float[] picture = channels > 3
            ? Arrays.copyOfRange(values, 3 * h * w, 4 * h * w) : null;
        return new Pass(boxes, input.scale(), picture, h, w);
    }

    /** numpy's median of the boxes' float32 heights. */
    private static float median(float[][] boxes) {
        float[] heights = new float[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            heights[i] = boxes[i][3] - boxes[i][1];
        }
        Arrays.sort(heights);
        int n = heights.length;
        return n % 2 == 1 ? heights[n / 2] : (heights[n / 2 - 1] + heights[n / 2]) / 2f;
    }

    private static float clip(float v, float max) {
        return Math.min(Math.max(v, 0f), max);
    }

    /**
     * What detection found.
     *
     * @param boxes    Line boxes
     * @param pictures Picture regions
     */
    record Detection(float[][] boxes, float[][] pictures) {
    }

    /** One detector pass at one scale. */
    private record Pass(float[][] boxes, double scale, float[] picture, int height, int width) {
    }
}
