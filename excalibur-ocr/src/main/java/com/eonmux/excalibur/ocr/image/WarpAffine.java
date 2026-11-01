// Reproduces the documented behaviour of OpenCV 5.0's cv::warpAffine with INTER_LINEAR on
// 8-bit single-channel images (warpAffineLinearInvoker_8UC1 in imgproc/src/warp_kernels.simd.hpp,
// as dispatched on AVX2 hardware).
package com.eonmux.excalibur.ocr.image;

import java.util.Objects;

/**
 * Affine warp of a gray image with bilinear sampling, exactly as OpenCV 5.0's
 * {@code cv2.warpAffine(src, M, (width, height))} computes it.
 *
 * <p>OpenCV inverts the matrix in {@code double}, narrows it to {@code float}, and samples each
 * result pixel at the mapped source point with single-precision bilinear weights. Each row is
 * computed in blocks of {@value #VECTOR_BLOCK} pixels by vector code and the remainder by scalar
 * code, and the two round differently:</p>
 * <ul>
 *   <li>vector: {@code sx = fma(m00, x, y m01 + m02)}, the row term rounded after its
 *       product and again after its sum;</li>
 *   <li>scalar: {@code sx = fma(x, m00, y m01) + m02}, the contraction GCC makes of OpenCV's
 *       {@code x*M[0] + y*M[1] + M[2]} in its AVX2 build.</li>
 * </ul>
 * <p>Both then blend with fused multiply-adds, {@code p0 + f (p1 - p0)}, and round half to
 * even. These forms were confirmed against {@code cv2.warpAffine} on thousands of random
 * cases.</p>
 * <p>Both are reproduced, with the block width of OpenCV's AVX2 dispatch (8 float lanes, two
 * registers per block), the instruction set nearly every x86-64 machine running Indra selects.
 * On a machine where OpenCV picks AVX-512 or plain SSE the block is 32 or 8 pixels and only the
 * scalar remainder of each row changes.</p>
 */
public final class WarpAffine {

    /** Pixels per vector block on AVX2: two registers of eight floats. */
    static final int VECTOR_BLOCK = 16;

    private WarpAffine() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The image warped by a forward map: result pixel {@code (x, y)} samples the source at
     * {@code transform.inverse()} of {@code (x, y)}.
     *
     * @param src       The image
     * @param transform The forward map from source to result coordinates
     * @param width     Columns of the result, at least 1
     * @param height    Rows of the result, at least 1
     * @param border    What a sample outside the source reads
     * @return The warped image
     */
    public static GrayImage warp(GrayImage src, AffineTransform transform, int width,
                                 int height, BorderMode border) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(transform, "transform must not be null");
        Objects.requireNonNull(border, "border must not be null");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("a warped image is at least 1x1, got " + width
                + "x" + height);
        }
        AffineTransform inv = transform.inverse();
        Sampler sampler = new Sampler(src, border);
        float m00 = (float) inv.m00();
        float m01 = (float) inv.m01();
        float m02 = (float) inv.m02();
        float m10 = (float) inv.m10();
        float m11 = (float) inv.m11();
        float m12 = (float) inv.m12();
        byte[] out = new byte[width * height];
        int vectorEnd = width - width % VECTOR_BLOCK;
        for (int y = 0; y < height; y++) {
            int base = y * width;
            float rowX = y * m01 + m02;
            float rowY = y * m11 + m12;
            for (int x = 0; x < vectorEnd; x++) {
                float sx = Math.fma(m00, (float) x, rowX);
                float sy = Math.fma(m10, (float) x, rowY);
                out[base + x] = (byte) sampler.sample(sx, sy);
            }
            float tailY = y * m01;
            float tailYy = y * m11;
            for (int x = vectorEnd; x < width; x++) {
                float sx = Math.fma((float) x, m00, tailY) + m02;
                float sy = Math.fma((float) x, m10, tailYy) + m12;
                out[base + x] = (byte) sampler.sample(sx, sy);
            }
        }
        return GrayImage.adopt(width, height, out);
    }

    /**
     * Bilinear sampling of the source with OpenCV's border handling and fused blends.
     */
    private static final class Sampler {
        private final byte[] pixels;
        private final int cols;
        private final int rows;
        private final boolean replicate;

        Sampler(GrayImage src, BorderMode border) {
            this.pixels = src.raw();
            this.cols = src.width();
            this.rows = src.height();
            this.replicate = border == BorderMode.REPLICATE;
        }

        int sample(float sx, float sy) {
            int ix = (int) Math.floor(sx);
            int iy = (int) Math.floor(sy);
            float fx = sx - ix;
            float fy = sy - iy;
            float p00;
            float p01;
            float p10;
            float p11;
            if (ix >= 0 && iy >= 0 && ix < cols - 1 && iy < rows - 1) {
                int at = iy * cols + ix;
                p00 = pixels[at] & 0xFF;
                p01 = pixels[at + 1] & 0xFF;
                p10 = pixels[at + cols] & 0xFF;
                p11 = pixels[at + cols + 1] & 0xFF;
            } else {
                if (!replicate && (ix < -1 || ix >= cols || iy < -1 || iy >= rows)) {
                    return 0;
                }
                p00 = pixel(ix, iy);
                p01 = pixel(ix + 1, iy);
                p10 = pixel(ix, iy + 1);
                p11 = pixel(ix + 1, iy + 1);
            }
            float v0 = Math.fma(fx, p01 - p00, p00);
            float v1 = Math.fma(fx, p11 - p10, p10);
            return AreaResize.saturate(Math.fma(fy, v1 - v0, v0));
        }

        private int pixel(int x, int y) {
            if (x >= 0 && y >= 0 && x < cols && y < rows) {
                return pixels[y * cols + x] & 0xFF;
            }
            if (!replicate) {
                return 0;
            }
            return pixels[Math.clamp(y, 0, rows - 1) * cols + Math.clamp(x, 0, cols - 1)] & 0xFF;
        }
    }
}
