package com.eonmux.excalibur.ocr.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A reader for the JSON fixtures that Python's {@code json.dumps} writes: objects become ordered
 * maps, arrays lists, integers {@code Long}, other numbers {@code Double}.
 */
final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /**
     * The value a JSON text holds.
     *
     * @param text The text
     * @return The value
     */
    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.skipSpace();
        if (json.pos != text.length()) {
            throw json.error("trailing text");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    static int integer(Object value) {
        return ((Number) value).intValue();
    }

    private Object value() {
        skipSpace();
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> objectValue();
            case '[' -> arrayValue();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> numberValue();
        };
    }

    private Map<String, Object> objectValue() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (text.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            String key = string();
            skipSpace();
            expect(':');
            map.put(key, value());
            skipSpace();
            if (text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            expect(',');
        }
    }

    private List<Object> arrayValue() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipSpace();
        if (text.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(value());
            skipSpace();
            if (text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            expect(',');
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = text.charAt(pos++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> out.append(e);
            }
        }
    }

    private Object numberValue() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String s = text.substring(start, pos);
        if (s.isEmpty()) {
            throw error("unexpected character");
        }
        boolean integral = s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0;
        return integral ? (Object) Long.parseLong(s) : (Object) Double.parseDouble(s);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected literal");
        }
        pos += word.length();
        return value;
    }

    private void expect(char c) {
        if (text.charAt(pos) != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at offset " + pos);
    }
}
