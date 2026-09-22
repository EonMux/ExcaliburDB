package com.eonmux.excalibur.api.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON parser and writer, for the one place in this codebase that needs a real one.
 *
 * <h2>Why not {@link com.eonmux.excalibur.api.protocol.JsonText}</h2>
 * <p>That one locates a field by searching the whole document for its quoted name and reading
 * forward from the first hit, which is enough for a flat request body of known scalars. It is not
 * enough here, because the first hit is not always the field. Given
 * {@code {"context_entities":["namespace"],"namespace":"tenant-a"}} it finds the entity name and
 * answers {@code "namespace"}; given a nested object carrying the same key it answers the inner
 * one. Both are silent - a different field's value, not an error - and both are reachable here,
 * because the strings flowing through this server are entity names and questions chosen by a
 * model rather than field names chosen by us. Scope has to be parsed to be respected.</p>
 *
 * <h2>What it produces</h2>
 * <p>{@link Map} for objects, in document order; {@link List} for arrays; {@link String},
 * {@link Long} or {@link Double} for numbers, {@link Boolean}, and null. Integral numbers stay
 * integral, because a JSON-RPC id that arrives as {@code 1} and is echoed as {@code 1.0} is an id
 * the client will not match to its request.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and safe for concurrent use; a parse allocates its own cursor.</p>
 *
 * @author ExcaliburDB
 */
public final class Json {

    /**
     * Deepest nesting accepted.
     *
     * <p>Parsing is recursive and the input arrives from another process, so depth is bounded
     * rather than left to exhaust the stack.
     */
    public static final int MAX_DEPTH = 64;

    private Json() {
    }

    /**
     * Parses one JSON document.
     *
     * @param text The document
     * @return The value: Map, List, String, Long, Double, Boolean or null
     * @throws JsonException if the text is not one well-formed JSON value
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("no JSON to parse");
        }
        Cursor cursor = new Cursor(text);
        cursor.skipWhitespace();
        Object value = cursor.value(0);
        cursor.skipWhitespace();
        if (!cursor.atEnd()) {
            throw new JsonException("trailing text after the JSON value at " + cursor.index);
        }
        return value;
    }

    /**
     * Writes a value as JSON.
     *
     * @param value Map, List, CharSequence, Number, Boolean or null
     * @return The JSON text
     * @throws JsonException if the value is of a type with no JSON form
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    /**
     * The object at a field, or null when absent or not an object.
     *
     * @param value The containing value
     * @param field The field name
     * @return The nested object, or null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> objectAt(Object value, String field) {
        Object found = at(value, field);
        return found instanceof Map ? (Map<String, Object>) found : null;
    }

    /**
     * The string at a field, or null when absent or not a string.
     *
     * @param value The containing value
     * @param field The field name
     * @return The string, or null
     */
    public static String stringAt(Object value, String field) {
        Object found = at(value, field);
        return found instanceof String ? (String) found : null;
    }

    /**
     * The value at a field, or null when the container is not an object.
     *
     * @param value The containing value
     * @param field The field name
     * @return The value, or null
     */
    public static Object at(Object value, String field) {
        return value instanceof Map ? ((Map<?, ?>) value).get(field) : null;
    }

    /**
     * A new object that preserves the order fields are put in.
     *
     * @return The map
     */
    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    // ============================================
    // Writing
    // ============================================

    private static void write(Object value, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("value nests deeper than " + MAX_DEPTH);
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence text) {
            writeString(text, out);
        } else if (value instanceof Boolean flag) {
            out.append(flag.booleanValue());
        } else if (value instanceof Number number) {
            writeNumber(number, out);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out, depth + 1);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out, depth + 1);
            }
            out.append(']');
        } else {
            throw new JsonException("no JSON form for " + value.getClass().getName());
        }
    }

    private static void writeNumber(Number number, StringBuilder out) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                // JSON has no spelling for either, and emitting the Java one produces a document
                // the receiver cannot parse.
                throw new JsonException("cannot write " + d + " as JSON");
            }
            out.append(d);
        } else {
            out.append(number.longValue());
        }
    }

    private static void writeString(CharSequence value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ============================================
    // Parsing
    // ============================================

    /** Position in the text, and the productions readable from it. */
    private static final class Cursor {
        private final String text;
        private int index;

        Cursor(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("input nests deeper than " + MAX_DEPTH);
            }
            if (atEnd()) {
                throw new JsonException("expected a value at " + index);
            }
            char c = text.charAt(index);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            index++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new JsonException("expected a field name at " + index);
                }
                String field = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(field, value(depth + 1));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    index++;
                } else if (c == '}') {
                    index++;
                    return map;
                } else {
                    throw new JsonException("expected ',' or '}' at " + index);
                }
            }
        }

        private List<Object> array(int depth) {
            index++;
            List<Object> items = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return items;
            }
            while (true) {
                skipWhitespace();
                items.add(value(depth + 1));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    index++;
                } else if (c == ']') {
                    index++;
                    return items;
                } else {
                    throw new JsonException("expected ',' or ']' at " + index);
                }
            }
        }

        private String string() {
            index++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = text.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("unterminated escape");
                }
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicode());
                    default -> throw new JsonException("unknown escape '\\" + escape + "'");
                }
            }
        }

        private char unicode() {
            if (index + 4 > text.length()) {
                throw new JsonException("truncated \\u escape");
            }
            String digits = text.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException e) {
                throw new JsonException("'" + digits + "' is not a \\u escape");
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, index)) {
                throw new JsonException("expected '" + word + "' at " + index);
            }
            index += word.length();
            return value;
        }

        private Object number() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            boolean integral = true;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c >= '0' && c <= '9') {
                    index++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    index++;
                } else {
                    break;
                }
            }
            String literal = text.substring(start, index);
            if (literal.isEmpty() || "-".equals(literal)) {
                throw new JsonException("expected a number at " + start);
            }
            try {
                // An id that arrives as 1 has to be echoed as 1, not 1.0, or the client cannot
                // match the response to the request that asked for it.
                return integral ? (Object) Long.valueOf(literal) : (Object) Double.valueOf(literal);
            } catch (NumberFormatException e) {
                if (integral) {
                    // Beyond long, but still a number; keep it rather than reject the document.
                    try {
                        return Double.valueOf(literal);
                    } catch (NumberFormatException ignored) {
                        throw new JsonException("'" + literal + "' is not a number");
                    }
                }
                throw new JsonException("'" + literal + "' is not a number");
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return text.charAt(index);
        }

        private void expect(char c) {
            if (atEnd() || text.charAt(index) != c) {
                throw new JsonException("expected '" + c + "' at " + index);
            }
            index++;
        }
    }

    /** Raised when text is not JSON, or a value has no JSON form. */
    public static final class JsonException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message What was wrong
         */
        public JsonException(String message) {
            super(message);
        }
    }
}
