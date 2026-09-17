package io.excalibur.config;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses typed configuration values out of a raw property map.
 *
 * <h2>Resolution Rules</h2>
 * <p>A key that is absent, or present with a blank value, resolves to the caller's
 * default. A blank value is treated as absent because both a properties line written
 * as <code>key=</code> and a container environment declaration that passes a name
 * through with no value reach this class as an empty string.</p>
 *
 * <p>A key that is present with a value the target type cannot represent is a
 * misconfiguration and is rejected with an {@link IllegalArgumentException} naming
 * the key. Silently substituting the compiled-in default would make a typo
 * indistinguishable from correct configuration: the substituted value is in range,
 * so {@link ConfigValidation} accepts it and the operator never learns that the
 * running system disagrees with the file.</p>
 *
 * <p>Note that inline comments are not part of the properties format. A line such as
 * <code>excalibur.query.threshold=0.8 # 80%</code> yields the value
 * <code>0.8 # 80%</code>, which is rejected here rather than silently discarded.</p>
 *
 * @author ExcaliburDB
 */
final class PropertyParser {

    // Accepted spellings for a boolean value, in either case
    private static final Set<String> TRUE_TOKENS = Set.of("true", "1", "yes", "on");
    private static final Set<String> FALSE_TOKENS = Set.of("false", "0", "no", "off");

    private PropertyParser() {
        // Utility class
    }

    /**
     * Parses an int property.
     *
     * @param props The properties map
     * @param key The property key
     * @param defaultValue Value to use when the key is absent or blank
     * @return The parsed value
     * @throws IllegalArgumentException if the value is present but not an integer
     */
    static int parseInt(Map<String, String> props, String key, int defaultValue) {
        String value = trimmedValue(props, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalidValue(key, value, "a 32-bit integer");
        }
    }

    /**
     * Parses a long property.
     *
     * @param props The properties map
     * @param key The property key
     * @param defaultValue Value to use when the key is absent or blank
     * @return The parsed value
     * @throws IllegalArgumentException if the value is present but not a long
     */
    static long parseLong(Map<String, String> props, String key, long defaultValue) {
        String value = trimmedValue(props, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalidValue(key, value, "a 64-bit integer");
        }
    }

    /**
     * Parses a double property.
     *
     * <p>Non-finite results are rejected: {@code Double.parseDouble} accepts the
     * literals {@code NaN} and {@code Infinity}, and NaN in particular survives every
     * ordered range check downstream while making each comparison against it false.</p>
     *
     * <p>This is the only place finiteness is enforced. Every decimal setting in every
     * section is read through here, so no section and no validator carries a NaN guard of
     * its own: a guard that cannot fire reads like a check and performs none, and copies of
     * one invariant drift apart. Removing this check therefore breaks every section at once,
     * which is what {@code ConfigFinitenessTest} pins.</p>
     *
     * @param props The properties map
     * @param key The property key
     * @param defaultValue Value to use when the key is absent or blank
     * @return The parsed value
     * @throws IllegalArgumentException if the value is present but not a finite number
     */
    static double parseDouble(Map<String, String> props, String key, double defaultValue) {
        String value = trimmedValue(props, key);
        if (value == null) {
            return defaultValue;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw invalidValue(key, value, "a finite decimal number");
        }
        if (!Double.isFinite(parsed)) {
            throw invalidValue(key, value, "a finite decimal number");
        }
        return parsed;
    }

    /**
     * Parses a boolean property.
     *
     * <p>Both the true-set and the false-set are recognised explicitly so that the
     * caller's default is reachable for a blank value and an unrecognised spelling is
     * rejected instead of collapsing to {@code false}.</p>
     *
     * @param props The properties map
     * @param key The property key
     * @param defaultValue Value to use when the key is absent or blank
     * @return The parsed value
     * @throws IllegalArgumentException if the value is present but not a boolean
     */
    static boolean parseBoolean(Map<String, String> props, String key, boolean defaultValue) {
        String value = trimmedValue(props, key);
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (TRUE_TOKENS.contains(normalized)) {
            return true;
        }
        if (FALSE_TOKENS.contains(normalized)) {
            return false;
        }
        throw invalidValue(key, value, "a boolean (true/false, 1/0, yes/no, on/off)");
    }

    /**
     * Parses a string property.
     *
     * @param props The properties map
     * @param key The property key
     * @param defaultValue Value to use when the key is absent or blank
     * @return The trimmed value, or the default
     */
    static String parseString(Map<String, String> props, String key, String defaultValue) {
        String value = trimmedValue(props, key);
        return value != null ? value : defaultValue;
    }

    /**
     * Returns the trimmed value for a key, or null when the key is absent or blank.
     *
     * @param props The properties map
     * @param key The property key
     * @return Trimmed value, or null if there is nothing to parse
     */
    private static String trimmedValue(Map<String, String> props, String key) {
        String value = props.get(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Builds the rejection for a value that cannot be parsed as the requested type.
     *
     * @param key The property key
     * @param value The offending value
     * @param expected Human-readable description of the expected form
     * @return Exception to throw
     */
    private static IllegalArgumentException invalidValue(String key, String value, String expected) {
        return new IllegalArgumentException(
            "Invalid value for " + key + ": expected " + expected + ", got: " + value);
    }
}
