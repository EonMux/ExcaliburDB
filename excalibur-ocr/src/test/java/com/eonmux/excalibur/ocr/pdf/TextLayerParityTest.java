package com.eonmux.excalibur.ocr.pdf;

import com.eonmux.excalibur.ocr.image.GrayImage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("The text layer of the fixture PDFs")
class TextLayerParityTest {

    /** Most a character box may lie from pdfium's, in points (substitute-font glyph boxes). */
    private static final double BOX_TOLERANCE = 1.0;
    /** Most the boxes may lie from pdfium's on average, in points. */
    private static final double MEAN_BOX_TOLERANCE = 0.15;
    /** Most the share of dark pixels in a render may differ from pdfium's. */
    private static final double INK_TOLERANCE = 0.01;
    /**
     * Most the count of steps across may differ from pdfium's: a step is a neighbour pair whose
     * centres differ by less than 0.3 of a box height, and the glyph boxes of non-embedded
     * standard fonts differ slightly (AFM metrics here, pdfium's Foxit faces there).
     */
    private static final int STEP_TOLERANCE = 3;
    /**
     * Reads whose ink check depends on glyphs drawn in a substitute for a non-embedded font:
     * PDFBox's system substitute for Times-Roman leaves text 1.5 points high lighter than
     * pdfium's Foxit face does at 72 dpi, and draws nothing for a glyph name the font lacks,
     * where pdfium draws Foxit's {@code .notdef} box. The verdict here is Indra's or a share of
     * inkless runs.
     */
    private static final Set<String> SUBSTITUTE_INK = Set.of("layout.pdf#2 first",
        "fonts.pdf#2 first");
    private static final String NO_INK = " of text runs have no ink";

    /** A text layer read on the way to Indra's decision, and the step that read it. */
    private record Read(String step, TextLayer layer) {
    }

    /** Box deviations of one comparison. */
    private static final class Deviation {
        double sum;
        double max;
        int count;

        void add(PageBox a, PageBox b) {
            double d = Math.max(Math.max(Math.abs(a.x0() - b.x0()), Math.abs(a.y0() - b.y0())),
                Math.max(Math.abs(a.x1() - b.x1()), Math.abs(a.y1() - b.y1())));
            sum += d;
            max = Math.max(max, d);
            count++;
        }
    }

    @TestFactory
    @DisplayName("reads the layers Indra reads, to the same verdicts and texts")
    Stream<DynamicTest> readsAsIndra() {
        return TextLayerFixtures.expected().entrySet().stream().map(e -> DynamicTest.dynamicTest(
            e.getKey(), () -> withPage(e.getKey(), page -> {
                Deviation dev = new Deviation();
                compareReads(e.getKey(), Json.object(e.getValue()), page, dev);
                assertThat(dev.max).as("largest box deviation").isLessThan(BOX_TOLERANCE);
            })));
    }

    @Test
    @DisplayName("places characters within a fraction of a point of pdfium on average")
    void boxesAgreeOnAverage() throws IOException {
        Deviation dev = new Deviation();
        for (Map.Entry<String, Object> e : TextLayerFixtures.expected().entrySet()) {
            withPage(e.getKey(),
                page -> compareReads(e.getKey(), Json.object(e.getValue()), page, dev));
        }
        assertThat(dev.count).isGreaterThan(5000);
        assertThat(dev.sum / dev.count).isLessThan(MEAN_BOX_TOLERANCE);
    }

    @TestFactory
    @DisplayName("measures and renders each page as pypdfium2 does")
    Stream<DynamicTest> geometryAsPdfium() {
        return TextLayerFixtures.expected().entrySet().stream().map(e -> DynamicTest.dynamicTest(
            e.getKey(), () -> withPage(e.getKey(), page ->
                compareGeometry(Json.object(Json.object(e.getValue()).get("geometry")), page))));
    }

    private interface PageCheck {
        void check(PdfPage page) throws IOException;
    }

