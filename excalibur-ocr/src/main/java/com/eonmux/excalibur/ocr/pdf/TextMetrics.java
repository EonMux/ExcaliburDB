package com.eonmux.excalibur.ocr.pdf;

/**
 * The measures pdfium's text page uses to decide where spaces and line breaks go, from the
 * helper functions of {@code cpdf_textpage.cpp}.
 */
final class TextMetrics {

    private TextMetrics() {
        throw new AssertionError("Static methods only");
    }

    /**
     * A character's advance in thousandths of an em, falling back to its glyph's width:
     * {@code GetCharWidth}.
     *
     * @param code The character code
     * @param font Its font
     * @return The width, never negative
     */
    static int charWidth(int code, PdfFont font) {
        if (code == PdfFont.INVALID_CODE) {
            return 0;
        }
        int w = font.charWidth(code);
        if (w > 0) {
            return w;
        }
        PdfFont.GlyphBox box = font.charBox(code);
        return Math.max(box.right() - box.left(), 0);
    }

    /** {@code NormalizeThreshold}: a width cut down more the wider it is. */
    static float normalizeThreshold(float threshold, int t1, int t2, int t3) {
        if (threshold < t1) {
            return threshold / 2.0f;
        }
        if (threshold < t2) {
            return threshold / 4.0f;
        }
        if (threshold < t3) {
            return threshold / 5.0f;
        }
        return threshold / 6.0f;
    }

    /**
     * The smallest gap inside an object with character spacing that is still no word break:
     * {@code CalculateBaseSpace}.
     */
    static float baseSpace(TextObject obj, FxMatrix matrix) {
        float charSpace = obj.charSpace();
        if (charSpace == 0.0f || obj.count() < 2) {
            return 0.0f;
        }
        boolean hasKerning = false;
        float spacing = matrix.distance(charSpace);
        float fontSizeH = obj.fontSizeH();
        float base = spacing;
        for (int i = 0; i < obj.count(); i++) {
            float k = obj.kerning(i);
            if (k != 0) {
                float kerning = -fontSizeH * k / 1000;
                base = Math.min(base, kerning + spacing);
                hasKerning = true;
            }
        }
        if (base < 0.0 || (obj.count() == 2 && hasKerning)) {
            return 0.0f;
        }
        return base;
    }

    /** {@code CalculateBaseSpaceAdjustment}: character spacing taken back out of a gap. */
    static float baseSpaceAdjustment(TextObject obj, FxMatrix matrix) {
        float charSpace = obj.charSpace();
        if (charSpace > 0.001f) {
            return -matrix.distance(charSpace);
        }
        if (charSpace < -0.001f) {
            return matrix.distance(Math.abs(charSpace));
        }
        return 0.0f;
    }

    /**
     * The gap inside an object that makes a word break: half the space's width, or a share of
     * the character's own width when the font's space is missing or wide:
     * {@code CalculateSpaceThreshold}.
     */
    static float spaceThreshold(PdfFont font, float fontSizeH, int code) {
        int spaceCode = font.spaceCode();
        float threshold = 0;
        if (spaceCode != PdfFont.INVALID_CODE) {
            threshold = fontSizeH * font.charWidth(spaceCode) / 1000;
        }
        if (threshold > fontSizeH / 3) {
            threshold = 0;
        } else {
            threshold /= 2;
        }
        if (threshold == 0) {
            threshold = charWidth(code, font);
            threshold = normalizeThreshold(threshold, 300, 500, 700);
            threshold = fontSizeH * threshold / 1000;
        }
        return threshold;
    }

    /** Whether the gap between two objects makes a space: {@code GenerateSpace}. */
    static boolean generateSpace(FxPoint pos, float lastPos, float thisWidth, float lastWidth,
                                 float threshold) {
        if (Math.abs(lastPos + lastWidth - pos.x()) <= threshold) {
            return false;
        }
        float thresholdPos = threshold + lastWidth;
        float difference = pos.x() - lastPos;
        if (Math.abs(difference) > thresholdPos) {
            return true;
        }
        if (pos.x() < 0 && -thresholdPos > difference) {
            return true;
        }
        return difference > thisWidth + lastWidth;
    }

    /** Whether two objects' boxes leave no vertical overlap: {@code EndHorizontalLine}. */
    static boolean endHorizontalLine(FxRect thisRect, FxRect prevRect) {
        if (thisRect.height() <= 4.5 || prevRect.height() <= 4.5) {
            return false;
        }
        float top = Math.min(thisRect.top(), prevRect.top());
        float bottom = Math.max(thisRect.bottom(), prevRect.bottom());
        return bottom >= top;
    }

    /** Whether an object leaves the current vertical line: {@code EndVerticalLine}. */
    static boolean endVerticalLine(FxRect thisRect, FxRect prevRect, FxRect lineRect,
                                   float thisFontSize, float prevFontSize) {
        if (thisRect.width() <= thisFontSize * 0.1f || prevRect.width() <= prevFontSize * 0.1f) {
            return false;
        }
        float left = Math.max(thisRect.left(), lineRect.left());
        float right = Math.min(thisRect.right(), lineRect.right());
        return right <= left;
    }

    /** {@code IsHyphenCode}: a hyphen-minus or soft hyphen. */
    static boolean isHyphenCode(int c) {
        return c == 0x2D || c == 0xAD;
    }

    /** {@code IsControlChar}: pdfium's own marks, except a hyphen it made. */
    static boolean isControlChar(CharInfo info) {
        return switch (info.unicode()) {
            case 0x2, 0x3, 0x93, 0x94, 0x96, 0x97, 0x98, 0xFFFE ->
                info.type() != CharInfo.Type.HYPHEN;
            default -> false;
        };
    }

    /** {@code IsNormalCharacter}: text, not a control mark or an empty code. */
    static boolean isNormalCharacter(CharInfo info) {
        return info.unicode() != 0 ? !isControlChar(info) : info.charCode() != 0;
    }

    /**
     * Whether an object's characters read right to left: {@code IsRightToLeft}, a code standing
     * for a character the font gives no text.
     */
    static boolean isRightToLeft(TextObject obj) {
        WideBuffer str = new WideBuffer();
        for (int i = 0; i < obj.count(); i++) {
            int code = obj.code(i);
            String u = obj.unicode(code, obj.codeLength(i));
            int wc = u.isEmpty() ? 0 : u.charAt(0);
            if (wc == 0) {
                wc = code;
            }
            if (wc != 0) {
                str.append(wc);
            }
        }
        return Bidi.split(str.toArray()).direction() == Bidi.Direction.RIGHT;
    }

    /**
     * The first character of an object's first code, the code itself when the font gives it no
     * text: the {@code current_char} of {@code ProcessInsertObject}.
     */
    static int firstChar(TextObject obj) {
        String u = obj.unicode(obj.code(0), obj.codeLength(0));
        return u.isEmpty() ? obj.code(0) : u.charAt(0);
    }
}
