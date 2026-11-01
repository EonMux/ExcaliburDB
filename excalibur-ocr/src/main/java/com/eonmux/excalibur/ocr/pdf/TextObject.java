package com.eonmux.excalibur.ocr.pdf;

import org.apache.pdfbox.cos.COSDictionary;

import java.util.Collections;
import java.util.List;

/**
 * One text-showing operator ({@code Tj}, {@code TJ}, {@code '} or {@code "}) as pdfium's
 * {@code CPDF_TextObject} holds it: its character codes, each one's offset along the baseline,
 * the spacing adjustment after each, the text state, and the object's bounds.
 *
 * <p>Coordinates are in the space of the form the operator was drawn in ({@code formMatrix} takes
 * them to the page), because pdfium compares objects in that space. {@code matrix} is the text
 * matrix with horizontal scaling and the form-local CTM applied, without the font size, and with
 * the object's start as its translation, as {@code GetTextMatrix} returns it.</p>
 */
final class TextObject implements ContentItem {

    private final PdfFont font;
    private final int[] codes;
    private final int[] lengths;
    private final float[] positions;
    private final float[] kernings;
    private final float fontSize;
    private final float charSpace;
    private final float wordSpace;
    private final FxMatrix matrix;
    private final FxMatrix formMatrix;
    private final int renderMode;
    private final List<COSDictionary> marks;
    private final FxRect rect;

    /**
     * Builds the object and lays out its characters: {@code SetSegments} then
     * {@code CalcPositionDataInternal}.
     *
     * @param state  The text state the operator ran with
     * @param codes  Character codes
     * @param lengths Each code's length in bytes
     * @param kernings The {@code TJ} adjustment after each code, in thousandths of an em
     */
    TextObject(TextState state, int[] codes, int[] lengths, float[] kernings) {
        this.font = state.font();
        this.codes = codes;
        this.lengths = lengths;
        this.kernings = kernings;
        this.fontSize = state.fontSize();
        this.charSpace = state.charSpace();
        this.wordSpace = state.wordSpace();
        this.matrix = state.matrix();
        this.formMatrix = state.formMatrix();
        this.renderMode = state.renderMode();
        this.marks = state.marks();
        this.positions = new float[codes.length];
        this.rect = layout(state.lineWidth());
    }

    /**
     * The text state an operator runs with.
     *
     * @param font       Font
     * @param fontSize   {@code Tf} size
     * @param charSpace  {@code Tc}
     * @param wordSpace  {@code Tw}
     * @param matrix     Text matrix with scaling and form-local CTM, translated to the start
     * @param formMatrix Form space to page space
     * @param renderMode {@code Tr}, fill for a Type 3 font
     * @param lineWidth  Stroke width, which widens the bounds of stroked text
     * @param marks      Property dictionaries of the enclosing marked content, outermost first
     */
    record TextState(PdfFont font, float fontSize, float charSpace, float wordSpace,
                     FxMatrix matrix, FxMatrix formMatrix, int renderMode, float lineWidth,
                     List<COSDictionary> marks) {
    }

    /** {@code CalcPositionDataInternal}: offsets along the baseline and the object's bounds. */
    private FxRect layout(float lineWidth) {
        float current = 0;
        float minX = 10000f;
        float maxX = -10000f;
        float minY = 10000f;
        float maxY = -10000f;
        boolean vertical = font.verticalWriting();
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            positions[i] = current;
            PdfFont.GlyphBox box = font.charBox(code);
            float width;
            if (vertical) {
                int cid = font.cid(code);
                int[] origin = font.verticalOrigin(cid, code);
                int left = box.left() - origin[0];
                int right = box.right() - origin[0];
                int top = box.top() - origin[1];
                int bottom = box.bottom() - origin[1];
                minX = Math.min(minX, Math.min(left, right));
                maxX = Math.max(maxX, Math.max(left, right));
                float charTop = current + top * fontSize / 1000;
                float charBottom = current + bottom * fontSize / 1000;
                minY = Math.min(minY, Math.min(charTop, charBottom));
                maxY = Math.max(maxY, Math.max(charTop, charBottom));
                width = font.verticalWidth(cid) * fontSize / 1000;
            } else {
                minY = Math.min(minY, Math.min(box.top(), box.bottom()));
                maxY = Math.max(maxY, Math.max(box.top(), box.bottom()));
                float charLeft = current + box.left() * fontSize / 1000;
                float charRight = current + box.right() * fontSize / 1000;
                minX = Math.min(minX, Math.min(charLeft, charRight));
                maxX = Math.max(maxX, Math.max(charLeft, charRight));
                width = font.charWidth(code) * fontSize / 1000;
            }
            current += width;
            current += wordSpaceFor(i);
            current += charSpace;
            current -= (kernings[i] * fontSize) / 1000;
        }
        if (vertical) {
            minX = minX * fontSize / 1000;
            maxX = maxX * fontSize / 1000;
        } else {
            minY = minY * fontSize / 1000;
            maxY = maxY * fontSize / 1000;
        }
        FxRect bounds = matrix.apply(new FxRect(minX, minY, maxX, maxY));
        if (renderMode == 1 || renderMode == 2 || renderMode == 5 || renderMode == 6) {
            float half = lineWidth / 2;
            FxRect n = bounds.normalized();
            bounds = new FxRect(n.left() - half, n.bottom() - half, n.right() + half,
                n.top() + half);
        }
        return bounds;
    }

    /** Word spacing applies to a single-byte code 32: {@code GetWordSpaceIfNeeded}. */
    private float wordSpaceFor(int i) {
        return codes[i] == ' ' && lengths[i] == 1 ? wordSpace : 0;
    }

    int count() {
        return codes.length;
    }

    int code(int i) {
        return codes[i];
    }

    int codeLength(int i) {
        return lengths[i];
    }

    float kerning(int i) {
        return kernings[i];
    }

    /**
     * Where a character starts, relative to the object's start in text space scaled by the font
     * size: {@code GetItemInfo(i).origin_}.
     */
    FxPoint origin(int i) {
        if (!font.verticalWriting()) {
            return new FxPoint(positions[i], 0);
        }
        int cid = font.cid(codes[i]);
        int[] v = font.verticalOrigin(cid, codes[i]);
        return new FxPoint(0 - fontSize * v[0] / 1000, positions[i] - fontSize * v[1] / 1000);
    }

    /** A character's advance scaled by the font size: {@code CPDF_TextObject::GetCharWidth}. */
    float charWidth(int code) {
        float size = fontSize / 1000;
        if (!font.verticalWriting()) {
            return font.charWidth(code) * size;
        }
        return font.verticalWidth(font.cid(code)) * size;
    }

    /** The Unicode text of a code in this object's font. */
    String unicode(int code, int length) {
        return font.unicode(code, length);
    }

    PdfFont font() {
        return font;
    }

    float fontSize() {
        return fontSize;
    }

    float charSpace() {
        return charSpace;
    }

    /** The font size scaled by the text matrix's horizontal unit: {@code GetFontSizeH}. */
    float fontSizeH() {
        return Math.abs((float) Math.hypot(matrix.a(), matrix.b()) * fontSize);
    }

    FxMatrix matrix() {
        return matrix;
    }

    FxPoint pos() {
        return new FxPoint(matrix.e(), matrix.f());
    }

    FxMatrix formMatrix() {
        return formMatrix;
    }

    FxRect rect() {
        return rect;
    }

    int renderMode() {
        return renderMode;
    }

    List<COSDictionary> marks() {
        return Collections.unmodifiableList(marks);
    }
}