    private static void withPage(String key, PageCheck check) throws IOException {
        int hash = key.lastIndexOf('#');
        try (PDDocument doc = TextLayerFixtures.open(key.substring(0, hash))) {
            check.check(PdfPage.of(doc, Integer.parseInt(key.substring(hash + 1))));
        }
    }

    /** The reads of {@code gen_textlayer.reads}, which follow {@code Converter.pdf_page}. */
    private static List<Read> reads(PdfPage page) throws IOException {
        double s = DocumentLayer.CHECK_DPI / 72.0;
        GrayImage check = PageRenderer.render(page, s);
        TextLayer tl = TextLayers.read(page, check, s);
        List<Read> reads = new ArrayList<>(List.of(new Read("first", tl)));
        int k = TextLayers.pageTurn(tl.stats());
        if (k != 0 && (page.rotation() + 90 * k) % 360 == 0) {
            PdfPage upright = page.withRotation(0);
            reads.add(new Read("unrotated",
                TextLayers.read(upright, PageRenderer.render(upright, s), s)));
            reads.add(new Read("turned", TextLayers.read(page, check.rot90(-k), s, k)));
        } else if (k != 0) {
            reads.add(new Read("turned", TextLayers.read(page, check.rot90(-k), s, k)));
        } else if (TextLayers.verticalLayout(tl.stats())) {
            reads.add(new Read("vertical", TextLayers.read(page, check.rot90(1), s, 0, 3)));
        }
        return reads;
    }

    private static void compareReads(String key, Map<String, Object> expected, PdfPage page,
                                     Deviation dev) throws IOException {
        List<Object> want = Json.array(expected.get("reads"));
        List<Read> got = reads(page);
        assertThat(got.stream().map(Read::step).toList()).as("read steps")
            .isEqualTo(want.stream().map(o -> Json.object(o).get("step")).toList());
        for (int i = 0; i < got.size(); i++) {
            compareLayer(key, Json.object(Json.object(want.get(i)).get("layer")), got.get(i), dev);
        }
    }

    private static void compareLayer(String key, Map<String, Object> want, Read read,
                                     Deviation dev) {
        TextLayer got = read.layer();
        String step = read.step();
        boolean substitute = SUBSTITUTE_INK.contains(key + " " + step);
        if (!substitute || got.reason().equals(want.get("reason"))) {
            assertThat(got.reason()).as(step + " reason").isEqualTo(want.get("reason"));
            assertThat(got.trusted()).as(step + " trusted").isEqualTo(want.get("trusted"));
        } else {
            assertThat(got.reason()).as(step + " reason").endsWith(NO_INK);
            assertThat(got.trusted()).as(step + " trusted").isFalse();
        }
        List<Object> size = Json.array(want.get("size"));
        assertThat(got.width()).as(step + " width").isEqualTo(Json.number(size.get(0)));
        assertThat(got.height()).as(step + " height").isEqualTo(Json.number(size.get(1)));
        compareStats(Json.object(want.get("stats")), got.stats(), step, substitute);
        compareLines(want.get("lines"), got.lines(), step + " lines", dev);
        compareLines(want.get("rotated"), got.rotated(), step + " rotated", dev);
    }

    private static void compareStats(Map<String, Object> want, TextStats got, String step,
                                     boolean substituteInk) {
        Map<String, Object> map = got.asMap();
        assertThat(map.keySet()).as(step + " stats keys").isEqualTo(want.keySet());
        for (Map.Entry<String, Object> e : want.entrySet()) {
            Object g = map.get(e.getKey());
            Object w = e.getValue();
            if (e.getKey().equals("no_ink") && substituteInk) {
                continue;
            }
            if (e.getKey().equals("steps")) {
                compareSteps(Json.array(w), got, step);
            } else if (w instanceof List<?> list) {
                assertThat(((List<?>) g).stream().map(v -> ((Number) v).longValue()).toList())
                    .as(step + " " + e.getKey())
                    .isEqualTo(list.stream().map(v -> ((Number) v).longValue()).toList());
            } else {
                assertThat(((Number) g).doubleValue()).as(step + " " + e.getKey())
                    .isEqualTo(Json.number(w));
            }
        }
    }

