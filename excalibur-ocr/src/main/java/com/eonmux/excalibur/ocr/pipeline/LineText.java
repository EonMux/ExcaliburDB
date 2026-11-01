// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.text.PythonText;
import com.eonmux.excalibur.ocr.text.Rtl;
import com.eonmux.excalibur.ocr.text.Vocab;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A line's tokens as text, and where a line is cut: Indra's {@code OrtOCR._decode},
 * {@code split_line} and {@code gutter_cuts}, for one page.
 *
 * <p>Positions are doubles, as Python's floats are; where Indra compares them with its float32
 * boxes, numpy makes the comparison in float32, and so does this.</p>
 */
final class LineText {

    /** Cut a detected line at a wider gap, in line heights (the label rule outside tables). */
    static final double SPLIT_GAP = 3.0;

    /** Cut a table line into cells at a wider gap (the label rule in tables). */
    static final double CELL_GAP = 1.8;

    /** Cut at a smaller gap when other lines' edges line up with it: a column gutter. */
    static final double GUTTER_GAP = 1.6;

    /** How many other line edges must line up with a gap, within half a line height. */
    static final int GUTTER_EDGES = 3;

    private final Vocab vocab;
    private final Integer spaceId;
    private final boolean pageRtl;

    /**
     * @param vocab   The base recognizer's vocabulary
     * @param pageRtl Whether the page reads right to left
     */
    LineText(Vocab vocab, boolean pageRtl) {
        this.vocab = vocab;
        this.spaceId = vocab.index().get(" ");
        this.pageRtl = pageRtl;
    }

    /**
     * The base vocabulary's space id.
     *
     * @return It, or null when the vocabulary has none
     */
    Integer spaceId() {
        return spaceId;
    }

    /**
     * Whether a token is a space: the character, or the base vocabulary's space id.
     *
     * @param token The token
     * @return Whether it is a space
     */
    boolean isSpace(Token token) {
        return token.isText() ? token.text().equals(" ")
            : spaceId != null && token.id() == spaceId;
    }

    /**
     * The text of a line's tokens. Vocabulary ids are decoded; characters are joined with their
     * whitespace collapsed, and a group recognizer's - in visual order - are composed and, when
     * they hold right-to-left text, put in logical order by the page's direction.
     *
     * @param tokens The tokens, all of one kind
     * @return Their text
     */
    String decode(List<Token> tokens) {
        if (!tokens.isEmpty() && tokens.getFirst().isText()) {
            StringBuilder joined = new StringBuilder();
            tokens.forEach(t -> joined.append(t.text()));
            String text = PythonText.collapseSpaces(joined.toString());
            if (!tokens.getFirst().fromLayer()) {
                text = Normalizer.normalize(text, Normalizer.Form.NFC);
                if (Rtl.hasRtl(text)) {
                    text = pageRtl ? Rtl.flip(text, true, true) : Rtl.flip(text);
                }
            }
            return text;
        }
        return vocab.decode(tokens.stream().mapToInt(Token::id).toArray());
    }

    /**
     * Cuts a line where two neighbouring characters are more than {@code gap} line heights apart,
     * or across one of {@code at}: the detector merged two cells or columns.
     *
     * @param box    The line's box, {@code [x0, y0, x1, y1, ...]}
     * @param tokens Its tokens
     * @param gap    The widest gap left whole, in line heights
     * @param at     Further positions to cut across, or null for none
     * @return The pieces, one when nothing is cut
     */
    List<Piece> split(float[] box, List<Token> tokens, double gap, double[] at) {
        double h = lineHeight(box);
        double[] cuts = at == null ? new double[0] : at.clone();
        Arrays.sort(cuts);
        List<List<Token>> segments = new ArrayList<>();
        segments.add(new ArrayList<>());
        Double prev = null;
        for (Token token : tokens) {
            double x = token.x();
            if (!isSpace(token)) {
                if (prev != null && (x - prev > gap * h || crosses(cuts, prev, x))) {
                    segments.add(new ArrayList<>());
                }
                prev = x;
            }
            segments.getLast().add(token);
        }
        if (segments.size() == 1) {
            return List.of(new Piece(Arrays.copyOf(box, 4), decode(tokens), tokens));
        }
        List<Piece> pieces = new ArrayList<>();
        for (List<Token> segment : segments) {
            String text = PythonText.strip(decode(segment));
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            boolean any = false;
            for (Token token : segment) {
                if (!isSpace(token)) {
                    lo = Math.min(lo, token.x());
                    hi = Math.max(hi, token.x());
                    any = true;
                }
            }
            if (!text.isEmpty() && any) {
                float[] cut = {
                    (float) Math.max(box[0], lo - 0.35 * h), box[1],
                    (float) Math.min(box[2], hi + 0.35 * h), box[3]};
                pieces.add(new Piece(cut, text, segment));
            }
        }
        return pieces;
    }

    /**
     * Per line, the positions of gaps that sit on a column gutter: a gap over
     * {@link #GUTTER_GAP} line heights where at least {@link #GUTTER_EDGES} other lines end at
     * its left side or start at its right side.
     *
     * @param boxes    The lines' boxes
     * @param readings What each says
     * @return Per line, where to cut it
     */
    double[][] gutterCuts(float[][] boxes, List<Reading> readings) {
        double[][] cuts = new double[boxes.length][];
        for (int i = 0; i < boxes.length; i++) {
            double h = lineHeight(boxes[i]);
            double[] xs = readings.get(i).tokens().stream().filter(t -> !isSpace(t))
                .mapToDouble(Token::x).toArray();
            List<Double> mine = new ArrayList<>();
            for (int k = 0; k + 1 < xs.length; k++) {
                double a = xs[k];
                double c = xs[k + 1];
                if (c - a <= GUTTER_GAP * h) {
                    continue;
                }
                // numpy compares the float32 edges with these in float32.
                float gl = (float) (a + 0.35 * h);
                float gr = (float) (c - 0.35 * h);
                float tolerance = (float) (0.5 * h);
                int near = 0;
                for (int j = 0; j < boxes.length; j++) {
                    if (j != i && (Math.abs(boxes[j][2] - gl) < tolerance
                            || Math.abs(boxes[j][0] - gr) < tolerance)) {
                        near++;
                    }
                }
                if (near >= GUTTER_EDGES) {
                    mine.add((a + c) / 2);
                }
            }
            cuts[i] = mine.stream().mapToDouble(Double::doubleValue).toArray();
        }
        return cuts;
    }

    /** A line's height for its gap rules: at least one pixel. */
    static double lineHeight(float[] box) {
        return Math.max(1.0, (double) (box[3] - box[1]));
    }

    private static boolean crosses(double[] cuts, double from, double to) {
        for (double c : cuts) {
            if (from < c && c < to) {
                return true;
            }
        }
        return false;
    }

    /**
     * One piece of a cut line.
     *
     * @param box    Its box, {@code [x0, y0, x1, y1]}, float32 as numpy stores it
     * @param text   Its text
     * @param tokens Its tokens
     */
    record Piece(float[] box, String text, List<Token> tokens) {
    }
}
