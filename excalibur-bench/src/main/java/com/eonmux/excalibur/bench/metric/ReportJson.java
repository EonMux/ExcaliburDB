package com.eonmux.excalibur.bench.metric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete JSON reader for the reports this module writes, and a matching string
 * encoder.
 *
 * <p>Values map to {@link Map} (insertion-ordered), {@link List}, {@link String},
 * {@link Double}, {@link Boolean} and {@code null}. The one departure from RFC 8259 is
 * that the bare tokens {@code NaN}, {@code Infinity} and {@code -Infinity} are accepted
 * as numbers: {@link BenchmarkResult#toJson()} appends raw doubles, so a report holding
 * a non-finite score is still one this module wrote and must still be readable.</p>
 *
 * <p>Anything else malformed is rejected with {@link IllegalArgumentException}. A reader
 * that skipped what it could not parse would turn a damaged report into an apparently
 * clean one.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Stateless entry points; each parse uses its own cursor.</p>
 */
final class ReportJson {

    private final String text;
    private int pos;

    private ReportJson(String text) {
        this.text = text;
    }

    /**
     * Parses one complete JSON document.
     *
     * @param text the document
     * @return the value it holds
     * @throws IllegalArgumentException if the text is not one well-formed value
     */
    static Object parse(String text) {
        ReportJson reader = new ReportJson(text);
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (reader.pos != text.length()) {
            throw reader.error("trailing content");
        }
        return value;
    }

    /**
     * Encodes a string as a JSON string literal, quotes included.
     *
     * <p>Every control character is escaped, not only the common ones: a stack trace
     * captured from a benchmark's console can hold anything the benchmark printed.</p>
     *
     * @param s the string
     * @return the literal
     */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            case 'N' -> readLiteral("NaN", Double.NaN);
            case 'I' -> readLiteral("Infinity", Double.POSITIVE_INFINITY);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> readObject() {
        pos++;
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) {
            return Collections.unmodifiableMap(object);
        }
        do {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw error("expected a field name");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            object.put(key, readValue());
            skipWhitespace();
        } while (consume(','));
        expect('}');
        return Collections.unmodifiableMap(object);
    }

    private List<Object> readArray() {
        pos++;
        List<Object> array = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) {
            return Collections.unmodifiableList(array);
        }
        do {
            array.add(readValue());
            skipWhitespace();
        } while (consume(','));
        expect(']');
        return Collections.unmodifiableList(array);
    }

    private String readString() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                break;
            }
            char escape = text.charAt(pos++);
            switch (escape) {
                case '"', '\\', '/' -> sb.append(escape);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("truncated unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("malformed unicode escape");
                    }
                    pos += 4;
                }
                default -> throw error("unknown escape '\\" + escape + "'");
            }
        }
        throw error("unterminated string");
    }

    private Double readNumber() {
        int start = pos;
        if (text.startsWith("-Infinity", pos)) {
            pos += "-Infinity".length();
            return Double.NEGATIVE_INFINITY;
        }
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("malformed number '" + text.substring(start, pos) + "'");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, pos)) {
            throw error("unexpected token");
        }
        pos += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private boolean consume(char c) {
        if (pos < text.length() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!consume(c)) {
            throw error("expected '" + c + "'");
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("malformed JSON at offset " + pos + ": " + what);
    }
}
