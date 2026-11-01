package com.eonmux.excalibur.ocr.pdf;

import org.apache.fontbox.FontBoxFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A PDFBox font answering the questions pdfium's text page asks of its {@code CPDF_Font}: advance
 * widths and glyph boxes in thousandths of an em, as integers; the Unicode text of a character
 * code; the code of the space; and the vertical metrics of a CID font.
 *
 * <p>The answers follow pdfium's rules where they differ from PDFBox's. A simple font's widths
 * come from {@code /Widths} as integers (with {@code /MissingWidth} outside its range), else from
 * the font program. A glyph box is the control box of the outline, every point of it including
 * curve control points, as FreeType measures it for pdfium; a CID font's box has its top raised
 * by 1/64 as {@code CFX_Face::GetCharBBox} does, a simple font's does not. An unembedded standard
 * font is measured as pdfium's substitute ({@link StandardFontMetrics}). Text comes from
 * {@link FontUnicode}.</p>
 */
final class PdfFont {

    /** pdfium's {@code CPDF_Font::kInvalidCharCode}. */
    static final int INVALID_CODE = -1;

    private final PDFont font;
    private final FontUnicode unicode;
    private final VerticalMetrics vertical;
    private final StandardFontMetrics standard;
    private final Map<Integer, Integer> widths = new HashMap<>();
    private final Map<Integer, GlyphBox> boxes = new HashMap<>();

    /**
     * A glyph box in thousandths of an em: {@code top} is the highest point and {@code bottom}
     * the lowest.
     *
     * @param left   Left edge
     * @param bottom Lowest point
     * @param right  Right edge
     * @param top    Highest point
     */
    record GlyphBox(int left, int bottom, int right, int top) {
        static final GlyphBox ZERO = new GlyphBox(0, 0, 0, 0);
    }

    PdfFont(PDFont font) {
        this.font = font;
        this.unicode = new FontUnicode(font);
        this.vertical = font instanceof PDType0Font t && t.getDescendantFont() != null
            && font.isVertical() ? new VerticalMetrics(t.getDescendantFont().getCOSObject())
            : null;
        this.standard = StandardFontMetrics.of(font);
    }

    boolean isCid() {
        return font instanceof PDType0Font;
    }

    boolean isType3() {
        return font instanceof PDType3Font;
    }

    /** Whether the font writes top to bottom: a CID font with a vertical CMap. */
    boolean verticalWriting() {
        return vertical != null;
    }

    /**
     * The character codes of a string with their byte lengths, as the font's encoding splits it.
     *
     * @param bytes The string
     * @return Pairs {@code [code, length]} in order
     * @throws IOException if the font cannot read the string
     */
    int[][] decode(byte[] bytes) throws IOException {
        InputStream in = new ByteArrayInputStream(bytes);
        List<int[]> out = new ArrayList<>();
        while (in.available() > 0) {
            int before = in.available();
            int code = font.readCode(in);
            out.add(new int[] {code, before - in.available()});
        }
        return out.toArray(new int[0][]);
    }

    /** The CID of a code; the code itself for a simple font. */
    int cid(int code) {
        return font instanceof PDType0Font t ? t.codeToCID(code) : code;
    }

    /** The text of a code: {@code UnicodeFromCharCode}, empty when the font gives none. */
    String unicode(int code, int length) {
        return unicode.text(code, length);
    }

    /** The code of the space: {@code CharCodeFromUnicode(' ')}. */
    int spaceCode() {
        return unicode.spaceCode();
    }

    /**
     * The advance width in thousandths of an em: {@code CPDF_Font::GetCharWidth}.
     *
     * @param code The character code
     * @return The width, 0 when the font gives none
     */
    int charWidth(int code) {
        return widths.computeIfAbsent(code, this::loadWidth);
    }

