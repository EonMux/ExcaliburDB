package com.eonmux.excalibur.ocr.pdf;

import java.util.ArrayList;
import java.util.List;

/**
 * The character lists of pdfium's text page: the page's finished characters
 * ({@code char_list_}, {@code text_buf_}) and the line being built ({@code temp_char_list_},
 * {@code temp_text_buf_}), with the steps that move a line into the page.
 *
 * <p>The two text buffers hold what pdfium's searches and space tests read; the character lists
 * are what {@code FPDFText_GetUnicode} and {@code FPDFText_GetCharBox} report. A line is closed
 * by dropping repeated spaces, splitting it into runs of one direction and copying the runs into
 * the page, right-to-left runs reversed, mirrored and normalized.</p>
 */
final class TextBuffers {

    private final boolean rightToLeft;
    private final List<CharInfo> chars = new ArrayList<>();
    private final WideBuffer text = new WideBuffer();
    private final List<CharInfo> tempChars = new ArrayList<>();
    private final WideBuffer tempText = new WideBuffer();

    /**
     * @param rightToLeft Whether the document asks for right-to-left reading
     *                    ({@code /ViewerPreferences /Direction /R2L})
     */
    TextBuffers(boolean rightToLeft) {
        this.rightToLeft = rightToLeft;
    }

    List<CharInfo> chars() {
        return chars;
    }

    WideBuffer text() {
        return text;
    }

    WideBuffer tempText() {
        return tempText;
    }

    int tempSize() {
        return tempChars.size();
    }

    CharInfo tempChar(int i) {
        return tempChars.get(i);
    }

    /** Adds a character and its unit to the line being built. */
    void appendTemp(int unit, CharInfo info) {
        tempText.append(unit);
        tempChars.add(info);
    }

    /** Drops the last character of the line being built. */
    void dropLastTemp() {
        tempText.deleteLast();
        tempChars.removeLast();
    }

    /** The text that space tests look at: the line being built, else the page. */
    WideBuffer currentText() {
        return tempText.isEmpty() ? text : tempText;
    }

    /** The last character, of the line being built or else the page: {@code GetPrevCharInfo}. */
    CharInfo previous() {
        if (!tempChars.isEmpty()) {
            return tempChars.getLast();
        }
        return chars.isEmpty() ? null : chars.getLast();
    }

    /**
     * Reverses what an object added to the line: {@code ReverseTempTextBufs}.
     *
     * @param charIndex Size of the character list before the object
     * @param textIndex Size of the text before it
     */
    void reverseTemp(int charIndex, int textIndex) {
        if (charIndex < tempChars.size()) {
            java.util.Collections.reverse(tempChars.subList(charIndex, tempChars.size()));
        }
        if (textIndex < tempText.length()) {
            tempText.reverseFrom(textIndex);
        }
    }

    /**
     * Ends a line after an object whose first character is a hyphen continuation:
     * {@code ProcessGenerateCharacter(kHyphen)}. Trailing spaces go, and the last character
     * becomes pdfium's hyphen mark.
     */
    void markHyphen() {
        while (!tempText.isEmpty() && tempText.back() == ' ') {
            dropLastTemp();
        }
        if (tempChars.isEmpty()) {
            return;
        }
        CharInfo last = tempChars.getLast();
        tempText.deleteLast();
        tempChars.set(tempChars.size() - 1, last.withType(CharInfo.Type.HYPHEN).withUnicode(0x2));
        tempText.append(0xFFFE);
    }

    /**
     * A space or line break placed after the last character: {@code GenerateCharInfo} and
     * {@code AppendGeneratedCharacter}.
     *
     * @param unit       The character
     * @param formMatrix The form the next object is in
     * @param temp       Whether it goes to the line being built rather than the page
     */
    void appendGenerated(int unit, FxMatrix formMatrix, boolean temp) {
        CharInfo prev = previous();
        if (prev == null) {
            return;
        }
        int preWidth = 0;
        TextObject obj = prev.textObject();
        if (obj != null && prev.charCode() != PdfFont.INVALID_CODE) {
            preWidth = TextMetrics.charWidth(prev.charCode(), obj.font());
        }
        float fontSize = obj != null ? obj.fontSize() : prev.box().height();
        if (fontSize == 0) {
            fontSize = 1f;
        }
        FxPoint origin = new FxPoint(prev.origin().x() + preWidth * fontSize / 1000,
            prev.origin().y());
        CharInfo info = new CharInfo(CharInfo.Type.GENERATED, unit, PdfFont.INVALID_CODE, origin,
            new FxRect(origin.x(), origin.y(), origin.x(), origin.y()), formMatrix, null);
        if (temp) {
            appendTemp(unit, info);
        } else {
            text.append(unit);
            chars.add(info);
        }
    }

