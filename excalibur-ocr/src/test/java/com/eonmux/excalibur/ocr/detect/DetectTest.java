package com.eonmux.excalibur.ocr.detect;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.image.Box;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Line detection pre- and post-processing")
class DetectTest {

    private static Map<String, NdArray> f;

    @BeforeAll
    static void load() {
        f = Fixtures.npz("imaging/detect.npz");
    }

    @Test
    @DisplayName("builds Indra's detector input exactly, rounding sizes half to even")
    void pageToInput() {
        int k = 0;
        for (; f.containsKey("pi" + k + "_page"); k++) {
            GrayImage page = Fixtures.image(f.get("pi" + k + "_page"));
            int longSide = (int) f.get("pi" + k + "_long").scalarLong();
            NdArray ink = f.get("pi" + k + "_ink");
            DetectorInput input = DetectorInput.of(page, longSide);

            assertThat(input.scale()).as("case %d scale", k)
                .isEqualTo(f.get("pi" + k + "_scale").doubles()[0]);
            assertThat(new int[] {input.ink().height(), input.ink().width()})
                .as("case %d shape", k).containsExactly(ink.shape());
            assertThat(input.ink().values()).as("case %d ink", k).containsExactly(ink.floats());
        }
        assertThat(k).isGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("rounds a height of exactly 4.5 down to 4, as Python's round does")
    void halfToEven() {
        GrayImage page = Fixtures.image(f.get("pi10_page"));
        float[] ink = DetectorInput.of(page, 45).ink().values();

        assertThat(page.height() * (45.0 / page.width())).isEqualTo(4.5);
        assertThat(ink).hasSize(32 * 64);
        for (int x = 0; x < 45; x++) {
            assertThat(ink[3 * 64 + x]).as("row 3, column %d", x).isPositive();
            assertThat(ink[4 * 64 + x]).as("row 4, column %d", x).isZero();
        }
    }

    @Test
    @DisplayName("decodes line boxes as Indra's decode does, with defaults and with growth")
    void decode() {
        int k = 0;
        for (; f.containsKey("dec" + k + "_out"); k++) {
            NdArray out = f.get("dec" + k + "_out");
            List<ScoredBox> boxes = LineDecoder.decode(out.floats(), out.shape()[1],
                out.shape()[2]);
            assertMatches(boxes, f.get("dec" + k + "_boxes"), "case " + k);
        }
        assertThat(k).isEqualTo(5);
        NdArray out = f.get("decg_out");
        List<ScoredBox> grown = LineDecoder.decode(out.floats(), out.shape()[1], out.shape()[2],
            0.3, 4, 0.1, 0.05);
        assertMatches(grown, f.get("decg_boxes"), "grown");
    }

    @Test
    @DisplayName("finds picture regions as Indra's picture_boxes does, clipped to the page")
    void pictureBoxes() {
        int k = 0;
        for (; f.containsKey("pic" + k + "_logit"); k++) {
            NdArray logit = f.get("pic" + k + "_logit");
            double[] params = f.get("pic" + k + "_params").doubles();
            List<Box> boxes = PictureRegions.find(logit.floats(), logit.shape()[0],
                logit.shape()[1], params[0], (int) params[2], (int) params[1]);
            float[] expected = f.get("pic" + k + "_boxes").floats();

            assertThat(boxes).as("case %d count", k).hasSize(expected.length / 4);
            for (int i = 0; i < boxes.size(); i++) {
                Box b = boxes.get(i);
                assertThat(new float[] {b.x0(), b.y0(), b.x1(), b.y1()}).as("case %d box %d", k, i)
                    .containsExactly(java.util.Arrays.copyOfRange(expected, i * 4, i * 4 + 4));
            }
        }
        assertThat(k).isEqualTo(3);
    }

    @Test
    @DisplayName("rejects malformed detector outputs and inputs")
    void validates() {
        assertThatThrownBy(() -> LineDecoder.decode(new float[5], 1, 2))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineDecoder.decode(new float[0], 0, 2))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PictureRegions.find(new float[3], 2, 2, 1.0, 4, 4))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PictureRegions.find(new float[4], 2, 2, 0.0, 4, 4))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DetectorInput.of(GrayImage.filled(2, 2, 0), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoredBox(null, 1f)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DetectorInput(null, 1.0))
            .isInstanceOf(NullPointerException.class);
        assertThat(LineDecoder.decode(new float[] {-9f, 0f, 0f}, 1, 1)).isEmpty();
        assertThat(DetectorInput.of(GrayImage.filled(1, 1, 255), 1).ink())
            .isEqualTo(InkMap.of(32, 32, new float[32 * 32]));
    }

    private static void assertMatches(List<ScoredBox> boxes, NdArray expected, String what) {
        float[] e = expected.floats();
        assertThat(boxes).as(what + " count").hasSize(e.length / 5);
        for (int i = 0; i < boxes.size(); i++) {
            ScoredBox s = boxes.get(i);
            float[] got = {s.box().x0(), s.box().y0(), s.box().x1(), s.box().y1(), s.score()};
            assertThat(got).as("%s line %d", what, i)
                .containsExactly(java.util.Arrays.copyOfRange(e, i * 5, i * 5 + 5));
        }
    }
}