    /** Steps decide {@code vertical_layout}: the decision must agree, the counts nearly. */
    private static void compareSteps(List<Object> want, TextStats got, String step) {
        int down = Json.integer(want.get(0));
        int right = Json.integer(want.get(1));
        assertThat(got.stepCounts().get(0)).as(step + " steps down")
            .isCloseTo(down, within(STEP_TOLERANCE));
        assertThat(got.stepCounts().get(1)).as(step + " steps across")
            .isCloseTo(right, within(STEP_TOLERANCE));
        assertThat(TextLayers.verticalLayout(got)).as(step + " vertical layout")
            .isEqualTo(down >= 30 && down > 2 * right);
    }

    private static void compareLines(Object json, List<TextLine> got, String what,
                                     Deviation dev) {
        List<TextLine> want = TextLayerFixtures.lines(json);
        assertThat(got.stream().map(TextLine::text).toList()).as(what + " texts")
            .isEqualTo(want.stream().map(TextLine::text).toList());
        for (int i = 0; i < want.size(); i++) {
            TextLine w = want.get(i);
            TextLine g = got.get(i);
            assertThat(g.quarter()).as(what + " quarter").isEqualTo(w.quarter());
            assertThat(g.seq()).as(what + " seq").isEqualTo(w.seq());
            assertThat(g.chars().stream().map(RunChar::codePoint).toList()).as(what + " chars")
                .isEqualTo(w.chars().stream().map(RunChar::codePoint).toList());
            dev.add(g.bbox(), w.bbox());
            for (int j = 0; j < w.chars().size(); j++) {
                dev.add(g.chars().get(j).box(), w.chars().get(j).box());
            }
        }
    }

    private static void compareGeometry(Map<String, Object> want, PdfPage page)
        throws IOException {
        List<Object> size = Json.array(want.get("size"));
        assertThat(page.width()).isEqualTo(Json.number(size.get(0)));
        assertThat(page.height()).isEqualTo(Json.number(size.get(1)));
        assertThat(page.rotation()).isEqualTo(Json.integer(want.get("rotation")));
        List<Object> rows = Json.array(want.get("affine"));
        Affine a = page.deviceAffine();
        double[] got = {a.a00(), a.a01(), a.a02(), a.a10(), a.a11(), a.a12()};
        for (int r = 0; r < 2; r++) {
            List<Object> row = Json.array(rows.get(r));
            for (int c = 0; c < 3; c++) {
                assertThat(got[3 * r + c]).as("affine " + r + c)
                    .isCloseTo(Json.number(row.get(c)), within(1e-9));
            }
        }
        assertThat(TextLayers.invisibleTextFrac(page))
            .isCloseTo(Json.number(want.get("invisible")), within(1e-12));
        assertThat(DocumentLayer.pageScale(page.width(), page.height(), 200))
            .isEqualTo(Json.number(want.get("page_scale")));
        for (Object o : Json.array(want.get("renders"))) {
            Map<String, Object> r = Json.object(o);
            double scale = Json.number(r.get("scale"));
            GrayImage gray = PageRenderer.render(page, scale);
            assertThat(gray.width()).as("render width at " + scale)
                .isEqualTo(Json.integer(r.get("width")));
            assertThat(gray.height()).as("render height at " + scale)
                .isEqualTo(Json.integer(r.get("height")));
            assertThat(inkShare(gray)).as("ink at " + scale)
                .isCloseTo(Json.number(r.get("ink")), within(INK_TOLERANCE));
        }
    }

    private static double inkShare(GrayImage gray) {
        byte[] px = gray.pixels();
        int dark = 0;
        for (byte b : px) {
            dark += (b & 0xFF) < TextLayers.DARK ? 1 : 0;
        }
        return (double) dark / px.length;
    }
}
