package com.eonmux.excalibur.retrieval.pipeline;

import java.util.Locale;

import com.eonmux.excalibur.graph.store.RelationSelector;

/**
 * Which way along an edge a traversal may step from the entities a question names.
 *
 * <p>Named as {@link RelationSelector} names them, because that is what each value becomes: a
 * forward step follows a fact from its subject to its object, a reverse step from its object back
 * to its subject.
 *
 * <p>{@link #BOTH} is the default and the right answer to almost every question - a question is
 * about an edge, not about the end someone asserted it from, and walking one way only answers
 * "who did Ada Lovelace collaborate with" while refusing the same question asked about Charles
 * Babbage. The other two are for a caller that knows the direction carries meaning: "what did
 * this company acquire" wants the {@code acquired} edges it is the subject of, and the ones where
 * it was acquired are a different answer.
 */
public enum TraversalDirection {

    /** Subject to object: the facts the named entity asserts. */
    FORWARD,

    /** Object to subject: the facts asserted about the named entity. */
    REVERSE,

    /** Either way along every edge. */
    BOTH;

    /** The wire name, as a request spells it. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The selectors following {@code relation} in this direction.
     *
     * @param relation a relation id
     * @return one selector, or two for {@link #BOTH}
     */
    int[] selectorsFor(int relation) {
        return switch (this) {
            case FORWARD -> new int[] { RelationSelector.forward(relation) };
            case REVERSE -> new int[] { RelationSelector.reverse(relation) };
            case BOTH -> RelationSelector.both(relation);
        };
    }

    /**
     * Reads a direction a request named.
     *
     * <p>Refused rather than defaulted when it is not one of the three: a caller that asked for
     * one direction and silently got both would read facts it excluded as though they answered its
     * question.
     *
     * @param text the value as sent
     * @return the direction
     * @throws IllegalArgumentException if it is null or not {@code forward}, {@code reverse} or
     *                                  {@code both}
     */
    public static TraversalDirection parse(String text) {
        if (text != null) {
            for (TraversalDirection direction : values()) {
                if (direction.wireName().equals(text.trim().toLowerCase(Locale.ROOT))) {
                    return direction;
                }
            }
        }
        throw new IllegalArgumentException("'direction' must be \"forward\", \"reverse\" or "
            + "\"both\", got: " + text);
    }
}
