// Ported from Indra OCR 1.1.2, math.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.math;

import com.eonmux.excalibur.ocr.text.PythonText;

import java.util.ArrayList;
import java.util.List;

/** The text of a math reading's tokens: Indra's {@code detokenize}. */
public final class MathText {

    private MathText() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Joins tokens into text: outside math they are joined as they are; inside it, between two
     * {@code $} tokens, by spaces, as LaTeX that reads back the same.
     *
     * @param tokens The tokens
     * @return Their text, whitespace collapsed
     */
    public static String detokenize(List<String> tokens) {
        StringBuilder out = new StringBuilder();
        List<String> math = new ArrayList<>();
        boolean inMath = false;
        for (String t : tokens) {
            if (t.equals("$")) {
                if (inMath) {
                    out.append('$').append(String.join(" ", math)).append('$');
                    math.clear();
                }
                inMath = !inMath;
            } else if (inMath) {
                math.add(t);
            } else {
                out.append(t);
            }
        }
        if (inMath && !math.isEmpty()) {
            out.append('$').append(String.join(" ", math)).append('$');
        }
        return PythonText.collapseSpaces(out.toString());
    }
}
