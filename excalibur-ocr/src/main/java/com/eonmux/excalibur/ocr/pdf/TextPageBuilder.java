package com.eonmux.excalibur.ocr.pdf;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds pdfium's text page from a page's text objects: {@code CPDF_TextPage::ProcessObject} and
 * the steps it drives.
 *
 * <p>Objects are gathered into a line while their starts stay at about one height, ordered left
 * to right on the displayed page; an object that repeats one drawn just before (fake bold) is
 * dropped. Each finished line is then read object by object: between two objects the builder
 * decides on nothing, a space, a line break or a hyphenated line break
 * ({@link InsertDecision}), then adds the object's characters ({@link ObjectReader}), or its
 * marked-content {@code /ActualText} instead.</p>
 */
final class TextPageBuilder {

    /** pdfium's {@code kSizeEpsilon}. */
    static final float SIZE_EPSILON = 0.01f;

    /** A text object and the matrix of the form it is in: {@code TransformedTextObject}. */
    record Placed(TextObject obj, FxMatrix formMatrix) {
    }

    private final TextBuffers buffers;
    private final InsertDecision decision;
    private final ObjectReader reader;
    private final FxMatrix display;
    private final List<Placed> line = new ArrayList<>();
    private TextObject prevObj;
    private FxMatrix prevMatrix = FxMatrix.IDENTITY;
    private FxRect lineRect = FxRect.EMPTY;

    private TextPageBuilder(PdfPage page, List<ContentItem> items, boolean rightToLeft) {
        this.buffers = new TextBuffers(rightToLeft);
        this.display = page.displayMatrix();
        this.decision = new InsertDecision(buffers, display,
            FlowOrientation.of(items, page.widthF(), page.heightF()));
        this.reader = new ObjectReader(buffers);
    }

    /**
     * The characters of a page's text, in pdfium's order.
     *
     * @param page        The page
     * @param items       Its content, as {@link ContentCollector} reads it
     * @param rightToLeft Whether the document reads right to left
     * @return The characters
     */
    static List<CharInfo> build(PdfPage page, List<ContentItem> items, boolean rightToLeft) {
        TextPageBuilder builder = new TextPageBuilder(page, items, rightToLeft);
        if (!items.isEmpty()) {
            builder.processHolder(items);
            builder.processLine();
            builder.line.clear();
            builder.buffers.closeTempLine();
        }
        return List.copyOf(builder.buffers.chars());
    }

    /** The objects of the page or a form: {@code ProcessObject}, {@code ProcessFormObject}. */
    private void processHolder(List<ContentItem> items) {
        for (int i = 0; i < items.size(); i++) {
            switch (items.get(i)) {
                case TextObject t -> processTextObject(t, items, i);
                case ContentItem.Form f -> processHolder(f.items());
            }
        }
    }

    /** Adds an object to the current line, or starts a new one: {@code ProcessTextObject}. */
    private void processTextObject(TextObject obj, List<ContentItem> holder, int index) {
        if (Math.abs(obj.rect().width()) < SIZE_EPSILON) {
            return;
        }
        FxMatrix formMatrix = obj.formMatrix();
        Placed placed = new Placed(obj, formMatrix);
        if (line.isEmpty()) {
            line.add(placed);
            return;
        }
        if (isSameAsPreTextObject(obj, holder, index)) {
            return;
        }
        Placed prev = line.getLast();
        TextObject p = prev.obj();
        float prevWidth = TextMetrics.charWidth(p.code(p.count() - 1), p.font()) * p.fontSize()
            / 1000;
        prevWidth = p.matrix().times(prev.formMatrix()).distance(Math.abs(prevWidth));
        float thisWidth = Math.abs(TextMetrics.charWidth(obj.code(0), obj.font())
            * obj.fontSize() / 1000);
        thisWidth = obj.matrix().times(formMatrix).distance(Math.abs(thisWidth));
        float threshold = Math.max(prevWidth, thisWidth) / 4;
        FxPoint prevPos = displayed(prev);
        FxPoint thisPos = displayed(placed);
        if (Math.abs(thisPos.y() - prevPos.y()) > threshold * 2) {
            processLine();
            line.clear();
            line.add(placed);
            return;
        }
        for (int i = line.size(); i > 0; i--) {
            if (thisPos.x() >= displayed(line.get(i - 1)).x()) {
                line.add(i, placed);
                return;
            }
        }
        line.addFirst(placed);
    }

    private FxPoint displayed(Placed p) {
        return display.apply(p.formMatrix().apply(p.obj().pos()));
    }