    /**
     * Whether a line break after the current text is a word broken by a hyphen:
     * {@code IsHyphen}.
     *
     * @param current The first character of the next object
     * @return True when the text ends in a hyphen after a letter and the next character is a
     *         letter or digit, or the hyphen came from a split or replaced character
     */
    boolean isHyphen(int current) {
        WideBuffer t = currentText();
        if (t.isEmpty()) {
            return false;
        }
        int i = t.length() - 1;
        while (i > 0 && t.get(i) == 0x20) {
            i--;
        }
        if (!TextMetrics.isHyphenCode(t.get(i))) {
            return false;
        }
        if (i > 0 && PyChars.isWAlpha(t.get(i - 1)) && PyChars.isWAlnum(current)) {
            return true;
        }
        CharInfo prev = previous();
        return prev != null
            && (prev.type() == CharInfo.Type.PIECE || prev.type() == CharInfo.Type.ACTUAL_TEXT)
            && TextMetrics.isHyphenCode(prev.unicode());
    }

    /**
     * Moves the line being built into the page: {@code CloseTempLine}.
     */
    void closeTempLine() {
        if (tempChars.isEmpty()) {
            return;
        }
        dropRepeatedSpaces();
        int[] str = tempText.toArray();
        Bidi.Line line = Bidi.split(str);
        if (rightToLeft) {
            line = Bidi.rightToLeft(line);
        }
        Bidi.Direction current = line.direction();
        for (Bidi.Segment segment : line.segments()) {
            int start = segment.start();
            int end = start + segment.count();
            if (segment.direction() == Bidi.Direction.RIGHT
                || (segment.direction() == Bidi.Direction.NEUTRAL
                && current == Bidi.Direction.RIGHT)) {
                current = Bidi.Direction.RIGHT;
                boolean actualText = segment.count() > 0
                    && tempChars.get(start).type() == CharInfo.Type.ACTUAL_TEXT;
                if (actualText) {
                    for (int i = start; i < end; i++) {
                        addCharInfo(str[i], tempChars.get(i), true);
                    }
                } else {
                    for (int i = end - 1; i >= start; i--) {
                        addCharInfo(str[i], tempChars.get(i), true);
                    }
                }
            } else {
                if (segment.direction() != Bidi.Direction.LEFT_WEAK) {
                    current = Bidi.Direction.LEFT;
                }
                for (int i = start; i < end; i++) {
                    addCharInfo(str[i], tempChars.get(i), false);
                }
            }
        }
        tempChars.clear();
        tempText.clear();
    }

    /** Of each run of spaces in the line, keeps the first. */
    private void dropRepeatedSpaces() {
        boolean prevSpace = false;
        for (int i = 0; i < tempText.length(); i++) {
            if (tempText.get(i) != ' ') {
                prevSpace = false;
                continue;
            }
            if (prevSpace) {
                tempText.delete(i);
                tempChars.remove(i);
                i--;
            }
            prevSpace = true;
        }
    }

    /**
     * Adds one character of a closed line to the page: {@code AddCharInfo}. Characters of a
     * right-to-left run are mirrored and normalized, and so become pieces even when
     * normalization leaves them as they are; ligatures U+FB00 to U+FB06 are split.
     */
    private void addCharInfo(int unit, CharInfo info, boolean rtl) {
        if (!TextMetrics.isNormalCharacter(info)) {
            chars.add(info);
            return;
        }
        int wc = rtl ? Bidi.mirror(unit) : unit;
        if (!rtl && (wc < 0xFB00 || wc > 0xFB06)) {
            text.append(wc);
            chars.add(info);
            return;
        }
        CharInfo piece = info.withType(CharInfo.Type.PIECE);
        for (int c : Bidi.normalize(wc)) {
            text.append(c);
            chars.add(piece.withUnicode(c));
        }
    }
}
