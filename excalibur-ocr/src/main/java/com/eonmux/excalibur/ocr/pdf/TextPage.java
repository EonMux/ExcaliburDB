package com.eonmux.excalibur.ocr.pdf;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.List;

/**
 * A page's text as pdfium's {@code FPDFText_LoadPage} lays it out: characters in reading order,
 * with the spaces, line breaks and hyphen marks pdfium inserts, each with its Unicode unit, its
 * baseline angle and its box in PDF page space.
 *
 * <p>Also counts the page's text objects drawn invisibly, as pypdfium2's
 * {@code page.get_objects(max_depth=4)} sees them.</p>
 */
final class TextPage {

    /** pypdfium2's {@code get_objects(max_depth=4)}: objects at form levels 0 to 3. */
    private static final int MAX_LEVEL = 3;
    private static final int INVISIBLE = 3;

    private final List<CharInfo> chars;
    private final int textObjects;
    private final int invisibleObjects;

    private TextPage(List<CharInfo> chars, int textObjects, int invisibleObjects) {
        this.chars = chars;
        this.textObjects = textObjects;
        this.invisibleObjects = invisibleObjects;
    }

    /**
     * Reads a page's text.
     *
     * @param page The page, with the rotation to read it at
     * @return Its text page
     * @throws IOException if the page content cannot be read
     */
    static TextPage read(PdfPage page) throws IOException {
        List<ContentItem> items = ContentCollector.collect(page.pdPage());
        int[] counts = new int[2];
        count(items, 0, counts);
        List<CharInfo> chars = TextPageBuilder.build(page, items,
            directionRightToLeft(page.document()));
        return new TextPage(chars, counts[0], counts[1]);
    }

    private static void count(List<ContentItem> items, int level, int[] counts) {
        for (ContentItem item : items) {
            switch (item) {
                case TextObject t -> {
                    counts[0]++;
                    if (t.renderMode() == INVISIBLE) {
                        counts[1]++;
                    }
                }
                case ContentItem.Form f -> {
                    if (f.level() <= MAX_LEVEL) {
                        count(f.items(), f.level(), counts);
                    }
                }
            }
        }
    }

    /** {@code CPDF_ViewerPreferences::IsDirectionR2L}. */
    private static boolean directionRightToLeft(PDDocument document) {
        COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
        if (!(catalog.getDictionaryObject(COSName.VIEWER_PREFERENCES) instanceof COSDictionary vp)) {
            return false;
        }
        COSBase direction = vp.getDictionaryObject(COSName.DIRECTION);
        String value = direction instanceof COSName n ? n.getName()
            : direction instanceof COSString s ? s.getString() : "";
        return "R2L".equals(value);
    }

    /** {@code FPDFText_CountChars}. */
    int count() {
        return chars.size();
    }

    /** {@code FPDFText_GetUnicode}: the character's unit, or code for an unmapped one. */
    int unicode(int i) {
        return chars.get(i).unicode();
    }

    /** {@code FPDFText_GetCharAngle}. */
    float angle(int i) {
        return chars.get(i).angle();
    }

    /** {@code FPDFText_GetCharBox}: the box in PDF page space. */
    FxRect box(int i) {
        return chars.get(i).box();
    }

    /** The number of text objects at form levels 0 to 3. */
    int textObjects() {
        return textObjects;
    }

    /** The number of those drawn invisibly (render mode 3). */
    int invisibleObjects() {
        return invisibleObjects;
    }
}
