package com.eonmux.excalibur.api.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsing of configuration values shared by the security policies.
 *
 * <p>Every reader here follows the same rule: a value that is present and cannot be understood
 * fails the startup, naming the key that carried it. These keys are the node's security policy -
 * its rate limits, its request bounds, its TLS settings - and falling back to the shipped
 * default would leave an operator believing a policy is in force that is not. The failure
 * happens while configuration is being read, before the listener is bound, which is where every
 * other misconfiguration in this server is refused.
 *
 * <p>An absent value is not a failure; it is how a deployment says "the default is fine".
 *
 * <p>Package-private on purpose. This is not a general-purpose parsing library; it exists so that
 * {@link ApiSecurityConfig} and {@link TlsConfig} read the same key the same way.
 */
final class ConfigText {

    private ConfigText() {
        // Utility class
    }

    /** The value with surrounding whitespace removed, or null when absent or blank. */
    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The named property, trimmed, or null when absent or blank. */
    static String value(Map<String, String> properties, String key) {
        return trimToNull(properties.get(key));
    }

    /**
     * A comma-separated list, with blank entries dropped.
     *
     * <p>Returns an empty list for an absent value, which every caller reads as "nothing
     * configured" rather than "everything allowed".
     */
    static List<String> splitList(String value) {
        List<String> items = new ArrayList<>();
        if (value == null) {
            return items;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    /**
     * A strictly positive, finite double.
     *
     * <p>Zero and negatives are refused rather than clamped or ignored: a rate limit of zero
     * would stop the server answering anything, which is never what an operator meant to type,
     * and answering it with the shipped limit instead hands back a policy nobody chose.
     *
     * @throws IllegalArgumentException if the value is present and is not a positive number
     */
    static double positiveDouble(Map<String, String> properties, String key, double defaultValue) {
        String raw = value(properties, key);
        if (raw == null) {
            return defaultValue;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw refuse(key, raw, "a number", e);
        }
        if (parsed <= 0 || !Double.isFinite(parsed)) {
            throw refuse(key, raw, "a positive, finite number", null);
        }
        return parsed;
    }

    /**
     * A strictly positive long.
     *
     * @throws IllegalArgumentException if the value is present and is not a positive whole number
     */
    static long positiveLong(Map<String, String> properties, String key, long defaultValue) {
        String raw = value(properties, key);
        if (raw == null) {
            return defaultValue;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw refuse(key, raw, "a whole number", e);
        }
        if (parsed <= 0) {
            throw refuse(key, raw, "a positive whole number", null);
        }
        return parsed;
    }

    /**
     * The refusal every reader here raises, worded the same way.
     *
     * <p>The message quotes the value back. That is safe only because every key routed through
     * these readers is numeric or boolean - the token, the keystore passwords and the key
     * password are read by {@link #trimToNull} and {@link #value}, which never reach here.
     * Do not route a secret-bearing key through a reader that refuses: the message reaches the
     * startup log.</p>
     *
     * <p>The reminder about where a value ends is there because the commonest way to land here
     * is a trailing comment. This file's format has no comment syntax after the {@code =}, so
     * {@code max-top-k=1000  # cap} is the seven-character value {@code 1000  # cap}, and an
     * operator reading only "must be a whole number" is looking at what seems to be one.</p>
     *
     * @param key      The property that carried the value
     * @param raw      What it carried
     * @param expected What it had to be
     * @param cause    The parse failure, when there was one
     * @return The exception to throw
     */
    private static IllegalArgumentException refuse(String key, String raw, String expected,
                                                   Throwable cause) {
        return new IllegalArgumentException(
            key + " must be " + expected + ", got: " + raw
                + " (a value is everything after the first '=' to the end of the line, so a "
                + "trailing comment is part of it)", cause);
    }

    /**
     * A boolean flag.
     *
     * <p>Only {@code true} and {@code false} are accepted, case-insensitively. Anything else is
     * refused - notably not {@link Boolean#parseBoolean}, which silently reads every typo,
     * including {@code "TRUE "} with a stray character or {@code "yes"}, as false. For a flag
     * whose whole job is to relax a safety check, reading a typo as a value is worse than not
     * starting at all.
     *
     * @throws IllegalArgumentException if the value is present and is neither true nor false
     */
    static boolean flag(Map<String, String> properties, String key, boolean defaultValue) {
        String raw = value(properties, key);
        if (raw == null) {
            return defaultValue;
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        throw refuse(key, raw, "true or false", null);
    }

    /**
     * A boolean flag that says whether it was set at all.
     *
     * <p>For a setting whose absence is not the same as either value - TLS, where "not stated"
     * means "decide from whether key material was configured" - so that the caller does not have
     * to test for presence and then read it again with a default that cannot be reached.</p>
     *
     * @param properties The effective settings
     * @param key        The property to read
     * @return true, false, or null when the property is absent or blank
     * @throws IllegalArgumentException if the value is present and is neither true nor false
     */
    static Boolean flagOrNull(Map<String, String> properties, String key) {
        return value(properties, key) == null ? null : flag(properties, key, false);
    }
}
