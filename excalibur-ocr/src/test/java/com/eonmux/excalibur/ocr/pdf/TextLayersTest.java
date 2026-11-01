package com.eonmux.excalibur.ocr.pdf;

import com.eonmux.excalibur.ocr.text.PythonText;
import com.eonmux.excalibur.ocr.image.GrayImage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The text-layer checks")
class TextLayersTest {

    @Test
    @DisplayName("count replacement, private-use, unassigned and control characters, not tabs")
    void badChars() {
        assertThat(TextLayers.badCharCount("plain text\t")).isZero();
        assertThat(TextLayers.badCharCount("a�bc\u0001d͸")).isEqualTo(4);
        assertThat(TextLayers.badCharCount("\uD800")).isEqualTo(1);
    }

    @Test
    @DisplayName("name the quarter turn of a baseline within a hundredth of a turn")
    void quarter() {
        assertThat(TextLayers.quarter(1, 0)).isZero();
        assertThat(TextLayers.quarter(0, 1)).isEqualTo(1);
        assertThat(TextLayers.quarter(-1, 0)).isEqualTo(2);
        assertThat(TextLayers.quarter(0, -1)).isEqualTo(3);
        assertThat(TextLayers.quarter(1, 0.01)).isZero();
        assertThat(TextLayers.quarter(1, 0.1)).isNull();
        assertThat(TextLayers.quarter(1, -1)).isNull();
    }

    @Test
    @DisplayName("turn rendered points into the frame of the page turned upright")
    void turnFrame() {
        assertThat(TextLayers.turnFrame(1, 2, 10, 20, 0)).containsExactly(1, 2);
        assertThat(TextLayers.turnFrame(1, 2, 10, 20, 1)).containsExactly(18, 1);
        assertThat(TextLayers.turnFrame(1, 2, 10, 20, 2)).containsExactly(9, 18);
        assertThat(TextLayers.turnFrame(1, 2, 10, 20, 3)).containsExactly(2, 9);
    }

    @Test
    @DisplayName("read vertical writing from steps that mostly go down")
    void verticalLayout() {
        assertThat(TextLayers.verticalLayout(TextStats.NONE)).isFalse();
        assertThat(TextLayers.verticalLayout(stats(10, List.of(0, 0, 0, 0), 30, 14))).isTrue();
        assertThat(TextLayers.verticalLayout(stats(10, List.of(0, 0, 0, 0), 30, 15))).isFalse();
        assertThat(TextLayers.verticalLayout(stats(10, List.of(0, 0, 0, 0), 29, 0))).isFalse();
    }

    @Test
    @DisplayName("turn a page whose characters mostly share one quarter turn")
    void pageTurn() {
        assertThat(TextLayers.pageTurn(TextStats.NONE)).isZero();
        assertThat(TextLayers.pageTurn(stats(10, List.of(4, 6, 0, 0), 0, 0))).isEqualTo(1);
        assertThat(TextLayers.pageTurn(stats(10, List.of(5, 0, 0, 5), 0, 0))).isZero();
        assertThat(TextLayers.pageTurn(stats(10, List.of(0, 3, 3, 4), 0, 0))).isZero();
        assertThat(TextLayers.pageTurn(stats(10, List.of(0, 0, 6, 6), 0, 0))).isEqualTo(2);
        assertThat(TextLayers.pageTurn(stats(10, List.of(0, 0, 0, 9), 0, 0))).isEqualTo(3);
    }

    @Test
    @DisplayName("join surrogate pairs and replace lone surrogates")
    void codePoint() {
        assertThat(TextLayers.codePoint(0xD835, 0xDC00))
            .isEqualTo(new TextLayers.CodePoint(0x1D400, true));
        assertThat(TextLayers.codePoint(0xD835, 'a'))
            .isEqualTo(new TextLayers.CodePoint(0xFFFD, false));
        assertThat(TextLayers.codePoint(0xDC00, 0)).isEqualTo(new TextLayers.CodePoint(0xFFFD, false));
        assertThat(TextLayers.codePoint('a', 0)).isEqualTo(new TextLayers.CodePoint('a', false));
        assertThat(TextLayers.codePoint(0x110000, 0).codePoint()).isEqualTo(0xFFFD);
    }

    @Test
    @DisplayName("count right-to-left pairs that step left to right")
    void visualRtl() {
        TextLine visual = run(List.of(
            new RunChar('א', new PageBox(0, 0, 1, 1)),
            new RunChar('ב', new PageBox(2, 0, 3, 1)),
            new RunChar('a', new PageBox(4, 0, 5, 1)),
            new RunChar('ג', new PageBox(6, 0, 7, 1)),
            new RunChar('ד', new PageBox(4, 0, 5, 1))));

        TextLayers.RtlPairs got = TextLayers.visualRtlFrac(List.of(visual));

        assertThat(got.pairs()).isEqualTo(2);
        assertThat(got.share()).isEqualTo(0.5);
        assertThat(TextLayers.visualRtlFrac(List.of()).share()).isZero();
    }

