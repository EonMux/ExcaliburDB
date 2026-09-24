package com.eonmux.excalibur.api.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
     * Reads a numeric argument that may be fractional, or null when the model sent none.
     *
     * <p>Only the type is checked here, with the same tolerance for a quoted number as
     * {@link #wholeNumber}. The range is the node's to enforce: it refuses an out-of-range value
     * with a message naming its bounds, and restating those bounds here would give the tool a
     * second limit to drift from the HTTP one. A value that is not finite is refused, since JSON
     * has no way to carry it.</p>
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The value, or null
     * @throws IllegalArgumentException if it is present and is not a finite number
     */
    static Double number(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) {
            return null;
        }
        double read;
        if (value instanceof Number number) {
            read = number.doubleValue();
        } else if (value instanceof String text) {
            try {
                read = Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Argument '" + field + "' must be a number, got: " + text, e);
            }
        } else {
            throw new IllegalArgumentException(
                "Argument '" + field + "' must be a number, got: " + value);
        }
        if (!Double.isFinite(read)) {
            throw new IllegalArgumentException(
                "Argument '" + field + "' must be a finite number, got: " + value);
        }
        return read;
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
     * <p>The elements are returned exactly as sent, blank ones included. Only the type is this
     * reader's to check; what a blank or padded element means is the node's to decide, and
     * deciding it here too would forward a body the model never wrote - {@code [""]} arriving as
     * {@code []} - so the tool and the same request over HTTP could register different
     * sources.</p>
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The elements as sent, or null when the field is absent or null
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
            out.add(text);
        }
        return List.copyOf(out);
    }

    /**
     * Reads a list argument whose elements the caller inspects, telling empty apart from absent.
     *
     * <p>A null element is refused rather than skipped. The node refuses the same array over
     * HTTP, and a reader that skipped it would send a shorter list than the model wrote and
     * report the call as having done what was asked.</p>
     *
     * @param args  The call's arguments
     * @param field The field name
     * @return The elements, in order, or null when the field is absent or null
     * @throws IllegalArgumentException if it is present and is not a list, or holds a null
     */
    static List<Object> optionalList(Map<String, Object> args, String field) {
        Object value = args.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                "Argument '" + field + "' must be an array, got: " + value);
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == null) {
                throw new IllegalArgumentException(
                    "Argument '" + field + "' element " + i + " is null");
            }
        }
        return List.copyOf(list);
    }

    /**
     * Refuses arguments the tool does not take, naming every one of them.
     *
     * <p>For a tool whose settings shape what the node goes on to do unattended. A misspelled
     * setting is never read by the tool, so it would be dropped exactly as an argument of the
     * wrong type would be, and the call would report success for work the model did not ask
     * for.</p>
     *
     * @param args     The call's arguments
     * @param accepted The argument names the tool's input schema declares
     * @throws IllegalArgumentException if any argument is not among them
     */
    static void requireKnown(Map<String, Object> args, Set<String> accepted) {
        List<String> unknown = new ArrayList<>();
        for (String name : args.keySet()) {
            if (!accepted.contains(name)) {
                unknown.add("'" + name + "'");
            }
        }
        if (!unknown.isEmpty()) {
            unknown.sort(null);
            throw new IllegalArgumentException((unknown.size() == 1 ? "Argument " : "Arguments ")
                + String.join(", ", unknown)
                + (unknown.size() == 1 ? " is" : " are") + " not taken by this tool, which takes "
                + String.join(", ", new TreeSet<>(accepted))
                + ". Correct the name, or leave it out.");
        }
    }

    /**
     * Checks the objects of an array argument against the schema one element is declared with,
     * and returns them ready to forward.
     *
     * <p>For the arrays whose elements the node reads member by member - a supplied document, an
     * asserted fact. The node refuses a member those routes do not read, but only with a message
     * about its own request body, after the call has gone out; checked here against the schema
     * the tool advertised, the refusal names the argument and element the model wrote, as
     * {@link #requireKnown} does for a tool's own arguments: that each member is declared, that a
     * required one is present, and that its value has the declared type.</p>
     *
     * <p>A value is forwarded as written, with one exception: a whole number given as
     * {@code 5.0} or {@code "5"} is sent as {@code 5}, and a number given as {@code "0.5"} as
     * {@code 0.5}, the tolerance {@link #wholeLong} and {@link #number} give a top-level
     * argument - forwarded as written, either would be refused by a node that reads the member
     * as a number. An explicit null on an optional member is kept: it is how a fact says it has
     * no known start or still holds.</p>
     *
     * @param field      The array argument, for the message
     * @param elements   Its elements, as {@link #optionalList} read them
     * @param itemSchema The JSON Schema one element is declared with: an object schema with
     *                   {@code properties} and {@code required}
     * @return New element objects, in order, holding the members as they are to be sent
     * @throws IllegalArgumentException if an element is not an object, names a member the schema
     *                                  does not declare, lacks a required one, or holds a value
     *                                  of the wrong type
     */
    static List<Object> declaredElements(String field, List<Object> elements,
                                         Map<String, Object> itemSchema) {
        Map<String, Object> properties = Json.objectAt(itemSchema, "properties");
        List<?> required = (List<?>) itemSchema.get("required");
        List<Object> checked = new ArrayList<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            String which = "Argument '" + field + "' element " + i;
            if (!(elements.get(i) instanceof Map<?, ?> element)) {
                throw new IllegalArgumentException(which + " must be an object, got: "
                    + elements.get(i));
            }
            Map<String, Object> members = new LinkedHashMap<>();
            element.forEach((name, value) -> members.put(String.valueOf(name), value));
            requireDeclaredMembers(which, members, properties);
            for (Object name : required) {
                if (members.get(name) == null) {
                    throw new IllegalArgumentException(which + " needs '" + name + "'.");
                }
            }
            Map<String, Object> sent = new LinkedHashMap<>();
            for (Map.Entry<String, Object> member : members.entrySet()) {
                sent.put(member.getKey(), typedMember(which, members, member.getKey(),
                    Json.stringAt(properties.get(member.getKey()), "type")));
            }
            checked.add(sent);
        }
        return checked;
    }

    private static void requireDeclaredMembers(String which, Map<String, Object> members,
                                               Map<String, Object> properties) {
        List<String> unknown = new ArrayList<>();
        for (String name : members.keySet()) {
            if (!properties.containsKey(name)) {
                unknown.add("'" + name + "'");
            }
        }
        if (!unknown.isEmpty()) {
            unknown.sort(null);
            throw new IllegalArgumentException(which + " has " + String.join(", ", unknown)
                + ", which " + (unknown.size() == 1 ? "is" : "are") + " not taken: each element "
                + "takes " + String.join(", ", properties.keySet())
                + ". Correct the name, or leave it out.");
        }
    }

    /**
     * One member's value as it is to be sent, after checking it has the declared type.
     *
     * @param which   The element, for the message
     * @param members The element's members
     * @param name    The member
     * @param type    Its declared JSON type
     * @return The value to send; null when it is null
     */
    private static Object typedMember(String which, Map<String, Object> members, String name,
                                      String type) {
        Object value = members.get(name);
        if (value == null) {
            return null;
        }
        if ("string".equals(type)) {
            if (value instanceof String) {
                return value;
            }
            throw mistyped(which, name, "a string", value, null);
        }
        try {
            return switch (type) {
                case "integer" -> wholeLong(members, name);
                case "number" -> number(members, name);
                default -> value;
            };
        } catch (IllegalArgumentException e) {
            throw mistyped(which, name, "integer".equals(type) ? "a whole number" : "a number",
                value, e);
        }
    }

    private static IllegalArgumentException mistyped(String which, String name, String expected,
                                                     Object value, Throwable cause) {
        return new IllegalArgumentException(which + " has '" + name + "' that is not " + expected
            + ", got: " + value, cause);
    }
}
