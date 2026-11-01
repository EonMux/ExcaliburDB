package com.eonmux.excalibur.ocr.pdf;

/**
 * Adds one text object's characters to the line being built:
 * {@code CPDF_TextPage::ProcessTextObjectItems}.
 *
 * <p>A wide {@code TJ} adjustment between two characters becomes a space. A character's box is
 * its glyph box at the font size, made at least one unit tall and one advance wide, then taken to
 * the page. A character that repeats one of the last seven of the same font at nearly the same
 * place (overprinted fake bold) is dropped.</p>
 */
final class ObjectReader {

    private static final int DUPLICATE_LOOKBACK = 7;
    private static final float DUPLICATE_GAP = 0.07f;

    private final TextBuffers buffers;

    ObjectReader(TextBuffers buffers) {
        this.buffers = buffers;
    }

    /**
     * Reads an object's characters into the line.
     *
     * @param obj        The object
     * @param formMatrix Its form's matrix
     * @param matrix     Its text matrix then the form's
     * @return Whether the caller is to reverse what was added: right-to-left text drawn mirrored
     */
    boolean read(TextObject obj, FxMatrix formMatrix, FxMatrix matrix) {
        float baseSpace = TextMetrics.baseSpace(obj, matrix)
            + TextMetrics.baseSpaceAdjustment(obj, matrix);
        float spacing = 0;
        for (int i = 0; i < obj.count(); i++) {
            int code = obj.code(i);
            FxPoint origin = obj.origin(i);
            if (i > 0 && obj.kerning(i - 1) != 0) {
                WideBuffer str = buffers.currentText();
                if (!str.isEmpty() && str.back() != ' ') {
                    spacing = -obj.fontSizeH() * obj.kerning(i - 1) / 1000;
                }
            }
            spacing -= baseSpace;
            if (spacing != 0 && i > 0) {
                addKerningSpace(obj, code, origin, spacing, formMatrix, matrix);
            }
            spacing = 0;
            addCharacter(obj, i, code, origin, matrix, i == 0);
        }
        float det = matrix.a() * matrix.d() - matrix.b() * matrix.c();
        return TextMetrics.isRightToLeft(obj) && det < 0;
    }

    private void addKerningSpace(TextObject obj, int code, FxPoint origin, float spacing,
                                 FxMatrix formMatrix, FxMatrix matrix) {
        float threshold = TextMetrics.spaceThreshold(obj.font(), obj.fontSizeH(), code);
        if (threshold != 0 && spacing >= threshold) {
            FxPoint o = matrix.apply(origin);
            buffers.appendTemp(' ', new CharInfo(CharInfo.Type.GENERATED, ' ',
                PdfFont.INVALID_CODE, o, new FxRect(o.x(), o.y(), o.x(), o.y()), formMatrix, obj));
        }
    }

    private void addCharacter(TextObject obj, int i, int code, FxPoint origin, FxMatrix matrix,
                              boolean first) {
        String text = obj.unicode(code, obj.codeLength(i));
        int[] units = text.chars().toArray();
        CharInfo.Type type = CharInfo.Type.NORMAL;
        if (units.length == 0 && code != 0) {
            units = new int[] {code};
            type = CharInfo.Type.NOT_UNICODE;
        }
        CharInfo info = new CharInfo(type, 0, code, matrix.apply(origin),
            matrix.apply(glyphBox(obj, code, origin)), matrix, obj);
        if (units.length == 0) {
            buffers.appendTemp(0xFFFE, info);
            return;
        }
        if (!isDuplicate(info, obj)) {
            for (int c : units) {
                buffers.appendTemp(c != 0 ? c : 0xFFFE, info.withUnicode(c));
            }
        } else if (first) {
            WideBuffer str = buffers.tempText();
            if (!str.isEmpty() && str.back() == ' ') {
                buffers.dropLastTemp();
            }
        }
    }

    /** The character's box in text space, before the text matrix. */
    private static FxRect glyphBox(TextObject obj, int code, FxPoint origin) {
        PdfFont.GlyphBox r = obj.font().charBox(code);
        float size = obj.fontSize() / 1000;
        float left = r.left() * size + origin.x();
        float bottom = r.bottom() * size + origin.y();
        float right = r.right() * size + origin.x();
        float top = r.top() * size + origin.y();
        if (Math.abs(top - bottom) < TextPageBuilder.SIZE_EPSILON) {
            top = bottom + size;
        }
        if (Math.abs(right - left) < TextPageBuilder.SIZE_EPSILON) {
            right = left + obj.charWidth(code);
        }
        return new FxRect(left, bottom, right, top);
    }

    /** Whether one of the last characters of the line shows the same code at about this place. */
    private boolean isDuplicate(CharInfo info, TextObject obj) {
        int size = buffers.tempSize();
        int count = Math.min(size, DUPLICATE_LOOKBACK);
        float threshold = info.matrix().xDistance(DUPLICATE_GAP * obj.fontSize());
        for (int n = size; n > size - count; n--) {
            CharInfo candidate = buffers.tempChar(n - 1);
            if (candidate.charCode() != info.charCode()) {
                continue;
            }
            if (candidate.textObject() == null || candidate.textObject().font() != obj.font()) {
                continue;
            }
            float dx = candidate.origin().x() - info.origin().x();
            float dy = candidate.origin().y() - info.origin().y();
            if (Math.abs(dx) < threshold && Math.abs(dy) < threshold) {
                return true;
            }
        }
        return false;
    }
}
