package com.eonmux.excalibur.ocr.layout;

import com.eonmux.excalibur.ocr.array.NdArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.boxes;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.groups;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.ints;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.map;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.maps;
import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.strings;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page markdown against Indra's {@code layout_post} ({@code tools/fixtures/gen_layout.py}):
 * the synthetic pages' markdown with page furniture kept or dropped and with or without
 * pictures, and hand-made blocks of every class, tables, wrapped cells, hyphenated and CJK
 * lines. Variants with the table heads are left out: Indra 1.1.2 reads pages without them.
 */
@DisplayName("Page markdown, as Indra's layout_post writes it")
class PageMarkdownTest {

    @Nested
    @DisplayName("On the synthetic pages")
    class Pages {

        @Test
        @DisplayName("writes each page's markdown in every variant without table heads")
        void pages() {
            int compared = 0;
            for (String name : LayoutFixtures.PAGES) {
                LayoutFixtures.Page page = LayoutFixtures.page(name);
                List<Map<String, Object>> runs = page.runs();
                int last = runs.size() - 1;
                Map<String, Object> run = runs.get(last);
                float[][] b = page.boxes(last);
                List<String> texts = strings(run.get("texts"));
                int[] order = ints(run.get("order"));
                Map<Integer, List<PageMarkdown.Cell>> cells = cells(map(page.doc().get("cells")),
                    page.arrays().get("cell_boxes"));
                NdArray pictures = page.arrays().get("pictures");
                for (Map<String, Object> v : maps(page.doc().get("markdown"))) {
                    if ((Boolean) v.get("tab")) {
                        continue;
                    }
                    boolean keep = (Boolean) v.get("keep_furniture");
                    boolean pics = (Boolean) v.get("pictures");
                    String got = PageMarkdown.of(b, texts, order, page.array("r" + last + "_cls"),
                        page.array("r" + last + "_same"), keep, cells,
                        pics ? boxes(pictures) : null);
                    assertThat(got).as("%s keep=%s pictures=%s", name, keep, pics)
                        .isEqualTo(v.get("markdown"));
                    compared++;
                }
            }
            assertThat(compared).isGreaterThanOrEqualTo(LayoutFixtures.PAGES.size() * 2);
        }
    }

    @Nested
    @DisplayName("On hand-made blocks")
    class Synthetic {

        private final Map<String, NdArray> arrays = LayoutFixtures.syntheticArrays();
        private final Map<String, Object> doc = LayoutFixtures.syntheticDoc();
        private final Map<String, Object> md = map(doc.get("markdown"));
        private final List<String> texts = strings(md.get("texts"));
        private final float[][] b = boxes(arrays.get("md_boxes"));
        private final float[] cls = arrays.get("md_cls").floats();
        private final float[] same = arrays.get("md_same").floats();

