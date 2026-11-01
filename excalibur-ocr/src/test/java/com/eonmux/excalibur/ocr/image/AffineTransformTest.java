package com.eonmux.excalibur.ocr.image;

import com.eonmux.excalibur.ocr.array.NdArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("An affine transform")
class AffineTransformTest {

    /**
     * Rows of the fixture where glibc's cos or sin is one ulp from the correctly rounded value
     * the port computes: 4 skew-search angles, both signs, and one random angle.
     */
    private static final int GLIBC_MISROUNDED_ROWS = 9;

    @Test
    @DisplayName("matches cv2.getRotationMatrix2D wherever the C library's trig is correctly rounded")
    void rotationMatchesOpenCv() {
        double[] rows = ImagingFixtures.load("rotation.npz").get("rows").doubles();
        int cases = rows.length / 9;
        int trigMisses = 0;
        for (int c = 0; c < cases; c++) {
            int r = c * 9;
            AffineTransform m = AffineTransform.rotation(rows[r], rows[r + 1], rows[r + 2], 1.0);
            double[] got = {m.m00(), m.m01(), m.m02(), m.m10(), m.m11(), m.m12()};
            double[] expected = Arrays.copyOfRange(rows, r + 3, r + 9);
            if (got[0] == expected[0] && got[1] == expected[1]) {
                assertThat(got).as("angle %s", rows[r + 2]).containsExactly(expected);
            } else {
                trigMisses++;
                assertThat(Math.abs(got[0] - expected[0])).isLessThanOrEqualTo(Math.ulp(got[0]));
                assertThat(Math.abs(got[1] - expected[1])).isLessThanOrEqualTo(Math.ulp(got[1]));
            }
        }
        assertThat(cases).isGreaterThan(4000);
        assertThat(trigMisses).isEqualTo(GLIBC_MISROUNDED_ROWS);
    }

    @Test
    @DisplayName("narrows the centre to single precision as cv::Point2f does")
    void centreIsFloat() {
        AffineTransform m = AffineTransform.rotation(0.1, 0.2, 180, 1.0);

        assertThat(Math.abs(m.m02() - 2 * (double) 0.1f)).isLessThan(1e-15);
        assertThat(Math.abs(m.m02() - 0.2)).isGreaterThan(1e-9);
    }

    @Test
    @DisplayName("inverts as cv::invertAffineTransform does, and degenerates to zero")
    void inverse() {
        AffineTransform m = new AffineTransform(2, 0, 4, 0, 4, 8);
        AffineTransform inv = m.inverse();

        assertThat(inv).isEqualTo(new AffineTransform(0.5, -0.0, -2, -0.0, 0.25, -2));
        assertThat(new AffineTransform(1, 2, 3, 2, 4, 6).inverse())
            .isEqualTo(new AffineTransform(0, -0.0, 0, -0.0, 0, 0));
    }

    @Test
    @DisplayName("keeps the signed zero of a zero angle and refuses angles beyond its range")
    void trigEdges() {
        assertThat(ExactTrig.sin(-0.0)).isEqualTo(-0.0);
        assertThat(ExactTrig.cos(0.0)).isEqualTo(1.0);
        assertThat(ExactTrig.sin(Math.PI / 6)).isEqualTo(0.49999999999999994);
        assertThatThrownBy(() -> ExactTrig.sin(1e6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExactTrig.cos(Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
