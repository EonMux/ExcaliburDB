package com.eonmux.excalibur.ocr.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads JSON text (RFC 8259) into plain Java values, for the model metadata Indra OCR ships as
 * JSON ({@code meta.json} and the vocabularies it embeds).
 *
 * <p>Values map as Python's {@code json.loads} maps them: an object to an unmodifiable
 * {@link Map} in document order (a repeated key keeps its last value), an array to an
 * unmodifiable {@link List}, a string to a {@link String}, a number without a fraction or
 * exponent to a {@link Long} (a {@link Double} when it does not fit), any other number to a
 * {@link Double}, {@code true}/{@code false} to a {@link Boolean} and {@code null} to
 * {@code null}. Escaped UTF-16 surrogates are kept as they are, so an escaped pair reads as one
 * supplementary character. Anything outside the grammar - trailing commas, comments, single
 * quotes, {@code NaN}, text after the value - is refused with its position.</p>
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Reads one JSON value.
     *
     * @param text The JSON text
     * @return The value, as described in the class documentation
     * @throws IllegalArgumentException if the text is not one well-formed JSON value
     */
    public static Object parse(String text) {
        Objects.requireNonNull(text, "text must not be null");
        Json reader = new Json(text);
        reader.skipSpace();
        Object value = reader.value();
        reader.skipSpace();
        if (reader.pos != text.length()) {
            throw reader.error("text after the JSON value");
        }
        return value;
    }

    /**
     * Reads a JSON object.
     *
     * @param text The JSON text
     * @return Its members in document order
     * @throws IllegalArgumentException if the text is not one well-formed JSON object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("expected a JSON object, got " + describe(value));
        }
        return (Map<String, Object>) value;
    }

    /**
     * Names a parsed value's JSON type, for messages about a value of the wrong type.
     *
     * @param value A value {@link #parse} returned
     * @return {@code object}, {@code array}, {@code string}, {@code number}, {@code boolean} or
     *     {@code null}
     */
    public static String describe(Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> m -> "object";
            case List<?> l -> "array";
            case String s -> "string";
            case Number n -> "number";
            case Boolean b -> "boolean";
            default -> value.getClass().getSimpleName();
        };
    }

    private Object value() {
        if (pos >= text.length()) {
            throw error("a value expected, found the end of the text");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object() {
        pos++;
        Map<String, Object> members = new LinkedHashMap<>();
        skipSpace();
        if (peek() == '}') {
            pos++;
            return Collections.unmodifiableMap(members);
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("a member name expected");
            }
            String name = string();
            skipSpace();
            expect(':');
            skipSpace();
            members.put(name, value());
            skipSpace();
            if (peek() == ',') {
                pos++;
            } else {
                expect('}');
                return Collections.unmodifiableMap(members);
            }
        }
    }

    private List<Object> array() {
        pos++;
        List<Object> items = new ArrayList<>();
        skipSpace();
        if (peek() == ']') {
            pos++;
            return Collections.unmodifiableList(items);
        }
        while (true) {
            skipSpace();
            items.add(value());
            skipSpace();
            if (peek() == ',') {
                pos++;
            } else {
                expect(']');
                return Collections.unmodifiableList(items);
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("control character U+" + String.format("%04X", (int) c)
                    + " in a string");
            }
            if (c == '\\') {
                out.append(escape());
            } else {
                out.append(c);
            }
        }
    }

    private char escape() {
        if (pos >= text.length()) {
            throw error("unterminated escape");
        }
        char c = text.charAt(pos++);
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> unicodeEscape();
            default -> throw error("invalid escape \\" + c);
        };
    }

    private char unicodeEscape() {
        if (pos + 4 > text.length()) {
            throw error("truncated \\u escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(pos + i), 16);
            if (digit < 0) {
                throw error("invalid \\u escape");
            }
            value = value * 16 + digit;
        }
        pos += 4;
        return (char) value;
    }

    private Number number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (!digits()) {
            throw error("a digit expected");
        }
        boolean integral = true;
        if (peek() == '.') {
            pos++;
            integral = false;
            if (!digits()) {
                throw error("a digit expected after the decimal point");
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            integral = false;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!digits()) {
                throw error("a digit expected in the exponent");
            }
        }
        String literal = text.substring(start, pos);
        if (integral) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException tooLarge) {
                return Double.parseDouble(literal);
            }
        }
        return Double.parseDouble(literal);
    }

    private boolean digits() {
        int start = pos;
        while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
            pos++;
        }
        return pos > start;
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected text");
        }
        pos += word.length();
        return value;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("'" + c + "' expected");
        }
        pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void skipSpace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Invalid JSON at offset " + pos + ": " + what);
    }
}
