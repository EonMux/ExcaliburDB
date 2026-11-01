package com.eonmux.excalibur.ocr.layout;

import com.eonmux.excalibur.ocr.array.NdArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.ints;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.map;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.maps;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Line features are computed bit for bit as Indra's line_features computes them")
class OrderFeaturesTest {

    private static Map<String, NdArray> arrays;
    private static Map<String, Object> doc;

    @BeforeAll
    static void load() {
        arrays = LayoutFixtures.syntheticArrays();
        doc = LayoutFixtures.syntheticDoc();
    }

    @Test
    @DisplayName("boxes of every shape with text of every script, and without text")
    void synthetic() {
        List<Map<String, Object>> cases = maps(doc.get("features"));
        for (int k = 0; k < cases.size(); k++) {
            float[][] boxes = LayoutFixtures.boxes(arrays.get("feat" + k + "_boxes"));
            int[] wh = ints(cases.get(k).get("wh"));
            List<String> texts = strings(cases.get(k).get("texts"));

            assertThat(OrderFeatures.of(boxes, wh[0], wh[1], texts)).as("case %d", k)
                .containsExactly(arrays.get("feat" + k + "_out").floats());
            assertThat(OrderFeatures.of(boxes, wh[0], wh[1], null)).as("case %d without text", k)
                .containsExactly(arrays.get("feat" + k + "_out_notext").floats());
        }
        assertThat(OrderFeatures.of(new float[0][], 100, 100, List.of())).isEmpty();
    }

    @Test
    @DisplayName("display length counts wide and fullwidth characters twice, by code point")
    void displayLen() {
        for (Map<String, Object> c : maps(map(doc.get("text")).get("display_len"))) {
            String text = (String) c.get("text");

            assertThat(OrderFeatures.displayLen(text)).as("%s", text)
                .isEqualTo(((Number) c.get("len")).intValue());
        }
    }

    @Test
    @DisplayName("the length feature rounds log1p(n) / 5 as Python does for every length to 4096")
    void lengthFeature() {
        List<?> expected = (List<?>) map(doc.get("text")).get("log1p");
        float[][] box = {{0, 0, 10, 10}};
        for (int n = 1; n < expected.size(); n++) {
            float[] f = OrderFeatures.of(box, 100, 100, List.of("a".repeat(n)));

            assertThat(f[OrderFeatures.N_GEOM]).as("length %d", n)
                .isEqualTo(((Number) expected.get(n)).floatValue());
        }
    }

    @Test
    @DisplayName("a list marker, a number, a hyphen, a sentence end and a capital set their flags")
    void textFlags() {
        float[][] boxes = new float[5][];
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] = new float[] {0, 20 * i, 100, 20 * i + 15};
        }
        float[] f = OrderFeatures.of(boxes, 200, 200,
            List.of("• item", "12) step", "12.", "tumori-", "Ends here。"));
        int at = OrderFeatures.N_GEOM;

        assertThat(f[at + 2]).isEqualTo(1f);
        assertThat(f[OrderFeatures.N_FEATS + at + 2]).isEqualTo(1f);
        assertThat(f[2 * OrderFeatures.N_FEATS + at + 2]).as("a bare number is not an item")
            .isEqualTo(0f);
        assertThat(f[3 * OrderFeatures.N_FEATS + at + 3]).isEqualTo(1f);
        assertThat(f[4 * OrderFeatures.N_FEATS + at + 4]).isEqualTo(1f);
        assertThat(f[4 * OrderFeatures.N_FEATS + at + 5]).isEqualTo(1f);
    }

    @Test
    @DisplayName("malformed arguments are refused with a message naming them")
    void refusals() {
        float[][] one = {{0, 0, 1, 1}};
        assertThatThrownBy(() -> OrderFeatures.of(one, 0, 10, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("page size");
        assertThatThrownBy(() -> OrderFeatures.of(one, 10, 10, List.of()))
            .hasMessageContaining("1 boxes but 0 texts");
        assertThatThrownBy(() -> OrderFeatures.of(one, 10, 10, Arrays.asList((String) null)))
            .hasMessageContaining("text 0 is null");
        assertThatThrownBy(() -> OrderFeatures.of(new float[][] {{0, 0, Float.POSITIVE_INFINITY, 1}},
            10, 10, null)).hasMessageContaining("not finite");
        assertThatThrownBy(() -> OrderFeatures.displayLen(null))
            .isInstanceOf(NullPointerException.class);
    }
}
