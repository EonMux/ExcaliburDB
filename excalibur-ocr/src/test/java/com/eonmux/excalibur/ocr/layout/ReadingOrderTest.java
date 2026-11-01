package com.eonmux.excalibur.ocr.layout;

import com.eonmux.excalibur.ocr.array.NdArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.groups;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.ints;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.maps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Reading orders are decoded as Indra's order_post decodes them")
class ReadingOrderTest {

    private static Map<String, NdArray> arrays;
    private static Map<String, Object> doc;

    @BeforeAll
    static void load() {
        arrays = LayoutFixtures.syntheticArrays();
        doc = LayoutFixtures.syntheticDoc();
    }

    @Test
    @DisplayName("pairwise logits decode to Indra's order, ties and antisymmetric ones included")
    void decode() {
        List<Map<String, Object>> cases = maps(doc.get("decode"));
        for (int k = 0; k < cases.size(); k++) {
            NdArray a = arrays.get("dec" + k + "_a");
            int n = a.shape()[0];

            assertThat(ReadingOrder.decode(a.floats(), n)).as("case %d", k)
                .containsExactly(ints(cases.get(k).get("order")));
            assertThat(ReadingOrder.decode(a.floats(), n, 1)).as("case %d, one sweep", k)
                .containsExactly(ints(cases.get(k).get("order_1sweep")));
        }
    }

    @Test
    @DisplayName("same-block clustering gives Indra's groups, ties included")
    void cluster() {
        List<Map<String, Object>> cases = maps(doc.get("cluster"));
        for (int k = 0; k < cases.size(); k++) {
            NdArray s = arrays.get("clu" + k);

            assertThat(Clustering.cluster(s.floats(), s.shape()[0])).as("case %d", k)
                .containsExactlyElementsOf(groups(cases.get(k).get("clusters")));
        }
    }

    @Test
    @DisplayName("blocks then lines follow Indra's order, grid blocks row by row, with and without classes")
    void decodeBlocks() {
        List<Map<String, Object>> cases = maps(doc.get("blocks_order"));
        for (int k = 0; k < cases.size(); k++) {
            float[][] boxes = LayoutFixtures.boxes(arrays.get("bo" + k + "_boxes"));
            float[] a = arrays.get("bo" + k + "_a").floats();
            float[] same = arrays.get("bo" + k + "_same").floats();
            float[] cls = arrays.get("bo" + k + "_cls").floats();

            assertThat(ReadingOrder.decodeBlocks(a, same, boxes, cls, boxes.length)).as("case %d", k)
                .containsExactly(ints(cases.get(k).get("order")));
            assertThat(ReadingOrder.decodeBlocks(a, same, boxes, null, boxes.length))
                .as("case %d without classes", k)
                .containsExactly(ints(cases.get(k).get("order_nocls")));
        }
    }

    @Test
    @DisplayName("rows, XY-cut and XY-cut blocks match Indra on grids, ties, duplicates and crossed boxes")
    void geometry() {
        List<Map<String, Object>> cases = maps(doc.get("xycut"));
        for (int k = 0; k < cases.size(); k++) {
            float[][] boxes = LayoutFixtures.boxes(arrays.get("xy" + k));
            Map<String, Object> c = cases.get(k);

            assertThat(ReadingOrder.topDown(boxes)).as("case %d rows", k)
                .containsExactly(ints(c.get("top_down")));
            assertThat(ReadingOrder.xycut(boxes)).as("case %d xycut", k)
                .containsExactly(ints(c.get("xycut")));
            assertThat(ReadingOrder.xycutBlocks(boxes)).as("case %d blocks", k)
                .containsExactlyElementsOf(groups(c.get("blocks")));
            assertThat(ReadingOrder.xycutBlocks(boxes, 3.0)).as("case %d blocks, gap 3", k)
                .containsExactlyElementsOf(groups(c.get("blocks_gap3")));
            assertThat(ReadingOrder.xycutBlocks(boxes, 0.0)).as("case %d blocks, gap 0", k)
                .containsExactlyElementsOf(groups(c.get("blocks_gap0")));
        }
    }

    @Test
    @DisplayName("no line or one line is its own order")
    void trivial() {
        assertThat(ReadingOrder.decode(new float[0], 0)).isEmpty();
        assertThat(ReadingOrder.decode(new float[] {3f}, 1)).containsExactly(0);
        assertThat(ReadingOrder.decodeBlocks(new float[] {1f}, new float[] {1f},
            new float[][] {{0, 0, 1, 1}}, null, 1)).containsExactly(0);
        assertThat(Clustering.cluster(new float[0], 0)).isEmpty();
        assertThat(ReadingOrder.xycut(new float[0][])).isEmpty();
    }

    @Test
    @DisplayName("malformed arguments are refused with a message naming them")
    void refusals() {
        float[][] one = {{0, 0, 1, 1}};
        assertThatThrownBy(() -> ReadingOrder.decode(new float[3], 2))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a must be [2, 2]");
        assertThatThrownBy(() -> ReadingOrder.decode(new float[4], 2, -1))
            .hasMessageContaining("sweeps");
        assertThatThrownBy(() -> ReadingOrder.decode(new float[0], -1))
            .hasMessageContaining("negative");
        assertThatThrownBy(() -> ReadingOrder.decode(null, 1))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReadingOrder.decodeBlocks(new float[1], new float[1], one,
            new float[3], 1)).hasMessageContaining("cls must be [1, 10]");
        assertThatThrownBy(() -> ReadingOrder.decodeBlocks(new float[4], new float[4], one,
            null, 2)).hasMessageContaining("2 lines but 1 boxes");
        assertThatThrownBy(() -> ReadingOrder.xycut(new float[][] {{0, 0, 1}}))
            .hasMessageContaining("box 0 has 3 values");
        assertThatThrownBy(() -> ReadingOrder.xycut(new float[][] {{0, Float.NaN, 1, 1}}))
            .hasMessageContaining("not finite");
        assertThatThrownBy(() -> ReadingOrder.xycut(new float[][] {null}))
            .hasMessageContaining("box 0 is null");
        assertThatThrownBy(() -> ReadingOrder.xycutBlocks(one, Double.NaN))
            .hasMessageContaining("minGap");
    }
}
