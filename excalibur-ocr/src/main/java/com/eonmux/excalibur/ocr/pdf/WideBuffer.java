package com.eonmux.excalibur.ocr.pdf;

import java.util.Arrays;

/**
 * A growable string of pdfium wide characters ({@code wchar_t}): pdfium's {@code CFX_WideTextBuf}
 * as its text page uses it. A unit is an int, as a raw character code above U+FFFF fits in a
 * {@code wchar_t} but not in a Java {@code char}.
 */
final class WideBuffer {

    private int[] units = new int[64];
    private int length;

    int length() {
        return length;
    }

    boolean isEmpty() {
        return length == 0;
    }

    int get(int i) {
        return units[i];
    }

    /** The last unit, or 0 when empty, as {@code WideStringView::Back}. */
    int back() {
        return length == 0 ? 0 : units[length - 1];
    }

    void append(int unit) {
        if (length == units.length) {
            units = Arrays.copyOf(units, length * 2);
        }
        units[length++] = unit;
    }

    void deleteLast() {
        length--;
    }

    void delete(int i) {
        System.arraycopy(units, i + 1, units, i, length - i - 1);
        length--;
    }

    void clear() {
        length = 0;
    }

    /** Reverses the units from {@code from} to the end. */
    void reverseFrom(int from) {
        for (int i = from, j = length - 1; i < j; i++, j--) {
            int t = units[i];
            units[i] = units[j];
            units[j] = t;
        }
    }

    int[] toArray() {
        return Arrays.copyOf(units, length);
    }
}