    private int loadWidth(int code) {
        try {
            if (font instanceof PDType3Font t3) {
                return type3Width(t3, code);
            }
            if (font instanceof PDType0Font) {
                return (int) font.getWidth(code);
            }
            COSDictionary dict = font.getCOSObject();
            if (dict.getDictionaryObject(COSName.WIDTHS) instanceof COSArray widthArray) {
                return listedWidth(dict, widthArray, code);
            }
            return programWidth(code);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** A width from {@code /Widths}: {@code CPDF_FaceBasedSimpleFont::LoadCharWidths}. */
    private static int listedWidth(COSDictionary dict, COSArray widthArray, int code) {
        int missing = 0;
        if (dict.getDictionaryObject(COSName.FONT_DESC) instanceof COSDictionary desc
            && desc.getDictionaryObject(COSName.MISSING_WIDTH) instanceof COSNumber n) {
            missing = n.intValue();
        }
        int start = dict.getInt(COSName.FIRST_CHAR, 0);
        int end = dict.getInt(COSName.LAST_CHAR, 0);
        if (start < 0 || start > 255) {
            return missing;
        }
        if (end == 0 || end >= start + widthArray.size()) {
            end = start + widthArray.size() - 1;
        }
        end = Math.min(end, 255);
        if (code < start || code > end) {
            return missing;
        }
        return widthArray.getObject(code - start) instanceof COSNumber n ? n.intValue() : 0;
    }

    /** A simple font's width from its font program, in thousandths of an em. */
    private int programWidth(int code) throws IOException {
        if (standard != null) {
            return standard.width(glyphName(code));
        }
        if (font instanceof PDType1Font t1 && !t1.isEmbedded()) {
            FontBoxFont substitute = t1.getFontBoxFont();
            return roundf(substitute.getWidth(t1.codeToName(code))
                * substitute.getFontMatrix().getFirst().floatValue() * 1000);
        }
        return roundf(font.getWidthFromFont(code));
    }

    /**
     * The name the font's encoding gives a code, which a standard font's metrics are keyed by.
     * Not PDFBox's {@code codeToName}: that names the glyph in the substitute PDFBox draws with,
     * and is {@code .notdef} for a glyph the substitute lacks (Symbol's {@code copyrightsans} in a
     * system font, say) where pdfium's own standard faces have it.
     */
    private String glyphName(int code) {
        PDSimpleFont simple = (PDSimpleFont) font;
        return simple.getEncoding() == null ? null : simple.getEncoding().getName(code);
    }

    private static int type3Width(PDType3Font font, int code) throws IOException {
        Matrix m = font.getFontMatrix();
        float w = font.getWidth(code);
        if (w == 0) {
            w = font.getWidthFromFont(code);
        }
        return roundf(w * m.getScaleX() * 1000);
    }

    /**
     * The glyph's box in thousandths of an em: {@code CPDF_Font::GetCharBBox}.
     *
     * @param code The character code
     * @return The box, all zero for an empty or unknown glyph
     */
    GlyphBox charBox(int code) {
        return boxes.computeIfAbsent(code, this::loadBox);
    }

    private GlyphBox loadBox(int code) {
        try {
            if (font instanceof PDType3Font t3) {
                return type3Box(t3, code);
            }
            if (standard != null) {
                return standard.box(glyphName(code));
            }
            if (font instanceof PDVectorFont vector) {
                GlyphBox box = controlBox(inEm(vector.getNormalizedPath(code)));
                return isCid() ? raiseTop(box) : box;
            }
        } catch (IOException | RuntimeException e) {
            return GlyphBox.ZERO;
        }
        return GlyphBox.ZERO;
    }

    /**
     * A glyph outline in thousandths of an em. PDFBox gives TrueType outlines that way, but Type 1
     * and CFF outlines in the units of their program, which for a substitute of a font that is
     * not embedded (a TrueType face with 2048 units to the em, say) are not thousandths; pdfium
     * measures every face against its own em ({@code NormalizeFontMetric}).
     */
    private GeneralPath inEm(GeneralPath path) throws IOException {
        FontBoxFont program = font instanceof PDType1Font t1 ? t1.getFontBoxFont()
            : font instanceof PDType1CFont c ? c.getFontBoxFont() : null;
        if (program == null) {
            return path;
        }
        List<Number> m = program.getFontMatrix();
        double a = m.get(0).doubleValue() * 1000;
        double b = m.get(1).doubleValue() * 1000;
        double c = m.get(2).doubleValue() * 1000;
        double d = m.get(3).doubleValue() * 1000;
        if (a == 1 && b == 0 && c == 0 && d == 1) {
            return path;
        }
        return new GeneralPath(path.createTransformedShape(new AffineTransform(a, b, c, d, 0, 0)));
    }

    /** The bounds of every point of a glyph outline, control points included. */
    private static GlyphBox controlBox(GeneralPath path) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double[] c = new double[6];
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int points = switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 1;
                case PathIterator.SEG_QUADTO -> 2;
                case PathIterator.SEG_CUBICTO -> 3;
                default -> 0;
            };
            for (int p = 0; p < points; p++) {
                minX = Math.min(minX, c[2 * p]);
                maxX = Math.max(maxX, c[2 * p]);
                minY = Math.min(minY, c[2 * p + 1]);
                maxY = Math.max(maxY, c[2 * p + 1]);
            }
        }
        if (minX > maxX) {
            return GlyphBox.ZERO;
        }
        return new GlyphBox(metric(minX), metric(minY), metric(maxX), metric(maxY));
    }

    /** pdfium's {@code NormalizeFontMetric}: add half a unit, then truncate. */
    private static int metric(double v) {
        return (int) (v + 0.5);
    }

    /** pdfium raises a CID glyph's top by 1/64 of itself, in integer arithmetic. */
    private static GlyphBox raiseTop(GlyphBox b) {
        return new GlyphBox(b.left(), b.bottom(), b.right(), b.top() + b.top() / 64);
    }

    /** A Type 3 glyph's {@code d1} box through the font matrix: {@code CPDF_Type3Char}. */
    private static GlyphBox type3Box(PDType3Font font, int code) throws IOException {
        PDType3CharProc proc = font.getCharProc(code);
        PDRectangle r = proc == null ? null : proc.getGlyphBBox();
        if (r == null) {
            r = font.getFontBBox();
        }
        if (r == null) {
            return GlyphBox.ZERO;
        }
        FxMatrix m = ContentCollector.fx(font.getFontMatrix());
        FxRect box = m.apply(new FxRect(r.getLowerLeftX(), r.getLowerLeftY(), r.getUpperRightX(),
            r.getUpperRightY()));
        return new GlyphBox(roundf(box.left() * 1000), roundf(box.bottom() * 1000),
            roundf(box.right() * 1000), roundf(box.top() * 1000));
    }

    /** The vertical advance of a CID in thousandths of an em: {@code GetVertWidth}. */
    int verticalWidth(int cid) {
        return vertical.advance(cid);
    }

    /** The vertical origin of a code's glyph: {@code GetVertOrigin}. */
    int[] verticalOrigin(int cid, int code) {
        return vertical.origin(cid, charWidth(code));
    }

    /** C's {@code FXSYS_roundf}: half away from zero, saturating. */
    static int roundf(float v) {
        if (Float.isNaN(v)) {
            return 0;
        }
        double r = Math.signum(v) * Math.floor(Math.abs((double) v) + 0.5);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, r));
    }
}
