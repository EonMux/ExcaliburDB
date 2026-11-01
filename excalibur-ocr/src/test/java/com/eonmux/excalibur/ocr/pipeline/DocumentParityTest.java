package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.Deadline;
import com.eonmux.excalibur.ocr.IndraOptions;
import com.eonmux.excalibur.ocr.ReadMode;
import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.pdf.PdfPage;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import com.eonmux.excalibur.ocr.testing.Models;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole pages against Indra OCR's own output on synthetic PDFs ({@code
 * tools/fixtures/gen_documents.py}): scanned pages read by OCR, upright, tilted and turned, and
 * text-layer pages read from their layer, checked against their image, or completed by OCR -
 * in every mode, and with math and page furniture on.
 *
 * <p>Pages are drawn as the very images pdfium drew for Indra, so every step after rendering is
 * held to Indra's exactly. PDFBox and pdfium draw some things differently (a glyph a font lacks,
 * hinting, anti-aliasing), which moves what OCR reads; the parity report
 * ({@code ./gradlew :excalibur-ocr:parity}) measures both together on real documents.</p>
 */
@DisplayName("Whole PDF pages, as Indra OCR reads them")
class DocumentParityTest {

    private static final Map<String, String> PDFS = Map.of(
        "scan.pdf", "document/scan.pdf",
        "layout.pdf", "textlayer/layout.pdf",
        "scripts.pdf", "textlayer/scripts.pdf",
        "orient.pdf", "textlayer/orient.pdf",
        "sideways.pdf", "textlayer/sideways.pdf",
        "fonts.pdf", "textlayer/fonts.pdf",
        "hidden.pdf", "textlayer/hidden.pdf",
        "forms.pdf", "textlayer/forms.pdf");

    @Test
    @DisplayName("writes every page's markdown exactly as Indra does, from pdfium's images")
    void pages() throws Exception {
        Map<String, Object> fixture = Json.parseObject(Fixtures.text("document/documents.json"));
        Renders renders = new Renders(list(fixture.get("renders")),
            Fixtures.npz("document/documents.npz"));
        List<String> differ = new ArrayList<>();
        int compared = 0;
        for (Map<String, Object> c : list(fixture.get("cases"))) {
            IndraOptions options = new IndraOptions(ReadMode.of((String) c.get("mode")), 200.0,
                (Boolean) c.get("math"), (Boolean) c.get("furniture"), true, 1);
            try (PdfPages pages = PdfPages.load(Models.dir(), options, renders::render)) {
                Map<String, Object> documents = map(c.get("documents"));
                for (Map.Entry<String, Object> doc : documents.entrySet()) {
                    List<Map<String, Object>> want = list(doc.getValue());
                    try (PDDocument pdf = Loader.loadPDF(
                            Fixtures.bytes(PDFS.get(doc.getKey())))) {
                        renders.name(pdf, doc.getKey());
                        assertThat(pdf.getNumberOfPages()).isEqualTo(want.size());
                        for (int i = 0; i < want.size(); i++) {
                            String got = pages.read(pdf, i, Deadline.NONE).part();
                            compared++;
                            if (!got.equals(want.get(i).get("part"))) {
                                differ.add(options.describe() + " " + doc.getKey() + " page "
                                    + i + " (" + want.get(i).get("source") + "):\n--- indra\n"
                                    + want.get(i).get("part") + "\n--- port\n" + got);
                            }
                        }
                    }
                }
            }
        }
        assertThat(compared).isGreaterThan(0);
        assertThat(differ).as("pages that differ from Indra's").isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /** pdfium's images of the pages, by document, page, rotation and scale. */
    private static final class Renders {

        private final Map<PDDocument, String> names = new IdentityHashMap<>();
        private final Map<String, GrayImage> images = new HashMap<>();

        Renders(List<Map<String, Object>> index, Map<String, NdArray> arrays) {
            for (Map<String, Object> r : index) {
                images.put(key((String) r.get("pdf"), ((Number) r.get("page")).intValue(),
                        ((Number) r.get("rotation")).intValue(),
                        ((Number) r.get("scale")).doubleValue()),
                    Fixtures.image(arrays.get((String) r.get("key"))));
            }
        }

        void name(PDDocument doc, String name) {
            names.put(doc, name);
        }

        GrayImage render(PdfPage page, double scale) {
            String key = key(names.get(page.document()), page.index(), page.rotation(), scale);
            GrayImage image = images.get(key);
            if (image == null) {
                throw new AssertionError("Indra drew no image " + key);
            }
            return image;
        }

        private static String key(String pdf, int page, int rotation, double scale) {
            return pdf + "#" + page + "@" + rotation + "x" + scale;
        }
    }
}
