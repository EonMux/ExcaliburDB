// Ported from Indra OCR 1.1.2, order_post.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.layout;

import com.eonmux.excalibur.ocr.array.NumpyOps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Geometric reading orders: rows top to bottom, and recursive XY-cut, which splits a set of
 * lines at its widest whitespace gap across or down until no gap is left.
 *
 * <p>Every method takes the page's boxes and a list of line indices into them, and returns
 * indices from that list, so a block of lines is ordered without copying its boxes. The
 * recursion of Indra's {@code rec} runs on an explicit stack, so a page of many thin slices
 * cannot overflow the call stack; it visits the parts in the same order.</p>
 */
final class XyCut {

    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    /** The floor of half a line height in {@link #topDown}, Indra's {@code 1e-3}. */
    private static final float MIN_ROW = 1e-3f;

    private XyCut() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Rows top to bottom, left to right within a row: lines whose vertical centres round to the
     * same multiple of half the median line height share a row. Ports {@code order_top_down}.
     *
     * @param boxes The page's boxes
     * @param ids   The lines to order
     * @return The same lines in reading order
     */
    static int[] topDown(float[][] boxes, int[] ids) {
        int n = ids.length;
        if (n == 0) {
            return new int[0];
        }
        float[] heights = new float[n];
        for (int k = 0; k < n; k++) {
            heights[k] = boxes[ids[k]][3] - boxes[ids[k]][1];
        }
        float half = NumpyOps.median(heights) * 0.5f;
        float row = MIN_ROW > half ? MIN_ROW : half;
        long[] rowKey = new long[n];
        Integer[] pos = new Integer[n];
        for (int k = 0; k < n; k++) {
            float[] b = boxes[ids[k]];
            rowKey[k] = (long) Math.rint((b[1] + b[3]) / 2 / row);
            pos[k] = k;
        }
        Comparator<Integer> byRow = Comparator.comparingLong(k -> rowKey[k]);
        Arrays.sort(pos, byRow.thenComparing((p, q) -> compare(boxes[ids[p]][0], boxes[ids[q]][0])));
        int[] out = new int[n];
        for (int k = 0; k < n; k++) {
            out[k] = ids[pos[k]];
        }
        return out;
    }

    /**
     * Recursive XY-cut: split at the widest whitespace gap on either axis, the upper or left
     * part first, and fall back to {@link #topDown} where no gap is left. Ports
     * {@code order_xycut}.
     *
     * @param boxes The page's boxes
     * @param ids   The lines to order
     * @return The same lines in reading order
     */
    static int[] order(float[][] boxes, int[] ids) {
        List<Integer> out = new ArrayList<>(ids.length);
        Deque<int[]> work = new ArrayDeque<>();
        work.push(ids);
        while (!work.isEmpty()) {
            int[] part = work.pop();
            if (part.length <= 1) {
                appendAll(out, part);
                continue;
            }
            Cut y = bestCut(boxes, part, AXIS_Y);
            Cut x = bestCut(boxes, part, AXIS_X);
            if (y.k < 0 && x.k < 0) {
                appendAll(out, topDown(boxes, part));
                continue;
            }
            Cut cut = y.gap >= x.gap ? y : x;
            work.push(cut.after(part));
            work.push(cut.before(part));
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Blocks in reading order by recursive XY-cut, for pages the order model does not know. A
     * cut needs a whitespace gap of at least {@code minGap} median line heights; lines inside a
     * block go by {@link #order}. Ports {@code xycut_blocks}.
     *
     * @param boxes  The page's boxes
     * @param minGap The smallest gap that separates blocks, in median line heights
     * @return The blocks, each its line indices in reading order
     */
    static List<int[]> blocks(float[][] boxes, double minGap) {
        int n = boxes.length;
        if (n == 0) {
            return List.of();
        }
        float[] heights = new float[n];
        for (int i = 0; i < n; i++) {
            heights[i] = boxes[i][3] - boxes[i][1];
        }
        float threshold = (float) (minGap * NumpyOps.median(heights));
        List<int[]> out = new ArrayList<>();
        Deque<int[]> work = new ArrayDeque<>();
        work.push(identity(n));
        while (!work.isEmpty()) {
            int[] part = work.pop();
            if (part.length > 1) {
                Cut y = bestCut(boxes, part, AXIS_Y);
                Cut x = bestCut(boxes, part, AXIS_X);
                Cut cut = y.gap >= x.gap ? y : x;
                if (cut.k >= 0 && cut.gap >= threshold) {
                    work.push(cut.after(part));
                    work.push(cut.before(part));
                    continue;
                }
            }
            out.add(order(boxes, part));
        }
        return List.copyOf(out);
    }

    /**
     * The widest gap between the lines' extents along one axis: they are sorted by their low
     * edge, and a gap opens where a low edge passes every high edge before it.
     */
    private static Cut bestCut(float[][] boxes, int[] part, int axis) {
        int lo = axis == AXIS_Y ? 1 : 0;
        int hi = lo + 2;
        float[] low = new float[part.length];
        for (int k = 0; k < part.length; k++) {
            low[k] = boxes[part[k]][lo];
        }
        int[] sorted = NumpyOps.argsort(low);
        float end = boxes[part[sorted[0]]][hi];
        float bestGap = 0f;
        int bestK = -1;
        for (int k = 1; k < sorted.length; k++) {
            float gap = low[sorted[k]] - end;
            if (gap > bestGap) {
                bestGap = gap;
                bestK = k;
            }
            end = Math.max(end, boxes[part[sorted[k]]][hi]);
        }
        return new Cut(bestGap, bestK, sorted);
    }

    /** numpy's comparison of float32 keys in a Python sort: by value, -0.0 equal to 0.0. */
    private static int compare(float a, float b) {
        return a < b ? -1 : a > b ? 1 : 0;
    }

    private static void appendAll(List<Integer> out, int[] ids) {
        for (int id : ids) {
            out.add(id);
        }
    }

    /**
     * The line indices {@code 0 .. n-1}: Python's {@code list(range(n))}.
     *
     * @param n How many lines
     * @return The indices in order
     */
    static int[] identity(int n) {
        int[] ids = new int[n];
        Arrays.setAll(ids, i -> i);
        return ids;
    }

    /**
     * A cut through a part: its gap, the position in the sorted order where it falls (-1 for
     * none) and that order.
     */
    private record Cut(float gap, int k, int[] sorted) {

        int[] before(int[] part) {
            return pick(part, 0, k);
        }

        int[] after(int[] part) {
            return pick(part, k, sorted.length);
        }

        private int[] pick(int[] part, int from, int to) {
            int[] out = new int[to - from];
            for (int i = from; i < to; i++) {
                out[i - from] = part[sorted[i]];
            }
            return out;
        }
    }
}
