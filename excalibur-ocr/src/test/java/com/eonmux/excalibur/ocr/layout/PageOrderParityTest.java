package com.eonmux.excalibur.ocr.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.util.List;
import java.util.Map;

import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.groups;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.ints;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.strings;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A page read by Indra's pipeline is ordered exactly as Indra ordered it")
class PageOrderParityTest {

    static final List<String> PAGES = LayoutFixtures.PAGES;

    @ParameterizedTest(name = "{0}")
    @FieldSource("PAGES")
    @DisplayName("the order model's features are bit for bit the ones Indra fed it")
    void features(String name) {
        LayoutFixtures.Page page = LayoutFixtures.page(name);
        List<Map<String, Object>> runs = page.runs();
        for (int r = 0; r < runs.size(); r++) {
            int[] wh = ints(runs.get(r).get("wh"));
            float[] feats = OrderFeatures.of(page.boxes(r), wh[0], wh[1],
                strings(runs.get(r).get("texts")));

            assertThat(feats).as("run %d", r).containsExactly(page.array("r" + r + "_feats"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("PAGES")
    @DisplayName("blocks, their order logits, their order and the lines in them are Indra's")
    void blockOrder(String name) {
        LayoutFixtures.Page page = LayoutFixtures.page(name);
        List<Map<String, Object>> runs = page.runs();
        for (int r = 0; r < runs.size(); r++) {
            float[][] boxes = page.boxes(r);
            int n = boxes.length;
            float[] a = page.array("r" + r + "_a");
            float[] same = page.array("r" + r + "_same");
            float[] cls = page.array("r" + r + "_cls");

            float[] ab = ReadingOrder.blockOrderLogits(a, n, Clustering.cluster(same, n));
            assertThat(ab).as("run %d block logits", r).containsExactly(page.array("r" + r + "_ab0"));
            int m = (int) Math.round(Math.sqrt(ab.length));
            assertThat(ReadingOrder.decode(ab, m)).as("run %d block order", r)
                .containsExactly(ints(((List<?>) runs.get(r).get("inner_orders")).getFirst()));
            assertThat(ReadingOrder.decodeBlocks(a, same, boxes, cls, n)).as("run %d order", r)
                .containsExactly(ints(runs.get(r).get("order")));
        }
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("PAGES")
    @DisplayName("XY-cut orders the page's lines and blocks as Indra's does")
    void xycut(String name) {
        LayoutFixtures.Page page = LayoutFixtures.page(name);
        List<Map<String, Object>> runs = page.runs();
        for (int r = 0; r < runs.size(); r++) {
            float[][] boxes = page.boxes(r);

            assertThat(ReadingOrder.xycut(boxes)).containsExactly(
                ints(runs.get(r).get("order_xycut")));
            assertThat(ReadingOrder.xycutBlocks(boxes))
                .containsExactlyElementsOf(groups(runs.get(r).get("xycut_blocks")));
        }
    }
}
