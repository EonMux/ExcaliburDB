package io.excalibur.retrieval.rerank;

import java.util.List;

/**
 * A model that judges a whole batch of candidates against a query in one call.
 *
 * <p>The signature takes a list and returns a list precisely so that a per-candidate call cannot be
 * expressed through it. Scoring fifty candidates one at a time means fifty prefills of the same
 * query and instructions — the shared prefix is the bulk of the prompt, so the cost is roughly
 * fifty times what it needs to be, and fifty round trips of latency on top.
 */
@FunctionalInterface
public interface BatchRelevanceModel {

    /**
     * Scores every candidate in one call.
     *
     * @param query      the query text
     * @param candidates candidate texts, in the order they were ranked
     * @return one score per candidate, same order and same length; higher means more relevant
     * @throws RuntimeException if the model is unavailable — the caller treats this as a failure
     *                          worth recording against the circuit breaker
     */
    List<Double> scoreBatch(String query, List<String> candidates);
}
