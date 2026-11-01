package com.eonmux.excalibur.ocr.pdf;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;

import java.util.ArrayList;
import java.util.List;

/**
 * A CID font's vertical metrics, its {@code /W2} and {@code /DW2} entries, read as pdfium's
 * {@code CPDF_CIDFont} reads them: values are 16-bit integers, the default advance is -1000 and the
 * default origin sits half the horizontal advance across and 880 up.
 */
final class VerticalMetrics {

    private final int defaultY;
    private final int defaultAdvance;
    private final List<int[]> ranges = new ArrayList<>();

    /**
     * @param descendant The CID font dictionary
     */
    VerticalMetrics(COSDictionary descendant) {
        COSBase dw2 = descendant.getDictionaryObject(COSName.DW2);
        int vy = 880;
        int w1 = -1000;
        if (dw2 instanceof COSArray a && a.size() == 2) {
            vy = intAt(a, 0);
            w1 = intAt(a, 1);
        }
        this.defaultY = vy;
        this.defaultAdvance = w1;
        if (descendant.getDictionaryObject(COSName.W2) instanceof COSArray w2) {
            load(w2);
        }
    }

    /** Reads {@code /W2}: {@code cid [w1 vx vy ...]} lists and {@code first last w1 vx vy} runs. */
    private void load(COSArray w2) {
        int i = 0;
        while (i < w2.size()) {
            int first = intAt(w2, i);
            if (w2.getObject(i + 1) instanceof COSArray list) {
                for (int j = 0; j + 2 < list.size(); j += 3) {
                    int cid = first + j / 3;
                    ranges.add(new int[] {cid, cid, intAt(list, j), intAt(list, j + 1),
                        intAt(list, j + 2)});
                }
                i += 2;
            } else {
                ranges.add(new int[] {first, intAt(w2, i + 1), intAt(w2, i + 2),
                    intAt(w2, i + 3), intAt(w2, i + 4)});
                i += 5;
            }
        }
    }

    private static int intAt(COSArray a, int i) {
        return i < a.size() && a.getObject(i) instanceof COSNumber n ? n.intValue() : 0;
    }

    /**
     * The vertical advance of a CID: {@code GetVertWidth}.
     *
     * @param cid The CID
     * @return The advance in thousandths of an em, usually negative
     */
    int advance(int cid) {
        for (int[] r : ranges) {
            if (cid >= r[0] && cid <= r[1]) {
                return (short) r[2];
            }
        }
        return (short) defaultAdvance;
    }

    /**
     * The origin of a CID's glyph for vertical writing: {@code GetVertOrigin}.
     *
     * @param cid             The CID
     * @param horizontalWidth Its horizontal advance
     * @return {@code [x, y]} in thousandths of an em
     */
    int[] origin(int cid, int horizontalWidth) {
        for (int[] r : ranges) {
            if (cid >= r[0] && cid <= r[1]) {
                return new int[] {(short) r[3], (short) r[4]};
            }
        }
        return new int[] {(short) (horizontalWidth / 2), (short) defaultY};
    }
}
