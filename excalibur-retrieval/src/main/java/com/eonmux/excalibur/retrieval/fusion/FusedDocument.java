package com.eonmux.excalibur.retrieval.fusion;

import java.util.List;

/**
 * A candidate after fusion, with the breakdown of how it got there.
 *
 * <p>The breakdown is kept rather than discarded because two later stages need it. Calibrated
 * abstention thresholds on the fused score and has to know whether that score came from one signal
 * or several agreeing — one signal ranking something first is much weaker evidence than three
 * signals ranking it in their top ten. And when a result is wrong, the breakdown is what says which
 * signal to fix.
 *
 * @param documentId    the candidate
 * @param score         the fused score, higher meaning better; under {@link ScorePassthrough}
 *                      it is the one signal's own score
 * @param contributions one entry per signal that returned this document, best rank first
 */
public record FusedDocument(long documentId, double score, List<SignalContribution> contributions)
        implements Comparable<FusedDocument> {

    public FusedDocument {
        if (Double.isNaN(score)) {
            // Refused here, where every consumer reads it: the abstention policy and its
            // calibrator would otherwise each meet a NaN on their own terms and judge it apart.
            throw new IllegalArgumentException("score must not be NaN for document " + documentId);
        }
        if (contributions == null) {
            throw new IllegalArgumentException("contributions must not be null");
        }
        contributions = List.copyOf(contributions);
    }

    /**
     * How many signals returned this document.
     *
     * <p>Agreement between independent signals is a different kind of evidence than a high score
     * from one, and abstention reads it separately.
     */
    public int signalCount() {
        return contributions.size();
    }

    /** The best position any signal gave this document. */
    public int bestRank() {
        return contributions.stream().mapToInt(SignalContribution::rank).min().orElse(0);
    }

    /** Orders best first, breaking ties on document id so the result is deterministic. */
    @Override
    public int compareTo(FusedDocument other) {
        int byScore = Double.compare(other.score, this.score);
        return byScore != 0 ? byScore : Long.compare(this.documentId, other.documentId);
    }
}