        private int[] inOrder() {
            int[] order = new int[texts.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            return order;
        }

        @Test
        @DisplayName("writes every block class, with and without furniture, pictures and cells")
        void variants() {
            Map<Integer, List<PageMarkdown.Cell>> cells = cells(map(md.get("cells")),
                arrays.get("md_cell_boxes"));
            float[][] pictures = boxes(arrays.get("md_pictures"));
            int compared = 0;
            for (Map<String, Object> v : maps(md.get("variants"))) {
                if ((Boolean) v.get("tab")) {
                    continue;
                }
                boolean keep = (Boolean) v.get("keep_furniture");
                boolean pics = (Boolean) v.get("pictures");
                boolean useCells = (Boolean) v.get("cells");
                String got = PageMarkdown.of(b, texts, inOrder(), cls, same, keep,
                    useCells ? cells : null, pics ? pictures : null);
                assertThat(got).as("keep=%s pictures=%s cells=%s", keep, pics, useCells)
                    .isEqualTo(v.get("markdown"));
                compared++;
            }
            assertThat(compared).isEqualTo(8);
        }

        @Test
        @DisplayName("reads a line with an empty cell list as the line itself")
        void emptyCells() {
            Map<Integer, List<PageMarkdown.Cell>> cells = new HashMap<>(cells(
                map(md.get("cells")), arrays.get("md_cell_boxes")));
            String without = PageMarkdown.of(b, texts, inOrder(), cls, same, false, cells, null);
            cells.put(texts.indexOf("Bob"), List.of());
            assertThat(PageMarkdown.of(b, texts, inOrder(), cls, same, false, cells, null))
                .isEqualTo(without);
        }

        @Test
        @DisplayName("writes lines in a shuffled order, and nothing for an empty page")
        void shuffledAndEmpty() {
            int[] shuffled = toInts(arrays.get("md_order_shuffled").longs());
            assertThat(PageMarkdown.of(b, texts, shuffled, cls, same, false, null, null))
                .isEqualTo(md.get("shuffled_markdown"));
            assertThat(PageMarkdown.of(new float[0][], List.of(), new int[0], new float[0],
                new float[0], false, null, null)).isEqualTo(md.get("empty"));
        }

        @Test
        @DisplayName("splits the reading order into blocks where lines are not the same block")
        void splitBlocks() {
            int n = texts.size();
            assertThat(PageMarkdown.splitBlocks(inOrder(), same, n))
                .isEqualTo(lists(groups(md.get("split"))));
            int[] shuffled = toInts(arrays.get("md_order_shuffled").longs());
            assertThat(PageMarkdown.splitBlocks(shuffled, same, n))
                .isEqualTo(lists(groups(md.get("split_shuffled"))));
        }

        @Test
        @DisplayName("writes tables: title rows, wrapped cells, missing cells and spans")
        void tables() {
            List<Map<String, Object>> cases = maps(doc.get("tables"));
            for (int k = 0; k < cases.size(); k++) {
                Map<String, Object> c = cases.get(k);
                float[][] tb = boxes(arrays.get("tab" + k + "_boxes"));
                List<List<Integer>> rows = PageMarkdown.rows(tb);
                assertThat(rows).as("table %d rows", k).isEqualTo(lists(groups(c.get("rows"))));
                assertThat(PageMarkdown.mergeWrapped(rows, tb)).as("table %d merged", k)
                    .isEqualTo(lists(groups(c.get("merged"))));
                assertThat(PageMarkdown.tableHtml(tb, strings(c.get("texts"))))
                    .as("table %d html", k).isEqualTo(c.get("html"));
            }
        }

        @Test
        @DisplayName("joins lines across hyphens and between CJK characters")
        void joinLines() {
            Map<String, Object> text = map(doc.get("text"));
            for (Map<String, Object> c : maps(text.get("join_lines"))) {
                assertThat(PageMarkdown.joinLines(strings(c.get("lines"))))
                    .as("%s", c.get("lines")).isEqualTo(c.get("out"));
            }
            for (Map<String, Object> c : maps(text.get("cjk"))) {
                int cp = ((Number) c.get("cp")).intValue();
                assertThat(PageMarkdown.cjk(cp)).as("U+%04X", cp).isEqualTo(c.get("cjk"));
            }
        }
    }

    /** The cells of lines that hold several, as the fixture lists them: line, box and text. */
    private static Map<Integer, List<PageMarkdown.Cell>> cells(Map<String, Object> fixture,
                                                               NdArray cellBoxes) {
        int[] line = ints(fixture.get("line"));
        List<String> texts = strings(fixture.get("texts"));
        float[][] b = line.length == 0 ? new float[0][] : boxes(cellBoxes);
        Map<Integer, List<PageMarkdown.Cell>> out = new HashMap<>();
        for (int j = 0; j < line.length; j++) {
            out.computeIfAbsent(line[j], i -> new ArrayList<>())
                .add(new PageMarkdown.Cell(b[j], texts.get(j)));
        }
        return out;
    }

    private static List<List<Integer>> lists(List<int[]> groups) {
        return groups.stream()
            .map(g -> Arrays.stream(g).boxed().toList())
            .toList();
    }

    private static int[] toInts(long[] values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Math.toIntExact(values[i]);
        }
        return out;
    }
}
