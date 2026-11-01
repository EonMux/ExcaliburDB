package com.eonmux.excalibur.ocr.pdf;

/**
 * One character of pdfium's text page, as {@code CPDF_TextPage::CharInfo}.
 *
 * @param type       Where the character came from
 * @param unicode    Its UTF-16 code unit (or a raw character code), 0 for none
 * @param charCode   The font's character code, {@link PdfFont#INVALID_CODE} when generated
 * @param origin     The glyph origin in page space
 * @param box        The glyph's box in page space (zero-sized for generated characters)
 * @param matrix     Text space (scaled by the font size) to page space; the form matrix alone
 *                   for a generated character
 * @param textObject The text object that drew it, null when generated
 */
record CharInfo(Type type, int unicode, int charCode, FxPoint origin, FxRect box, FxMatrix matrix,
                TextObject textObject) {

    /** pdfium's {@code CPDF_TextPage::CharType}. */
    enum Type {
        /** A character of the content with Unicode text. */
        NORMAL,
        /** A space or line break the text page inserted. */
        GENERATED,
        /** A character whose font gives no text; {@code unicode} holds its code. */
        NOT_UNICODE,
        /** A hyphen at a line break, reported as code 2. */
        HYPHEN,
        /** One character of a normalized sequence (a ligature split up, right-to-left text). */
        PIECE,
        /** A character of a marked-content {@code /ActualText} replacement. */
        ACTUAL_TEXT
    }

    CharInfo withUnicode(int u) {
        return new CharInfo(type, u, charCode, origin, box, matrix, textObject);
    }

    CharInfo withType(Type t) {
        return new CharInfo(t, unicode, charCode, origin, box, matrix, textObject);
    }

    /**
     * The angle of the character's baseline, as {@code FPDFText_GetCharAngle}: the matrix's
     * {@code atan2(c, a)}, in {@code [0, 2 pi)} and single precision.
     *
     * @return The angle in radians
     */
    float angle() {
        float a = (float) Math.atan2(matrix.c(), matrix.a());
        if (a < 0) {
            a = (float) (2 * (float) Math.PI) + a;
        }
        return a;
    }
}
