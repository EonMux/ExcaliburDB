// Ported from Indra OCR 1.1.2, order_post.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.layout;

import com.eonmux.excalibur.ocr.array.NumpyMath;
import com.eonmux.excalibur.ocr.array.NumpyOps;

import java.util.ArrayList;
import java.util.List;

/**
 * A page's reading order: decoded from the order model's pairwise logits, or by geometry for
 * pages the model does not know.
 *
 * <p>The model's outputs come as ONNX Runtime returns them for one page, row-major and without
 * the batch dimension: {@code a [N, N]} (a positive {@code a[i, j]}: line i before line j),
 * {@code cls [N, BlockClasses.COUNT]} (block class logits) and {@code same [N, N]} (a positive
 * value: the two lines share a block). Boxes are rows {@code [x0, y0, x1, y1, ...]} in page
 * pixels, float32 as Indra holds them; extra columns are ignored. Every order returned is a
 * permutation of the line indices.</p>
 */
public final class ReadingOrder {

    /** Local-search sweeps of {@link #decode(float[], int)}, Indra's default. */
    public static final int SWEEPS = 4;
    /** The smallest whitespace gap between blocks of {@link #xycutBlocks(float[][])}. */
    public static final double MIN_GAP = 1.0;
    /** How much an insertion must gain to be taken, in summed log-probability. */
    private static final double MIN_GAIN = 1e-6;

    private ReadingOrder() {
        throw new AssertionError("Static methods only");
    }

    /**
     * An order from pairwise order logits, with Indra's four sweeps. Ports {@code decode_order}.
     *
     * @param a Row-major {@code [n, n]} logits; {@code a[i, j] > 0} means i before j
     * @param n How many lines
     * @return The lines in reading order
     */
    public static int[] decode(float[] a, int n) {
        return decode(a, n, SWEEPS);
    }

    /**
     * An order from pairwise order logits. Ports {@code decode_order}.
     *
     * <p>Lines start ranked by how many lines each precedes, summed in log-probability. Then
     * each sweep takes every line out in turn and puts it back where it most increases the
     * summed log-probability of the pairwise relations, which can move a line far, such as a
     * column's short last line that the ranking put after the next column. Sweeps stop early
     * once none moves a line.</p>
     *
     * @param a      Row-major {@code [n, n]} logits; {@code a[i, j] > 0} means i before j
     * @param n      How many lines
     * @param sweeps The most local-search sweeps
     * @return The lines in reading order
     */
    public static int[] decode(float[] a, int n, int sweeps) {
        LayoutArgs.requireMatrix(a, n, n, "a");
        if (sweeps < 0) {
            throw new IllegalArgumentException("sweeps must not be negative, got " + sweeps);
        }
        if (n <= 1) {
            return XyCut.identity(n);
        }
        float[] lp = new float[n * n];
        for (int k = 0; k < lp.length; k++) {
            lp[k] = -NumpyMath.logaddexp(0f, -a[k]);
        }
        int[] order = NumpyOps.argsort(rankScores(lp, n));
        for (int sweep = 0; sweep < sweeps; sweep++) {
            boolean improved = false;
            for (int x : order.clone()) {
                int[] moved = bestInsertion(lp, n, order, x);
                if (moved != null) {
                    order = moved;
                    improved = true;
                }
            }
            if (!improved) {
                break;
            }
        }
        return order;
    }