    @Test
    @DisplayName("find runs with no dark pixel under them, slicing as numpy does")
    void noInk() {
        byte[] px = new byte[10 * 10];
        java.util.Arrays.fill(px, (byte) 255);
        px[5 * 10 + 5] = 0;
        GrayImage gray = GrayImage.of(10, 10, px);

        assertThat(TextLayers.noInkFrac(gray, List.of(line(4, 4, 6, 6)), 1.0)).isZero();
        assertThat(TextLayers.noInkFrac(gray, List.of(line(0, 0, 3, 3)), 1.0)).isEqualTo(1.0);
        assertThat(TextLayers.noInkFrac(gray, List.of(line(2, 2, 3, 3)), 2.0)).isZero();
        assertThat(TextLayers.noInkFrac(gray, List.of(line(0, 0, 9, -4)), 1.0)).isZero();
        assertThat(TextLayers.noInkFrac(gray, List.of(line(0, 0, 9, -30)), 1.0)).isEqualTo(1.0);
        assertThat(TextLayers.noInkFrac(gray, List.of(line(20, 20, 30, 30)), 1.0))
            .isEqualTo(1.0);
        assertThat(TextLayers.noInkFrac(gray, List.of(), 1.0)).isZero();
    }

    @Test
    @DisplayName("format shares and round as Python does")
    void pythonFormatting() {
        assertThat(PyFormat.percent(0.125)).isEqualTo("12%");
        assertThat(PyFormat.percent(0.135)).isEqualTo("14%");
        assertThat(PyFormat.percent(1.0)).isEqualTo("100%");
        assertThat(PyFormat.roundHalfEven(0.0625, 3)).isEqualTo(0.062);
        assertThat(PyFormat.roundHalfEven(2.675, 2)).isEqualTo(2.67);
        assertThat(PythonText.collapseSpaces("  a \t b　c  ")).isEqualTo("a b c");
        assertThat(PythonText.collapseSpaces(" ")).isEmpty();
    }

    @Test
    @DisplayName("refuse turns and frames that are not quarter turns")
    void validation() throws IOException {
        try (PDDocument doc = TextLayerFixtures.open("orient.pdf")) {
            PdfPage page = PdfPage.of(doc, 0);
            GrayImage gray = PageRenderer.render(page, 0.25);
            assertThatThrownBy(() -> TextLayers.read(page, gray, 0.25, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TextLayers.read(page, gray, 0.25, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TextLayers.extractRuns(page, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("extract runs and their statistics as the layer read does")
    void extractRuns() throws IOException {
        try (PDDocument doc = TextLayerFixtures.open("orient.pdf")) {
            PdfPage page = PdfPage.of(doc, 0);
            TextLayers.RunsAndStats runs = TextLayers.extractRuns(page, 0, 0);
            TextLayer layer = TextLayers.read(page, PageRenderer.render(page, 1.0), 1.0);

            assertThat(runs.runs()).hasSize(layer.lines().size() + layer.rotated().size());
            assertThat(runs.stats().withTurnAndFrame(0, 0)).isEqualTo(layer.stats());
            assertThat(TextLayers.invisibleTextFrac(page)).isZero();
        }
    }

    @Test
    @DisplayName("keep statistics as Indra's dict, with its defaults")
    void statsDict() {
        TextStats st = TextStats.counts(5, 1, 0, List.of(4, 1, 0, 0)).withBoxes(2, 3, 4)
            .withTurnAndFrame(1, 3).withVisualRtl(0.5).withNoInk(0.25).withVertical(true);

        Map<String, Object> map = st.asMap();

        assertThat(map.keySet()).containsExactly("chars", "rotated", "bad", "quarters",
            "off_page", "steps", "turn", "frame", "visual_rtl", "no_ink", "vertical");
        assertThat(st.turnOrZero()).isEqualTo(1);
        assertThat(st.frameOrTurn()).isEqualTo(3);
        assertThat(st.isVertical()).isTrue();
        assertThat(st.withTurn(2).turn()).isEqualTo(2);
        assertThat(TextStats.NONE.asMap()).isEmpty();
        assertThat(TextStats.NONE.turnOrZero()).isZero();
        assertThat(TextStats.NONE.frameOrTurn()).isZero();
        assertThat(TextStats.NONE.isVertical()).isFalse();
        assertThat(TextStats.NONE.charCount()).isZero();
        assertThatThrownBy(() -> st.withTurn(4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("keep runs and layers whole and immutable")
    void records() {
        TextLine ln = line(0, 0, 1, 1);
        TextLayer layer = new TextLayer(List.of(ln), 10, 20, true, "", TextStats.NONE, List.of());

        assertThat(layer.withStats(TextStats.NONE.withVertical(true)).stats().isVertical())
            .isTrue();
        assertThat(new RunChar(0x1D400, ln.bbox()).text()).isEqualTo("𝐀");
        assertThatThrownBy(() -> layer.lines().add(ln))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new TextLine(ln.bbox(), "x", List.of(), 4, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextLine(null, "x", List.of(), 0, 0))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TextLayer(List.of(), 1, 1, true, null, TextStats.NONE,
            List.of())).isInstanceOf(NullPointerException.class);
    }

    private static TextStats stats(int chars, List<Integer> quarters, int down, int right) {
        return TextStats.counts(chars, 0, 0, quarters).withBoxes(0, down, right);
    }

    private static TextLine line(double x0, double y0, double x1, double y1) {
        return new TextLine(new PageBox(x0, y0, x1, y1), "x", List.of(), 0, 0);
    }

    private static TextLine run(List<RunChar> chars) {
        return new TextLine(new PageBox(0, 0, 1, 1), "x", chars, 0, 0);
    }
}