    /**
     * Whether one of the five text objects drawn before this one in the same content stream
     * shows the same codes at nearly the same place: {@code IsSameAsPreTextObject}.
     */
    private boolean isSameAsPreTextObject(TextObject obj, List<ContentItem> holder, int index) {
        int checked = 0;
        int i = index;
        while (checked < 5 && i > 0) {
            i--;
            if (!(holder.get(i) instanceof TextObject other) || other == obj) {
                continue;
            }
            if (isSameTextObject(other, obj)) {
                return true;
            }
            checked++;
        }
        return false;
    }

    /** {@code IsSameTextObject(text_obj1, text_obj2)}. */
    private boolean isSameTextObject(TextObject obj1, TextObject obj2) {
        FxRect prevRect = obj2.rect();
        FxRect curRect = obj1.rect();
        if (prevRect.isEmpty() && curRect.isEmpty()) {
            float xdiff = Math.abs(prevRect.left() - curRect.left());
            List<CharInfo> chars = buffers.chars();
            if (chars.size() >= 2 && xdiff > chars.get(chars.size() - 2).box().width()) {
                return false;
            }
        }
        if (!prevRect.isEmpty() || !curRect.isEmpty()) {
            prevRect = prevRect.intersect(curRect);
            if (prevRect.isEmpty()) {
                return false;
            }
            if (Math.abs(prevRect.width() - curRect.width()) > curRect.width() / 2) {
                return false;
            }
            if (obj2.fontSize() != obj1.fontSize()) {
                return false;
            }
        }
        int n = obj2.count();
        if (n != obj1.count()) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (obj1.code(i) != obj2.code(i)) {
                return false;
            }
        }
        float dx = obj1.pos().x() - obj2.pos().x();
        float dy = obj1.pos().y() - obj2.pos().y();
        float fontSize = obj2.fontSize();
        float charSize = TextMetrics.charWidth(obj2.code(n - 1), obj2.font());
        float maxPreSize = Math.max(Math.max(prevRect.height(), prevRect.width()), fontSize);
        return Math.abs(dx) <= 0.9 * charSize * fontSize / 1000
            && Math.abs(dy) <= maxPreSize / 8;
    }

    /** Reads the objects of a finished line: {@code ProcessTransformedTextObjects}. */
    private void processLine() {
        for (Placed placed : line) {
            TextObject obj = placed.obj();
            if (Math.abs(obj.rect().width()) < SIZE_EPSILON) {
                continue;
            }
            FxMatrix formMatrix = placed.formMatrix();
            MarkedContent.State state = MarkedContent.before(obj, prevObj);
            if (state == MarkedContent.State.DONE) {
                prevObj = obj;
                prevMatrix = formMatrix;
                continue;
            }
            if (prevObj != null) {
                if (!insertBetween(obj, formMatrix)) {
                    continue;
                }
            } else {
                lineRect = obj.rect();
            }
            int charIndex = buffers.tempSize();
            int textIndex = buffers.tempText().length();
            boolean reverse;
            if (state == MarkedContent.State.DELAY) {
                reverse = MarkedContent.addActualText(placed, prevObj, buffers);
            } else {
                reverse = reader.read(obj, formMatrix, obj.matrix().times(formMatrix));
            }
            prevObj = obj;
            prevMatrix = formMatrix;
            if (reverse) {
                buffers.reverseTemp(charIndex, textIndex);
            }
        }
    }

    /**
     * Decides what goes between the previous object and this one and adds it; false when this
     * object is a hyphen that ends the line and is to be skipped.
     */
    private boolean insertBetween(TextObject obj, FxMatrix formMatrix) {
        CharInfo prevChar = buffers.previous();
        if (prevChar != null && prevChar.textObject() != null) {
            prevObj = prevChar.textObject();
        }
        InsertDecision.Generate type = decision.decide(obj, formMatrix, prevObj, prevMatrix,
            lineRect);
        lineRect = type == InsertDecision.Generate.LINE_BREAK ? obj.rect()
            : lineRect.union(obj.rect());
        return generate(type, obj, formMatrix);
    }

    /** {@code ProcessGenerateCharacter}. */
    private boolean generate(InsertDecision.Generate type, TextObject obj, FxMatrix formMatrix) {
        switch (type) {
            case SPACE -> buffers.appendGenerated(' ', formMatrix, true);
            case LINE_BREAK -> {
                buffers.closeTempLine();
                if (!buffers.text().isEmpty()) {
                    buffers.appendGenerated('\r', formMatrix, false);
                    buffers.appendGenerated('\n', formMatrix, false);
                }
            }
            case HYPHEN -> {
                if (obj.count() == 1 && TextMetrics.isHyphenCode(TextMetrics.firstChar(obj))) {
                    return false;
                }
                buffers.markHyphen();
            }
            case NONE -> {
                // Nothing goes between the objects.
            }
        }
        return true;
    }
}
