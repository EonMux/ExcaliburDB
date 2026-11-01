package com.eonmux.excalibur.ocr.pdf;

import com.eonmux.excalibur.ocr.image.Box;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("The document layer helpers")
class DocumentLayerTest {

    private static final Map<String, Object> FIXTURE = TextLayerFixtures.document();

    @TestFactory
    @DisplayName("place text-layer characters in detected lines as Indra's assign_runs does")
    Stream<DynamicTest> assignRunsAsIndra() {
        Map<String, Object> pages = TextLayerFixtures.expected();
        return Json.array(FIXTURE.get("assign")).stream().map(Json::object).map(c ->
            DynamicTest.dynamicTest((String) c.get("page"), () -> {
                List<Object> reads = Json.array(
                    Json.object(pages.get((String) c.get("page"))).get("reads"));
                List<TextLine> runs = TextLayerFixtures.lines(
                    Json.object(Json.object(reads.getLast()).get("layer")).get("lines"));
                List<Box> boxes = boxes(c.get("boxes"));
                double k = Json.number(c.get("k"));

                DocumentLayer.Assignment got = DocumentLayer.assignRuns(runs, boxes, k);

                assertThat(got.lost()).isEqualTo(Json.integer(c.get("lost")));
                compareTokens(Json.array(c.get("tokens")), got.lines());
                assertThat(DocumentLayer.assignRuns(runs, List.of(), k).lost())
                    .isEqualTo(Json.integer(Json.array(c.get("none")).getFirst()));
                assertThat(DocumentLayer.assignRuns(runs, List.of(), k).lines()).isEmpty();
                List<Box> rest = boxes.size() > 1 ? boxes.subList(1, boxes.size()) : boxes;
                List<Object> covered = Json.array(c.get("covered"));
                for (int i = 0; i < covered.size(); i++) {
                    assertThat(DocumentLayer.covered(boxes.get(i), rest)).as("covered " + i)
                        .isEqualTo(covered.get(i));
                }
            }));
    }

    @Test
    @DisplayName("measure scripts and agreement as Indra does")
    void scriptHelpersAsIndra() {
        for (Object o : Json.array(FIXTURE.get("latin_share"))) {
            List<Object> c = Json.array(o);
            assertThat(DocumentLayer.latinShare((String) c.get(0))).as((String) c.get(0))
                .isEqualTo(Json.number(c.get(1)));
        }
        for (Object o : Json.array(FIXTURE.get("right_to_left"))) {
            List<Object> c = Json.array(o);
            assertThat(DocumentLayer.rightToLeft(tokens((String) c.get(0))))
                .as((String) c.get(0)).isEqualTo(c.get(1));
        }
        for (Object o : Json.array(FIXTURE.get("layer_agrees"))) {
            List<Object> c = Json.array(o);
            List<Boolean> agree = Json.array(c.get(0)).stream().map(Boolean.class::cast).toList();
            assertThat(DocumentLayer.layerAgrees(agree)).as(agree.toString())
                .isEqualTo(c.get(1));
        }
    }

    @Test
    @DisplayName("hold Indra's constants")
    void constants() {
        Map<String, Object> c = Json.object(FIXTURE.get("constants"));
        assertThat(DocumentLayer.CHECK_DPI).isEqualTo(Json.number(c.get("CHECK_DPI")));
        assertThat(DocumentLayer.MAX_PAGE_PIXELS).isEqualTo(Json.number(c.get("MAX_PAGE_PIXELS")));
        assertThat(DocumentLayer.READABLE_MIN).isEqualTo(Json.number(c.get("READABLE_MIN")));
        assertThat(DocumentLayer.AGREE_LINES).isEqualTo(Json.integer(c.get("AGREE_LINES")));
        assertThat(DocumentLayer.AGREE_SHARE).isEqualTo(Json.number(c.get("AGREE_SHARE")));
        assertThat(DocumentLayer.LATIN_CODES).isEqualTo(Json.number(c.get("LATIN_CODES")));
        assertThat(DocumentLayer.NOT_THE_PAGE).isEqualTo(c.get("NOT_THE_PAGE"));
    }

