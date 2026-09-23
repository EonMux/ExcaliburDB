package com.eonmux.excalibur.planner;

/**
 * The grammatical shape of a mention, used to rule out impossible pronoun bindings.
 *
 * <p>Coarse on purpose. This is not a parser; it is enough to stop "it" binding to a person and
 * "they" binding to a single object, which removes most wrong bindings for almost no cost. Anything
 * finer needs the entity's type from the graph, which is not available until after linking — and
 * linking is what this resolution feeds.
 */
public enum Grammar {

    /** A single person: binds he, she, him, her, his, hers, they, them, their. */
    SINGULAR_PERSON,

    /** A single non-person: binds it, its, this, that. */
    SINGULAR_THING,

    /** Several of anything: binds they, them, their, these, those. */
    PLURAL,

    /**
     * Shape not known.
     *
     * <p>Binds any pronoun. Being permissive here is deliberate: refusing to bind an unclassified
     * mention would send the user a question about something a person would find obvious.
     */
    UNKNOWN;

    /** The wire name, as a request spells it. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Reads a shape a request named.
     *
     * <p>Refused rather than read as {@link #UNKNOWN} when it is not one of these: a caller that
     * sent a shape did so to rule a binding out, and one silently treated as having sent nothing
     * gets back the ambiguity it was trying to avoid, with nothing to say why.</p>
     *
     * @param text the value as sent, in any case
     * @return the shape
     * @throws IllegalArgumentException if it is null or not one of the wire names
     */
    public static Grammar parse(String text) {
        if (text != null) {
            String wanted = text.trim().toLowerCase(java.util.Locale.ROOT);
            for (Grammar grammar : values()) {
                if (grammar.wireName().equals(wanted)) {
                    return grammar;
                }
            }
        }
        java.util.List<String> accepted = new java.util.ArrayList<>();
        for (Grammar grammar : values()) {
            accepted.add("\"" + grammar.wireName() + "\"");
        }
        throw new IllegalArgumentException("a grammatical shape must be one of "
            + String.join(", ", accepted) + ", got: " + text);
    }
}
