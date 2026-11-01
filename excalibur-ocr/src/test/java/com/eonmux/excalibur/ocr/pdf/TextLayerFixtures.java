package com.eonmux.excalibur.ocr.pdf;

import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The text-layer fixtures: the PDFs that {@code tools/fixtures/MakeTextLayerPdfs.java} writes and
 * Indra's verdicts on them from {@code tools/fixtures/gen_textlayer.py}.
 */
final class TextLayerFixtures {

    static final String DIR = "textlayer/";

    private TextLayerFixtures() {
        throw new AssertionError("Static methods only");
    }

    /** Pages by {@code file#index}, with their geometry and the layers Indra read. */
    static Map<String, Object> expected() {
        return Json.object(Json.parse(Fixtures.text(DIR + "expected.json")));
    }

    /** Indra's {@code document} helpers on inputs built from the layers. */
    static Map<String, Object> document() {
        return Json.object(Json.parse(Fixtures.text(DIR + "document.json")));
    }

    /**
     * A fixture PDF.
     *
     * @param name Its file name
     * @return The open document
     */
    static PDDocument open(String name) {
        try (InputStream in = TextLayerFixtures.class.getResourceAsStream(
            "/fixtures/" + DIR + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture PDF " + name);
            }
            return Loader.loadPDF(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs as {@code gen_textlayer.py} stores them. */
    static List<TextLine> lines(Object json) {
        List<TextLine> lines = new ArrayList<>();
        for (Object o : Json.array(json)) {
            Map<String, Object> r = Json.object(o);
            List<RunChar> chars = new ArrayList<>();
            for (Object c : Json.array(r.get("chars"))) {
                List<Object> pair = Json.array(c);
                chars.add(new RunChar(((String) pair.get(0)).codePointAt(0), box(pair.get(1))));
            }
            Object seq = r.get("seq");
            lines.add(new TextLine(box(r.get("bbox")), (String) r.get("text"), chars,
                Json.integer(r.get("quarter")), seq == null ? null : Json.integer(seq)));
        }
        return lines;
    }

    static PageBox box(Object json) {
        List<Object> v = Json.array(json);
        return new PageBox(Json.number(v.get(0)), Json.number(v.get(1)), Json.number(v.get(2)),
            Json.number(v.get(3)));
    }
}
