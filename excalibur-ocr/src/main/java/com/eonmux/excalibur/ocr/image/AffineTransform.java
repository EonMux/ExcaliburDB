// Reproduces the documented behaviour of OpenCV 5.0's cv::getRotationMatrix2D
// (geometry/src/geometry.cpp) and of the matrix inversion in cv::warpAffine.
package com.eonmux.excalibur.ocr.image;

/**
 * A 2x3 affine matrix mapping {@code (x, y)} to {@code (m00 x + m01 y + m02, m10 x + m11 y +
 * m12)}, in double precision as OpenCV holds it.
 *
 * @param m00 Row 0, column 0
 * @param m01 Row 0, column 1
 * @param m02 Row 0, column 2: the x translation
 * @param m10 Row 1, column 0
 * @param m11 Row 1, column 1
 * @param m12 Row 1, column 2: the y translation
 */
public record AffineTransform(double m00, double m01, double m02, double m10, double m11,
                              double m12) {

    private static final double DEGREES_TO_RADIANS = Math.PI / 180;

    /**
     * The rotation about a centre by an angle, counter-clockwise on screen for a positive angle,
     * with a uniform scale; OpenCV's {@code getRotationMatrix2D}.
     *
     * <p>The centre is narrowed to {@code float}, as OpenCV's {@code Point2f} argument narrows
     * it. The cosine and sine are correctly rounded, the closest portable match to the C
     * library's; see {@link ExactTrig}.</p>
     *
     * @param centerX      The centre's x
     * @param centerY      The centre's y
     * @param angleDegrees The angle in degrees, at most about 3,600 in magnitude
     * @param scale        The scale factor
     * @return The matrix
     * @throws IllegalArgumentException if the angle is not finite or is out of range
     */
    public static AffineTransform rotation(double centerX, double centerY, double angleDegrees,
                                           double scale) {
        double cx = (float) centerX;
        double cy = (float) centerY;
        double angle = angleDegrees * DEGREES_TO_RADIANS;
        double alpha = ExactTrig.cos(angle) * scale;
        double beta = ExactTrig.sin(angle) * scale;
        return new AffineTransform(alpha, beta, (1 - alpha) * cx - beta * cy,
            -beta, alpha, beta * cx + (1 - alpha) * cy);
    }

    /**
     * The inverse map, computed as {@code cv::warpAffine} computes it before sampling; a
     * singular matrix inverts to zeros there, and here.
     *
     * @return The inverse
     */
    public AffineTransform inverse() {
        double d = m00 * m11 - m01 * m10;
        d = d != 0 ? 1.0 / d : 0;
        double a00 = m11 * d;
        double a01 = m01 * -d;
        double a10 = m10 * -d;
        double a11 = m00 * d;
        double b0 = -a00 * m02 - a01 * m12;
        double b1 = -a10 * m02 - a11 * m12;
        return new AffineTransform(a00, a01, b0, a10, a11, b1);
    }
}
