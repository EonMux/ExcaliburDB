package com.eonmux.excalibur.ocr.text;

/**
 * The parts of Python's {@code unicodedata} module and of {@code str.isalpha} that Indra OCR's
 * text handling uses, answered from Java's own character database so that ported code reads like
 * the Python it came from.
 *
 * <p>The property values are Python's: two-letter general categories such as {@code Lu} or
 * {@code Mn}, and bidirectional classes such as {@code L}, {@code AL} or {@code NSM}, with the
 * empty string for a code point that has none (an unassigned one).</p>
 *
 * <p>Java 21 reads Unicode 15.0; Python 3.14 reads Unicode 16.0. The two agree on every code
 * point Unicode 15.0 assigns except six supplementary ones that Unicode 16.0 reclassified:
 * U+1171E (now {@code Mc} and {@code L}) and U+1D6C1, U+1D6FB, U+1D735, U+1D76F and U+1D7A9
 * (now bidi class {@code ON}). The roughly 5,800 code points assigned since Unicode 15.0 read
 * here as unassigned: category {@code Cn}, no bidi class. None of these is in any recognizer's
 * vocabulary, and only 22 of them are in the Basic Multilingual Plane.</p>
 */
public final class UnicodeData {

    private static final String[] BIDI = {
        "L", "R", "AL", "EN", "ES", "ET", "AN", "CS", "NSM", "BN", "B", "S", "WS", "ON",
        "LRE", "LRO", "RLE", "RLO", "PDF", "LRI", "RLI", "FSI", "PDI"
    };

    private UnicodeData() {
        throw new AssertionError("Static methods only");
    }

    /**
     * A code point's general category, as Python's {@code unicodedata.category} names it.
     *
     * @param codePoint The code point
     * @return The two-letter category, {@code Cn} for an unassigned code point
     */
    public static String category(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.UPPERCASE_LETTER -> "Lu";
            case Character.LOWERCASE_LETTER -> "Ll";
            case Character.TITLECASE_LETTER -> "Lt";
            case Character.MODIFIER_LETTER -> "Lm";
            case Character.OTHER_LETTER -> "Lo";
            case Character.NON_SPACING_MARK -> "Mn";
            case Character.ENCLOSING_MARK -> "Me";
            case Character.COMBINING_SPACING_MARK -> "Mc";
            case Character.DECIMAL_DIGIT_NUMBER -> "Nd";
            case Character.LETTER_NUMBER -> "Nl";
            case Character.OTHER_NUMBER -> "No";
            case Character.SPACE_SEPARATOR -> "Zs";
            case Character.LINE_SEPARATOR -> "Zl";
            case Character.PARAGRAPH_SEPARATOR -> "Zp";
            case Character.CONTROL -> "Cc";
            case Character.FORMAT -> "Cf";
            case Character.PRIVATE_USE -> "Co";
            case Character.SURROGATE -> "Cs";
            case Character.DASH_PUNCTUATION -> "Pd";
            case Character.START_PUNCTUATION -> "Ps";
            case Character.END_PUNCTUATION -> "Pe";
            case Character.CONNECTOR_PUNCTUATION -> "Pc";
            case Character.OTHER_PUNCTUATION -> "Po";
            case Character.MATH_SYMBOL -> "Sm";
            case Character.CURRENCY_SYMBOL -> "Sc";
            case Character.MODIFIER_SYMBOL -> "Sk";
            case Character.OTHER_SYMBOL -> "So";
            case Character.INITIAL_QUOTE_PUNCTUATION -> "Pi";
            case Character.FINAL_QUOTE_PUNCTUATION -> "Pf";
            default -> "Cn";
        };
    }

    /**
     * A code point's bidirectional class, as Python's {@code unicodedata.bidirectional} names
     * it.
     *
     * @param codePoint The code point
     * @return The class, or the empty string for a code point without one
     */
    public static String bidirectional(int codePoint) {
        byte directionality = Character.getDirectionality(codePoint);
        return directionality >= 0 && directionality < BIDI.length ? BIDI[directionality] : "";
    }

    /**
     * Whether a code point is a combining mark that does not take space of its own: general
     * category {@code Mn} or {@code Me}, the marks Indra keeps with the character before them.
     *
     * @param codePoint The code point
     * @return Whether it is a non-spacing or enclosing mark
     */
    public static boolean isNonSpacingMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    /**
     * Python's {@code str.isalpha}: whether a string is not empty and every code point in it is
     * a letter (general category {@code Lu}, {@code Ll}, {@code Lt}, {@code Lm} or {@code Lo}).
     *
     * @param text The string
     * @return Whether it is all letters
     */
    public static boolean isAlpha(String text) {
        return !text.isEmpty() && text.codePoints().allMatch(Character::isLetter);
    }
}
