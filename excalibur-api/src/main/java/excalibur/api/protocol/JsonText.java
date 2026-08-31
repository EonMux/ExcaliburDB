package excalibur.api.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal JSON reading and writing helpers shared by the HTTP endpoints.
 *
 * <p>The API deliberately carries no JSON dependency. Keeping the hand-rolled
 * parsing and escaping in one place is what stops four endpoints from drifting
 * apart - and stops a locale-sensitive number format from reaching a response
 * body.</p>
 *
 * <h2>Number formatting</h2>
 * <p>Every floating-point value is formatted with {@link Locale#ROOT}. The default
 * locale decides the decimal separator, so {@code String.format("%.4f", 0.5)} emits
 * {@code 0,5000} on a machine running under, for example, {@code de_DE} - which is
 * not valid JSON and breaks every client parser.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class JsonText {

    private JsonText() {
        // Utility class
    }

    /**
     * Formats a double for a JSON body using a locale-independent decimal point.
     *
     * @param value    The value
     * @param decimals Number of fractional digits
     * @return The formatted number
     */
    public static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /**
     * Escapes a string for inclusion in a JSON string literal.
     *
     * <p>Every character below {@code U+0020} is escaped, not just the five with short
     * forms. RFC 8259 section 7 forbids them raw, and they are reachable: {@link #unescape}
     * decodes {@code \\u0001} in a request into a real control character, which the endpoints
     * then echo back in an error message or a document id. A response carrying one is
     * rejected outright by every strict parser, so a 404 the client could have acted on
     * arrives as a parse failure instead.</p>
     *
     * @param value The raw value (may be null)
     * @return The escaped value; empty string for null
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Decodes JSON string escapes in a single pass.
     *
     * @param value The escaped value (may be null)
     * @return The decoded value, or null when the input was null
     */
    public static String unescape(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(i + 1);
            switch (next) {
                case '"' -> { sb.append('"'); i++; }
                case '\\' -> { sb.append('\\'); i++; }
                case '/' -> { sb.append('/'); i++; }
                case 'n' -> { sb.append('\n'); i++; }
                case 'r' -> { sb.append('\r'); i++; }
                case 't' -> { sb.append('\t'); i++; }
                case 'b' -> { sb.append('\b'); i++; }
                case 'f' -> { sb.append('\f'); i++; }
                case 'u' -> i = appendUnicode(value, i, sb);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int appendUnicode(String value, int backslashIndex, StringBuilder sb) {
        if (backslashIndex + 5 < value.length()) {
            try {
                sb.append((char) Integer.parseInt(value.substring(backslashIndex + 2, backslashIndex + 6), 16));
                return backslashIndex + 5;
            } catch (NumberFormatException e) {
                sb.append('\\');
                return backslashIndex;
            }
        }
        sb.append('\\');
        return backslashIndex;
    }

    /**
     * Extracts a string field.
     *
     * @param json  The JSON document
     * @param field The field name
     * @return The decoded value, or null when absent
     */
    public static String string(String json, String field) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) {
            return null;
        }
        start = json.indexOf('"', start + pattern.length() + 1);
        if (start < 0) {
            return null;
        }
        int end = closingQuote(json, start + 1);
        if (end < 0) {
            return null;
        }
        return unescape(json.substring(start + 1, end));
    }

    /**
     * Finds the closing quote of a JSON string, skipping escaped characters.
     *
     * @param json The JSON document
     * @param from Index just after the opening quote
     * @return Index of the closing quote, or -1 when unterminated
     */
    public static int closingQuote(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extracts a nested object as raw JSON text.
     *
     * @param json  The JSON document
     * @param field The field name
     * @return The object text including braces, or null when absent
     */
    public static String object(String json, String field) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) {
            return null;
        }
        start = json.indexOf('{', start);
        if (start < 0) {
            return null;
        }
        int depth = 1;
        int end = start + 1;
        boolean inString = false;
        boolean escaped = false;
        while (end < json.length() && depth > 0) {
            char c = json.charAt(end);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
            end++;
        }
        return json.substring(start, end);
    }

    /**
     * Extracts an array of strings.
     *
     * @param json  The JSON document
     * @param field The field name
     * @return The decoded values, or null when absent or malformed
     */
    public static String[] stringArray(String json, String field) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) {
            return null;
        }
        start = json.indexOf('[', start);
        if (start < 0) {
            return null;
        }
        List<String> items = new ArrayList<>();
        int i = start + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ']') {
                return items.toArray(new String[0]);
            }
            if (c == '"') {
                int closeQuote = closingQuote(json, i + 1);
                if (closeQuote < 0) {
                    return null;
                }
                items.add(unescape(json.substring(i + 1, closeQuote)));
                i = closeQuote + 1;
            } else {
                i++;
            }
        }
        return null;
    }

    /**
     * Extracts an integer field.
     *
     * @param json         The JSON document
     * @param field        The field name
     * @param defaultValue Value returned when the field is absent or not a number
     * @return The parsed value
     * @throws IllegalArgumentException if the field is present but is not an integer
     */
    public static int integer(String json, String field, int defaultValue) {
        String token = numericToken(json, field, false);
        if (token == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field '" + field + "' is not a valid integer");
        }
    }

    /**
     * Extracts a long field.
     *
     * @param json         The JSON document
     * @param field        The field name
     * @param defaultValue Value returned when the field is absent or not a number
     * @return The parsed value
     * @throws IllegalArgumentException if the field is present but is not an integer
     */
    public static long longValue(String json, String field, long defaultValue) {
        String token = numericToken(json, field, false);
        if (token == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field '" + field + "' is not a valid integer");
        }
    }

    /**
     * Extracts a float field.
     *
     * @param json         The JSON document
     * @param field        The field name
     * @param defaultValue Value returned when the field is absent or not a number
     * @return The parsed value
     */
    public static float floatValue(String json, String field, float defaultValue) {
        String token = numericToken(json, field, true);
        if (token == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field '" + field + "' is not a valid number");
        }
    }

    /**
     * Extracts a double field.
     *
     * @param json         The JSON document
     * @param field        The field name
     * @param defaultValue Value returned when the field is absent or not a number
     * @return The parsed value
     */
    public static double doubleValue(String json, String field, double defaultValue) {
        String token = numericToken(json, field, true);
        if (token == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field '" + field + "' is not a valid number");
        }
    }

    /**
     * Extracts a boolean field.
     *
     * @param json         The JSON document
     * @param field        The field name
     * @param defaultValue Value returned when the field is absent
     * @return The parsed value
     */
    public static boolean booleanValue(String json, String field, boolean defaultValue) {
        int start = valueStart(json, field);
        if (start < 0) {
            return defaultValue;
        }
        if (json.startsWith("true", start)) {
            return true;
        }
        if (json.startsWith("false", start)) {
            return false;
        }
        throw new IllegalArgumentException("Field '" + field + "' must be true or false");
    }

    /**
     * Returns the numeric token for a field, or null when the field is absent.
     *
     * <p>A field that is present but does not hold a number is rejected rather than
     * silently replaced by the default: a client that sends {@code "top_k": "ten"}
     * has made a mistake, and answering as though it had sent nothing hides it.</p>
     */
    private static String numericToken(String json, String field, boolean fractional) {
        int start = valueStart(json, field);
        if (start < 0) {
            return null;
        }
        int end = start;
        while (end < json.length() && isNumericChar(json.charAt(end), fractional)) {
            end++;
        }
        if (end == start) {
            throw new IllegalArgumentException("Field '" + field + "' must be a number");
        }
        return json.substring(start, end);
    }

    private static boolean isNumericChar(char c, boolean fractional) {
        if (Character.isDigit(c) || c == '-' || c == '+') {
            return true;
        }
        return fractional && (c == '.' || c == 'e' || c == 'E');
    }

    private static int valueStart(String json, String field) {
        String pattern = "\"" + field + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) {
            return -1;
        }
        start += pattern.length();
        while (start < json.length()
            && (json.charAt(start) == ':' || Character.isWhitespace(json.charAt(start)))) {
            start++;
        }
        return start;
    }
}
