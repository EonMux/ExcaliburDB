package com.eonmux.excalibur.api.protocol;

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
     * <p>A field that is present but does not hold a string is rejected rather than read as
     * absent, for the reason the numeric accessors reject one: the two are not the same thing
     * and several callers act on the difference. A guard written as "the caller supplied no
     * document ids" is defeated by document ids of the wrong type, and the request it was
     * written to refuse goes through instead.</p>
     *
     * @param json  The JSON document
     * @param field The field name
     * @return The decoded value, or null when absent or explicitly null
     * @throws IllegalArgumentException if the field is present and is not a string
     */
    public static String string(String json, String field) {
        int start = valueStart(json, field);
        if (start < 0 || isExplicitNull(json, start)) {
            return null;
        }
        if (json.charAt(start) != '"') {
            throw new IllegalArgumentException("Field '" + field + "' must be a string");
        }
        int end = closingQuote(json, start + 1);
        if (end < 0) {
            throw new IllegalArgumentException(
                "Field '" + field + "' is a string that is never closed");
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
     * @return The object text including braces, or null when absent or explicitly null
     * @throws IllegalArgumentException if the field is present and is not a closed object
     */
    public static String object(String json, String field) {
        int start = valueStart(json, field);
        if (start < 0 || isExplicitNull(json, start)) {
            return null;
        }
        if (json.charAt(start) != '{') {
            throw new IllegalArgumentException("Field '" + field + "' must be an object");
        }
        int end = endOfContainer(json, start);
        if (end < 0) {
            throw new IllegalArgumentException(
                "Field '" + field + "' is an object that is never closed");
        }
        return json.substring(start, end);
    }

    /**
     * Extracts an array of strings.
     *
     * <p>An element that is not a string is refused rather than skipped. Skipping shortens the
     * list silently: a batch of three document ids becomes a batch of two, is answered 200,
     * and nothing in the response names the one that was dropped.</p>
     *
     * @param json  The JSON document
     * @param field The field name
     * @return The decoded values, or null when absent or explicitly null
     * @throws IllegalArgumentException if the field is present and is not a closed array of
     *                                  strings
     */
    public static String[] stringArray(String json, String field) {
        int start = valueStart(json, field);
        if (start < 0 || isExplicitNull(json, start)) {
            return null;
        }
        int limit = requireArray(json, field, start);
        List<String> items = new ArrayList<>();
        int i = start + 1;
        while (i < limit) {
            char c = json.charAt(i);
            if (c == ']') {
                return items.toArray(new String[0]);
            }
            if (isBetweenElements(c)) {
                i++;
                continue;
            }
            if (c != '"') {
                throw new IllegalArgumentException(
                    "Field '" + field + "' must hold strings, and one element is not one");
            }
            int closeQuote = closingQuote(json, i + 1);
            if (closeQuote < 0 || closeQuote >= limit) {
                throw new IllegalArgumentException(
                    "Field '" + field + "' holds a string that is never closed");
            }
            items.add(unescape(json.substring(i + 1, closeQuote)));
            i = closeQuote + 1;
        }
        throw new IllegalArgumentException(
            "Field '" + field + "' is an array that is never closed");
    }

    /**
     * Extracts an array of objects, each as raw JSON text.
     *
     * <p>The elements are returned unparsed because what is in them belongs to whoever asked:
     * one reader wants two fields of each, another wants five, and a shape fixed here would have
     * to be widened for every caller. Each element is bounded by brace matching that knows about
     * strings, so a brace inside a text value does not split the array in the wrong place.</p>
     *
     * <p>An element that is not an object is refused rather than skipped, for the reason a
     * non-string element of a string array is.</p>
     *
     * @param json  The JSON document
     * @param field The field name
     * @return One raw object per element, empty for an empty array, or null when the field is
     *         absent or explicitly null
     * @throws IllegalArgumentException if the field is present and is not a closed array of
     *                                  objects
     */
    public static String[] objectArray(String json, String field) {
        int start = valueStart(json, field);
        if (start < 0 || isExplicitNull(json, start)) {
            return null;
        }
        int limit = requireArray(json, field, start);
        List<String> items = new ArrayList<>();
        int i = start + 1;
        while (i < limit) {
            char c = json.charAt(i);
            if (c == ']') {
                return items.toArray(new String[0]);
            }
            if (isBetweenElements(c)) {
                i++;
                continue;
            }
            if (c != '{') {
                throw new IllegalArgumentException(
                    "Field '" + field + "' must hold objects, and one element is not one");
            }
            int end = endOfContainer(json, i);
            if (end < 0 || end > limit) {
                throw new IllegalArgumentException(
                    "Field '" + field + "' holds an object that is never closed");
            }
            items.add(json.substring(i, end));
            i = end;
        }
        throw new IllegalArgumentException(
            "Field '" + field + "' is an array that is never closed");
    }

    /**
     * Requires the value at {@code start} to be an array, and returns where it ends.
     *
     * @param json  The JSON document
     * @param field The field name, for the message
     * @param start Where its value begins
     * @return The index just past the array
     * @throws IllegalArgumentException if it is not an array, or is never closed
     */
    private static int requireArray(String json, String field, int start) {
        if (json.charAt(start) != '[') {
            throw new IllegalArgumentException("Field '" + field + "' must be an array");
        }
        int limit = endOfContainer(json, start);
        if (limit < 0) {
            throw new IllegalArgumentException(
                "Field '" + field + "' is an array that is never closed");
        }
        return limit;
    }

    /** Whether a character separates elements rather than beginning one. */
    private static boolean isBetweenElements(char c) {
        return c == ',' || Character.isWhitespace(c);
    }

    /**
     * Whether the value at {@code start} is the literal {@code null}.
     *
     * <p>Treated as absence everywhere, because that is what a caller means by it. Clients
     * serialise an optional field as {@code null} rather than omitting it - it is the default
     * of most encoders - so refusing it would turn an encoder setting into a 400 on requests
     * that are asking for exactly what an omitted field asks for.</p>
     *
     * @param json  The JSON document
     * @param start Where the value begins, or a negative index for an absent field
     * @return true if the field carries an explicit null
     */
    private static boolean isExplicitNull(String json, int start) {
        return start >= 0 && json.startsWith("null", start);
    }

    /**
     * Whether a field carries a value.
     *
     * <p>The typed accessors take a default, so an absent field and one the caller set to the
     * default value read the same. Where the answer changes what is reported back - a field
     * this path cannot honour, say - the difference matters.</p>
     *
     * <p>An explicit {@code null} is absence here as it is in every typed accessor. Answering
     * true for it would let a caller of this method act on a value the accessor then reads as
     * missing: a bounded setting taking its minimum, or a field reported as ignored that the
     * request only spelled out as unset.</p>
     *
     * @param json  The JSON document
     * @param field The field name
     * @return true if the document carries the field with a value other than {@code null}
     */
    public static boolean has(String json, String field) {
        int start = valueStart(json, field);
        return start >= 0 && !isExplicitNull(json, start);
    }

    /**
     * Where the value of a top-level member ends.
     *
     * <p>For a writer that adds a member beside an existing one without re-encoding the body:
     * the index returned is where a {@code , "name": value} pair can be inserted so that it
     * follows the member it belongs with.</p>
     *
     * @param json  The JSON document
     * @param field The member name
     * @return Index just past the member's value, or -1 when there is no such member or the
     *         document is malformed
     */
    public static int valueEnd(String json, String field) {
        int start = valueStart(json, field);
        return start < 0 ? -1 : endOfValue(json, start);
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
        if (start < 0 || isExplicitNull(json, start)) {
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
     *
     * <p>The token has to run all the way to the end of the value, not merely start there.
     * Reading the leading digits and stopping turns {@code "timeout_ms": 3e4} into 3 and
     * {@code "top_k": 1.5} into 1 - a request answered with 200 against numbers the caller
     * never sent. Exponent form is not exotic: Python and JavaScript both emit it for large
     * float values, so this arrives from ordinary clients.</p>
     */
    private static String numericToken(String json, String field, boolean fractional) {
        int start = valueStart(json, field);
        if (start < 0 || isExplicitNull(json, start)) {
            return null;
        }
        int end = start;
        while (end < json.length() && isNumericChar(json.charAt(end), fractional)) {
            end++;
        }
        if (end == start || !endsValue(json, end)) {
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

    /**
     * Whether a JSON value may legally end at the given index.
     *
     * @param json  The document
     * @param index The index just past the token
     * @return true if what follows closes the value rather than continuing it
     */
    private static boolean endsValue(String json, int index) {
        if (index >= json.length()) {
            return true;
        }
        char c = json.charAt(index);
        return c == ',' || c == '}' || c == ']' || Character.isWhitespace(c);
    }

    /**
     * Where the value of the top-level member named {@code field} begins.
     *
     * <p>Walks the members of the outermost object and compares each name, rather than searching
     * the document for the quoted name and reading forward from the first hit. The difference is
     * not cosmetic: the strings in these bodies are entity names, document ids and source ids
     * chosen by the caller, so a search finds whichever of those happens to spell a field name
     * first. A request carrying {@code "context_entities":["query"]} answered a different
     * question than the one asked, and one carrying a document id of {@code source_id} erased a
     * different source - silently in both cases, because a wrong value is not a parse error.
     *
     * <p>Nested members are deliberately not found. A field belongs to the object it is written
     * in; a caller that wants one out of a sub-object asks for that object first.
     *
     * <p>Iterative, so a deeply nested body costs stack no matter how it is shaped.
     *
     * @param json  The document
     * @param field The member name
     * @return Index of the first character of its value, or -1 when there is no such member
     */
    private static int valueStart(String json, String field) {
        if (json == null || field == null) {
            return -1;
        }
        int i = skipWhitespace(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') {
            return -1;
        }
        i++;
        while (true) {
            i = skipWhitespace(json, i);
            if (i >= json.length() || json.charAt(i) != '"') {
                return -1;
            }
            int nameEnd = closingQuote(json, i + 1);
            if (nameEnd < 0) {
                return -1;
            }
            String name = unescape(json.substring(i + 1, nameEnd));
            i = skipWhitespace(json, nameEnd + 1);
            if (i >= json.length() || json.charAt(i) != ':') {
                return -1;
            }
            i = skipWhitespace(json, i + 1);
            if (i >= json.length()) {
                return -1;
            }
            if (name.equals(field)) {
                return i;
            }
            i = endOfValue(json, i);
            if (i < 0) {
                return -1;
            }
            i = skipWhitespace(json, i);
            if (i >= json.length() || json.charAt(i) != ',') {
                return -1;
            }
            i++;
        }
    }

    /**
     * Refuses a document that is not one object whose members can all be walked.
     *
     * <p>Every reader here finds a member by walking the members before it, and stops at the
     * first one it cannot walk - so a member written after a missing comma or a stray token reads
     * as absent. For most fields that only swaps a value for its default. For a field that
     * confines what a request may touch, absent means unconfined, and a typo would widen the
     * request rather than fail it. A route with such a field checks the whole document with this
     * before reading any of it.</p>
     *
     * <p>Structural only: it walks each member's name, colon and value to the closing brace, as
     * the readers do, and does not re-validate what is inside a nested value.</p>
     *
     * @param json The document
     * @throws IllegalArgumentException if it is not a single object whose members are separated
     *                                  by commas and closed by a brace, with nothing after it
     */
    public static void requireObject(String json) {
        memberNames(json, "The request body must be a single well-formed JSON object");
    }

    /**
     * The names of an object's members, in the order they are written, after checking the
     * object can be walked the way {@link #requireObject} checks it.
     *
     * <p>For a route that refuses members it does not know. A misspelled optional field reads
     * as absent to every accessor here, so a request carrying one would be answered as though
     * the caller had not asked for what it plainly did; comparing the names against the ones the
     * route reads is what turns the misspelling into an error the caller can see.</p>
     *
     * @param json      The object
     * @param malformed What to say when it is not one well-formed object
     * @return The member names, decoded; empty for {@code {}}
     * @throws IllegalArgumentException with {@code malformed} if it is not a single object whose
     *                                  members are separated by commas and closed by a brace,
     *                                  with nothing after it
     */
    public static List<String> memberNames(String json, String malformed) {
        List<String> names = new ArrayList<>();
        int i = json == null ? 0 : skipWhitespace(json, 0);
        if (json == null || i >= json.length() || json.charAt(i) != '{') {
            throw new IllegalArgumentException(malformed);
        }
        i = skipWhitespace(json, i + 1);
        if (i < json.length() && json.charAt(i) == '}') {
            requireNothingAfter(json, i + 1, malformed);
            return names;
        }
        while (true) {
            if (i >= json.length() || json.charAt(i) != '"') {
                throw new IllegalArgumentException(malformed);
            }
            int nameEnd = closingQuote(json, i + 1);
            if (nameEnd >= 0) {
                names.add(unescape(json.substring(i + 1, nameEnd)));
            }
            i = nameEnd < 0 ? -1 : skipWhitespace(json, nameEnd + 1);
            if (i < 0 || i >= json.length() || json.charAt(i) != ':') {
                throw new IllegalArgumentException(malformed);
            }
            i = skipWhitespace(json, i + 1);
            i = i >= json.length() ? -1 : endOfValue(json, i);
            if (i < 0) {
                throw new IllegalArgumentException(malformed);
            }
            i = skipWhitespace(json, i);
            if (i < json.length() && json.charAt(i) == '}') {
                requireNothingAfter(json, i + 1, malformed);
                return names;
            }
            if (i >= json.length() || json.charAt(i) != ',') {
                throw new IllegalArgumentException(malformed);
            }
            i = skipWhitespace(json, i + 1);
        }
    }

    private static void requireNothingAfter(String json, int from, String malformed) {
        if (skipWhitespace(json, from) != json.length()) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private static int skipWhitespace(String json, int from) {
        int i = from;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Index just past the value starting at {@code from}, or -1 when it is malformed.
     *
     * @param json The document
     * @param from Index of the value's first character
     * @return Index just past it
     */
    private static int endOfValue(String json, int from) {
        char c = json.charAt(from);
        if (c == '"') {
            int end = closingQuote(json, from + 1);
            return end < 0 ? -1 : end + 1;
        }
        if (c == '{' || c == '[') {
            return endOfContainer(json, from);
        }
        int i = from;
        while (i < json.length()) {
            char v = json.charAt(i);
            if (v == ',' || v == '}' || v == ']' || Character.isWhitespace(v)) {
                break;
            }
            i++;
        }
        return i == from ? -1 : i;
    }

    /**
     * Index just past the object or array opening at {@code from}, or -1 when it is unterminated.
     *
     * @param json The document
     * @param from Index of the opening brace or bracket
     * @return Index just past the matching close
     */
    private static int endOfContainer(String json, int from) {
        char open = json.charAt(from);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (inString && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
        }
        return -1;
    }
}
