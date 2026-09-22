package com.eonmux.excalibur.fsck.report;

/**
 * The little JSON writing this tool needs, without a dependency to ship it.
 *
 * <p>The report is a fixed shape of strings, numbers and arrays, and the only thing that
 * can go wrong in writing it is an unescaped character in a detail message or a file name -
 * which is exactly what this handles, and the reason the strings are not simply
 * concatenated at each call site.</p>
 */
public final class Json {

    private Json() {
        throw new AssertionError("Utility class");
    }

    /**
     * Renders a string as a JSON string literal, or as null.
     *
     * @param value The value, which may be null
     * @return The literal, quoted and escaped
     */
    public static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            append(text, value.charAt(i));
        }
        return text.append('"').toString();
    }

    /**
     * Appends one character in its JSON form.
     *
     * @param text  Where to append
     * @param value The character
     */
    private static void append(StringBuilder text, char value) {
        switch (value) {
            case '"' -> text.append("\\\"");
            case '\\' -> text.append("\\\\");
            case '\n' -> text.append("\\n");
            case '\r' -> text.append("\\r");
            case '\t' -> text.append("\\t");
            case '\b' -> text.append("\\b");
            case '\f' -> text.append("\\f");
            default -> appendPlain(text, value);
        }
    }

    /**
     * Appends a character that needs no named escape.
     *
     * @param text  Where to append
     * @param value The character
     */
    private static void appendPlain(StringBuilder text, char value) {
        if (value < 0x20) {
            text.append(String.format("\\u%04x", (int) value));
        } else {
            text.append(value);
        }
    }
}
