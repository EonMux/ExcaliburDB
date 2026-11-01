// Ported from Indra OCR 1.1.2, document.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import com.eonmux.excalibur.ocr.image.Box;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The parts of Indra's {@code document} module that join a PDF text layer to the page image:
 * render scales, the checks that a layer is the page's own text, and the assignment of text-layer
 * characters to detected lines.
 */
public final class DocumentLayer {

    /** Resolution of the render for the text-layer ink checks: {@code CHECK_DPI}. */
    public static final double CHECK_DPI = 72.0;
    /** Pixels above which a page renders at a lower resolution: {@code MAX_PAGE_PIXELS}. */
    public static final double MAX_PAGE_PIXELS = 100e6;
    /** Share of the layer's letters the recognizer must know: {@code READABLE_MIN}. */
    public static final double READABLE_MIN = 0.9;
    /** Text-layer lines whose script is checked against the image: {@code AGREE_LINES}. */
    public static final int AGREE_LINES = 6;
    /** Share of agreeing lines under which a layer is not the page's: {@code AGREE_SHARE}. */
    public static final double AGREE_SHARE = 0.25;
    /** Latin share of a line whose image is another script (legacy codes): {@code LATIN_CODES}. */
    public static final double LATIN_CODES = 0.9;
    /** The note on a page sent to OCR by the agreement check: {@code NOT_THE_PAGE}. */
    public static final String NOT_THE_PAGE = "text layer does not match the page";

    private static final float MIN_AREA = 1e-6f;

    private DocumentLayer() {
        throw new AssertionError("Static methods only");
    }

    /**
     * One character placed in a detected line: an entry of {@code assign_runs}'s tokens.
     *
     * @param codePoint The character
     * @param x         Its centre x in pixels (a space takes the character's before it)
     * @param run       The content index of the run it came from
     */
    public record Token(int codePoint, double x, int run) {
    }

    /**
     * The result of {@code assign_runs}.
     *
     * @param lines Tokens per detected line, in the boxes' order
     * @param lost  Characters whose centre lies in no box
     */
    public record Assignment(List<List<Token>> lines, int lost) {
        public Assignment {
            lines = lines.stream().map(List::copyOf).toList();
        }
    }

    /**
     * Pixels per point for a page: the dpi, lowered for a page so large that its render would
     * pass {@link #MAX_PAGE_PIXELS}. {@code Converter.page_scale}.
     *
     * @param width  Page width in points
     * @param height Page height in points
     * @param dpi    The resolution asked for
     * @return The scale
     */
    public static double pageScale(double width, double height, double dpi) {
        return Math.min(dpi / 72.0, Math.sqrt(MAX_PAGE_PIXELS / Math.max(width * height, 1.0)));
    }

    /**
     * Share of the letters in a text that are basic Latin: {@code latin_share}.
     *
     * @param text Any text
     * @return The share, 0 for a text without letters
     */
    public static double latinShare(String text) {
        int letters = 0;
        int ascii = 0;
        for (int cp : text.codePoints().toArray()) {
            if (Character.isLetter(cp)) {
                letters++;
                ascii += cp < 0x80 ? 1 : 0;
            }
        }
        return (double) ascii / Math.max(letters, 1);
    }

    /**
     * Whether a text layer matches its page: at least {@link #AGREE_SHARE} of the checked lines
     * agree, or none were checked. {@code layer_agrees}.
     *
     * @param agree Per checked line, whether it agrees
     * @return The verdict
     */
    public static boolean layerAgrees(List<Boolean> agree) {
        long yes = agree.stream().filter(Boolean::booleanValue).count();
        return agree.isEmpty() || yes >= AGREE_SHARE * agree.size();
    }

    /**
     * Unicode bidi base direction of tokens: most strong characters are right to left.
     * {@code right_to_left}.
     *
     * @param tokens Tokens
     * @return True when right-to-left characters outnumber left-to-right ones
     */
    public static boolean rightToLeft(List<Token> tokens) {
        int rtl = 0;
        int ltr = 0;
        for (Token t : tokens) {
            if (PyChars.isRtl(t.codePoint())) {
                rtl++;
            } else if (Character.getDirectionality(t.codePoint())
                == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                ltr++;
            }
        }
        return rtl > ltr;
    }

    /**
     * Whether more than half of a box lies inside one of the boxes: {@code _covered}, in single
     * precision as Indra's {@code float32} arrays.
     *
     * @param box   The box
     * @param boxes The boxes
     * @return True when covered
     */
    public static boolean covered(Box box, List<Box> boxes) {
        return covered(box, boxes, 0.5f);
    }

