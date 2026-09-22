package com.eonmux.excalibur.retrieval.abstain;

/**
 * One answered query: the confidence a policy reported, and whether that answer was right.
 *
 * <p>Deliberately not the same thing as {@link AbstentionCalibrator.LabelledOutcome}. That one
 * asks whether a query should have been answered at all and is fitted against the fused score;
 * this one asks whether the number attached to an answer meant anything. The two are fitted
 * separately because they fail separately — a policy can pick exactly the right queries to answer
 * and still state its confidence in them dishonestly, and a policy can be perfectly calibrated
 * over a set of queries it should never have answered.
 *
 * <p>It exists only for queries that were answered. A refusal reports no confidence, and an
 * ambiguity reports a list of candidates rather than a probability, so neither has anything to
 * calibrate. That makes the calibration sample a subset of the query set, and the error measured
 * over it describes the answers given rather than all questions asked — which is what a caller
 * wants to know, and is why coverage stays {@link AbstentionCalibrator#calibrate}'s business.
 *
 * @param confidence what the policy reported, in {@code [0, 1]}
 * @param correct    whether the answer it reported that confidence for was right
 */
public record ConfidenceOutcome(double confidence, boolean correct) {

    public ConfidenceOutcome {
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0, 1], got " + confidence);
        }
    }
}
