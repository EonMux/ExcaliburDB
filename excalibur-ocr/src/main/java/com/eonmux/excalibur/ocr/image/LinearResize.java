// Reproduces the documented behaviour of OpenCV 5.0's fixed-point bilinear resize
// (resizeGeneric_ with HResizeLinear and VResizeLinear in imgproc/src/resize.cpp) on 8-bit
// single-channel images.
package com.eonmux.excalibur.ocr.image;

/**
 * Bilinear resize of a gray image in OpenCV's 11-bit fixed point.
 *
 * <p>Used for {@link Interpolation#LINEAR}, and for {@link Interpolation#AREA} whenever a side
 * grows, where OpenCV snaps the weights to the source grid instead. Weights are computed in
 * {@code float} and rounded half to even to multiples of 1/2048. Each source row is first
 * blended horizontally into 32-bit sums, two rows are then blended vertically with the sums cut
 * to 16 bits ({@code >> 4}), each product cut by {@code >> 16}, and the total rounded with
 * {@code (t + 2) >> 2}: integer arithmetic, so OpenCV's vector and scalar code agree and so does
 * this.</p>
 */
final class LinearResize {

    private static final int COEF_SCALE = 2048;

    private LinearResize() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The resized image.
     *
     * @param src       The image
     * @param width     Columns of the result
     * @param height    Rows of the result
     * @param invScaleX {@code width / src.width()}, as OpenCV computes it
     * @param invScaleY {@code height / src.height()}
     * @param areaMode  Whether to place samples as {@link Interpolation#AREA} does
     * @return The resized image
     */
    static GrayImage resize(GrayImage src, int width, int height, double invScaleX,
                            double invScaleY, boolean areaMode) {
        Axis xs = Axis.of(src.width(), width, invScaleX, areaMode, true);
        Axis ys = Axis.of(src.height(), height, invScaleY, areaMode, false);
        int[] row0 = new int[width];
        int[] row1 = new int[width];
        byte[] out = new byte[width * height];
        int lastRow = src.height() - 1;
        for (int dy = 0; dy < height; dy++) {
            int sy = ys.offset[dy];
            horizontal(src, Math.clamp(sy, 0, lastRow), xs, row0);
            horizontal(src, Math.clamp(sy + 1L, 0, lastRow), xs, row1);
            int b0 = ys.weight0[dy];
            int b1 = ys.weight1[dy];
            int base = dy * width;
            for (int dx = 0; dx < width; dx++) {
                out[base + dx] = (byte) ((((b0 * (row0[dx] >> 4)) >> 16)
                    + ((b1 * (row1[dx] >> 4)) >> 16) + 2) >> 2);
            }
        }
        return GrayImage.adopt(width, height, out);
    }

    private static void horizontal(GrayImage src, int y, Axis xs, int[] dst) {
        byte[] in = src.raw();
        int rowStart = y * src.width();
        for (int dx = 0; dx < dst.length; dx++) {
            int sx = rowStart + xs.offset[dx];
            dst[dx] = dx < xs.blendEnd
                ? (in[sx] & 0xFF) * xs.weight0[dx] + (in[sx + 1] & 0xFF) * xs.weight1[dx]
                : (in[sx] & 0xFF) * COEF_SCALE;
        }
    }

    /**
     * Sample positions and weights along one axis, as OpenCV's {@code resize} computes them.
     */
    private static final class Axis {
        final int[] offset;
        final int[] weight0;
        final int[] weight1;
        /** Columns from here on take their one source pixel whole (OpenCV's {@code xmax}). */
        final int blendEnd;

        private Axis(int[] offset, int[] weight0, int[] weight1, int blendEnd) {
            this.offset = offset;
            this.weight0 = weight0;
            this.weight1 = weight1;
            this.blendEnd = blendEnd;
        }

        /**
         * The axis's table. Columns clamp a sample that falls off the source to its edge with
         * a whole weight; rows keep the raw position and weights, and the caller clamps the two
         * rows it reads, as OpenCV does.
         */
        static Axis of(int sourceSize, int targetSize, double invScale, boolean areaMode,
                       boolean columns) {
            double scale = 1.0 / invScale;
            int[] offset = new int[targetSize];
            int[] weight0 = new int[targetSize];
            int[] weight1 = new int[targetSize];
            int blendEnd = targetSize;
            for (int d = 0; d < targetSize; d++) {
                int s;
                float f;
                if (!areaMode) {
                    f = (float) ((d + 0.5) * scale - 0.5);
                    s = (int) Math.floor(f);
                    f -= s;
                } else {
                    s = (int) Math.floor(d * scale);
                    f = (float) ((d + 1) - (s + 1) * invScale);
                    f = f <= 0 ? 0f : f - (int) Math.floor(f);
                }
                if (columns) {
                    if (s < 0) {
                        f = 0;
                        s = 0;
                    }
                    if (s + 1 >= sourceSize) {
                        blendEnd = Math.min(blendEnd, d);
                        if (s >= sourceSize - 1) {
                            f = 0;
                            s = sourceSize - 1;
                        }
                    }
                }
                offset[d] = s;
                weight0[d] = fixedPoint(1f - f);
                weight1[d] = fixedPoint(f);
            }
            return new Axis(offset, weight0, weight1, blendEnd);
        }

        /** OpenCV's {@code saturate_cast<short>(w * INTER_RESIZE_COEF_SCALE)}. */
        private static int fixedPoint(float w) {
            double rounded = Math.rint(w * COEF_SCALE);
            return (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, rounded));
        }
    }
}
