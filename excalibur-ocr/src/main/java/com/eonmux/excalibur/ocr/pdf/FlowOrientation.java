package com.eonmux.excalibur.ocr.pdf;

import java.util.List;

/**
 * Which way most of a page's lines run, judged from how its text objects cover the page's width
 * and height: pdfium's {@code CPDF_TextPage::FindTextlineFlowOrientation}. Only text objects
 * drawn by the page itself count, not those inside forms.
 */
enum FlowOrientation {
    UNKNOWN, HORIZONTAL, VERTICAL;

    /**
     * The orientation of a page's text.
     *
     * @param items  The page's content
     * @param width  The page width in points
     * @param height The page height in points
     * @return The orientation
     */
    static FlowOrientation of(List<ContentItem> items, float width, float height) {
        int pageWidth = (int) width;
        int pageHeight = (int) height;
        if (pageWidth <= 0 || pageHeight <= 0) {
            return UNKNOWN;
        }
        boolean[] horizontal = new boolean[pageWidth];
        boolean[] vertical = new boolean[pageHeight];
        float lineHeight = 0.0f;
        int startH = pageWidth;
        int endH = 0;
        int startV = pageHeight;
        int endV = 0;
        for (ContentItem item : items) {
            if (!(item instanceof TextObject t)) {
                continue;
            }
            FxRect r = t.rect();
            int minH = (int) clamp(r.left(), pageWidth);
            int maxH = (int) clamp(r.right(), pageWidth);
            int minV = (int) clamp(r.bottom(), pageHeight);
            int maxV = (int) clamp(r.top(), pageHeight);
            if (minH >= maxH || minV >= maxV) {
                continue;
            }
            java.util.Arrays.fill(horizontal, minH, maxH, true);
            java.util.Arrays.fill(vertical, minV, maxV, true);
            startH = Math.min(startH, minH);
            endH = Math.max(endH, maxH);
            startV = Math.min(startV, minV);
            endV = Math.max(endV, maxV);
            if (lineHeight <= 0.0f) {
                lineHeight = r.height();
            }
        }
        int doubleLineHeight = (int) (2 * lineHeight);
        if (endV - startV < doubleLineHeight) {
            return HORIZONTAL;
        }
        if (endH - startH < doubleLineHeight) {
            return VERTICAL;
        }
        float sumH = filled(horizontal, startH, endH);
        if (sumH > 0.8f) {
            return HORIZONTAL;
        }
        float sumV = filled(vertical, startV, endV);
        if (sumH > sumV) {
            return HORIZONTAL;
        }
        return sumH < sumV ? VERTICAL : UNKNOWN;
    }

    private static float clamp(float v, int max) {
        return Math.clamp(v, 0.0f, (float) max);
    }

    /** {@code MaskPercentFilled}: the share of a span of the mask that is set. */
    private static float filled(boolean[] mask, int start, int end) {
        if (start >= end) {
            return 0;
        }
        int count = 0;
        for (int i = start; i < end; i++) {
            if (mask[i]) {
                count++;
            }
        }
        return (float) count / (end - start);
    }
}
