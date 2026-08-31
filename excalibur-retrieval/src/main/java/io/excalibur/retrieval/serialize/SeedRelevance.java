package io.excalibur.retrieval.serialize;

/**
 * How well each entity matched the question that started the traversal.
 *
 * <p>One of the three things edge selection ranks on, and the only one that knows anything about
 * the question — depth and extraction confidence are both properties of the graph alone. Without
 * it, a two-hop subgraph around a strongly matching entity and one around a barely matching entity
 * are indistinguishable at serialisation time.
 */
@FunctionalInterface
public interface SeedRelevance {

    /** Relevance of {@code entityId} to the question, higher meaning more relevant. */
    double scoreOf(long entityId);

    /**
     * Treats every entity as equally relevant.
     *
     * <p>Falls back to ranking on depth and confidence alone, which is a real degradation rather
     * than a neutral default: it is the right choice only when there genuinely is no per-entity
     * score to hand.
     */
    static SeedRelevance uniform() {
        return entityId -> 0.0;
    }
}
