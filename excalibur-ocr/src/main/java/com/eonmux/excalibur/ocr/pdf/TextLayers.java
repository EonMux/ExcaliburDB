// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import com.eonmux.excalibur.ocr.image.GrayImage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Text-layer runs of a PDF page and the checks that decide whether to trust them: Indra's
 * {@code textlayer} module.
 *
 * <p>A digital PDF already holds its text, and when the layer is sound reading it is faster than
 * OCR and free of recognition errors. A layer is not trusted when it is mostly invisible text (an
 * OCR layer over a scan), when its characters show a broken font map, when right-to-left letters
 * are stored in visual order, or when too many runs have no ink under them on the rendered
 * page.</p>
 *
 * <p>Coordinates are PDF points in the frame that pdfium renders (crop box and page rotation
 * applied), with a top-left origin.</p>
 */
public final class TextLayers {

    /** Most invisible text objects a trusted layer has: {@code MAX_INVISIBLE_FRAC}. */
    public static final double MAX_INVISIBLE_FRAC = 0.05;
    /** Most bad characters a trusted layer has: {@code MAX_BAD_CHAR_FRAC}. */
    public static final double MAX_BAD_CHAR_FRAC = 0.005;
    /** Most runs without ink a trusted layer has: {@code MAX_NO_INK_LINE_FRAC}. */
    public static final double MAX_NO_INK_LINE_FRAC = 0.03;
    /**
     * Most right-to-left letter pairs that step left to right: {@code MAX_VISUAL_RTL_FRAC}. A
     * layer in logical order steps right to left (0 to 1%); one in visual order steps left to
     * right (more than half).
     */
    public static final double MAX_VISUAL_RTL_FRAC = 0.25;
    /** Fewest right-to-left letter pairs for the visual-order test: {@code MIN_RTL_PAIRS}. */
    public static final int MIN_RTL_PAIRS = 20;
    /** The reason for a layer in visual right-to-left order: {@code VISUAL_RTL}. */
    public static final String VISUAL_RTL = "right-to-left text in visual order";
    /** The reason for a layer whose codes are not text: {@code BROKEN_FONT_MAP}. */
    public static final String BROKEN_FONT_MAP = "broken font map";
    /** The reason for a page with no upright text runs. */
    public static final String NO_TEXT_LAYER = "no text layer";
    /** The gray level below which a pixel counts as ink: {@code DARK}. */
    public static final int DARK = 160;

    private static final double QUARTER_TOLERANCE = 0.01;

    private TextLayers() {
        throw new AssertionError("Static methods only");
    }

    /**
     * A character from pdfium's UTF-16 units: Indra's {@code code_point}.
     *
     * @param codePoint The character
     * @param usedNext  Whether it took the next unit as the low half of a surrogate pair
     */
    public record CodePoint(int codePoint, boolean usedNext) {
    }

    /**
     * Right-to-left letter pairs inside runs: Indra's {@code visual_rtl_frac} result.
     *
     * @param pairs Neighbouring right-to-left letters inside a run
     * @param share The share of them that step left to right on the page
     */
    public record RtlPairs(int pairs, double share) {
    }

    /**
     * Characters that show a broken font map: {@code bad_char_count}.
     *
     * @param text Any text
     * @return The number of replacement, private-use, surrogate, unassigned and control
     *         characters other than tab
     */
    public static int badCharCount(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return (int) text.codePoints().filter(PyChars::isBad).count();
    }

    /**
     * Quarter turns (counter-clockwise, as {@code np.rot90}) that a baseline running in direction
     * {@code (dx, dy)} (rendered frame, y down) is turned from left to right: {@code _quarter}.
     *
     * @param dx Direction x
     * @param dy Direction y
     * @return 0 to 3, or null when the direction is not within 0.01 quarter turns of one
     */
    public static Integer quarter(double dx, double dy) {
        double q = Math.atan2(dy, dx) / (Math.PI / 2);
        double k = Math.rint(q);
        return Math.abs(q - k) < QUARTER_TOLERANCE ? Math.floorMod((int) k, 4) : null;
    }

    /**
     * A rendered-frame point in the frame of the page turned upright again
     * ({@code np.rot90(gray, -turn)}): {@code _turn_frame}.
     *
     * @param px   Point x
     * @param py   Point y
     * @param w    Page width
     * @param h    Page height
     * @param turn Quarter turns
     * @return {@code [x, y]} in the turned frame
     */
    public static double[] turnFrame(double px, double py, double w, double h, int turn) {
        return switch (turn) {
            case 1 -> new double[] {h - py, px};
            case 2 -> new double[] {w - px, h - py};
            case 3 -> new double[] {py, w - px};
            default -> new double[] {px, py};
        };
    }

    /**
     * Whether a page's upright characters run top to bottom (vertical writing): most steps
     * between neighbouring characters go down, not right. {@code vertical_layout}.
     *
     * @param stats A layer's statistics
     * @return True for vertical writing
     */
    public static boolean verticalLayout(TextStats stats) {
        List<Integer> steps = stats.stepCounts();
        int down = steps.get(0);
        int right = steps.get(1);
        return down >= 30 && down > 2 * right;
    }

