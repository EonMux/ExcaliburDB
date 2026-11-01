// Reproduces the documented behaviour of OpenCV 5.0's cv::resize on 8-bit single-channel images.
package com.eonmux.excalibur.ocr.image;

import java.util.Objects;

/**
 * Resizes a gray image exactly as OpenCV 5.0's {@code cv2.resize} does, pixel for pixel.
 *
 * <p>Indra OCR shrinks and enlarges every page and line crop with {@code cv2.resize}, and its
 * models were trained on those results, so the port must produce the same bytes, not merely
 * similar ones. OpenCV picks one of four algorithms from the scale factors, and this class makes
 * the same choice:</p>
 * <ul>
 *   <li>{@link Interpolation#AREA} shrinking both sides by whole factors: block means
 *       ({@link AreaResize#integerFactor}), with a 2x2 block rounded half up and any other block
 *       rounded half to even, as OpenCV's vector and scalar code do;</li>
 *   <li>{@link Interpolation#AREA} shrinking both sides otherwise: weighted area sums in single
 *       precision ({@link AreaResize#fractional});</li>
 *   <li>{@link Interpolation#AREA} enlarging either side, and {@link Interpolation#LINEAR}:
 *       11-bit fixed-point bilinear ({@link LinearResize}), with grid-snapped weights for area;</li>
 *   <li>{@link Interpolation#LINEAR} halving both sides exactly: the 2x2 block mean, which
 *       OpenCV substitutes because the two agree.</li>
 * </ul>
 * <p>OpenCV's scale arithmetic is in {@code double} and its weights in {@code float}; both are
 * reproduced in the same precision, so the rounding is the same.</p>
 */
public final class Resize {

    /** OpenCV's {@code DBL_EPSILON}, the tolerance under which a scale counts as a whole number. */
    private static final double DBL_EPSILON = Math.ulp(1.0);

    private Resize() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The image resized to the given size; {@code cv2.resize(src, (width, height),
     * interpolation=...)}.
     *
     * @param src           The image
     * @param width         Columns of the result, at least 1
     * @param height        Rows of the result, at least 1
     * @param interpolation How pixels are computed
     * @return The resized image; {@code src} itself when the size is unchanged, which is what
     *         OpenCV's copy holds
     * @throws IllegalArgumentException if a dimension is not positive
     */
    public static GrayImage resize(GrayImage src, int width, int height,
                                   Interpolation interpolation) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(interpolation, "interpolation must not be null");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("a resized image is at least 1x1, got " + width
                + "x" + height);
        }
        if (width == src.width() && height == src.height()) {
            return src;
        }
        double invScaleX = (double) width / src.width();
        double invScaleY = (double) height / src.height();
        double scaleX = 1.0 / invScaleX;
        double scaleY = 1.0 / invScaleY;
        int wholeX = (int) Math.rint(scaleX);
        int wholeY = (int) Math.rint(scaleY);
        boolean wholeFactors = Math.abs(scaleX - wholeX) < DBL_EPSILON
            && Math.abs(scaleY - wholeY) < DBL_EPSILON;

        Interpolation mode = interpolation;
        if (mode == Interpolation.LINEAR && wholeFactors && wholeX == 2 && wholeY == 2) {
            mode = Interpolation.AREA;
        }
        if (mode == Interpolation.AREA && scaleX >= 1 && scaleY >= 1) {
            return wholeFactors
                ? AreaResize.integerFactor(src, width, height, wholeX, wholeY)
                : AreaResize.fractional(src, width, height, scaleX, scaleY);
        }
        return LinearResize.resize(src, width, height, invScaleX, invScaleY,
            mode == Interpolation.AREA);
    }
}
