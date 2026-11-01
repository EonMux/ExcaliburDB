// Ported from Indra OCR 1.1.2, det_post.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.detect;

import com.eonmux.excalibur.ocr.array.NumpyFloat32;
import com.eonmux.excalibur.ocr.image.Box;
import com.eonmux.excalibur.ocr.image.Components;
import com.eonmux.excalibur.ocr.image.ConnectedComponents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns the line detector's output into line boxes; Indra's {@code decode}.
 *
 * <p>The detector predicts, at half the input resolution, a core-band logit and the distances
 * from each pixel up to the line's top and down to its bottom. Each 4-connected region of core
 * pixels above the probability threshold is one line: its top and bottom are the mean of its
 * pixels' predicted edges, and its sides the region's extent, widened by a fraction of the line
 * height to undo the core's horizontal shrink.</p>
 *
 * <p>The arithmetic follows numpy's types: probabilities and per-pixel edges in {@code float32}
 * (numpy's own {@code exp}, see {@link NumpyFloat32}), the per-line sums and means in
 * {@code float64} as {@code np.bincount} accumulates them in pixel order, the boxes in Python
 * floats, and the result narrowed to {@code float32}.</p>
 */
public final class LineDecoder {

    /** A core pixel is one whose probability exceeds this; Indra's default {@code thresh}. */
    public static final double THRESHOLD = 0.5;
    /** Lines with fewer core pixels are dropped; Indra's default {@code min_area}. */
    public static final int MIN_AREA = 2;
    /** The core is shorter than its line by this fraction of the height at each end. */
    static final double CORE_X_SHRINK = 0.15;
    /** The detector output is at 1/{@value} of the input resolution. */
    static final int OUT_STRIDE = 2;

    private LineDecoder() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The lines of one page with Indra's defaults: threshold 0.5, at least 2 core pixels, no
     * growth.
     *
     * @param output The detector output {@code [3, height, width]}: core logit, distance to
     *               the top, distance to the bottom; channels past the third are ignored
     * @param height Rows of each channel
     * @param width  Columns of each channel
     * @return The lines in the order of their regions' labels, in input pixels
     */
    public static List<ScoredBox> decode(float[] output, int height, int width) {
        return decode(output, height, width, THRESHOLD, MIN_AREA, 0.0, 0.0);
    }

    /**
     * The lines of one page; Indra's {@code decode(out, thresh, min_area, grow_y, grow_x)}.
     *
     * @param output    The detector output, at least {@code [3, height, width]}
     * @param height    Rows of each channel, at least 1
     * @param width     Columns of each channel, at least 1
     * @param threshold Core probability threshold, compared in {@code float32}
     * @param minArea   Fewest core pixels a line keeps
     * @param growY     Each box grows by this fraction of its height at top and bottom
     * @param growX     And by this fraction of its height at each side
     * @return The lines in the order of their regions' labels, in input pixels
     * @throws IllegalArgumentException if the output holds fewer than three channels
     */
    public static List<ScoredBox> decode(float[] output, int height, int width,
                                         double threshold, int minArea, double growY,
                                         double growX) {
        Objects.requireNonNull(output, "output must not be null");
        if (height < 1 || width < 1) {
            throw new IllegalArgumentException("output channels are at least 1x1, got " + width
                + "x" + height);
        }
        int n = height * width;
        if (output.length < 3L * n) {
            throw new IllegalArgumentException("output needs 3 channels of " + width + "x"
                + height + " = " + (3L * n) + " values, got " + output.length);
        }
        float[] prob = new float[n];
        boolean[] core = new boolean[n];
        float thresh = (float) threshold;
        for (int i = 0; i < n; i++) {
            prob[i] = 1f / (1f + NumpyFloat32.exp(-output[i]));
            core[i] = prob[i] > thresh;
        }
        Components cc = ConnectedComponents.label(core, width, height,
            ConnectedComponents.Connectivity.FOUR);
        if (cc.count() <= 1) {
            return List.of();
        }
        LineSums sums = LineSums.of(output, prob, cc);
        List<ScoredBox> boxes = new ArrayList<>();
        for (int i = 1; i < cc.count(); i++) {
            if (cc.area(i) < minArea) {
                continue;
            }
            double y0 = sums.mean(sums.top, i);
            double y1 = sums.mean(sums.bottom, i);
            double lh = Math.max(1.0, y1 - y0);
            double ex = Math.min(CORE_X_SHRINK * lh, 0.5 * lh);
            double x0 = cc.left(i) * OUT_STRIDE - ex;
            double x1 = (cc.left(i) + cc.boxWidth(i)) * OUT_STRIDE + ex;
            Box box = new Box((float) (x0 - growX * lh), (float) (y0 - growY * lh),
                (float) (x1 + growX * lh), (float) (y1 + growY * lh));
            boxes.add(new ScoredBox(box, (float) sums.mean(sums.score, i)));
        }
        return List.copyOf(boxes);
    }

    /** Per-line sums of the predicted edges and scores, as {@code np.bincount} adds them. */
    private record LineSums(double[] top, double[] bottom, double[] score, int[] count) {

        static LineSums of(float[] output, float[] prob, Components cc) {
            int width = cc.width();
            int[] label = cc.labels();
            int n = label.length;
            int labels = cc.count();
            double[] top = new double[labels];
            double[] bottom = new double[labels];
            double[] score = new double[labels];
            int[] count = new int[labels];
            for (int i = 0; i < n; i++) {
                int l = label[i];
                if (l == 0) {
                    continue;
                }
                float ys = ((float) (i / width) + 0.5f) * OUT_STRIDE;
                top[l] += ys - output[n + i];
                bottom[l] += ys + output[2 * n + i];
                score[l] += prob[i];
                count[l]++;
            }
            return new LineSums(top, bottom, score, count);
        }

        double mean(double[] sums, int label) {
            return sums[label] / Math.max((float) count[label], 1f);
        }
    }
}
