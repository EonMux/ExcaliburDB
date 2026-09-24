package com.eonmux.excalibur.retrieval.pipeline;

/**
 * What the {@link WalkLimits} left out of one walk.
 *
 * <p>Reported with the answer because a truncated walk is a different kind of partial from a
 * truncated context. The context budget drops facts the walk found; these are facts and entities
 * the walk never collected, so a caller that sees them can ask a narrower question rather than
 * reading the answer as everything the graph holds.</p>
 *
 * @param entitiesDropped entities a hop reached and did not admit
 * @param factsDropped    facts the walk passed and did not collect
 */
public record WalkTruncation(long entitiesDropped, long factsDropped) {

    /** A walk nothing was left out of. */
    public static final WalkTruncation NONE = new WalkTruncation(0, 0);

    public WalkTruncation {
        if (entitiesDropped < 0 || factsDropped < 0) {
            throw new IllegalArgumentException("dropped counts must not be negative, got "
                + entitiesDropped + " and " + factsDropped);
        }
    }

    /** Whether either limit left anything out. */
    public boolean truncated() {
        return entitiesDropped > 0 || factsDropped > 0;
    }
}
