package com.eonmux.excalibur.retrieval.serialize;

/**
 * The result of serialising a subgraph under a budget.
 *
 * <p>Carries what was dropped as well as what was kept. A caller that cannot tell whether the
 * budget bound cannot tell the difference between "this is everything the graph knows" and "this is
 * the first forty facts of two hundred" — and those warrant very different confidence in whatever
 * the model then says.
 *
 * @param text          the serialised form, ready for the prompt
 * @param tokens        what it actually costs, as the budget's counter measures it
 * @param edgesIncluded how many edges made it in
 * @param edgesDropped  how many were left out for want of budget
 * @param format        which serializer produced this, so an A/B test can attribute results
 */
public record SerializedSubgraph(String text, int tokens, int edgesIncluded, int edgesDropped,
                                 String format) {

    public SerializedSubgraph {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (edgesIncluded < 0 || edgesDropped < 0) {
            throw new IllegalArgumentException("edge counts must not be negative");
        }
    }

    /** Whether the budget bound, so the model is seeing a subset. */
    public boolean wasTruncated() {
        return edgesDropped > 0;
    }

    /** Fraction of the available edges that made it into the prompt. */
    public double retentionRatio() {
        int total = edgesIncluded + edgesDropped;
        return total == 0 ? 1.0 : edgesIncluded / (double) total;
    }
}
