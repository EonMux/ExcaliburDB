package com.eonmux.excalibur.ocr.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Python's string predicates, answered exactly as Python 3.14 answers them, for the ports of
 * Indra's text rules.
 *
 * <p>Python works on code points and reads Unicode 16.0; Java's {@link String} indexes UTF-16
 * units and Java 21 reads Unicode 15.0. Several predicates also have no Java equivalent at all:
 * {@code str.isspace} counts U+001C to U+001F and the no-break spaces that
 * {@link Character#isWhitespace(int)} does not, {@code str.isdigit} counts superscripts and
 * circled digits that {@link Character#isDigit(int)} does not, and Java has no East Asian width.
 * So each predicate here is read from a table of the code point ranges where Python's holds,
 * generated from Python itself by {@code excalibur-ocr/tools/gen_python_unicode.py}. Every
 * method takes code points, never UTF-16 units.</p>
 */
public final class PythonText {

    private static final String TABLE = "python-unicode.txt";

    private static final Ranges SPACE;
    private static final Ranges ALPHA;
    private static final Ranges LOWER;
    private static final Ranges UPPER;
    private static final Ranges DIGIT;
    private static final Ranges DECIMAL;
    private static final Ranges WIDE;

    static {
        Map<String, Ranges> table = readTable();
        SPACE = require(table, "space");
        ALPHA = require(table, "alpha");
        LOWER = require(table, "lower");
        UPPER = require(table, "upper");
        DIGIT = require(table, "digit");
        DECIMAL = require(table, "decimal");
        WIDE = require(table, "wide");
    }

    private PythonText() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Python's {@code str.isspace} for one code point.
     *
     * @param cp The code point
     * @return Whether Python counts it as whitespace
     */
    public static boolean isSpace(int cp) {
        return SPACE.contains(cp);
    }

    /**
     * Python's {@code str.isalpha} for one code point: general category Lu, Ll, Lt, Lm or Lo.
     *
     * @param cp The code point
     * @return Whether it is a letter
     */
    public static boolean isAlpha(int cp) {
        return ALPHA.contains(cp);
    }

    /**
     * Python's {@code str.islower} for one code point: the Unicode Lowercase property.
     *
     * @param cp The code point
     * @return Whether it is lowercase
     */
    public static boolean isLower(int cp) {
        return LOWER.contains(cp);
    }

    /**
     * Python's {@code str.isupper} for one code point: the Unicode Uppercase property.
     *
     * @param cp The code point
     * @return Whether it is uppercase
     */
    public static boolean isUpper(int cp) {
        return UPPER.contains(cp);
    }

    /**
     * Python's {@code str.isdigit} for one code point: a decimal digit or another digit such as
     * a superscript or a circled digit.
     *
     * @param cp The code point
     * @return Whether Python counts it as a digit
     */
    public static boolean isDigit(int cp) {
        return DIGIT.contains(cp);
    }

    /**
     * Python's {@code str.isdecimal} for one code point, which is also what {@code \d} matches in
     * a Python regular expression over {@code str}: general category Nd.
     *
     * @param cp The code point
     * @return Whether it is a decimal digit
     */
    public static boolean isDecimal(int cp) {
        return DECIMAL.contains(cp);
    }

    /**
     * Whether {@code unicodedata.east_asian_width} of the code point is {@code W} or {@code F}:
     * a character that takes two columns, such as a CJK ideograph or a fullwidth letter.
     *
     * @param cp The code point
     * @return Whether it is wide
     */
    public static boolean isWide(int cp) {
        return WIDE.contains(cp);
    }

    /**
     * Python's {@code str.isdigit} for a string: at least one code point, every one a digit.
     *
     * @param text The string
     * @return Whether Python's {@code text.isdigit()} is true
     */
    public static boolean isDigit(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return !text.isEmpty() && text.codePoints().allMatch(PythonText::isDigit);
    }

    /**
     * Python's {@code str.strip()}: the string without leading and trailing whitespace.
     *
     * @param text The string
     * @return It stripped
     */
    public static String strip(String text) {
        Objects.requireNonNull(text, "text must not be null");
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && isSpace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    /**
     * Python's {@code str.split()}: the words between runs of whitespace.
     *
     * @param text The string
     * @return Its words, none empty
     */
    public static List<String> split(String text) {
        Objects.requireNonNull(text, "text must not be null");
        List<String> words = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && isSpace(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i));
            }
            int start = i;
            while (i < n && !isSpace(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i));
            }
            if (i > start) {
                words.add(text.substring(start, i));
            }
        }
        return words;
    }

    /**
     * Python's {@code " ".join(text.split())}: whitespace runs as one space, none at the ends.
     *
     * @param text The string
     * @return It with its whitespace collapsed
     */
    public static String collapseSpaces(String text) {
        return String.join(" ", split(text));
    }

    /**
     * How many letters a string holds: Python's {@code sum(c.isalpha() for c in text)}.
     *
     * @param text The string
     * @return Its letters
     */
    public static int letters(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return (int) text.codePoints().filter(PythonText::isAlpha).count();
    }

    /**
     * Python's {@code len(text)}: how many code points the string holds.
     *
     * @param text The string
     * @return Its length in code points
     */
    public static int length(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return text.codePointCount(0, text.length());
    }

    private static Ranges require(Map<String, Ranges> table, String name) {
        Ranges r = table.get(name);
        if (r == null) {
            throw new IllegalStateException(TABLE + " has no '" + name + "' line");
        }
        return r;
    }

    private static Map<String, Ranges> readTable() {
        try (InputStream in = PythonText.class.getResourceAsStream(TABLE)) {
            if (in == null) {
                throw new IllegalStateException(TABLE + " is missing from the classpath");
            }
            Map<String, Ranges> table = new HashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    String[] fields = line.strip().split(" ");
                    table.put(fields[0], Ranges.parse(Arrays.copyOfRange(fields, 1, fields.length)));
                }
            }
            return table;
        } catch (IOException e) {
            throw new UncheckedIOException("Reading " + TABLE + " failed", e);
        }
    }

    /**
     * Sorted, disjoint, inclusive code point ranges, searched by bisection.
     *
     * @param starts First code point of each range
     * @param ends   Last code point of each range
     */
    private record Ranges(int[] starts, int[] ends) {

        static Ranges parse(String[] fields) {
            int[] starts = new int[fields.length];
            int[] ends = new int[fields.length];
            for (int i = 0; i < fields.length; i++) {
                int dash = fields[i].indexOf('-');
                starts[i] = Integer.parseInt(fields[i], 0, dash, 16);
                ends[i] = Integer.parseInt(fields[i], dash + 1, fields[i].length(), 16);
            }
            return new Ranges(starts, ends);
        }

        boolean contains(int cp) {
            int i = Arrays.binarySearch(starts, cp);
            if (i >= 0) {
                return true;
            }
            int before = -i - 2;
            return before >= 0 && cp <= ends[before];
        }
    }
}
