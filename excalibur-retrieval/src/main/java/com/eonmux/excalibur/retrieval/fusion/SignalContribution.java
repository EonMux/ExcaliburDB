package com.eonmux.excalibur.retrieval.fusion;

/**
 * What one signal contributed to a fused score.
 *
 * @param rankerName   the signal
 * @param rank         one-based position this signal put the document at
 * @param contribution the term this signal added to the fused score
 */
public record SignalContribution(String rankerName, int rank, double contribution) {

    public SignalContribution {
        if (rank < 1) {
            throw new IllegalArgumentException(
                "rank is one-based; a signal that did not return the document contributes nothing "
                    + "and has no contribution recorded. Got " + rank);
        }
    }
}
