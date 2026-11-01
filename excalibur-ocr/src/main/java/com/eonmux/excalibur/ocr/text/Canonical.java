// Ported from Indra OCR 1.1.2, vocab.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.text;

import java.text.Normalizer;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indra's label canonicalization: maps characters and runs that an image cannot tell apart to
 * one form, so neither a recognizer's training targets nor its evaluation punish a reading that
 * is visually correct.
 *
 * <p>The rules, applied in order: NFC normalization; Romanian comma-below letters to their
 * cedilla look-alikes and bullet look-alikes to {@code •}; a doubled single quote or backtick
 * ({@code ''} or {@code ``}) to {@code "}; and a run of four or more of one leader or rule
 * character ({@code . _ - = · * ~}) to three of it, since the count cannot be read from the
 * image.</p>
 */
public final class Canonical {

    /** Port of {@code _CANON}: look-alike code points and the one each reads as. */
    private static final Map<Integer, Integer> LOOK_ALIKES = Map.of(
        0x0218, 0x015E,  // Ș -> Ş
        0x0219, 0x015F,  // ș -> ş
        0x021A, 0x0162,  // Ț -> Ţ
        0x021B, 0x0163,  // ț -> ţ
        0x25AA, 0x2022,  // ▪ -> •
        0x25CF, 0x2022,  // ● -> •
        0x2219, 0x2022,  // ∙ -> •
        0x2023, 0x2022,  // ‣ -> •
        0x2043, 0x2022   // ⁃ -> •
    );

    /** Port of {@code _RUNS}: four or more of one leader or rule character. */
    static final Pattern RUNS = Pattern.compile("([._\\-=·*~])\\1{3,}");

    /** Port of {@code _DOUBLE_SINGLE}: two single quotes or two backticks for a double quote. */
    static final Pattern DOUBLE_SINGLE = Pattern.compile("''|``");

    private Canonical() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The canonical form of a text. Port of {@code canonical}.
     *
     * @param text The text
     * @return Its canonical form
     */
    public static String canonical(String text) {
        Objects.requireNonNull(text, "text must not be null");
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder mapped = new StringBuilder(nfc.length());
        nfc.codePoints().forEach(c -> mapped.appendCodePoint(LOOK_ALIKES.getOrDefault(c, c)));
        String quoted = DOUBLE_SINGLE.matcher(mapped).replaceAll("\"");
        return RUNS.matcher(quoted)
            .replaceAll(m -> Matcher.quoteReplacement(m.group(1).repeat(3)));
    }
}