    /**
     * The quarter turn that holds most of the page's characters (a table set sideways on a
     * portrait page), or 0: {@code page_turn}.
     *
     * @param stats A layer's statistics
     * @return 0 to 3
     */
    public static int pageTurn(TextStats stats) {
        List<Integer> q = stats.quarterCounts();
        int k = 1;
        for (int i = 2; i < 4; i++) {
            if (q.get(i) > q.get(k)) {
                k = i;
            }
        }
        return q.get(k) > 0.5 * Math.max(stats.charCount(), 1) ? k : 0;
    }

    /**
     * The character of pdfium's UTF-16 unit {@code u}, given the next unit {@code u2}: a
     * character above U+FFFF comes as two surrogates, and a lone surrogate becomes U+FFFD.
     * {@code code_point}. A unit outside Unicode, where Python's {@code chr} would fail, also
     * becomes U+FFFD.
     *
     * @param u  A unit
     * @param u2 The next unit, or 0
     * @return The character and whether it used {@code u2}
     */
    public static CodePoint codePoint(int u, int u2) {
        if (u >= 0xD800 && u <= 0xDBFF && u2 >= 0xDC00 && u2 <= 0xDFFF) {
            return new CodePoint(0x10000 + ((u - 0xD800) << 10) + (u2 - 0xDC00), true);
        }
        if ((u >= 0xD800 && u <= 0xDFFF) || u < 0 || u > Character.MAX_CODE_POINT) {
            return new CodePoint(0xFFFD, false);
        }
        return new CodePoint(u, false);
    }

    /**
     * Text runs of a page that read left to right once it is turned upright by {@code turn}, in
     * content order, with boxes in the frame turned by {@code frame}: {@code extract_runs}.
     *
     * @param page  The page
     * @param turn  Quarter turns, 0 to 3
     * @param frame Quarter turns of the frame, 0 to 3 (a vertical page is read with turn 0 in
     *              frame 3)
     * @return The runs, and statistics as {@code extract_runs} returns them
     * @throws IOException if the page content cannot be read
     */
    public static RunsAndStats extractRuns(PdfPage page, int turn, int frame) throws IOException {
        requireQuarter(turn, "turn");
        requireQuarter(frame, "frame");
        RunExtractor.Extraction e = RunExtractor.extract(page, TextPage.read(page), turn, frame);
        return new RunsAndStats(e.runs(), e.stats());
    }

    /**
     * The result of {@link #extractRuns}.
     *
     * @param runs  The runs
     * @param stats Their statistics
     */
    public record RunsAndStats(List<TextLine> runs, TextStats stats) {
        public RunsAndStats {
            runs = List.copyOf(runs);
        }
    }

    /**
     * Neighbouring right-to-left letters inside runs and the share of them that step left to
     * right: {@code visual_rtl_frac}.
     *
     * @param runs Runs
     * @return The pair count and share
     */
    public static RtlPairs visualRtlFrac(List<TextLine> runs) {
        int pairs = 0;
        int right = 0;
        for (TextLine r : runs) {
            List<RunChar> cs = r.chars();
            for (int i = 0; i + 1 < cs.size(); i++) {
                RunChar a = cs.get(i);
                RunChar b = cs.get(i + 1);
                if (PyChars.isRtl(a.codePoint()) && PyChars.isRtl(b.codePoint())) {
                    pairs++;
                    if (b.box().x0() + b.box().x1() > a.box().x0() + a.box().x1()) {
                        right++;
                    }
                }
            }
        }
        return new RtlPairs(pairs, (double) right / Math.max(pairs, 1));
    }

    /**
     * Share of a page's text objects drawn invisibly (an OCR layer), counting objects inside
     * forms to three levels deep: {@code invisible_text_frac}.
     *
     * @param page The page
     * @return The share, 0 for a page without text objects
     * @throws IOException if the page content cannot be read
     */
    public static double invisibleTextFrac(PdfPage page) throws IOException {
        return invisibleFrac(TextPage.read(page));
    }

    private static double invisibleFrac(TextPage text) {
        return text.textObjects() == 0 ? 0.0
            : (double) text.invisibleObjects() / text.textObjects();
    }

    /**
     * Share of runs whose box holds no dark pixel in {@code gray}: {@code no_ink_frac}.
     *
     * @param gray  A render of the page, in the runs' frame
     * @param runs  Runs
     * @param scale Pixels per point of the render
     * @return The share
     */
    public static double noInkFrac(GrayImage gray, List<TextLine> runs, double scale) {
        int empty = 0;
        for (TextLine ln : runs) {
            PageBox b = ln.bbox();
            int r0 = sliceStart(b.y0() * scale, gray.height());
            int r1 = sliceStop(b.y1() * scale, gray.height());
            int c0 = sliceStart(b.x0() * scale, gray.width());
            int c1 = sliceStop(b.x1() * scale, gray.width());
            if (!hasInk(gray, r0, r1, c0, c1)) {
                empty++;
            }
        }
        return (double) empty / Math.max(runs.size(), 1);
    }

