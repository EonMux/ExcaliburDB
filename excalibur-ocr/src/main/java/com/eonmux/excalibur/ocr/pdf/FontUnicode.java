package com.eonmux.excalibur.ocr.pdf;

import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The text of a font's character codes by pdfium's rules ({@code UnicodeFromCharCode} and
 * {@code CharCodeFromUnicode} of {@code CPDF_Font} and its subclasses).
 *
 * <p>The ToUnicode map answers first. Past it, a simple font names its glyphs through its
 * encoding, and a CID font's codes are UCS-2 under a UCS-2 or UTF-16 CMap, or CIDs of a Chinese,
 * Japanese or Korean collection with Adobe's Unicode tables. PDFBox's further guesses, such as
 * reading an embedded font's own cmap backwards, are not made: a font pdfium cannot read gives
 * no text here either, and its codes then stand for themselves.</p>
 */
final class FontUnicode {

    private static final Set<String> CJK_ORDERINGS = Set.of("GB1", "CNS1", "Japan1", "Korea1");
    private static final int CID_LIMIT = 65536;

    private final PDFont font;
    private final CMap toUnicode;
    private final CMap cidToUnicode;
    private final boolean unicodeCodes;
    private final boolean identityCodes;
    private final Map<Long, String> texts = new HashMap<>();
    private Integer spaceCode;

    FontUnicode(PDFont font) {
        this.font = font;
        this.toUnicode = parseToUnicode(font.getCOSObject());
        String cmapName = font instanceof PDType0Font t && t.getCMap() != null
            ? t.getCMap().getName() : null;
        this.unicodeCodes = cmapName != null
            && (cmapName.contains("UCS2") || cmapName.contains("UTF16"));
        this.identityCodes = font instanceof PDType0Font
            && (cmapName == null || cmapName.startsWith("Identity"));
        this.cidToUnicode = font instanceof PDType0Font t && t.getDescendantFont() != null
            ? collectionUnicode(t.getDescendantFont()) : null;
    }

    private static CMap parseToUnicode(COSDictionary dict) {
        if (!(dict.getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream stream)) {
            return null;
        }
        try (InputStream in = stream.createInputStream()) {
            return new CMapParser().parse(new RandomAccessReadBuffer(in));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Adobe's CID-to-Unicode table for a CJK collection, as pdfium's {@code cid2unicode}. */
    private static CMap collectionUnicode(PDCIDFont descendant) {
        if (descendant.getCIDSystemInfo() == null
            || !CJK_ORDERINGS.contains(descendant.getCIDSystemInfo().getOrdering())) {
            return null;
        }
        try {
            return new CMapParser().parsePredefined(descendant.getCIDSystemInfo().getRegistry()
                + "-" + descendant.getCIDSystemInfo().getOrdering() + "-UCS2");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The text of a code: {@code UnicodeFromCharCode}.
     *
     * @param code   The code
     * @param length Its length in bytes
     * @return UTF-16 text, empty when the font gives none
     */
    String text(int code, int length) {
        return texts.computeIfAbsent(((long) code << 3) | length, k -> load(code, length));
    }

    private String load(int code, int length) {
        if (toUnicode != null) {
            String s = toUnicode.toUnicode(code, length);
            if (s == null && length != 1) {
                s = toUnicode.toUnicode(code);
            }
            if (s != null && !s.isEmpty()) {
                return s;
            }
        }
        if (font instanceof PDSimpleFont simple) {
            return encodingText(simple, code);
        }
        if (unicodeCodes) {
            return String.valueOf((char) code);
        }
        if (cidToUnicode != null) {
            String s = cidToUnicode.toUnicode(((PDType0Font) font).codeToCID(code));
            return s == null || s.isEmpty() ? "" : s.substring(0, 1);
        }
        return "";
    }

    /**
     * A simple font's text for a code from its encoding's glyph name. pdfium looks names up in
     * FreeType's list, which holds both the Adobe Glyph List and the ITC Zapf Dingbats names
     * ({@code a1} to {@code a191}), so a dingbat name means its dingbat in any font.
     */
    private static String encodingText(PDSimpleFont font, int code) {
        if (code > 255 || builtInTrueType(font)) {
            return "";
        }
        Encoding encoding = font.getEncoding();
        String name = encoding == null ? null : encoding.getName(code);
        if (name == null || ".notdef".equals(name)) {
            return "";
        }
        boolean dingbats = "ZapfDingbats".equals(font.getCOSObject()
            .getNameAsString(COSName.BASE_FONT));
        GlyphList first = dingbats ? GlyphList.getZapfDingbats() : GlyphList.getAdobeGlyphList();
        GlyphList second = dingbats ? GlyphList.getAdobeGlyphList() : GlyphList.getZapfDingbats();
        String s = first.toUnicode(name);
        if (s == null || s.isEmpty()) {
            s = second.toUnicode(name);
        }
        return s == null || s.isEmpty() ? "" : s.substring(0, 1);
    }

    /** A symbolic TrueType font with no /Encoding: its codes have no names. */
    private static boolean builtInTrueType(PDSimpleFont font) {
        return font instanceof PDTrueTypeFont && font.isSymbolic()
            && font.getCOSObject().getDictionaryObject(COSName.ENCODING) == null;
    }

    /**
     * The code that shows a space: {@code CharCodeFromUnicode(' ')}.
     *
     * @return The code; {@link PdfFont#INVALID_CODE} for a simple font with none, 0 for a CID
     *         font with none
     */
    int spaceCode() {
        if (spaceCode == null) {
            spaceCode = loadSpaceCode();
        }
        return spaceCode;
    }

    private int loadSpaceCode() {
        int mapped = toUnicode == null ? 0 : reverse(toUnicode.getCodesFromUnicode(" "));
        if (mapped != 0) {
            return mapped;
        }
        if (font instanceof PDSimpleFont simple) {
            for (int code = 0; code < 256; code++) {
                if (" ".equals(encodingText(simple, code))) {
                    return code;
                }
            }
            return PdfFont.INVALID_CODE;
        }
        return cidSpaceCode();
    }

    private static int reverse(byte[] codes) {
        int code = 0;
        if (codes != null) {
            for (byte b : codes) {
                code = (code << 8) | (b & 0xFF);
            }
        }
        return code;
    }

    /** {@code CPDF_CIDFont::CharCodeFromUnicode(' ')} past the ToUnicode map. */
    private int cidSpaceCode() {
        if (unicodeCodes || !identityCodes) {
            return ' ';
        }
        if (cidToUnicode == null) {
            return 0;
        }
        for (int cid = 0; cid < CID_LIMIT; cid++) {
            if (" ".equals(cidToUnicode.toUnicode(cid))) {
                return cid;
            }
        }
        return ' ';
    }
}
