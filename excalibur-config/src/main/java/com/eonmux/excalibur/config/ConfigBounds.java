package com.eonmux.excalibur.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads a property and checks it against a domain bound in one step.
 *
 * <h2>Why these live here</h2>
 * <p>{@link PropertyParser} answers "is this value of the right type"; these answer "is it a
 * value the engine can work with". Keeping them together is what makes the two rejections read
 * alike: every message names the property key the operator wrote, then the bound, then what
 * they wrote, so an operator correcting a file never has to map a field name back to a key.
 * Sections that share a bound therefore share its wording, which a per-section copy would not
 * guarantee.</p>
 *
 * <p>Only bounds more than one section needs belong here. A check that is particular to one
 * section - a ratio whose domain only that section uses, a relationship between two of its own
 * keys - stays with the section it belongs to, where its reason is visible.</p>
 *
 * @author ExcaliburDB
 */
final class ConfigBounds {

    private ConfigBounds() {
        // Utility class
    }

    /**
     * Reads an int property and requires it to fall within an inclusive range.
     *
     * @param props The properties map
     * @param key The property key, named in the failure message
     * @param fallback Value to use when the key is absent or blank
     * @param minimum Inclusive lower bound
     * @param maximum Inclusive upper bound
     * @return The parsed value
     * @throws IllegalArgumentException if the value is out of range
     */
    static int inRange(Map<String, String> props, String key, int fallback,
                       int minimum, int maximum) {
        int value = atLeast(props, key, fallback, minimum);
        if (value > maximum) {
            throw new IllegalArgumentException(
                key + " must be at most " + maximum + ", got: " + value);
        }
        return value;
    }

    /**
     * Reads an int property and requires it to meet a lower bound.
     *
     * @param props The properties map
     * @param key The property key, named in the failure message
     * @param fallback Value to use when the key is absent or blank
     * @param minimum Inclusive lower bound
     * @return The parsed value
     * @throws IllegalArgumentException if the value is below the bound
     */
    static int atLeast(Map<String, String> props, String key, int fallback, int minimum) {
        int value = PropertyParser.parseInt(props, key, fallback);
        if (value < minimum) {
            throw new IllegalArgumentException(
                key + " must be at least " + minimum + ", got: " + value);
        }
        return value;
    }

    /**
     * Reads a long property and requires it to meet a lower bound.
     *
     * @param props The properties map
     * @param key The property key, named in the failure message
     * @param fallback Value to use when the key is absent or blank
     * @param minimum Inclusive lower bound
     * @return The parsed value
     * @throws IllegalArgumentException if the value is below the bound
     */
    static long atLeastLong(Map<String, String> props, String key, long fallback, long minimum) {
        long value = PropertyParser.parseLong(props, key, fallback);
        if (value < minimum) {
            throw new IllegalArgumentException(
                key + " must be at least " + minimum + ", got: " + value);
        }
        return value;
    }

    /**
     * Reads a ratio that must name a real fraction of something: greater than zero, at most
     * all of it.
     *
     * <p>Shared by the two sections that decide whether a compaction repays its rewrite from a
     * tombstoned fraction. Zero is excluded because it would ask for a compaction on every
     * write. {@link GraphConfig}'s thresholds read a different domain and keep their own
     * check: a minimum score of zero means "accept any candidate" there, so zero is a value
     * rather than a mistake.</p>
     *
     * @param props The properties map
     * @param key The property key, named in the failure message
     * @param fallback Value to use when the key is absent or blank
     * @return The parsed ratio, in (0, 1]
     * @throws IllegalArgumentException if the value is outside that range
     */
    static double unitRatio(Map<String, String> props, String key, double fallback) {
        double value = PropertyParser.parseDouble(props, key, fallback);
        if (value <= 0 || value > 1) {
            throw new IllegalArgumentException(
                key + " must be in (0, 1], got: " + value);
        }
        return value;
    }

    /**
     * Reads a comma-separated key as a list, trimmed, with empty entries dropped.
     *
     * <p>An absent or blank key is an empty list rather than a list holding one empty
     * string, so a key written out with nothing after the {@code =} means the same as a key
     * that was never written.</p>
     *
     * @param props The properties map
     * @param key The property key
     * @return The entries, in the order given, possibly empty
     */
    static List<String> parseList(Map<String, String> props, String key) {
        return Arrays.stream(PropertyParser.parseString(props, key, "").split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
    }
}
