// Reproduces the documented behaviour of OpenCV 5.0's INTER_AREA shrinking (resizeAreaFast_ and
// resizeArea_ in imgproc/src/resize.cpp) on 8-bit single-channel images.
package com.eonmux.excalibur.ocr.image;

import java.util.Arrays;

/**
 * Area-averaging shrink of a gray image, OpenCV's {@code INTER_AREA} when neither side grows.
 *
 * <p>Two algorithms, chosen by {@link Resize}: block means when both factors are whole numbers,
 * and weighted sums over fractional source cells otherwise. Each reproduces OpenCV's arithmetic
 * and rounding exactly; see the methods.</p>
 */
final class AreaResize {

    /** OpenCV's tolerance for a cell edge that falls on a pixel boundary. */
    private static final double EDGE_TOLERANCE = 1e-3;

    private AreaResize() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Block means at whole-number factors; OpenCV's {@code resizeAreaFast_}.
     *
     * <p>A 2x2 block is rounded half up, {@code (sum + 2) >> 2}, as OpenCV's vector code and its
     * matching scalar remainder do. Any other block is {@code sum * (1f / area)} in single
     * precision rounded half to even, as its generic loop does.</p>
     *
     * @param src     The image, {@code factorX * width} by {@code factorY * height}
     * @param width   Columns of the result
     * @param height  Rows of the result
     * @param factorX Source columns per result column
     * @param factorY Source rows per result row
     * @return The shrunk image
     */
    static GrayImage integerFactor(GrayImage src, int width, int height, int factorX,
                                   int factorY) {
        if (src.width() != width * factorX || src.height() != height * factorY) {
            throw new IllegalArgumentException(src + " is not " + factorX + "x" + factorY
                + " times " + width + "x" + height);
        }
        byte[] in = src.raw();
        int stride = src.width();
        byte[] out = new byte[width * height];
        boolean halving = factorX == 2 && factorY == 2;
        float scale = 1f / (factorX * factorY);
        for (int dy = 0; dy < height; dy++) {
            int rowStart = dy * factorY * stride;
            for (int dx = 0; dx < width; dx++) {
                int origin = rowStart + dx * factorX;
                int sum = 0;
                for (int ky = 0; ky < factorY; ky++) {
                    int row = origin + ky * stride;
                    for (int kx = 0; kx < factorX; kx++) {
                        sum += in[row + kx] & 0xFF;
                    }
                }
                out[dy * width + dx] = (byte) (halving ? (sum + 2) >> 2
                    : saturate(sum * scale));
            }
        }
        return GrayImage.adopt(width, height, out);
    }

    /**
     * Weighted cell sums at fractional factors; OpenCV's {@code resizeArea_} with
     * {@code float} accumulators.
     *
     * <p>Each result row accumulates its source rows in table order, each source row first
     * reduced horizontally into a buffer; every product and sum is a {@code float} operation in
     * OpenCV's order, and the total is rounded half to even.</p>
     *
     * @param src    The image
     * @param width  Columns of the result, at most the source's
     * @param height Rows of the result, at most the source's
     * @param scaleX Source columns per result column, at least 1
     * @param scaleY Source rows per result row, at least 1
     * @return The shrunk image
     */
    static GrayImage fractional(GrayImage src, int width, int height, double scaleX,
                                double scaleY) {
        Table xs = Table.of(src.width(), width, scaleX);
        Table ys = Table.of(src.height(), height, scaleY);
        byte[] in = src.raw();
        int stride = src.width();
        byte[] out = new byte[width * height];
        float[] buf = new float[width];
        float[] sum = new float[width];
        int prevDy = ys.target[0];
        for (int j = 0; j < ys.size; j++) {
            float beta = ys.weight[j];
            int dy = ys.target[j];
            int rowStart = ys.source[j] * stride;
            Arrays.fill(buf, 0f);
            for (int k = 0; k < xs.size; k++) {
                buf[xs.target[k]] += (in[rowStart + xs.source[k]] & 0xFF) * xs.weight[k];
            }
            if (dy != prevDy) {
                store(sum, out, prevDy * width);
                for (int dx = 0; dx < width; dx++) {
                    sum[dx] = beta * buf[dx];
                }
                prevDy = dy;
            } else {
                for (int dx = 0; dx < width; dx++) {
                    sum[dx] += beta * buf[dx];
                }
            }
        }
        store(sum, out, prevDy * width);
        return GrayImage.adopt(width, height, out);
    }

    private static void store(float[] sum, byte[] out, int offset) {
        for (int dx = 0; dx < sum.length; dx++) {
            out[offset + dx] = (byte) saturate(sum[dx]);
        }
    }

    /**
     * OpenCV's {@code saturate_cast<uchar>(float)}: round half to even, then clamp.
     *
     * @param value The value
     * @return It as a gray level, 0 to 255
     */
    static int saturate(float value) {
        double rounded = Math.rint(value);
        return rounded <= 0 ? 0 : rounded >= 255 ? 255 : (int) rounded;
    }

    /**
     * Which source pixels feed which result pixels, and with what weight, along one axis;
     * OpenCV's {@code computeResizeAreaTab}.
     */
    private static final class Table {
        final int[] target;
        final int[] source;
        final float[] weight;
        final int size;

        private Table(int[] target, int[] source, float[] weight, int size) {
            this.target = target;
            this.source = source;
            this.weight = weight;
            this.size = size;
        }

        static Table of(int sourceSize, int targetSize, double scale) {
            int capacity = sourceSize * 2;
            int[] target = new int[capacity];
            int[] source = new int[capacity];
            float[] weight = new float[capacity];
            int k = 0;
            for (int d = 0; d < targetSize; d++) {
                double start = d * scale;
                double end = start + scale;
                double cellWidth = Math.min(scale, sourceSize - start);
                int first = (int) Math.ceil(start);
                int last = Math.min((int) Math.floor(end), sourceSize - 1);
                first = Math.min(first, last);
                if (first - start > EDGE_TOLERANCE) {
                    target[k] = d;
                    source[k] = first - 1;
                    weight[k++] = (float) ((first - start) / cellWidth);
                }
                for (int s = first; s < last; s++) {
                    target[k] = d;
                    source[k] = s;
                    weight[k++] = (float) (1.0 / cellWidth);
                }
                if (end - last > EDGE_TOLERANCE) {
                    target[k] = d;
                    source[k] = last;
                    weight[k++] = (float) (Math.min(Math.min(end - last, 1.0), cellWidth)
                        / cellWidth);
                }
            }
            return new Table(target, source, weight, k);
        }
    }
}
