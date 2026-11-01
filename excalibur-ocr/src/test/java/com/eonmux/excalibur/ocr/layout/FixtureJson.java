package com.eonmux.excalibur.ocr.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the JSON the fixture generators write with Python's {@code json.dumps}: objects as
 * maps, arrays as lists, strings, numbers as {@link Long} or {@link Double}, booleans and null.
 * {@code \\u} escapes, surrogate pairs among them, become the UTF-16 units they name.
 */
public final class FixtureJson {

    private final String text;
    private int pos;

    private FixtureJson(String text) {
        this.text = text;
    }

    /**
     * Parses a JSON document.
     *
     * @param text The document
     * @return Its value
     */
    public static Object parse(String text) {
        FixtureJson p = new FixtureJson(text);
        Object value = p.value();
        p.skipSpace();
        if (p.pos != text.length()) {
            throw p.error("trailing text");
        }
        return value;
    }

    private Object value() {
        skipSpace();
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipSpace();
            String key = string();
            skipSpace();
            expect(':');
            out.put(key, value());
            skipSpace();
            if (peek() == ',') {
                pos++;
            } else {
                expect('}');
                return out;
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(value());
            skipSpace();
            if (peek() == ',') {
                pos++;
            } else {
                expect(']');
                return out;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = text.charAt(pos++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(text, pos, pos + 4, 16));
                    pos += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String s = text.substring(start, pos);
        if (s.isEmpty()) {
            throw error("unexpected character");
        }
        if (s.contains(".") || s.contains("e") || s.contains("E")) {
            return Double.parseDouble(s);
        }
        return Long.parseLong(s);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("expected " + word);
        }
        pos += word.length();
        return value;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(pos);
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON " + what + " at " + pos);
    }
}
