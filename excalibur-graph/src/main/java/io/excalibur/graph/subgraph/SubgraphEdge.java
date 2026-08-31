package io.excalibur.graph.subgraph;

import io.excalibur.graph.provenance.Provenance;

/**
 * One edge selected into a subgraph, carrying everything needed to rank it, serialise it and
 * justify it.
 *
 * <p>Materialised only for edges that survived selection — a few dozen — so a record per edge is
 * the right shape here, where it would be entirely wrong inside traversal.
 *
 * @param subject          subject entity id
 * @param relation         relation id
 * @param object           object entity id
 * @param forwardEdgeIndex position in the store's forward edge order, so the columns can be
 *                         re-read without a search
 * @param validFrom        first day the fact held
 * @param validTo          first day it no longer held, or {@code TemporalFilter.OPEN}
 * @param provenance       where it came from and how much to trust it
 * @param depth            hops from the nearest seed, which is the strongest available proxy for
 *                         how much this edge has to do with what was asked
 */
public record SubgraphEdge(
    int subject,
    int relation,
    int object,
    long forwardEdgeIndex,
    int validFrom,
    int validTo,
    Provenance provenance,
    int depth
) {

    public SubgraphEdge {
        if (provenance == null) {
            throw new IllegalArgumentException("provenance must not be null");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative, got " + depth);
        }
    }

    /** Extractor confidence as a fraction of 1. */
    public double confidence() {
        return provenance.confidenceFraction();
    }
}
