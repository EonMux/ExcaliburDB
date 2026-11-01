// Ported from Indra OCR 1.1.2, rec_preproc.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.recognize;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.image.InkMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Line crops as model inputs: Indra's {@code pad_batch} and {@code width_batches}. */
public final class LineBatches {

    /** Lines in a batch at most. */
    public static final int MAX_BATCH = 64;

    /** A batch closes at a line wider than this times its first. */
    public static final double MAX_RATIO = 1.3;

    /** Widths under this count as this, so short lines do not fragment into tiny batches. */
    public static final int MIN_WIDTH = 64;

    /** Lines times width at most, in pixel columns: ONNX Runtime keeps its largest allocation. */
    public static final long MAX_AREA = 64L * 640;

    private LineBatches() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Ink maps right-padded to one width, a multiple of {@link LineCrops#WIDTH_MULTIPLE}.
     *
     * @param lines The crops, all of one height
     * @return {@code x}, float32 {@code [B, 1, H, W]}, and {@code widths}, int64 {@code [B]}
     */
    public static Batch pad(List<InkMap> lines) {
        int h = lines.getFirst().height();
        int widest = lines.stream().mapToInt(InkMap::width).max().orElseThrow();
        int m = LineCrops.WIDTH_MULTIPLE;
        int w = (widest + m - 1) / m * m;
        float[] values = new float[lines.size() * h * w];
        long[] widths = new long[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            InkMap line = lines.get(i);
            if (line.height() != h) {
                throw new IllegalArgumentException("lines of one batch share a height");
            }
            for (int y = 0; y < h; y++) {
                line.copyRow(y, values, (i * h + y) * w);
            }
            widths[i] = line.width();
        }
        return new Batch(new NdArray("f4", new int[] {lines.size(), 1, h, w}, values),
            new NdArray("i8", new int[] {lines.size()}, widths));
    }

    /**
     * Line indices in batches of similar width, with the default bounds.
     *
     * @param widths Each line's width
     * @param maxBatch Lines in a batch at most
     * @return The batches
     */
    public static List<int[]> widthBatches(int[] widths, int maxBatch) {
        return widthBatches(widths, maxBatch, MAX_RATIO, MIN_WIDTH, MAX_AREA);
    }

    /**
     * Line indices in batches of similar width, so little compute goes to padding. Lines are
     * taken narrowest first; a batch closes at {@code maxBatch} lines, at a line wider than
     * {@code maxRatio} times its first, or when lines times width would pass {@code maxArea}.
     *
     * @param widths   Each line's width
     * @param maxBatch Lines in a batch at most
     * @param maxRatio The widest line a batch takes, over its first
     * @param minWidth Widths under this count as this
     * @param maxArea  Lines times width at most
     * @return The batches
     */
    public static List<int[]> widthBatches(int[] widths, int maxBatch, double maxRatio,
                                           int minWidth, long maxArea) {
        int[] order = IntStream.range(0, widths.length).boxed()
            .sorted(Comparator.comparingInt(i -> widths[i])).mapToInt(Integer::intValue)
            .toArray();
        List<List<Integer>> batches = new ArrayList<>();
        for (int i : order) {
            List<Integer> last = batches.isEmpty() ? null : batches.getLast();
            int w = Math.max(widths[i], minWidth);
            if (last != null && last.size() < maxBatch
                    && w <= maxRatio * Math.max(widths[last.getFirst()], minWidth)
                    && (long) (last.size() + 1) * w <= maxArea) {
                last.add(i);
            } else {
                List<Integer> batch = new ArrayList<>();
                batch.add(i);
                batches.add(batch);
            }
        }
        return batches.stream().map(b -> b.stream().mapToInt(Integer::intValue).toArray())
            .toList();
    }

    /**
     * A batch of lines for a recognizer or classifier.
     *
     * @param x      The crops, {@code [B, 1, H, W]}
     * @param widths Each crop's own width
     */
    public record Batch(NdArray x, NdArray widths) {
    }
}
