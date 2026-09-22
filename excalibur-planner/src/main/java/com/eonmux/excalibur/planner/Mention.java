package com.eonmux.excalibur.planner;

/**
 * An entity named in the conversation, and when.
 *
 * @param text  the surface form, as written
 * @param grammar its grammatical shape, for ruling out impossible bindings
 * @param turn  which conversation turn it appeared in; higher is more recent
 */
public record Mention(String text, Grammar grammar, int turn) {

    public Mention {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("mention text must not be blank");
        }
        if (grammar == null) {
            throw new IllegalArgumentException("grammar must not be null");
        }
        if (turn < 0) {
            throw new IllegalArgumentException("turn must not be negative, got " + turn);
        }
    }

    /** A mention of unknown grammatical shape. */
    public static Mention of(String text, int turn) {
        return new Mention(text, Grammar.UNKNOWN, turn);
    }
}
