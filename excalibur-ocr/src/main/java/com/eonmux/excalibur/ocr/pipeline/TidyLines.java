// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.text.PythonText;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lines that carry no text of their own: Indra's {@code tidy_lines}.
 *
 * <ul>
 *   <li>A line of leader characters only - form fill-in dots and rules, specks - is dropped: three
 *       or more of them, a box three or more times wider than tall, or any dot or underscore
 *       alone. A short lone dash stays: it can be an empty table cell.</li>
 *   <li>A lone list marker - a bullet, {@code 1.}, {@code (a)} - joins the line that starts
 *       within {@link #MARKER_GAP} line heights after it on the same row (on its left on a
 *       right-to-left page), so the item keeps its marker. On a left-to-right page the marker's
 *       tokens join too, so a table line still splits into cells at its gaps.</li>
 * </ul>
 *
 * <p>Box arithmetic is in float32, as numpy does it on Indra's float32 boxes.</p>
 */
final class TidyLines {

    /** Dot leaders and fill-in rules. */
    private static final Set<Integer> LEADER_CHARS = codePoints(".·…‥_-–—=");

    /** A lone short dash can be a table cell; other lone marks are not. */
    private static final Set<Integer> DASHES = codePoints("-–—");

    /** List markers; not dashes, as a "-" can be a table cell. */
    private static final Set<String> BULLETS = "•◦▪▫●○■□‣⁃∙*".codePoints()
        .mapToObj(Character::toString).collect(Collectors.toUnmodifiableSet());

    /** A numbered list marker: "1.", "12)", "a.", "B)", "(3)", "(b)", "(iv)". */
    private static final Pattern ENUMERATOR = Pattern.compile(
        "(\\p{Nd}{1,3}|[A-Za-z])[.)]|\\((\\p{Nd}{1,3}|[A-Za-z]|[ivxlcIVXLC]{1,5})\\)");

    /** A list marker and its item's text are at most this many line heights apart. */
    static final float MARKER_GAP = 2.5f;

    private TidyLines() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Drops leader lines and joins lone list markers to their items.
     *
     * @param boxes   Line boxes, {@code [x0, y0, x1, y1]} pixels; not modified
     * @param rec     What each line says
     * @param rtl     Whether the page reads right to left
     * @param spaceId The base vocabulary's space id, or null when it has none
     * @return The lines kept, in their order
     */
    static Lines tidy(float[][] boxes, List<Reading> rec, boolean rtl, Integer spaceId) {
        int n = rec.size();
        float[][] b = new float[n][];
        for (int i = 0; i < n; i++) {
            b[i] = boxes[i].clone();
        }
        List<Reading> lines = new ArrayList<>(rec);
        boolean[] keep = new boolean[n];
        float[] h = new float[n];
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            keep[i] = true;
            h[i] = b[i][3] - b[i][1];
            w[i] = b[i][2] - b[i][0];
        }
        for (int i = 0; i < n; i++) {
            String core = lines.get(i).text().replace(" ", "");
            Set<Integer> chars = codePoints(core);
            if (!core.isEmpty() && LEADER_CHARS.containsAll(chars)) {
                boolean wide = w[i] >= 3f * h[i];
                boolean dash = chars.stream().anyMatch(DASHES::contains);
                if (PythonText.length(core) >= 3 || wide || !dash) {
                    keep[i] = false;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            Reading marker = lines.get(i);
            String t = PythonText.strip(marker.text());
            if (!keep[i] || !(BULLETS.contains(t) || ENUMERATOR.matcher(t).matches())) {
                continue;
            }
            int best = item(b, h, lines, keep, i, rtl);
            if (best < 0) {
                continue;
            }
            Reading target = lines.get(best);
            List<Token> joined = joinTokens(marker.tokens(), target.tokens(),
                (double) (b[i][2] + b[best][0]) / 2, rtl, spaceId);
            lines.set(best, new Reading(t + " " + PythonText.strip(target.text()), target.conf(),
                joined));
            b[best] = new float[] {
                Math.min(b[i][0], b[best][0]), Math.min(b[i][1], b[best][1]),
                Math.max(b[i][2], b[best][2]), Math.max(b[i][3], b[best][3])};
            keep[i] = false;
        }
        List<float[]> keptBoxes = new ArrayList<>();
        List<Reading> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                keptBoxes.add(b[i]);
                kept.add(lines.get(i));
            }
        }
        return new Lines(keptBoxes.toArray(float[][]::new), kept);
    }

    /** The line a marker belongs to: the nearest start on its row within the gap, or -1. */
    private static int item(float[][] b, float[] h, List<Reading> lines, boolean[] keep, int i,
                            boolean rtl) {
        int best = -1;
        float bestGap = 0;
        for (int j = 0; j < lines.size(); j++) {
            if (j == i || !keep[j] || PythonText.strip(lines.get(j).text()).isEmpty()) {
                continue;
            }
            float ov = Math.min(b[i][3], b[j][3]) - Math.max(b[i][1], b[j][1]);
            if (ov < 0.5f * Math.min(h[i], h[j])) {
                continue;
            }
            float gap = rtl ? b[i][0] - b[j][2] : b[j][0] - b[i][2];
            if (-0.3f * h[i] <= gap && gap <= MARKER_GAP * Math.max(h[i], h[j])
                    && (best < 0 || gap < bestGap)) {
                best = j;
                bestGap = gap;
            }
        }
        return best;
    }

    /**
     * The marker's tokens, a space, then the item's: only on a left-to-right page, and only when
     * both are the same kind of token, so a table line still splits at its gaps.
     */
    private static List<Token> joinTokens(List<Token> marker, List<Token> item, double x,
                                          boolean rtl, Integer spaceId) {
        boolean chars = !item.isEmpty() && item.getFirst().isText();
        if (rtl || marker.isEmpty() || item.isEmpty() || (!chars && spaceId == null)
                || marker.getFirst().isText() != chars) {
            return item;
        }
        Token last = marker.getLast();
        Token space;
        if (chars) {
            space = last.fromLayer() ? Token.ofLayer(" ", x, last.index()) : Token.ofText(" ", x);
        } else {
            space = Token.ofId(spaceId, x);
        }
        List<Token> joined = new ArrayList<>(marker.size() + 1 + item.size());
        joined.addAll(marker);
        joined.add(space);
        joined.addAll(item);
        return joined;
    }

    private static Set<Integer> codePoints(String text) {
        return text.codePoints().boxed().collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Line boxes and what each says, in step.
     *
     * @param boxes    {@code [x0, y0, x1, y1]} per line, pixels
     * @param readings What each line says
     */
    record Lines(float[][] boxes, List<Reading> readings) {
    }
}
