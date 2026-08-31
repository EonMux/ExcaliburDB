package io.excalibur.graph.store;

/**
 * Encoding that lets one {@code int[]} filter name both a relation and the direction it is
 * traversed in.
 *
 * <p>{@link EdgeStore} exposes three methods and no direction parameter, but a reverse index is
 * mandatory and real retrieval mixes directions within a single traversal: "who directed this
 * film" follows {@code directed} backwards from the film while "what else did they direct" follows
 * it forwards from the person, and both belong to one expansion. Folding direction into the
 * selector keeps that expressible without widening the interface.
 *
 * <p>A selector is an {@code int}:
 * <ul>
 *   <li>{@code s >= 0} — relation {@code s}, traversed subject to object</li>
 *   <li>{@code s < 0} — relation {@code -(s + 1)}, traversed object to subject</li>
 * </ul>
 *
 * <p>The negative encoding is {@code ~r}, so it is total over every non-negative relation id
 * including {@link Integer#MAX_VALUE}, and round-trips exactly. Negation ({@code -r}) would not:
 * it collides at zero and cannot represent {@link Integer#MIN_VALUE}.
 */
public final class RelationSelector {

    private RelationSelector() {
    }

    /** Selector for {@code relation} traversed subject to object. */
    public static int forward(int relation) {
        checkRelation(relation);
        return relation;
    }

    /** Selector for {@code relation} traversed object to subject. */
    public static int reverse(int relation) {
        checkRelation(relation);
        return ~relation;
    }

    /** Whether {@code selector} traverses against the edge's direction. */
    public static boolean isReverse(int selector) {
        return selector < 0;
    }

    /** The relation id a selector names, in either direction. */
    public static int relationOf(int selector) {
        return selector < 0 ? ~selector : selector;
    }

    /**
     * Selectors covering {@code relation} in both directions, for callers that do not care which
     * way a fact was asserted.
     */
    public static int[] both(int relation) {
        checkRelation(relation);
        return new int[] { relation, ~relation };
    }

    /**
     * Rejects a relation id that cannot be encoded. Relation ids are non-negative because the sign
     * bit carries direction; a negative id passed here would silently become a reverse traversal
     * of a different relation.
     */
    public static void checkRelation(int relation) {
        if (relation < 0) {
            throw new IllegalArgumentException(
                "relation id must be non-negative, got " + relation
                    + "; the sign bit encodes traversal direction");
        }
    }

    /** Human-readable form, for diagnostics and test failures. */
    public static String toString(int selector) {
        return (isReverse(selector) ? "<-" : "->") + relationOf(selector);
    }
}