    /** {@code max(0, int(v))} as a slice start of an axis of {@code length}. */
    private static int sliceStart(double v, int length) {
        return (int) Math.min(Math.max(0L, (long) v), length);
    }

    /**
     * {@code min(length, int(v) + 1)} as a slice stop: a negative stop counts from the end, as
     * Python slices do.
     */
    private static int sliceStop(double v, int length) {
        long stop = Math.min(length, (long) v + 1);
        return (int) (stop < 0 ? Math.max(stop + length, 0L) : stop);
    }

    private static boolean hasInk(GrayImage gray, int r0, int r1, int c0, int c1) {
        for (int y = r0; y < r1; y++) {
            for (int x = c0; x < c1; x++) {
                if (gray.get(x, y) < DARK) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code read_text_layer(page, gray, scale)}: the page read as it stands.
     *
     * @param page  The page
     * @param gray  A render of it at {@code scale}
     * @param scale Pixels per point of the render
     * @return The layer
     * @throws IOException if the page content cannot be read
     */
    public static TextLayer read(PdfPage page, GrayImage gray, double scale) throws IOException {
        return read(page, gray, scale, 0, 0);
    }

    /**
     * {@code read_text_layer(page, gray, scale, turn)}: the frame is the turn.
     *
     * @param page  The page
     * @param gray  A render of it at {@code scale}, turned by {@code rot90(gray, -turn)}
     * @param scale Pixels per point of the render
     * @param turn  Quarter turns that make the page upright
     * @return The layer
     * @throws IOException if the page content cannot be read
     */
    public static TextLayer read(PdfPage page, GrayImage gray, double scale, int turn)
        throws IOException {
        return read(page, gray, scale, turn, turn);
    }

    /**
     * A page's text-layer runs and the verdict on them: {@code read_text_layer}.
     *
     * @param page  The page, with the rotation it is read at
     * @param gray  A render of it at {@code scale}, turned upright by {@code turn} like the runs
     *              ({@code rot90(gray, -turn)}); a low resolution is enough for the ink check
     * @param scale Pixels per point of the render
     * @param turn  Quarter turns that make the page upright
     * @param frame Quarter turns of the frame the runs are given in
     * @return The layer
     * @throws IOException if the page content cannot be read
     */
    public static TextLayer read(PdfPage page, GrayImage gray, double scale, int turn, int frame)
        throws IOException {
        Objects.requireNonNull(page, "page must not be null");
        Objects.requireNonNull(gray, "gray must not be null");
        requireQuarter(turn, "turn");
        requireQuarter(frame, "frame");
        double w = page.width();
        double h = page.height();
        double width = frame % 2 == 1 ? h : w;
        double height = frame % 2 == 1 ? w : h;
        TextPage text = TextPage.read(page);
        double inv = invisibleFrac(text);
        if (inv > MAX_INVISIBLE_FRAC) {
            String reason = "invisible text " + PyFormat.percent(inv) + " (OCR layer)";
            return new TextLayer(List.of(), width, height, false, reason, TextStats.NONE,
                List.of());
        }
        RunExtractor.Extraction e = RunExtractor.extract(page, text, turn, frame);
        TextStats st = e.stats().withTurnAndFrame(turn, frame);
        List<TextLine> rotated = new ArrayList<>();
        List<TextLine> runs = new ArrayList<>();
        for (TextLine r : e.runs()) {
            (r.quarter() != 0 ? rotated : runs).add(r);
        }
        if (runs.isEmpty()) {
            return new TextLayer(runs, width, height, false, NO_TEXT_LAYER, st, rotated);
        }
        if (st.bad() > MAX_BAD_CHAR_FRAC * Math.max(st.chars(), 1)) {
            return new TextLayer(runs, width, height, false, BROKEN_FONT_MAP, st, rotated);
        }
        RtlPairs rtl = frame == turn ? visualRtlFrac(runs) : new RtlPairs(0, 0.0);
        if (rtl.pairs() >= MIN_RTL_PAIRS && rtl.share() > MAX_VISUAL_RTL_FRAC) {
            return new TextLayer(runs, width, height, false, VISUAL_RTL,
                st.withVisualRtl(PyFormat.roundHalfEven(rtl.share(), 3)), rotated);
        }
        double empty = noInkFrac(gray, runs, scale);
        st = st.withNoInk(PyFormat.roundHalfEven(empty, 4));
        if (empty > MAX_NO_INK_LINE_FRAC) {
            return new TextLayer(runs, width, height, false,
                PyFormat.percent(empty) + " of text runs have no ink", st, rotated);
        }
        return new TextLayer(runs, width, height, true, "", st, rotated);
    }

    private static void requireQuarter(int value, String name) {
        if (value < 0 || value > 3) {
            throw new IllegalArgumentException(name + " must be 0 to 3, got " + value);
        }
    }
}
