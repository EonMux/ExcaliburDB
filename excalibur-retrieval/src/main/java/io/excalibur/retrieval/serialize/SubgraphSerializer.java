package io.excalibur.retrieval.serialize;

import io.excalibur.graph.subgraph.Subgraph;

/**
 * Turns a selected subgraph into text for the model.
 *
 * <p>An interface because the format matters and nobody knows the best one in advance. Whether
 * facts read better as triples, as sentences, as a table or grouped by entity changes what the
 * model does with them, and the only way to find out is to run two and compare. That is only
 * possible if the format is a swappable component rather than a StringBuilder in the middle of the
 * query path.
 *
 * <p>Every implementation must honour the budget exactly and deduplicate on entity id.
 */
public interface SubgraphSerializer {

    /** Names the format, so an experiment can attribute an outcome to it. */
    String name();

    /**
     * Serialises as much of {@code subgraph} as the budget allows.
     *
     * @param subgraph  the evidence
     * @param budget    hard token ceiling; the result must not exceed it
     * @param relevance per-entity match scores, for ranking what to keep
     */
    SerializedSubgraph serialize(Subgraph subgraph, SerializationBudget budget,
                                 SeedRelevance relevance);
}
