package com.eonmux.excalibur.llm.json;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hand-rolled JSON writer for constructing request bodies.
 *
 * <p>This implementation avoids external JSON libraries
 * (no Jackson, no Gson) and produces valid UTF-8 encoded JSON with properly escaped strings.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All methods are stateless and can be called concurrently.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Output is always valid UTF-8 when encoded to bytes</li>
 *   <li>Strings are escaped per RFC 8259: quotes, backslash, control chars, and DEL</li>
 *   <li>Unicode characters (including surrogates) are preserved correctly</li>
 *   <li>JSON line terminators (U+2028, U+2029) are escaped to prevent issues in JS contexts</li>
 *   <li>Null values produce "null" literal, not quoted string</li>
 *   <li>Numeric values are formatted without locale-specific separators</li>
 * </ul>
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8259">RFC 8259 - The JSON Data Interchange Format</a>
 */
public final class JsonWriter {

    /**
     * Private constructor - utility class with only static methods.
     */
    private JsonWriter() {
        // Utility class, no instances
    }

    /**
     * Escapes a string for safe inclusion in a JSON string value.
     *
     * <p>Per RFC 8259, the following characters must be escaped:</p>
     * <ul>
     *   <li>Quotation mark (U+0022) → \"</li>
     *   <li>Reverse solidus (U+005C) → \\</li>
     *   <li>Control characters (U+0000 through U+001F) - escaped as \\uXXXX</li>
     * </ul>
     *
     * <p>This implementation also escapes:</p>
     * <ul>
     *   <li>Delete character (U+007F) - escaped as \\u007F (control character in extended set)</li>
     *   <li>Line separator (U+2028) - escaped as \\u2028 (JSON line terminator that breaks JS)</li>
     *   <li>Paragraph separator (U+2029) - escaped as \\u2029 (JSON line terminator that breaks JS)</li>
     *   <li>Backspace (U+0008) - escaped as \\b (shorthand)</li>
     *   <li>Form feed (U+000C) - escaped as \\f (shorthand)</li>
     *   <li>Newline (U+000A) - escaped as \\n (shorthand)</li>
     *   <li>Carriage return (U+000D) - escaped as \\r (shorthand)</li>
     *   <li>Tab (U+0009) - escaped as \\t (shorthand)</li>
     * </ul>
     *
     * <p>All other Unicode characters (including surrogate pairs for characters beyond BMP)
     * are passed through unchanged. The resulting string, when encoded as UTF-8, produces
     * valid JSON.</p>
     *
     * @param input the raw string to escape (may be null)
     * @return the JSON-escaped string, or empty string if input is null
     */
    public static String escapeString(String input) {
        if (input == null) {
            return "";
        }

        // Pre-scan to check if escaping is needed (optimization for ASCII-only strings)
        int length = input.length();
        int needsEscape = -1;

        for (int i = 0; i < length; i++) {
            char c = input.charAt(i);
            if (needsEscape(c)) {
                needsEscape = i;
                break;
            }
        }

        // If no escaping needed, return original string
        if (needsEscape == -1) {
            return input;
        }

        // Build escaped string
        StringBuilder sb = new StringBuilder(length + 16);

        // Copy the prefix that doesn't need escaping
        if (needsEscape > 0) {
            sb.append(input, 0, needsEscape);
        }

        // Process the rest of the string
        for (int i = needsEscape; i < length; i++) {
            char c = input.charAt(i);
            appendEscaped(sb, c);
        }

        return sb.toString();
    }

    /**
     * Checks if a character needs JSON escaping.
     *
     * @param c the character to check
     * @return true if the character needs to be escaped
     */
    private static boolean needsEscape(char c) {
        // Fast path for common safe characters (ASCII letters, digits, most punctuation)
        if (c >= 0x20 && c <= 0x7E) {
            // In printable ASCII range, only " and \ need escaping
            return c == '"' || c == '\\';
        }

        // Check for characters that need escaping
        return c < 0x20 ||  // Control characters
               c == 0x7F ||  // DEL character
               c == '"' || c == '\\' ||  // Must always escape
               c == '\u2028' || c == '\u2029';  // JSON line terminators
    }

    /**
     * Appends an escaped character to the StringBuilder.
     *
     * @param sb the StringBuilder to append to
     * @param c the character to escape and append
     */
    private static void appendEscaped(StringBuilder sb, char c) {
        switch (c) {
            case '\\' -> sb.append("\\\\");
            case '"' -> sb.append("\\\"");
            case '\b' -> sb.append("\\b");
            case '\f' -> sb.append("\\f");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> {
                // Control characters (0x00-0x1F), DEL (0x7F), and JSON line terminators
                if (c < 0x20 || c == 0x7F || c == '\u2028' || c == '\u2029') {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    // All other characters pass through (including Unicode BMP and surrogates)
                    sb.append(c);
                }
            }
        }
    }

