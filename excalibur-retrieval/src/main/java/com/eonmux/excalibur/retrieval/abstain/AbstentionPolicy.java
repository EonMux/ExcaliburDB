package com.eonmux.excalibur.retrieval.abstain;

import java.util.List;

import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;

/**
 * Decides whether retrieval should answer, decline, or hand back an ambiguity.
 *
 * <p>A separate seam from ranking on purpose. Ranking says which candidate is best; this says
 * whether the best candidate is good enough to act on, which is a different question with a
 * different failure mode — and one that has to be tuned against measured outcomes rather than
 * intuition. Keeping it here means the threshold can be recalibrated without touching a scorer.
 */
public interface AbstentionPolicy {

    /**
     * Decides what to do with a ranked result.
     *
     * @param fused    the fused candidates, best first; may be empty
     * @param subgraph the evidence assembled for the best candidates
     * @return what retrieval concluded
     */
    RetrievalOutcome decide(List<FusedDocument> fused, Subgraph subgraph);

    /**
     * A policy that never declines.
     *
     * <p>For measuring what abstention costs and buys: running a query set through this and through
     * a real policy is how the precision gained and the coverage lost are quantified. Not for
     * production, where it means answering confidently from a single weak match.
     */
    static AbstentionPolicy neverAbstain() {
        return (fused, subgraph) -> fused.isEmpty()
            ? RetrievalOutcome.NoResults.of(RetrievalOutcome.Reason.NO_MATCH)
            : new RetrievalOutcome.Results(subgraph, 1.0);
    }
}
