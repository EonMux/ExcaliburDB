package com.eonmux.excalibur.ocr.geometry;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Page geometry")
class GeometryTest {

    /** The skew fixtures' base pages, in the generator's order. */
    private static final String[] BASES = {"text", "blank", "little", "disk", "band"};

    private static Map<String, NdArray> f;

    @BeforeAll
    static void load() {
        f = Fixtures.npz("imaging/geometry.npz");
    }

    @Test
    @DisplayName("estimates Indra's skew angle on tilted, blank, sparse and tied pages")
    void estimateSkew() {
        int k = 0;
        for (; f.containsKey("skew" + k + "_case"); k++) {
            double[] c = f.get("skew" + k + "_case").doubles();
            GrayImage base = Fixtures.image(f.get("base_" + BASES[(int) c[0]]));
            GrayImage page = c[1] == 0 ? base : PageRotation.rotate(base, c[1]);

            assertThat(SkewEstimator.estimate(page)).as("case %d at side 800", k)
                .isEqualTo(f.get("skew" + k + "_angle").doubles()[0]);
            assertThat(SkewEstimator.estimate(page, 300)).as("case %d at side 300", k)
                .isEqualTo(f.get("skew" + k + "_side300").doubles()[0]);
        }
        assertThat(k).isEqualTo(10);
    }

    @Test
    @DisplayName("builds ink thumbnails and scores row sharpness as Indra does")
    void thumbsAndSharpness() {
        GrayImage tilted = PageRotation.rotate(Fixtures.image(f.get("base_text")), 1.7);
        GrayImage coarse = SkewEstimator.inkThumb(tilted, 400);
        double[] angles = f.get("sharp_angles").doubles();
        double[] scores = f.get("sharp_scores").doubles();

        assertThat(SkewEstimator.inkThumb(tilted, 800)).isEqualTo(Fixtures.image(f.get("thumb_fine")));
        assertThat(coarse).isEqualTo(Fixtures.image(f.get("thumb_coarse")));
        for (int i = 0; i < angles.length; i++) {
            assertThat(SkewEstimator.rowSharpness(coarse, angles[i])).as("angle %s", angles[i])
                .isEqualTo(scores[i]);
        }
    }

    @Test
    @DisplayName("previews and builds the orientation input as Indra does")
    void thumbnails() {
        GrayImage page = Fixtures.image(f.get("prev_page"));

        assertThat(Thumbnails.preview(page, 100)).isEqualTo(Fixtures.image(f.get("prev_100")));
        assertThat(Thumbnails.preview(page, 77)).isEqualTo(Fixtures.image(f.get("prev_77")));
        assertThat(Thumbnails.preview(page, 400)).isSameAs(page);
        assertThat(Thumbnails.preview(page)).isSameAs(page);
        assertInk(Thumbnails.orientInput(page), f.get("orient_768"));
        assertInk(Thumbnails.orientInput(page, 64), f.get("orient_64"));
        assertInk(Thumbnails.orientInput(Fixtures.image(f.get("orient_wide_page")), 45),
            f.get("orient_wide"));
    }

    @Test
    @DisplayName("rotates pages as Indra's rotate_page does")
    void rotatePage() {
        GrayImage page = Fixtures.image(f.get("prev_page"));
        for (int k = 0; f.containsKey("rot" + k + "_angle"); k++) {
            double angle = f.get("rot" + k + "_angle").doubles()[0];

            assertThat(PageRotation.rotate(page, angle)).as("angle %s", angle)
                .isEqualTo(Fixtures.image(f.get("rot" + k + "_out")));
        }
    }

    @Test
    @DisplayName("maps boxes back as Indra's unrotate_box does, rounding to a tenth half to even")
    void unrotateBox() {
        double[] rows = f.get("unrot_rows").doubles();
        int cases = rows.length / 11;
        for (int c = 0; c < cases; c++) {
            int r = c * 11;
            double[] box = {rows[r], rows[r + 1], rows[r + 2], rows[r + 3]};
            double[] got = PageRotation.unrotateBox(box, rows[r + 4], (int) rows[r + 5],
                (int) rows[r + 6]);
            for (int i = 0; i < 4; i++) {
                assertThat(got[i] == rows[r + 7 + i]).as("case %d value %d: %s vs %s", c, i,
                    got[i], rows[r + 7 + i]).isTrue();
            }
        }
        assertThat(cases).isGreaterThan(400);
    }

    @Test
    @DisplayName("rejects arguments it cannot use")
    void validates() {
        GrayImage page = GrayImage.filled(4, 4, 255);

        assertThatThrownBy(() -> SkewEstimator.estimate(page, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageRotation.unrotateBox(new double[3], 0, 4, 4))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Thumbnails.preview(page, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Thumbnails.orientInput(null))
            .isInstanceOf(NullPointerException.class);
        assertThat(SkewEstimator.estimate(page)).isZero();
    }

    private static void assertInk(InkMap got, NdArray expected) {
        assertThat(new int[] {got.height(), got.width()}).containsExactly(expected.shape());
        assertThat(got.values()).containsExactly(expected.floats());
    }
}
