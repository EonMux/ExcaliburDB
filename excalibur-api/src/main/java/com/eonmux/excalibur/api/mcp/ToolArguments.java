package com.eonmux.excalibur.api.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the arguments a model sent, and refuses the ones it cannot have meant.
 *
 * <p>Refused here rather than forwarded. The node validates everything it is sent and would
 * answer with the same objection, one round trip later - and its objection is worded for an
 * operator reading an HTTP response, not for a model deciding what to try next.</p>
 *
 * <p>An argument of the wrong type is refused, never dropped. Dropping it would run the call
 * without a setting the model believes it made - a crawl without its exclusions, a search
 * without its limit - and report success.</p>
 *
 * <h2>Numbers</h2>
 * <p>JSON has one number type and a model's JSON layer decides how to write a whole number, so
 * {@code 5} and {@code 5.0} are the same value and both are accepted. A fractional one is not:
 * none of these fields is a quantity that can be fractional, and silently truncating it would
 * answer a question nobody asked.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless.</p>
 */
final class ToolArguments {

    private ToolArguments() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads a string argument the tool cannot run without.
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The trimmed value
     * @throws IllegalArgumentException if it is absent, blank, or not a string
     */
    static String requiredString(Map<String, Object> args, String field) {
        String value = optionalString(args, field);
        if (value == null) {
            throw new IllegalArgumentException("Argument '" + field + "' is required.");
        }
        return value;
    }

    /**
     * Reads a string argument, or null when the model sent none.
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The trimmed value, or null when absent, null or blank
     * @throws IllegalArgumentException if it is present and is not a string
     */
    static String optionalString(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                "Argument '" + field + "' must be a string, got: " + value);
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Reads a whole-number argument, or null when the model sent none.
     *
     * <p>A number written as a string is accepted, because some models' JSON layers quote
     * every scalar; anything else that is not a whole number in {@code int} range is refused.</p>
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The value, or null
     * @throws IllegalArgumentException if it is present and is not a whole number
     */
    static Integer wholeNumber(Map<String, Object> args, String field) {
        Long value = wholeLong(args, field);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Argument '" + field + "' must be a whole number, got: " + args.get(field));
        }
        return value.intValue();
    }

    /**
     * Reads a whole-number argument that may exceed {@code int} range, or null when the model
     * sent none.
     *
     * <p>For a quantity measured in bytes, whose range belongs to the node rather than to this
     * process: reading it as an {@code int} here would answer a large value with a different
     * refusal than the one the HTTP API gives, and a limit the two surfaces state differently
     * is not a limit a model can be told about. The same tolerance as {@link #wholeNumber}
     * applies: a quoted number is read, a fractional one is refused.</p>
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The value, or null
     * @throws IllegalArgumentException if it is present and is not a whole number
     */
    static Long wholeLong(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            // Read exactly: a long past 2^53 does not survive a trip through double.
            return ((Number) value).longValue();
        }
        if (value instanceof Number number) {
            double exact = number.doubleValue();
            if (exact == Math.rint(exact) && exact >= Long.MIN_VALUE && exact <= Long.MAX_VALUE) {
                return (long) exact;
            }
        } else if (value instanceof String text) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Argument '" + field + "' must be a whole number, got: " + text, e);
            }
        }
        throw new IllegalArgumentException(
            "Argument '" + field + "' must be a whole number, got: " + value);
    }

    /**
     * Reads a true/false argument, or null when the model sent none.
     *
     * <p>Null rather than false when absent, because false is a setting of its own: a caller
     * that says nothing leaves the node's default in place, and one that says false overrules
     * it. The strings {@code "true"} and {@code "false"} are accepted in any case, for the same
     * reason a quoted number is; any other value - {@code 1}, {@code "yes"} - is refused, since
     * guessing which of the two it meant would decide a crawl's reach on the model's behalf.</p>
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The value, or null
     * @throws IllegalArgumentException if it is present and is not true or false
     */
    static Boolean optionalBoolean(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                return Boolean.valueOf(trimmed.equalsIgnoreCase("true"));
            }
        }
        throw new IllegalArgumentException(
            "Argument '" + field + "' must be true or false, got: " + value);
    }

    /**
     * Reads a list-of-strings argument, telling an empty list apart from an absent one.
     *
     * <p>The difference matters because the node gives an empty list a meaning of its own:
     * absent keeps its default, empty clears it. There is deliberately no reader that folds the
     * two together.</p>
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The non-blank elements, or null when the field is absent or null
     * @throws IllegalArgumentException if it is present and is not a list of strings
     */
    static List<String> optionalStringList(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> items)) {
            throw new IllegalArgumentException(
                "Argument '" + field + "' must be an array of strings, got: " + value);
        }
        List<String> out = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException(
                    "Argument '" + field + "' must be an array of strings, got an element: "
                        + item);
            }
            if (!text.isBlank()) {
                out.add(text.trim());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Reads a list-of-objects argument.
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The elements, empty when the field is absent
     * @throws IllegalArgumentException if it is present and is not a list
     */
    static List<Object> objectList(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                "Argument '" + field + "' must be an array, got: " + value);
        }
        List<Object> items = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element != null) {
                items.add(element);
            }
        }
        return List.copyOf(items);
    }
}
