// Ported from Indra OCR 1.1.2, rtl.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Right-to-left text for a recognizer that reads every line left to right.
 *
 * <p>A CTC recognizer emits characters in the order it sees them, left to right, so it reads a
 * line holding Arabic or Hebrew in visual order. {@link #flip} turns visual order into logical
 * order (how text is stored and typed), and the same call turns logical order back into visual
 * order:</p>
 * <ul>
 *   <li>A right-to-left line (more RTL letters than LTR letters) reverses the order of its
 *       units. A unit is a run of LTR text - Latin words and digits, with the spaces and
 *       punctuation between them - which keeps its own order, or one other cluster.</li>
 *   <li>A left-to-right line keeps its order, and each run of RTL text reverses.</li>
 * </ul>
 *
 * <p>A cluster is a base character with the combining marks after it (Arabic harakat, Hebrew
 * niqqud), so marks stay with their base. A mirrored character (a bracket) at right-to-left
 * level becomes its mirror image, as a renderer draws it. This is a small subset of the Unicode
 * bidi algorithm (UAX #9) without embeddings or isolates, resolving neutrals by runs; it is its
 * own inverse, so labels and decoding agree.</p>
 *
 * <p>Text is handled by code point, as Python's {@code str} is, so supplementary characters are
 * single characters here too. The training-label helpers of {@code rtl.py}
 * ({@code visual_label}, {@code label_from_visual}, {@code brackets_as_shapes}) are not used by
 * inference and are not ported.</p>
 */
public final class Rtl {

    /** Port of {@code _MIRROR}: each mirrored character and its mirror image. */
    private static final Map<Integer, Integer> MIRROR = mirrors("()[]{}<>«»‹›", ")(][}{><»«›‹");

    /** Port of {@code _PRESENTATION}: Hebrew and Arabic presentation form blocks. */
    private static final int[][] PRESENTATION = {{0xFB1D, 0xFDFF}, {0xFE70, 0xFEFF}};

    /** Port of {@code _TATWEEL}: the Arabic stretching character, which carries no letter. */
    private static final int TATWEEL = 0x0640;

    /** A cluster's direction: strong right-to-left, strong left-to-right, or neutral. */
    private enum Kind { R, L, N }

    private Rtl() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Whether a code point is a right-to-left letter: bidi class {@code R} or {@code AL}. Port
     * of {@code is_rtl}.
     *
     * @param codePoint The code point
     * @return Whether it is RTL
     */
    public static boolean isRtl(int codePoint) {
        String bidi = UnicodeData.bidirectional(codePoint);
        return bidi.equals("R") || bidi.equals("AL");
    }

    /**
     * Whether a text holds any right-to-left letter. Port of {@code has_rtl}.
     *
     * @param text The text
     * @return Whether any code point is RTL
     */
    public static boolean hasRtl(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return text.codePoints().anyMatch(Rtl::isRtl);
    }

    /**
     * Whether a line reads right to left: it holds more RTL letters than LTR letters (bidi
     * class {@code L}). The counts do not depend on the order, so logical and visual text agree.
     * Port of {@code rtl_base}.
     *
     * @param text The line
     * @return Whether its base direction is right to left
     */
    public static boolean rtlBase(String text) {
        Objects.requireNonNull(text, "text must not be null");
        long rtl = text.codePoints().filter(Rtl::isRtl).count();
        long ltr = text.codePoints().filter(c -> UnicodeData.bidirectional(c).equals("L")).count();
        return rtl > ltr;
    }

    /**
     * Presentation forms to base letters (NFKC of each code point in those blocks only), without
     * tatweel and without a combining mark that has no base character before it. Port of
     * {@code normalize}.
     *
     * @param text The text
     * @return The normalized text
     */
    public static String normalize(String text) {
        Objects.requireNonNull(text, "text must not be null");
        StringBuilder out = new StringBuilder(text.length());
        boolean any = false;
        for (int c : text.codePoints().toArray()) {
            if (isPresentationForm(c)) {
                out.append(Normalizer.normalize(Character.toString(c), Normalizer.Form.NFKC));
                any = true;
            } else if (c != TATWEEL && (any || !UnicodeData.isNonSpacingMark(c))) {
                out.appendCodePoint(c);
                any = true;
            }
        }
        return out.toString();
    }

    /**
     * Splits a text into clusters: each base character with the non-spacing and enclosing marks
     * after it. A mark at the start of the text is a cluster of its own. Port of
     * {@code clusters}.
     *
     * @param text The text
     * @return The clusters, in order; joined they are the text
     */
    public static List<String> clusters(String text) {
        Objects.requireNonNull(text, "text must not be null");
        List<StringBuilder> out = new ArrayList<>();
        for (int c : text.codePoints().toArray()) {
            if (!out.isEmpty() && UnicodeData.isNonSpacingMark(c)) {
                out.getLast().appendCodePoint(c);
            } else {
                out.add(new StringBuilder().appendCodePoint(c));
            }
        }
        return out.stream().map(StringBuilder::toString).toList();
    }

    /**
     * Logical order to visual order, or visual order back to logical order, with brackets at
     * right-to-left level mirrored and the base direction decided by {@link #rtlBase}. Port of
     * {@code flip(text)}.
     *
     * @param text The line
     * @return The line in the other order
     */
    public static String flip(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return flip(text, true, rtlBase(text));
    }

    /**
     * {@link #flip(String)} with mirroring chosen. Port of {@code flip(text, mirror)}.
     *
     * @param text   The line
     * @param mirror Whether brackets at right-to-left level change to their mirror image
     * @return The line in the other order
     */
    public static String flip(String text, boolean mirror) {
        Objects.requireNonNull(text, "text must not be null");
        return flip(text, mirror, rtlBase(text));
    }

    /**
     * {@link #flip(String)} with the base direction given, as a renderer lays out a line by its
     * paragraph's direction. Port of {@code flip(text, mirror, base_rtl)} with
     * {@code base_rtl} set; the pipeline passes {@code base_rtl=True} on a right-to-left page and
     * leaves it to {@link #rtlBase} otherwise.
     *
     * @param text    The line
     * @param mirror  Whether brackets at right-to-left level change to their mirror image
     * @param baseRtl Whether the line's base direction is right to left
     * @return The line in the other order; the line itself when it holds no RTL letter
     */
    public static String flip(String text, boolean mirror, boolean baseRtl) {
        Objects.requireNonNull(text, "text must not be null");
        if (!hasRtl(text)) {
            return text;
        }
        List<String> cls = clusters(text);
        List<Kind> kinds = cls.stream().map(Rtl::kind).toList();
        return baseRtl ? flipRtlBase(cls, kinds, mirror) : flipLtrBase(cls, kinds, mirror);
    }

    /** A right-to-left line: LTR runs kept whole, every other cluster alone, units reversed. */
    private static String flipRtlBase(List<String> cls, List<Kind> kinds, boolean mirror) {
        List<String> units = new ArrayList<>();
        List<int[]> spans = new ArrayList<>(runs(kinds, Kind.L, Kind.R));
        spans.add(new int[] {cls.size(), cls.size()});
        int i = 0;
        for (int[] span : spans) {
            for (int j = i; j < span[0]; j++) {
                units.add(mirror ? mirror(cls.get(j)) : cls.get(j));
            }
            if (span[0] < span[1]) {
                units.add(String.join("", cls.subList(span[0], span[1])));
            }
            i = span[1];
        }
        Collections.reverse(units);
        return String.join("", units);
    }

    /** A left-to-right line: each RTL run reversed in place. */
    private static String flipLtrBase(List<String> cls, List<Kind> kinds, boolean mirror) {
        List<String> out = new ArrayList<>(cls);
        for (int[] span : runs(kinds, Kind.R, Kind.L)) {
            for (int j = span[0]; j < span[1]; j++) {
                String c = cls.get(span[0] + span[1] - 1 - j);
                out.set(j, mirror ? mirror(c) : c);
            }
        }
        return String.join("", out);
    }

    /**
     * Spans {@code [start, end)} from a {@code strong} cluster to the last {@code strong} cluster
     * before a cluster of kind {@code stop} or the end. Port of {@code _runs}.
     */
    private static List<int[]> runs(List<Kind> kinds, Kind strong, Kind stop) {
        List<int[]> spans = new ArrayList<>();
        int start = -1;
        int last = -1;
        for (int i = 0; i <= kinds.size(); i++) {
            Kind k = i < kinds.size() ? kinds.get(i) : null;
            if (k == strong) {
                if (start < 0) {
                    start = i;
                }
                last = i;
            } else if (k == stop || k == null) {
                if (start >= 0) {
                    spans.add(new int[] {start, last + 1});
                }
                start = -1;
                last = -1;
            }
        }
        return spans;
    }

    /** Port of {@code _kind}: a cluster's direction, by its first code point. */
    private static Kind kind(String cluster) {
        int c = cluster.codePointAt(0);
        if (isRtl(c)) {
            return Kind.R;
        }
        return isLtr(c) ? Kind.L : Kind.N;
    }

    /**
     * Port of {@code _is_ltr}: a strong LTR character, or a digit (European or Arabic-Indic),
     * since digits read left to right inside RTL text.
     */
    private static boolean isLtr(int codePoint) {
        String bidi = UnicodeData.bidirectional(codePoint);
        return bidi.equals("L") || bidi.equals("EN") || bidi.equals("AN");
    }

    /** Port of {@code _mirror}: a cluster with its base character mirrored, marks kept. */
    private static String mirror(String cluster) {
        int c = cluster.codePointAt(0);
        int width = Character.charCount(c);
        return new StringBuilder().appendCodePoint(MIRROR.getOrDefault(c, c))
            .append(cluster, width, cluster.length()).toString();
    }

    private static boolean isPresentationForm(int codePoint) {
        for (int[] block : PRESENTATION) {
            if (codePoint >= block[0] && codePoint <= block[1]) {
                return true;
            }
        }
        return false;
    }

    private static Map<Integer, Integer> mirrors(String from, String to) {
        int[] a = from.codePoints().toArray();
        int[] b = to.codePoints().toArray();
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < a.length; i++) {
            map.put(a[i], b[i]);
        }
        return Map.copyOf(map);
    }
}
