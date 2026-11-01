package com.eonmux.excalibur.ocr.pdf;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;

import java.util.List;

/**
 * Marked-content {@code /ActualText} as pdfium's text page uses it
 * ({@code CPDF_TextPage::PreMarkedContent} and {@code ProcessMarkedContent}): the replacement text
 * stands in for the glyphs of the first object of its span, and the span's other objects add
 * nothing.
 */
final class MarkedContent {

    /** pdfium's {@code MarkedContentState}. */
    enum State {
        /** The object's own characters are read. */
        PASS,
        /** The object adds nothing: its span's text was already given. */
        DONE,
        /** The span's replacement text is read in place of the object's characters. */
        DELAY
    }

    private MarkedContent() {
        throw new AssertionError("Static methods only");
    }

    /**
     * How an object's marked content affects it: {@code PreMarkedContent}.
     *
     * @param obj  The object
     * @param prev The object read before it, or null
     * @return The state
     */
    static State before(TextObject obj, TextObject prev) {
        List<COSDictionary> marks = obj.marks();
        if (marks.isEmpty()) {
            return State.PASS;
        }
        String actual = null;
        for (COSDictionary mark : marks) {
            if (mark != ContentCollector.NO_PROPERTIES
                && mark.getDictionaryObject(COSName.ACTUAL_TEXT) instanceof COSString s) {
                actual = s.getString();
            }
        }
        if (actual == null) {
            return State.PASS;
        }
        if (prev != null) {
            List<COSDictionary> prevMarks = prev.marks();
            if (prevMarks.size() == marks.size() && prevMarks.getLast() == marks.getLast()) {
                return State.DONE;
            }
        }
        if (actual.isEmpty()) {
            return State.PASS;
        }
        return actual.codePoints().anyMatch(MarkedContent::printable) ? State.DELAY : State.DONE;
    }

    private static boolean printable(int wc) {
        return (wc > 0x80 && wc < 0xFFFD) || (wc <= 0x80 && isPrint(wc));
    }

    /** C's {@code isprint} in the C locale. */
    private static boolean isPrint(int c) {
        return c >= 0x20 && c < 0x7F;
    }

    /**
     * Adds the replacement text of an object's span to the line, dividing the object's box
     * evenly among its characters: {@code ProcessMarkedContent}. As in pdfium, the boxes and
     * origin stay in the object's own space.
     *
     * @param placed  The object and its form matrix
     * @param prev    The object read before it, or null
     * @param buffers The text page's buffers
     * @return Whether the text runs right to left, so that the caller reverses it
     */
    static boolean addActualText(TextPageBuilder.Placed placed, TextObject prev,
                                 TextBuffers buffers) {
        TextObject obj = placed.obj();
        String text = "";
        for (COSDictionary mark : obj.marks()) {
            if (mark != ContentCollector.NO_PROPERTIES) {
                text = unicodeText(mark.getDictionaryObject(COSName.ACTUAL_TEXT));
            }
        }
        if (text.isEmpty()) {
            return false;
        }
        int[] cps = text.codePoints().toArray();
        boolean rtl = TextMetrics.isRightToLeft(obj)
            || (prev != null && TextMetrics.isRightToLeft(prev)
            && Bidi.split(cps).direction() == Bidi.Direction.RIGHT);
        FxMatrix matrix = obj.matrix().times(placed.formMatrix());
        FxRect r = obj.rect();
        float share = r.width() / cps.length;
        FxRect rect = rtl ? new FxRect(r.right() - share, r.bottom(), r.right(), r.top())
            : new FxRect(r.left(), r.bottom(), r.left() + share, r.top());
        float step = rtl ? -rect.width() : rect.width();
        for (int k = 0; k < cps.length; k++) {
            int wc = cps[k];
            if (wc <= 0x80 && !isPrint(wc)) {
                wc = 0x20;
            }
            if (wc >= 0xFFFD) {
                continue;
            }
            float dx = k * step;
            FxRect box = new FxRect(rect.left() + dx, rect.bottom(), rect.right() + dx,
                rect.top());
            buffers.appendTemp(wc, new CharInfo(CharInfo.Type.ACTUAL_TEXT, wc,
                PdfFont.INVALID_CODE, obj.pos(), box, matrix, obj));
        }
        return rtl;
    }

    /** A dictionary value as text: {@code GetUnicodeTextFor}. */
    private static String unicodeText(COSBase value) {
        if (value instanceof COSString s) {
            return s.getString();
        }
        if (value instanceof COSName n) {
            return n.getName();
        }
        return "";
    }
}