    /**
     * {@code -lp.sum(1) + lp.sum(0)}: numpy sums each row pairwise and each column one row
     * after another.
     */
    private static float[] rankScores(float[] lp, int n) {
        float[] columns = new float[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                columns[j] += lp[i * n + j];
            }
        }
        float[] scores = new float[n];
        for (int i = 0; i < n; i++) {
            scores[i] = -NumpyOps.sum(lp, i * n, i * n + n) + columns[i];
        }
        return scores;
    }

    /**
     * The order with line {@code x} moved to its best position, or null when no position gains
     * more than {@link #MIN_GAIN} over where it is. Placed at position p, x follows the first p
     * other lines and precedes the rest: the gain is a float32 running sum over the ones before
     * plus one over the ones after, added in float64.
     */
    private static int[] bestInsertion(float[] lp, int n, int[] order, int x) {
        int[] rest = new int[n - 1];
        int cur = -1;
        for (int k = 0, r = 0; k < n; k++) {
            if (order[k] == x) {
                cur = k;
            } else {
                rest[r++] = order[k];
            }
        }
        double[] gains = new double[n];
        float before = 0f;
        for (int p = 1; p < n; p++) {
            before = p == 1 ? lp[rest[0] * n + x] : before + lp[rest[p - 1] * n + x];
            gains[p] = before;
        }
        float after = 0f;
        for (int p = n - 2; p >= 0; p--) {
            after = p == n - 2 ? lp[x * n + rest[p]] : after + lp[x * n + rest[p]];
            gains[p] += after;
        }
        int k = NumpyOps.argmax(gains);
        if (!(gains[k] > gains[cur] + MIN_GAIN)) {
            return null;
        }
        int[] out = new int[n];
        System.arraycopy(rest, 0, out, 0, k);
        out[k] = x;
        System.arraycopy(rest, k, out, k + 1, n - 1 - k);
        return out;
    }

    /**
     * The order with the layout heads: blocks first, then lines inside blocks. Ports
     * {@code decode_blocks_order}.
     *
     * <p>Blocks come from average-linkage clustering of the same-block logits. Inside a block,
     * lines go by XY-cut, or row by row when the block's summed class logits make it a table,
     * form or table of contents ({@link BlockClasses#GRID}). Blocks go in the order decoded
     * from the mean order logit between them.</p>
     *
     * @param a     Row-major {@code [n, n]} order logits
     * @param same  Row-major {@code [n, n]} same-block logits
     * @param boxes The lines' boxes
     * @param cls   Row-major {@code [n, BlockClasses.COUNT]} class logits, or null to order
     *              every block by XY-cut
     * @param n     How many lines
     * @return The lines in reading order
     */
    public static int[] decodeBlocks(float[] a, float[] same, float[][] boxes, float[] cls, int n) {
        LayoutArgs.requireMatrix(a, n, n, "a");
        LayoutArgs.requireMatrix(same, n, n, "same");
        LayoutArgs.requireBoxes(boxes);
        if (boxes.length != n) {
            throw new IllegalArgumentException(n + " lines but " + boxes.length + " boxes");
        }
        if (cls != null) {
            LayoutArgs.requireMatrix(cls, n, BlockClasses.COUNT, "cls");
        }
        if (n <= 1) {
            return XyCut.identity(n);
        }
        List<int[]> blocks = Clustering.cluster(same, n);
        List<int[]> inner = new ArrayList<>(blocks.size());
        for (int[] block : blocks) {
            boolean grid = cls != null && isGrid(cls, block);
            inner.add(grid ? XyCut.topDown(boxes, block) : XyCut.order(boxes, block));
        }
        int[] out = new int[n];
        int at = 0;
        for (int k : decode(blockOrderLogits(a, n, blocks), blocks.size())) {
            for (int line : inner.get(k)) {
                out[at++] = line;
            }
        }
        return out;
    }

    /** Whether a block's class, the argmax of its summed class logits, is a grid class. */
    private static boolean isGrid(float[] cls, int[] block) {
        float[] sums = new float[BlockClasses.COUNT];
        for (int line : block) {
            for (int c = 0; c < BlockClasses.COUNT; c++) {
                sums[c] += cls[line * BlockClasses.COUNT + c];
            }
        }
        return BlockClasses.GRID.contains(BlockClasses.LABELS.get(NumpyOps.argmax(sums)));
    }

    /**
     * The mean order logit between blocks, antisymmetric: {@code G a Gᵀ} in float64 with G the
     * block membership scaled by one over the block size, its upper triangle less its
     * transpose, rounded to float32.
     *
     * @param a      Row-major {@code [n, n]} order logits
     * @param n      How many lines
     * @param blocks The blocks
     * @return Row-major {@code [m, m]} block order logits
     */
    static float[] blockOrderLogits(float[] a, int n, List<int[]> blocks) {
        int m = blocks.size();
        double[][] ga = new double[m][n];
        for (int p = 0; p < m; p++) {
            int[] block = blocks.get(p);
            double g = 1.0 / block.length;
            for (int i : block) {
                for (int j = 0; j < n; j++) {
                    ga[p][j] += g * a[i * n + j];
                }
            }
        }
        double[][] gag = new double[m][m];
        for (int q = 0; q < m; q++) {
            int[] block = blocks.get(q);
            double g = 1.0 / block.length;
            for (int p = 0; p < m; p++) {
                for (int j : block) {
                    gag[p][q] += ga[p][j] * g;
                }
            }
        }
        float[] out = new float[m * m];
        for (int p = 0; p < m; p++) {
            for (int q = p + 1; q < m; q++) {
                out[p * m + q] = (float) gag[p][q];
                out[q * m + p] = (float) (0.0 - gag[p][q]);
            }
        }
        return out;
    }

    /**
     * Lines in reading order by recursive XY-cut. Ports {@code order_xycut}.
     *
     * @param boxes The lines' boxes
     * @return The lines in reading order
     */
    public static int[] xycut(float[][] boxes) {
        LayoutArgs.requireBoxes(boxes);
        return XyCut.order(boxes, XyCut.identity(boxes.length));
    }

    /**
     * Lines in rows top to bottom, left to right within a row. Ports {@code order_top_down}.
     *
     * @param boxes The lines' boxes
     * @return The lines in reading order
     */
    public static int[] topDown(float[][] boxes) {
        LayoutArgs.requireBoxes(boxes);
        return XyCut.topDown(boxes, XyCut.identity(boxes.length));
    }

    /**
     * Blocks in reading order by recursive XY-cut, at Indra's minimum gap of one median line
     * height. Ports {@code xycut_blocks}.
     *
     * @param boxes The lines' boxes
     * @return The blocks, each its line indices in reading order
     */
    public static List<int[]> xycutBlocks(float[][] boxes) {
        return xycutBlocks(boxes, MIN_GAP);
    }

    /**
     * Blocks in reading order by recursive XY-cut, for pages the order model does not know
     * (vertical pages, read turned). Ports {@code xycut_blocks}.
     *
     * @param boxes  The lines' boxes
     * @param minGap The smallest whitespace gap that separates blocks, in median line heights
     * @return The blocks, each its line indices in reading order
     */
    public static List<int[]> xycutBlocks(float[][] boxes, double minGap) {
        LayoutArgs.requireBoxes(boxes);
        if (!(minGap >= 0)) {
            throw new IllegalArgumentException("minGap must be a non-negative number, got "
                + minGap);
        }
        return XyCut.blocks(boxes, minGap);
    }
}
