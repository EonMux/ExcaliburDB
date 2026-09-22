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
    UNKNOWN
}
