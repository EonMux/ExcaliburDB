package com.eonmux.excalibur.retrieval.pipeline;

import com.eonmux.excalibur.config.GraphConfig;

/**
 * How wide one walk of the graph may grow.
 *
 * <p>Without a bound, the cost of a question is set by the widest entity it touches rather than by
 * the question: a hub with a million neighbours hands every one of them to the next hop and to the
 * facts collected, and a two-hop walk from it reads the graph. Both bounds keep what they keep by
 * a deterministic ranking, so a truncated answer is the same answer every time it is asked.</p>
 *
 * @param maxFrontier most new entities one hop admits; the rest are the ones reached along the
 *                    weakest edges - by extraction confidence, then the earlier valid-time start,
 *                    then the higher entity id
 * @param maxFacts    most facts one walk collects; the rest are the ones farthest from the entities
 *                    the question named, then the least confident, then the earliest start
 */
public record WalkLimits(int maxFrontier, int maxFacts) {

    public WalkLimits {
        if (maxFrontier < 1) {
            throw new IllegalArgumentException("maxFrontier must be at least 1, got " + maxFrontier);
        }
        if (maxFacts < 1) {
            throw new IllegalArgumentException("maxFacts must be at least 1, got " + maxFacts);
        }
    }

    /**
     * The limits a node applies when nothing configures them.
     *
     * @return {@code excalibur.graph.max-frontier} and {@code max-walk-facts} at their defaults
     */
    public static WalkLimits defaults() {
        return new WalkLimits(GraphConfig.DEFAULT_MAX_FRONTIER, GraphConfig.DEFAULT_MAX_WALK_FACTS);
    }

    /**
     * The limits a node is configured with.
     *
     * @param config the graph section
     * @return its {@code max-frontier} and {@code max-walk-facts}
     */
    public static WalkLimits of(GraphConfig config) {
        return new WalkLimits(config.getMaxFrontier(), config.getMaxWalkFacts());
    }
}
