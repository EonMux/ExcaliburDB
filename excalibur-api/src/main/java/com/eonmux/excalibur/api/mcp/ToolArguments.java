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
     * Reads a string argument, or null when the model did not send a usable one.
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The trimmed value, or null
     */
    static String optionalString(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (!(value instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Reads a whole-number argument, or null when the model sent none.
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The value, or null
     * @throws IllegalArgumentException if it is present and is not a whole number
     */
    static Integer wholeNumber(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number
                && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Field '" + field + "' must be a whole number, got: " + text, e);
            }
        }
        throw new IllegalArgumentException(
            "Field '" + field + "' must be a whole number, got: " + value);
    }

    /**
     * Reads a whole-number argument sent as a number, ignoring anything that is not one.
     *
     * <p>Distinct from {@link #wholeNumber(Map, String)} in what it does with a value of the
     * wrong kind: this one omits it, which is right for a field whose absence means "use the
     * node's default". A fractional number is still refused, because it is a count.</p>
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The value, or null when the model sent nothing numeric
     * @throws IllegalArgumentException if it is a number but not a whole one
     */
    static Integer optionalInteger(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (!(value instanceof Number number)) {
            return null;
        }
        double exact = number.doubleValue();
        if (exact != Math.rint(exact) || exact < Integer.MIN_VALUE || exact > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Argument '" + field + "' must be a whole number: " + value);
        }
        return (int) exact;
    }

    /**
     * Reads a list-of-strings argument.
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The non-blank elements, empty when the field is absent or is not a list
     */
    static List<String> stringList(Map<String, Object> args, String field) {
        Object value = args.get(field);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof String text && !text.isBlank()) {
                    out.add(text.trim());
                }
            }
        }
        return out;
    }

    /**
     * Reads a list-of-objects argument.
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The elements, empty when the field is absent or is not a list
     */
    static List<Object> objectList(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (!(value instanceof List<?> list)) {
            return List.of();
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
