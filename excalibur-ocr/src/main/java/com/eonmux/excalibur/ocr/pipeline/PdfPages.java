// Ported from Indra OCR 1.1.2, document.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.ConversionTimeoutException;
import com.eonmux.excalibur.ocr.Deadline;
import com.eonmux.excalibur.ocr.IndraOptions;
import com.eonmux.excalibur.ocr.ReadMode;
import com.eonmux.excalibur.ocr.image.Box;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;
import com.eonmux.excalibur.ocr.math.MathReader;
import com.eonmux.excalibur.ocr.pdf.DocumentLayer;
import com.eonmux.excalibur.ocr.pdf.PageBox;
import com.eonmux.excalibur.ocr.pdf.PageRenderer;
import com.eonmux.excalibur.ocr.pdf.PdfPage;
import com.eonmux.excalibur.ocr.pdf.TextLayer;
import com.eonmux.excalibur.ocr.pdf.TextLayers;
import com.eonmux.excalibur.ocr.pdf.TextLine;
import com.eonmux.excalibur.ocr.pdf.TextStats;
import com.eonmux.excalibur.ocr.recognize.LineCrops;
import com.eonmux.excalibur.ocr.text.PythonText;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The pages of a PDF as text: the text layer where it can be trusted, OCR elsewhere. Indra's
 * {@code document.Converter}.
 *
 * <p>In {@link ReadMode#AUTO} a page's text layer is read and checked; a page without a sound
 * layer (a scan, an OCR layer, a broken font map) is read by OCR. A sound layer's characters
 * are placed on the lines the detector finds, so inline math, sub- and superscripts land in
 * their line and a run across two table cells splits between them; lines with no layer text
 * (text inside an image) are read by OCR. {@link ReadMode#OCR} never reads the layer;
 * {@link ReadMode#TEXT} uses it even when the checks fail.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: pages may be read from many threads at once, each with its own
 * {@link PDDocument}.</p>
 */
public final class PdfPages implements AutoCloseable {

    private final OcrModels models;
    private final MathReader math;
    private final IndraOptions options;
    private final Recognition recognition;
    private final Assembly assembly;
    private final PageOcr ocr;
    private final Renderer renderer;

    private PdfPages(OcrModels models, MathReader math, IndraOptions options, Renderer renderer) {
        this.models = models;
        this.math = math;
        this.options = options;
        this.renderer = renderer;
        this.recognition = new Recognition(models, math);
        this.assembly = new Assembly(models, options.keepFurniture());
        this.ocr = new PageOcr(models, recognition, assembly);
    }

    /**
     * Loads the models from a directory.
     *
     * @param dir     The model directory
     * @param options How pages are read
     * @return The reader
     * @throws IOException if a model is missing or cannot be loaded
     */
    public static PdfPages load(Path dir, IndraOptions options) throws IOException {
        return load(dir, options, PageRenderer::render);
    }

    /**
     * Loads the models from a directory, to read pages drawn by the given renderer.
     *
     * @param dir      The model directory
     * @param options  How pages are read
     * @param renderer Draws a page at a scale, as {@link PageRenderer#render} does
     * @return The reader
     * @throws IOException if a model is missing or cannot be loaded
     */
    static PdfPages load(Path dir, IndraOptions options, Renderer renderer) throws IOException {
        Objects.requireNonNull(dir, "dir must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(renderer, "renderer must not be null");
        OcrModels models = OcrModels.load(dir);
        try {
            MathReader math = options.math() ? MathReader.load(dir) : null;
            return new PdfPages(models, math, options, renderer);
        } catch (IOException | RuntimeException e) {
            models.close();
            throw e;
        }
    }

    /**
     * Reads one page.
     *
     * @param doc      The document, used by this thread alone
     * @param index    The page's index
     * @param deadline When the conversion must be done by
     * @return The page's markdown and text
     * @throws IOException                if the page cannot be rendered
     * @throws ConversionTimeoutException if the deadline passes
     * @throws InterruptedException       if the thread is interrupted
     */
    public PageText read(PDDocument doc, int index, Deadline deadline)
            throws IOException, ConversionTimeoutException, InterruptedException {
        PdfPage page = PdfPage.of(doc, index);
        boolean turn = true;
        if (options.mode() != ReadMode.OCR) {
            Layer layer = readLayer(page, deadline);
            if (layer.page() != null) {
                return layer.page();
            }
            turn = layer.turn();
        }
        deadline.check();
        GrayImage gray = renderer.render(page, pageScale(page));
        return ocr.read(gray, turn, deadline);
    }

    /**
     * The page from its text layer when the layer can stand for it; otherwise whether OCR may
     * turn the page.
     */
    private Layer readLayer(PdfPage page, Deadline deadline)
            throws IOException, ConversionTimeoutException, InterruptedException {
        // Bounded like every other render: at one pixel per point, a page whose box a damaged or
        // hostile file sets to tens of thousands of points a side would otherwise allocate
        // gigabytes for the check alone, past what the page's memory lease accounts for.
        double s = DocumentLayer.pageScale(page.width(), page.height(), DocumentLayer.CHECK_DPI);
        GrayImage check = renderer.render(page, s);
        TextLayer tl = TextLayers.read(page, check, s);
        // A table set sideways: most characters share one quarter turn. Read the page turned
        // upright, as the OCR path does with the orientation model.
        int k = TextLayers.pageTurn(tl.stats());
        int rot = page.rotation();
        if (k != 0 && (rot + 90 * k) % 360 == 0) {
            // The page's /Rotate alone turns the text: read the page without it. pdfium builds
            // its text runs in the displayed frame, and on a turned page it can join a line's
            // font runs out of order.
            PdfPage straight = page.withRotation(0);
            TextLayer upright = TextLayers.read(straight, renderer.render(straight, s), s);
            if (!upright.lines().isEmpty()
                    && (upright.trusted() || options.mode() == ReadMode.TEXT)) {
                upright = upright.withStats(upright.stats().withTurn(k));
                PageText out = textPage(straight, upright, options.mode() == ReadMode.AUTO,
                    deadline);
                if (out != null) {
                    return new Layer(out, true);
                }
            }
            tl = TextLayers.read(page, check.rot90(-k), s, k);
        } else if (k != 0) {
            tl = TextLayers.read(page, check.rot90(-k), s, k);
        } else if (TextLayers.verticalLayout(tl.stats())) {
            // Vertical writing: upright characters, read in the page turned a quarter turn
            // counter-clockwise (columns become lines).
            tl = TextLayers.read(page, check.rot90(1), s, 0, 3);
            tl = tl.withStats(tl.stats().withVertical(true));
        }
        deadline.check();
        // A layer the recognizer cannot read beats OCR, unless its codes are junk.
        boolean broken = TextLayers.BROKEN_FONT_MAP.equals(tl.reason());
        boolean unreadable = !broken && !readable(tl.lines());
        if (!tl.lines().isEmpty()
                && (tl.trusted() || options.mode() == ReadMode.TEXT || unreadable)) {
            // A trusted layer in a script the recognizer reads is checked against what the page
            // shows: legacy fonts store other codes.
            PageText out = textPage(page, tl,
                options.mode() == ReadMode.AUTO && tl.trusted() && !unreadable, deadline);
            if (out != null) {
                return new Layer(out, true);
            }
        }
        // A layer with a broken font map still places its glyphs: when most run upright, the
        // page is upright, and the orientation model is not asked.
        TextStats st = tl.stats();
        boolean turn = !(broken && st.rotated() < 0.5 * st.chars());
        return new Layer(null, turn);
    }

    /**
     * The page from its text layer, or null when the layer's script does not match what the
     * page shows and OCR should read it instead.
     *
     * @param check Whether to compare the script of up to {@link DocumentLayer#AGREE_LINES} of the layer's
     *              lines with the script the line classifier sees in their images
     */
    private PageText textPage(PdfPage page, TextLayer tl, boolean check, Deadline deadline)
            throws IOException, ConversionTimeoutException, InterruptedException {
        double k = pageScale(page);
        TextStats st = tl.stats();
        int frame = st.frameOrTurn();
        boolean vertical = st.isVertical();
        GrayImage gray = renderer.render(page, k);
        if (frame != 0) {
            gray = gray.rot90(-frame);
        }
        deadline.check();
        float[][] boxes = ocr.detect(gray);
        DocumentLayer.Assignment assigned = DocumentLayer.assignRuns(tl.lines(), asBoxes(boxes),
            k);
        LineText decoder = new LineText(models.base.vocab(), false);
        Reading[] rec = new Reading[boxes.length];
        List<Integer> empty = new ArrayList<>();
        for (int i = 0; i < boxes.length; i++) {
            List<Token> tokens = assigned.lines().get(i).stream()
                .map(t -> Token.ofLayer(Character.toString(t.codePoint()), t.x(), t.run()))
                .toList();
            String text = tokens.isEmpty() ? "" : decoder.decode(tokens);
            if (text.isEmpty()) {
                empty.add(i);
            } else {
                rec[i] = new Reading(text, 1.0, tokens);
            }
        }
        if (check && !layerMatches(gray, boxes, rec)) {
            return null;
        }
        deadline.check();
        // Sideways runs (a rotated table header, a chart's axis label) come from the layer as
        // their own lines; detected lines on them are not read by OCR.
        List<TextLine> side = tl.rotated().stream()
            .filter(r -> !PythonText.strip(r.text()).isEmpty()).toList();
        float[][] sideBoxes = new float[side.size()][];
        for (int i = 0; i < side.size(); i++) {
            PageBox b = side.get(i).bbox();
            sideBoxes[i] = new float[] {(float) (b.x0() * k), (float) (b.y0() * k),
                (float) (b.x1() * k), (float) (b.y1() * k)};
        }
        if (!side.isEmpty() && !empty.isEmpty()) {
            List<Box> covering = asBoxes(sideBoxes);
            empty = empty.stream()
                .filter(i -> !DocumentLayer.covered(asBox(boxes[i]), covering)).toList();
        }
        if (!empty.isEmpty() && options.imageOcr() && readable(tl.lines())) {
            List<Reading> read = recognition.recognize(gray, pick(boxes, empty), vertical);
            for (int j = 0; j < empty.size(); j++) {
                if (!PythonText.strip(read.get(j).text()).isEmpty()) {
                    rec[empty.get(j)] = read.get(j);
                }
            }
        }
        deadline.check();
        if (math != null) {
            readMath(gray, boxes, rec);
        }
        List<float[]> keptBoxes = new ArrayList<>();
        List<Reading> keptRec = new ArrayList<>();
        for (int i = 0; i < boxes.length; i++) {
            if (rec[i] != null) {
                keptBoxes.add(boxes[i]);
                keptRec.add(rec[i]);
            }
        }
        for (int i = 0; i < side.size(); i++) {
            TextLine r = side.get(i);
            float[] b = sideBoxes[i];
            keptBoxes.add(new float[] {b[0], b[1], b[2], b[3], 1f});
            // One x: a sideways line is never cut.
            double cx = (double) (b[0] + b[2]) / 2;
            int seq = Objects.requireNonNull(r.seq(), "a run without a content position");
            List<Token> tokens = r.text().codePoints()
                .mapToObj(c -> Token.ofLayer(Character.toString(c), cx, seq)).toList();
            keptRec.add(new Reading(r.text(), 1.0, tokens));
        }
        return assembly.assemble(gray.width(), gray.height(),
            keptBoxes.toArray(float[][]::new), keptRec, vertical, null);
    }

    /**
     * Whether a layer's text is the page's: a line disagrees when its layer text is Latin while
     * the script router is sure its image is another script.
     */
    private boolean layerMatches(GrayImage gray, float[][] boxes, Reading[] rec) {
        List<Integer> full = new ArrayList<>();
        for (int i = 0; i < rec.length; i++) {
            if (rec[i] != null && PythonText.letters(rec[i].text()) >= 4) {
                full.add(i);
            }
        }
        int step = Math.max(1, full.size() / DocumentLayer.AGREE_LINES);
        List<Integer> pick = new ArrayList<>();
        for (int i = 0; i < full.size() && pick.size() < DocumentLayer.AGREE_LINES; i += step) {
            pick.add(full.get(i));
        }
        if (pick.isEmpty()) {
            return true;
        }
        Recognition.Groups groups = recognition.boxGroups(gray, pick(boxes, pick));
        List<Boolean> agree = new ArrayList<>(pick.size());
        for (int j = 0; j < pick.size(); j++) {
            agree.add(!(DocumentLayer.latinShare(rec[pick.get(j)].text())
                >= DocumentLayer.LATIN_CODES && !"base".equals(groups.names()[j])
                && groups.sure()[j]));
        }
        return DocumentLayer.layerAgrees(agree);
    }

    /**
     * Layer lines that hold math, by the classifier on their crops, read by the math reader
     * instead: the layer has the symbols but not the LaTeX. A line keeps its place in the
     * content order, its first character's.
     */
    private void readMath(GrayImage gray, float[][] boxes, Reading[] rec) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < rec.length; i++) {
            if (rec[i] != null && !rec[i].tokens().isEmpty()
                    && rec[i].tokens().getFirst().fromLayer()) {
                idx.add(i);
            }
        }
        if (idx.isEmpty()) {
            return;
        }
        List<InkMap> crops = LineCrops.cropLines(gray, pick(boxes, idx), models.height, 1, null);
        boolean[] flags = math.flags(crops);
        List<Integer> lines = new ArrayList<>();
        for (int j = 0; j < idx.size(); j++) {
            if (flags[j]) {
                lines.add(idx.get(j));
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        List<MathReader.Reading> read = math.read(gray, pick(boxes, lines));
        for (int j = 0; j < lines.size(); j++) {
            MathReader.Reading m = read.get(j);
            // A reading without math, or not confident, leaves the layer's text.
            if (m.text().contains("$") && m.conf() >= MathReader.MATH_MIN_CONF) {
                int i = lines.get(j);
                int first = rec[i].tokens().stream().mapToInt(Token::index).min().orElseThrow();
                double xc = (double) (boxes[i][0] + boxes[i][2]) / 2;
                rec[i] = new Reading(m.text(), 1.0, List.of(Token.ofLayer(m.text(), xc, first)));
            }
        }
    }

    /**
     * Pixels per point for a page: the set resolution, lowered for a page so large that its
     * render would pass {@link DocumentLayer#MAX_PAGE_PIXELS}.
     */
    private double pageScale(PdfPage page) {
        return DocumentLayer.pageScale(page.width(), page.height(), options.dpi());
    }

    /**
     * Whether the recognizer reads the script of these runs: it knows at least
     * {@link DocumentLayer#READABLE_MIN} of their letters, a Hangul syllable by its jamo.
     * Indra's {@code readable}.
     */
    private boolean readable(List<TextLine> runs) {
        List<Map<String, Integer>> vocabs = new ArrayList<>();
        vocabs.add(models.base.vocab().index());
        for (OcrModels.Recognizer group : models.groups.values()) {
            vocabs.add(group.vocab().index());
        }
        int letters = 0;
        int known = 0;
        for (TextLine run : runs) {
            for (int cp : run.text().codePoints().toArray()) {
                if (PythonText.isAlpha(cp)) {
                    letters++;
                    known += knows(vocabs, Character.toString(cp)) ? 1 : 0;
                }
            }
        }
        return letters == 0 || known >= DocumentLayer.READABLE_MIN * letters;
    }

    private static boolean knows(List<Map<String, Integer>> vocabs, String c) {
        String jamo = Normalizer.normalize(c, Normalizer.Form.NFD);
        for (Map<String, Integer> v : vocabs) {
            if (v.containsKey(c)
                    || jamo.codePoints().allMatch(j -> v.containsKey(Character.toString(j)))) {
                return true;
            }
        }
        return false;
    }

    private static List<Box> asBoxes(float[][] boxes) {
        List<Box> out = new ArrayList<>(boxes.length);
        for (float[] b : boxes) {
            out.add(asBox(b));
        }
        return out;
    }

    private static Box asBox(float[] b) {
        return new Box(b[0], b[1], b[2], b[3]);
    }

    private static float[][] pick(float[][] boxes, List<Integer> rows) {
        float[][] out = new float[rows.size()][];
        for (int j = 0; j < rows.size(); j++) {
            out[j] = boxes[rows.get(j)];
        }
        return out;
    }

    @Override
    public void close() {
        if (math != null) {
            math.close();
        }
        models.close();
    }

    /** Draws a page in gray at a scale: {@link PageRenderer#render}. */
    @FunctionalInterface
    interface Renderer {

        /**
         * @param page  The page, with the rotation to draw it at
         * @param scale Pixels per point
         * @return The image
         * @throws IOException if the page cannot be drawn
         */
        GrayImage render(PdfPage page, double scale) throws IOException;
    }

    /**
     * What the text layer gave.
     *
     * @param page The page from its layer, or null for OCR
     * @param turn Whether OCR may turn the page
     */
    private record Layer(PageText page, boolean turn) {
    }
}
