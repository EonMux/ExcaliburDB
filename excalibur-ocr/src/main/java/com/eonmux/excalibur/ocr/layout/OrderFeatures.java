// Ported from Indra OCR 1.1.2, order_post.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.layout;

import com.eonmux.excalibur.ocr.array.NumpyMath;
import com.eonmux.excalibur.ocr.array.NumpyOps;
import com.eonmux.excalibur.ocr.text.PythonText;

import java.util.List;
import java.util.Objects;

/**
 * The order model's input: sixteen features per text line, ten from its box and six from its
 * text.
 *
 * <p>The model ({@code order.onnx}) reads them as {@code feats [1, N, 16]} and scores every
 * pair of lines from them. They are computed in the precisions numpy uses: the box features in
 * float32 with numpy's own float32 logarithm, the text features in Python's double precision
 * rounded to float32 when stored.</p>
 */
public final class OrderFeatures {

    /** Features from a line's box. */
    public static final int N_GEOM = 10;
    /** Features from a line's text. */
    public static final int N_TEXT = 6;
    /** Features per line, the last dimension of the model's {@code feats} input. */
    public static final int N_FEATS = N_GEOM + N_TEXT;

    /** Characters that end a sentence, Indra's {@code SENTENCE_END}. */
    static final String SENTENCE_END = ".!?:;。！？：；．";
    /** Characters that start a list item. */
    private static final String MARKERS = "•-–▪◦*·→";
    /** The floor of a box's width and height, numpy's {@code np.maximum(..., 1e-3)}. */
    private static final float MIN_SIDE = 1e-3f;

    private OrderFeatures() {
        throw new AssertionError("Static methods only");
    }

    /**
     * A string's length with East Asian wide and fullwidth characters counted twice: its width
     * in Latin letters. Ports {@code display_len}.
     *
     * @param text The string
     * @return Its display length
     */
    public static int displayLen(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return text.codePoints().map(cp -> PythonText.isWide(cp) ? 2 : 1).sum();
    }

    /**
     * The features of a page's lines. Ports {@code line_features}.
     *
     * <p>Per line, from the box: its edges and centre over the page size, its width over the
     * page width, log(height / median height), log(width / height) / 4 and the median height
     * over the page height times 20. From the stripped text: log(1 + display length) / 5, the
     * share of digits, whether it starts like a list item, whether it ends with a hyphen,
     * whether it ends a sentence and whether it starts with a capital.</p>
     *
     * @param boxes Line boxes {@code [x0, y0, x1, y1, ...]} in page pixels; extra columns are
     *              ignored
     * @param pageW The page width in pixels
     * @param pageH The page height in pixels
     * @param texts The lines' texts, or null to leave the text features zero
     * @return Row-major {@code [N, N_FEATS]} features
     * @throws IllegalArgumentException if a box is malformed, a page side is not positive or
     *                                  the texts do not match the boxes
     */
    public static float[] of(float[][] boxes, int pageW, int pageH, List<String> texts) {
        LayoutArgs.requireBoxes(boxes);
        if (pageW < 1 || pageH < 1) {
            throw new IllegalArgumentException("page size must be positive, got " + pageW + "x"
                + pageH);
        }
        if (texts != null && texts.size() != boxes.length) {
            throw new IllegalArgumentException(boxes.length + " boxes but " + texts.size()
                + " texts");
        }
        int n = boxes.length;
        float[] out = new float[n * N_FEATS];
        if (n == 0) {
            return out;
        }
        float[] w = new float[n];
        float[] h = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = Math.max(boxes[i][2] - boxes[i][0], MIN_SIDE);
            h[i] = Math.max(boxes[i][3] - boxes[i][1], MIN_SIDE);
        }
        float medH = NumpyOps.median(h);
        float scale = (float) ((double) medH / pageH * 20.0);
        for (int i = 0; i < n; i++) {
            geometry(boxes[i], w[i], h[i], medH, pageW, pageH, out, i * N_FEATS);
            out[i * N_FEATS + N_GEOM - 1] = scale;
            if (texts != null) {
                text(Objects.requireNonNull(texts.get(i), "text " + i + " is null"), out,
                    i * N_FEATS + N_GEOM);
            }
        }
        return out;
    }

    /** The first nine box features, in float32 as numpy computes them. */
    private static void geometry(float[] b, float w, float h, float medH, int pageW, int pageH,
                                 float[] out, int at) {
        float fw = pageW;
        float fh = pageH;
        out[at] = b[0] / fw;
        out[at + 1] = b[1] / fh;
        out[at + 2] = b[2] / fw;
        out[at + 3] = b[3] / fh;
        out[at + 4] = (b[0] + b[2]) / (float) (2 * pageW);
        out[at + 5] = (b[1] + b[3]) / (float) (2 * pageH);
        out[at + 6] = w / fw;
        out[at + 7] = NumpyMath.log(h / medH);
        out[at + 8] = NumpyMath.log(w / h) / 4.0f;
    }

    /** The six text features, in double as Python computes them, stored as float32. */
    private static void text(String raw, float[] out, int at) {
        String t = PythonText.strip(raw);
        if (t.isEmpty()) {
            return;
        }
        int length = PythonText.length(t);
        int first = t.codePointAt(0);
        int last = t.codePointBefore(t.length());
        long digits = t.codePoints().filter(PythonText::isDigit).count();
        out[at] = (float) (StrictMath.log1p(displayLen(t)) / 5.0);
        out[at + 1] = (float) ((double) digits / length);
        out[at + 2] = MARKERS.indexOf(first) >= 0 || (numbered(t) && length > 3) ? 1f : 0f;
        out[at + 3] = t.endsWith("-") ? 1f : 0f;
        out[at + 4] = SENTENCE_END.indexOf(last) >= 0 ? 1f : 0f;
        out[at + 5] = PythonText.isUpper(first) ? 1f : 0f;
    }

    /** Python's {@code t[:3].rstrip(".)").isdigit()}: the line starts with a list number. */
    private static boolean numbered(String t) {
        int end = t.offsetByCodePoints(0, Math.min(3, PythonText.length(t)));
        while (end > 0 && (t.charAt(end - 1) == '.' || t.charAt(end - 1) == ')')) {
            end--;
        }
        return PythonText.isDigit(t.substring(0, end));
    }
}
