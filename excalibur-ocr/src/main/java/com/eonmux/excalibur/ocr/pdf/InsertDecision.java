package com.eonmux.excalibur.ocr.pdf;

/**
 * What pdfium's text page puts between two text objects of a line: nothing, a space, a line
 * break, or a line break that joins a hyphenated word ({@code CPDF_TextPage::ProcessInsertObject}
 * and {@code GetTextObjectWritingMode}).
 *
 * <p>A line ends where two objects' boxes stop overlapping across the writing direction, or where
 * the next object starts well above or below the previous one's baseline. Otherwise a space goes
 * in when the gap after the previous character is wider than a share of the characters'
 * widths.</p>
 */
final class InsertDecision {

    /** pdfium's {@code GenerateCharacter}. */
    enum Generate { NONE, SPACE, LINE_BREAK, HYPHEN }

    private static final float WRITING_EPSILON = 0.0001f;
    private static final float WRITING_THRESHOLD = 0.0872f;

    private final TextBuffers buffers;
    private final FxMatrix display;
    private final FlowOrientation pageDirection;

    InsertDecision(TextBuffers buffers, FxMatrix display, FlowOrientation pageDirection) {
        this.buffers = buffers;
        this.display = display;
        this.pageDirection = pageDirection;
    }

    /**
     * The decision between the previous object and the next.
     *
     * @param obj        The next object
     * @param formMatrix Its form's matrix
     * @param prev       The previous object
     * @param prevMatrix The matrix pdfium holds as the previous object's form matrix
     * @param lineRect   The bounds of the current line's objects
     * @return What goes between them
     */
    Generate decide(TextObject obj, FxMatrix formMatrix, TextObject prev, FxMatrix prevMatrix,
                    FxRect lineRect) {
        FlowOrientation mode = writingMode(obj);
        if (mode == FlowOrientation.UNKNOWN) {
            mode = writingMode(prev);
        }
        int current = TextMetrics.firstChar(obj);
        if (endsLine(mode, obj, prev, lineRect)) {
            return breakAt(current);
        }
        int last = prev.count() - 1;
        float lastPos = prev.origin(last).x();
        int lastWidthUnits = TextMetrics.charWidth(prev.code(last), prev.font());
        float lastWidth = Math.abs(lastWidthUnits * prev.fontSize() / 1000);
        int thisWidthUnits = TextMetrics.charWidth(obj.code(0), obj.font());
        float thisWidth = Math.abs(thisWidthUnits * obj.fontSize() / 1000);
        float threshold = Math.max(lastWidth, thisWidth) / 4;
        FxMatrix prevInverse = prev.matrix().times(prevMatrix).inverse();
        FxPoint pos = prevInverse.apply(formMatrix.apply(obj.pos()));
        if (lastWidth < thisWidth) {
            threshold = prevInverse.distance(threshold);
        }
        if (mode == FlowOrientation.HORIZONTAL && isNewLine(obj, prev, pos, threshold)) {
            return breakAt(current);
        }
        if (obj.count() == 1 && TextMetrics.isHyphenCode(current) && buffers.isHyphen(current)) {
            return Generate.HYPHEN;
        }
        if (current == ' ') {
            return Generate.NONE;
        }
        String prevText = prev.unicode(prev.code(last), prev.codeLength(last));
        if (!prevText.isEmpty() && prevText.charAt(prevText.length() - 1) == ' ') {
            return Generate.NONE;
        }
        float threshold2 = Math.max(lastWidthUnits, thisWidthUnits);
        threshold2 = TextMetrics.normalizeThreshold(threshold2, 400, 700, 800);
        if (lastWidthUnits >= thisWidthUnits) {
            threshold2 *= Math.abs(prev.fontSize());
        } else {
            threshold2 *= Math.abs(obj.fontSize());
            threshold2 = obj.matrix().times(formMatrix).distance(threshold2);
            threshold2 = prevInverse.distance(threshold2);
        }
        threshold2 /= 1000;
        if ((threshold2 < 1.4881 && threshold2 > 1.4879)
            || (threshold2 < 1.39001 && threshold2 > 1.38999)) {
            threshold2 *= 1.5;
        }
        return TextMetrics.generateSpace(pos, lastPos, thisWidth, lastWidth, threshold2)
            ? Generate.SPACE : Generate.NONE;
    }

    private Generate breakAt(int current) {
        return buffers.isHyphen(current) ? Generate.HYPHEN : Generate.LINE_BREAK;
    }

    private static boolean endsLine(FlowOrientation mode, TextObject obj, TextObject prev,
                                    FxRect lineRect) {
        return switch (mode) {
            case HORIZONTAL -> TextMetrics.endHorizontalLine(obj.rect(), prev.rect());
            case VERTICAL -> TextMetrics.endVerticalLine(obj.rect(), prev.rect(), lineRect,
                obj.fontSize(), prev.fontSize());
            case UNKNOWN -> false;
        };
    }

    /**
     * Whether the next object starts on another line of horizontal text, measured in the
     * previous object's text space; an object still inside the previous one's band on an upright
     * page is no new line.
     */
    private boolean isNewLine(TextObject obj, TextObject prev, FxPoint pos, float threshold) {
        FxRect rect = prev.rect();
        float rectHeight = rect.height();
        rect = rect.normalized();
        boolean newLine = (rect.isEmpty() && rectHeight > 5)
            || ((pos.y() > threshold * 2 || pos.y() < threshold * -3)
            && (Math.abs(pos.y()) >= 1 || Math.abs(pos.y()) > Math.abs(pos.x())));
        if (!newLine || prev.count() <= 1) {
            return newLine;
        }
        FxMatrix m = prev.matrix();
        boolean upright = display.a() > 0.9 && display.b() < 0.1 && display.c() < 0.1
            && display.d() < -0.9 && m.b() < 0.1 && m.c() < 0.1;
        if (prev.origin(prev.count() - 1).x() > prev.origin(0).x() && upright) {
            FxRect band = new FxRect(0, prev.rect().bottom(), 1000, prev.rect().top());
            if (band.contains(obj.pos())) {
                return false;
            }
            FxRect thisBand = new FxRect(0, obj.rect().bottom(), 1000, obj.rect().top());
            return !thisBand.contains(prev.pos());
        }
        return true;
    }

    /**
     * The direction an object's characters advance in, or the page's when it has one character
     * or runs diagonally: {@code GetTextObjectWritingMode}.
     */
    FlowOrientation writingMode(TextObject obj) {
        int count = obj.count();
        if (count <= 1) {
            return pageDirection;
        }
        FxMatrix m = obj.matrix();
        FxPoint first = m.apply(obj.origin(0));
        FxPoint last = m.apply(obj.origin(count - 1));
        float dx = Math.abs(last.x() - first.x());
        float dy = Math.abs(last.y() - first.y());
        if (dx <= WRITING_EPSILON && dy <= WRITING_EPSILON) {
            return FlowOrientation.UNKNOWN;
        }
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length >= 0.0001f) {
            dx /= length;
            dy /= length;
        }
        boolean underThreshold = dx <= WRITING_THRESHOLD;
        if (dy <= WRITING_THRESHOLD) {
            return underThreshold ? pageDirection : FlowOrientation.HORIZONTAL;
        }
        return underThreshold ? FlowOrientation.VERTICAL : pageDirection;
    }
}
