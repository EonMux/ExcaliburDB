package com.eonmux.excalibur.retrieval.serialize;

import java.util.List;

import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;

/**
 * The result of serialising a subgraph under a budget.
 *
 * <p>Carries what was dropped as well as what was kept. A caller that cannot tell whether the
 * budget bound cannot tell the difference between "this is everything the graph knows" and "this is
 * the first forty facts of two hundred" — and those warrant very different confidence in whatever
 * the model then says.
 *
 * <p>The facts that were kept are carried as edges as well as text. Each carries its provenance,
 * so a caller can say which document a stated fact came from without parsing the text back apart,
 * and they are in the order the text states them, which is the serializer's ranking of what was
 * worth prompt space. Re-ranking the subgraph to recover that order would be a second copy of the
 * ranking, free to drift from the one the answer was actually built with.
 *
 * @param text         the serialised form, ready for the prompt
 * @param tokens       what it actually costs, as the budget's counter measures it
 * @param edges        the facts the text states, in the order it states them
 * @param edgesDropped how many were left out for want of budget
 * @param format       which serializer produced this, so an A/B test can attribute results
 */
public record SerializedSubgraph(String text, int tokens, List<SubgraphEdge> edges,
                                 int edgesDropped, String format) {

    public SerializedSubgraph {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (edges == null) {
            throw new IllegalArgumentException("edges must not be null");
        }
        if (edgesDropped < 0) {
            throw new IllegalArgumentException("edge counts must not be negative");
        }
        edges = List.copyOf(edges);
    }

    /** How many edges made it in. */
    public int edgesIncluded() {
        return edges.size();
    }

    /** Whether the budget bound, so the model is seeing a subset. */
    public boolean wasTruncated() {
        return edgesDropped > 0;
    }

    /** Fraction of the available edges that made it into the prompt. */
    public double retentionRatio() {
        int total = edgesIncluded() + edgesDropped;
        return total == 0 ? 1.0 : edgesIncluded() / (double) total;
    }
}