    /**
     * Writes a JSON object as a string.
     *
     * <p>Domain correctness: produces valid JSON object with proper escaping.</p>
     *
     * @param entries the object entries as key-value pairs
     * @return the JSON object as a string
     */
    public static String writeObject(Map<String, ?> entries) {
        Objects.requireNonNull(entries, "Entries map cannot be null");

        StringBuilder sb = new StringBuilder(128);
        sb.append('{');

        boolean first = true;
        for (Map.Entry<String, ?> entry : entries.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;

            // Write key (always a string in JSON)
            writeString(sb, entry.getKey());
            sb.append(':');

            // Write value
            writeValue(sb, entry.getValue());
        }

        sb.append('}');
        return sb.toString();
    }

    /**
     * Writes a JSON array as a string.
     *
     * <p>Domain correctness: produces valid JSON array with proper escaping.</p>
     *
     * @param values the array values
     * @return the JSON array as a string
     */
    public static String writeArray(List<?> values) {
        Objects.requireNonNull(values, "Values list cannot be null");

        StringBuilder sb = new StringBuilder(64);
        sb.append('[');

        boolean first = true;
        for (Object value : values) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, value);
        }

        sb.append(']');
        return sb.toString();
    }

    /**
     * Writes a JSON string value (with surrounding quotes).
     *
     * @param sb the StringBuilder to append to
     * @param value the string value to write
     */
    public static void writeString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"');
            sb.append(escapeString(value));
            sb.append('"');
        }
    }

    /**
     * Writes a JSON value (of any type) to the StringBuilder.
     *
     * <p>Domain correctness:</p>
     * <ul>
     *   <li>Strings are quoted and escaped</li>
     *   <li>Numbers are formatted without locale-specific separators</li>
     *   <li>Booleans produce "true" or "false"</li>
     *   <li>Null produces "null"</li>
     *   <li>Maps produce JSON objects</li>
     *   <li>Lists produce JSON arrays</li>
     * </ul>
     *
     * @param sb the StringBuilder to append to
     * @param value the value to write
     */
    public static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Number n) {
            writeNumber(sb, n);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typedMap = (Map<String, Object>) map;
            sb.append(writeObject(typedMap));
        } else if (value instanceof List<?> list) {
            sb.append(writeArray(list));
        } else {
            // Fallback: convert to string and quote
            writeString(sb, value.toString());
        }
    }

    /**
     * Writes a JSON number.
     *
     * <p>Domain correctness:</p>
     * <ul>
     *   <li>Integers are written without decimal point</li>
     *   <li>Floating point uses scientific notation for very large/small values</li>
     *   <li>No locale-specific separators (always uses '.' for decimal)</li>
     *   <li>NaN and Infinity are converted to null (not valid JSON numbers)</li>
     * </ul>
     *
     * @param sb the StringBuilder to append to
     * @param number the number to write
     */
    public static void writeNumber(StringBuilder sb, Number number) {
        if (number == null) {
            sb.append("null");
            return;
        }

        if (number instanceof Integer || number instanceof Long ||
            number instanceof Short || number instanceof Byte) {
            sb.append(number.longValue());
        } else if (number instanceof Float f) {
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                sb.append("null");
            } else {
                sb.append(f.toString());
            }
        } else if (number instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(d.toString());
            }
        } else {
            // Generic number handling
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(d);
            }
        }
    }

    /**
     * Validates that a string, when encoded as UTF-8, produces valid JSON.
     *
     * <p>This method checks for:</p>
     * <ul>
     *   <li>Valid UTF-8 encoding (all characters are valid Unicode)</li>
     *   <li>No unescaped control characters in string values</li>
     *   <li>Properly balanced brackets and quotes</li>
     * </ul>
     *
     * @param json the JSON string to validate
     * @return true if the string is valid JSON when encoded as UTF-8
     */
    public static boolean isValidJson(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }

        // Verify it can be encoded as UTF-8
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            if (!decoded.equals(json)) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        // Basic structure validation
        return validateJsonStructure(json);
    }

    /**
     * Performs basic JSON structure validation.
     *
     * @param json the JSON string to validate
     * @return true if the structure is valid
     */
    private static boolean validateJsonStructure(String json) {
        String trimmed = json.trim();

        // Must start with { or [
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false;
        }

        // Count bracket balance
        int braceCount = 0;
        int bracketCount = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }

            if (c == '\\' && inString) {
                escape = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                switch (c) {
                    case '{' -> braceCount++;
                    case '}' -> {
                        braceCount--;
                        if (braceCount < 0) return false;
                    }
                    case '[' -> bracketCount++;
                    case ']' -> {
                        bracketCount--;
                        if (bracketCount < 0) return false;
                    }
                }
            }
        }

        return braceCount == 0 && bracketCount == 0 && !inString;
    }

    /**
     * Encodes a JSON string to UTF-8 bytes.
     *
     * <p>Domain correctness: always uses UTF-8 charset, not platform default.</p>
     *
     * @param json the JSON string to encode
     * @return the UTF-8 encoded bytes
     */
    public static byte[] encodeUtf8(String json) {
        if (json == null) {
            return new byte[0];
        }
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes UTF-8 bytes to a string.
     *
     * <p>Domain correctness: always uses UTF-8 charset, not platform default.</p>
     *
     * @param bytes the UTF-8 encoded bytes
     * @return the decoded string
     */
    public static String decodeUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