    @Test
    @DisplayName("lower the render scale of a page too large for the pixel budget")
    void pageScale() {
        assertThat(DocumentLayer.pageScale(612, 792, 200)).isEqualTo(200 / 72.0);
        assertThat(DocumentLayer.pageScale(10_000, 10_000, 200)).isCloseTo(1.0, within(1e-12));
        assertThat(DocumentLayer.pageScale(0, 0, 72)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("order a right-to-left line's runs from the right")
    void rightToLeftOrder() {
        PageBox left = new PageBox(10, 10, 20, 20);
        PageBox right = new PageBox(40, 10, 50, 20);
        TextLine first = new TextLine(left, "אב", List.of(
            new RunChar('א', left), new RunChar('ב', left)), 0, null);
        TextLine second = new TextLine(right, "גד", List.of(
            new RunChar('ג', right), new RunChar(' ', right), new RunChar('ד', right)), 0, null);
        List<Box> line = List.of(new Box(0, 0, 100, 100));

        List<DocumentLayer.Token> tokens =
            DocumentLayer.assignRuns(List.of(first, second), line, 1.0).lines().getFirst();

        assertThat(tokens.stream().map(t -> Character.toString(t.codePoint())).toList())
            .containsExactly("ג", " ", "ד", " ", "א", "ב");
        assertThat(tokens.stream().map(DocumentLayer.Token::run).toList())
            .containsExactly(1, 1, 1, 0, 0, 0);
        assertThat(DocumentLayer.rightToLeft(tokens)).isTrue();
    }

    @Test
    @DisplayName("send a character to the smallest box that holds it, and count the lost ones")
    void smallestBoxAndLost() {
        PageBox inside = new PageBox(5, 5, 6, 6);
        PageBox outside = new PageBox(500, 500, 501, 501);
        TextLine run = new TextLine(inside, "a b", List.of(new RunChar('a', inside),
            new RunChar('b', outside), new RunChar(' ', outside), new RunChar('c', inside)), 0,
            7);
        List<Box> boxes = List.of(new Box(0, 0, 100, 100), new Box(0, 0, 20, 20));

        DocumentLayer.Assignment got = DocumentLayer.assignRuns(List.of(run), boxes, 1.0);

        assertThat(got.lost()).isEqualTo(1);
        assertThat(got.lines().get(0)).isEmpty();
        assertThat(got.lines().get(1).stream().map(DocumentLayer.Token::codePoint).toList())
            .containsExactly((int) 'a', (int) 'c');
        assertThat(got.lines().get(1).getFirst().run()).isEqualTo(7);
    }

    @Test
    @DisplayName("count a box as covered only past the share, and a box of no area by its edge")
    void coveredShare() {
        Box box = new Box(0, 0, 10, 10);
        assertThat(DocumentLayer.covered(box, List.of(new Box(0, 0, 5, 10)))).isFalse();
        assertThat(DocumentLayer.covered(box, List.of(new Box(0, 0, 6, 10)))).isTrue();
        assertThat(DocumentLayer.covered(box, List.of(new Box(0, 0, 6, 10)), 0.7f)).isFalse();
        assertThat(DocumentLayer.covered(box, List.of())).isFalse();
        assertThat(DocumentLayer.covered(new Box(1, 1, 1, 1), List.of(box))).isFalse();
    }

    @Test
    @DisplayName("refuse missing runs or boxes")
    void validation() {
        assertThatThrownBy(() -> DocumentLayer.assignRuns(null, List.of(), 1))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DocumentLayer.assignRuns(List.of(), null, 1))
            .isInstanceOf(NullPointerException.class);
    }

    private static void compareTokens(List<Object> want, List<List<DocumentLayer.Token>> got) {
        assertThat(got).hasSize(want.size());
        for (int i = 0; i < want.size(); i++) {
            List<Object> line = Json.array(want.get(i));
            List<DocumentLayer.Token> g = got.get(i);
            assertThat(g).as("line " + i).hasSize(line.size());
            for (int j = 0; j < line.size(); j++) {
                List<Object> t = Json.array(line.get(j));
                assertThat(g.get(j).codePoint()).as("line " + i + " token " + j)
                    .isEqualTo(((String) t.get(0)).codePointAt(0));
                assertThat(g.get(j).x()).isCloseTo(Json.number(t.get(1)), within(1e-9));
                assertThat(g.get(j).run()).isEqualTo(Json.integer(t.get(2)));
            }
        }
    }

    private static List<Box> boxes(Object json) {
        List<Box> boxes = new ArrayList<>();
        for (Object o : Json.array(json)) {
            List<Object> v = Json.array(o);
            boxes.add(new Box((float) Json.number(v.get(0)), (float) Json.number(v.get(1)),
                (float) Json.number(v.get(2)), (float) Json.number(v.get(3))));
        }
        return boxes;
    }

    private static List<DocumentLayer.Token> tokens(String text) {
        return text.codePoints().mapToObj(c -> new DocumentLayer.Token(c, 0.0, 0)).toList();
    }
}
