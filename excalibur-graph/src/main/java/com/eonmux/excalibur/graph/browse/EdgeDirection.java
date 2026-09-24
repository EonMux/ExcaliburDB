package com.eonmux.excalibur.graph.browse;

import java.util.Locale;

/**
 * Which of an entity's edges a listing reads.
 */
public enum EdgeDirection {

    /** The edges the entity is the subject of. */
    OUT,

    /** The edges the entity is the object of. */
    IN,

    /** Both: the outgoing ones first, then the incoming ones. */
    BOTH;

    /**
     * How a request spells this direction.
     *
     * @return {@code out}, {@code in} or {@code both}
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a direction as a request spells it.
     *
     * @param text {@code out}, {@code in} or {@code both}, in any case
     * @return The direction
     * @throws IllegalArgumentException if it is none of the three
     */
    public static EdgeDirection parse(String text) {
        if (text != null) {
            String wanted = text.trim().toLowerCase(Locale.ROOT);
            for (EdgeDirection direction : values()) {
                if (direction.wireName().equals(wanted)) {
                    return direction;
                }
            }
        }
        throw new IllegalArgumentException(
            "'direction' must be \"out\", \"in\" or \"both\", got \"" + text + "\"");
    }
}