    /**
     * Whether more than {@code frac} of a box lies inside one of the boxes: {@code _covered}.
     *
     * @param box   The box
     * @param boxes The boxes
     * @param frac  The share
     * @return True when covered
     */
    public static boolean covered(Box box, List<Box> boxes, float frac) {
        float area = Math.max(box.width() * box.height(), MIN_AREA);
        for (Box b : boxes) {
            float ix = Math.max(Math.min(box.x1(), b.x1()) - Math.max(box.x0(), b.x0()), 0f);
            float iy = Math.max(Math.min(box.y1(), b.y1()) - Math.max(box.y0(), b.y0()), 0f);
            if (ix * iy / area > frac) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places text-layer characters in detected lines: {@code assign_runs}.
     *
     * <p>Each character goes to the smallest detected box that holds its centre (a space follows
     * the character before it), so a run that spans two table cells splits between them. Inside
     * a line each run's part keeps the PDF order, and the parts are ordered by x, right to left
     * in a right-to-left line, with a space between parts.</p>
     *
     * @param runs  Text-layer runs, in content order
     * @param boxes Detected line boxes, in pixels
     * @param k     Pixels per point
     * @return Tokens per line and the characters that fell outside every box
     */
    public static Assignment assignRuns(List<TextLine> runs, List<Box> boxes, double k) {
        Objects.requireNonNull(runs, "runs must not be null");
        Objects.requireNonNull(boxes, "boxes must not be null");
        if (boxes.isEmpty()) {
            int lost = 0;
            for (TextLine r : runs) {
                for (RunChar c : r.chars()) {
                    lost += PyChars.isSpace(c.codePoint()) ? 0 : 1;
                }
            }
            return new Assignment(List.of(), lost);
        }
        float[] area = new float[boxes.size()];
        List<Map<Integer, List<Token>>> parts = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            area[i] = boxes.get(i).width() * boxes.get(i).height();
            parts.add(new LinkedHashMap<>());
        }
        int lost = 0;
        for (int pos = 0; pos < runs.size(); pos++) {
            TextLine run = runs.get(pos);
            int idx = run.seq() != null ? run.seq() : pos;
            int j = -1;
            for (RunChar c : run.chars()) {
                if (!PyChars.isSpace(c.codePoint())) {
                    PageBox b = c.box();
                    double cx = (b.x0() + b.x1()) / 2 * k;
                    double cy = (b.y0() + b.y1()) / 2 * k;
                    j = smallestHolding(boxes, area, cx, cy);
                    if (j < 0) {
                        lost++;
                        continue;
                    }
                    parts.get(j).computeIfAbsent(idx, x -> new ArrayList<>())
                        .add(new Token(c.codePoint(), cx, idx));
                } else if (j >= 0) {
                    List<Token> part = parts.get(j).get(idx);
                    part.add(new Token(c.codePoint(), part.getLast().x(), idx));
                }
            }
        }
        List<List<Token>> lines = new ArrayList<>();
        for (Map<Integer, List<Token>> line : parts) {
            lines.add(order(line));
        }
        return new Assignment(lines, lost);
    }

    /**
     * The first box of least area that holds a point, or -1. numpy compares its float32 boxes
     * with a Python float in float32, so the point is rounded to float32 first.
     */
    private static int smallestHolding(List<Box> boxes, float[] area, double px, double py) {
        float cx = (float) px;
        float cy = (float) py;
        int best = -1;
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            if (b.x0() <= cx && cx <= b.x1() && b.y0() <= cy && cy <= b.y1()
                && (best < 0 || area[i] < area[best])) {
                best = i;
            }
        }
        return best;
    }

    /** One line's tokens: its run parts ordered by x, joined by spaces. */
    private static List<Token> order(Map<Integer, List<Token>> line) {
        List<Token> all = new ArrayList<>();
        line.values().forEach(all::addAll);
        boolean rtl = rightToLeft(all);
        List<Integer> keys = new ArrayList<>(line.keySet());
        if (rtl) {
            keys.sort((a, b) -> compare(-maxX(line.get(a)), -maxX(line.get(b))));
        } else {
            keys.sort((a, b) -> compare(minX(line.get(a)), minX(line.get(b))));
        }
        List<Token> tk = new ArrayList<>();
        for (int idx : keys) {
            if (!tk.isEmpty()) {
                tk.add(new Token(' ', tk.getLast().x(), idx));
            }
            tk.addAll(line.get(idx));
        }
        return tk;
    }

    /** Python's ordering of floats: -0.0 equals 0.0. */
    private static int compare(double a, double b) {
        return a < b ? -1 : a > b ? 1 : 0;
    }

    private static double minX(List<Token> part) {
        double m = Double.POSITIVE_INFINITY;
        for (Token t : part) {
            m = Math.min(m, t.x());
        }
        return m;
    }

    private static double maxX(List<Token> part) {
        double m = Double.NEGATIVE_INFINITY;
        for (Token t : part) {
            m = Math.max(m, t.x());
        }
        return m;
    }
}
