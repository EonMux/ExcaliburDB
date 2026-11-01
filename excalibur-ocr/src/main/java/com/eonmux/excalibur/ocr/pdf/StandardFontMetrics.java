package com.eonmux.excalibur.ocr.pdf;

import org.apache.fontbox.afm.CharMetric;
import org.apache.fontbox.afm.FontMetrics;
import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.util.HashMap;
import java.util.Map;

/**
 * Glyph metrics of an unembedded standard font, as pdfium measures them on the substitute face it
 * draws with: Adobe's metrics for every glyph the font has, and the substitute's {@code .notdef}
 * for a glyph name it lacks.
 *
 * <p>pdfium's substitutes share the standard fonts' advance widths, so the Adobe widths are
 * pdfium's. Their glyph boxes differ from Adobe's by a few thousandths of an em, which moves a
 * character's box by a fraction of a point and nothing else. The {@code .notdef} metrics were
 * read from pdfium (chromium/8076) itself.</p>
 */
final class StandardFontMetrics {

    /** The {@code .notdef} glyph of a substitute family: advance and box. */
    private record NotDef(int width, PdfFont.GlyphBox box) {
    }

    private static final NotDef SANS = new NotDef(750, new PdfFont.GlyphBox(125, 0, 625, 625));
    private static final NotDef SERIF = new NotDef(778, new PdfFont.GlyphBox(139, 0, 639, 625));
    private static final NotDef MONO = new NotDef(600, new PdfFont.GlyphBox(50, 0, 550, 625));
    private static final NotDef SYMBOL = new NotDef(250, PdfFont.GlyphBox.ZERO);
    private static final NotDef DINGBATS = new NotDef(278, PdfFont.GlyphBox.ZERO);

    private final Map<String, CharMetric> metrics = new HashMap<>();
    private final NotDef notDef;

    private StandardFontMetrics(FontMetrics afm, NotDef notDef) {
        for (CharMetric m : afm.getCharMetrics()) {
            metrics.putIfAbsent(m.getName(), m);
        }
        this.notDef = notDef;
    }

    /**
     * The metrics of a font, when it is an unembedded simple font that stands for one of the
     * standard fonts (by its own name or a common alias such as Arial).
     *
     * @param font The font
     * @return Its metrics, or null for any other font
     */
    static StandardFontMetrics of(PDFont font) {
        if (!(font instanceof PDSimpleFont) || font instanceof PDType3Font || font.isEmbedded()) {
            return null;
        }
        String base = font.getCOSObject().getNameAsString(COSName.BASE_FONT);
        Standard14Fonts.FontName name = base == null ? null
            : Standard14Fonts.getMappedFontName(base);
        if (name == null) {
            return null;
        }
        FontMetrics afm = Standard14Fonts.getAFM(name.getName());
        return afm == null ? null : new StandardFontMetrics(afm, family(name.getName()));
    }

    private static NotDef family(String name) {
        if (name.startsWith("Times")) {
            return SERIF;
        }
        if (name.startsWith("Courier")) {
            return MONO;
        }
        if (name.equals("Symbol")) {
            return SYMBOL;
        }
        if (name.equals("ZapfDingbats")) {
            return DINGBATS;
        }
        return SANS;
    }

    private CharMetric metric(String name) {
        if (name == null) {
            return null;
        }
        CharMetric m = metrics.get(name);
        if (m == null && "nbspace".equals(name)) {
            m = metrics.get("space");
        } else if (m == null && "sfthyphen".equals(name)) {
            m = metrics.get("hyphen");
        }
        return m;
    }

    /**
     * The advance of a glyph in thousandths of an em.
     *
     * @param name The glyph name, or null
     * @return The width
     */
    int width(String name) {
        CharMetric m = metric(name);
        return m == null ? notDef.width() : PdfFont.roundf(m.getWx());
    }

    /**
     * The box of a glyph in thousandths of an em.
     *
     * @param name The glyph name, or null
     * @return The box
     */
    PdfFont.GlyphBox box(String name) {
        CharMetric m = metric(name);
        if (m == null) {
            return notDef.box();
        }
        BoundingBox b = m.getBoundingBox();
        if (b == null) {
            return PdfFont.GlyphBox.ZERO;
        }
        return new PdfFont.GlyphBox(PdfFont.roundf(b.getLowerLeftX()),
            PdfFont.roundf(b.getLowerLeftY()), PdfFont.roundf(b.getUpperRightX()),
            PdfFont.roundf(b.getUpperRightY()));
    }
}
