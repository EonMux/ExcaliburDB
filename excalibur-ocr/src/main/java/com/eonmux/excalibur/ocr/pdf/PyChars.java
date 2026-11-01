// Ported from Indra OCR 1.1.2, textlayer.py and rtl.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import com.eonmux.excalibur.ocr.text.PythonText;

/**
 * Character classes as Indra's Python reads them ({@code str.isspace}, {@code unicodedata}
 * categories and bidirectional classes) and as pdfium reads them ({@code FXSYS_iswalpha},
 * {@code FXSYS_iswalnum}), for single code points.
 *
 * <p>Java's tables follow Unicode 15; Indra's Python may know a later version, so a character
 * assigned since then counts as unassigned here.</p>
 */
final class PyChars {

    private PyChars() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Python's {@code str.isspace}.
     *
     * @param cp A code point
     * @return Whether Python counts it as whitespace
     */
    static boolean isSpace(int cp) {
        return PythonText.isSpace(cp);
    }

    /** pdfium's {@code FXSYS_iswalpha} (ICU {@code u_isalpha}): a letter. */
    static boolean isWAlpha(int cp) {
        return cp >= 0 && cp <= Character.MAX_CODE_POINT && Character.isLetter(cp);
    }

    /** pdfium's {@code FXSYS_iswalnum} (ICU {@code u_isalnum}): a letter or decimal digit. */
    static boolean isWAlnum(int cp) {
        return cp >= 0 && cp <= Character.MAX_CODE_POINT && Character.isLetterOrDigit(cp);
    }

    /**
     * Whether a character shows a broken font map: Indra's {@code bad_char_count} for one
     * character. The replacement character, private-use, surrogate and unassigned code points,
     * and control characters other than tab are bad.
     *
     * @param cp A code point
     * @return Whether it is bad
     */
    static boolean isBad(int cp) {
        if (cp == 0xFFFD) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.PRIVATE_USE || type == Character.SURROGATE
            || type == Character.UNASSIGNED || (type == Character.CONTROL && cp != '\t');
    }

    /**
     * Indra's {@code rtl.is_rtl}: a character of bidirectional class R or AL.
     *
     * @param cp A code point
     * @return Whether it is written right to left
     */
    static boolean isRtl(int cp) {
        byte d = Character.getDirectionality(cp);
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
            || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }
}
