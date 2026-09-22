package io.excalibur.retrieval.abstain;

/**
 * One band of a reliability diagram: what a range of confidences claimed, against what it achieved.
 *
 * <p>A single calibration error is a summary, and a summary hides which end of the scale is wrong.
 * A system that is honest at 0.6 and wildly overconfident at 0.95 has the same expected
 * calibration error as one that is mildly overconfident everywhere, and the two call for different
 * responses — the first is dangerous precisely where a caller is most likely to act without
 * checking. The bins are kept so that distinction survives.
 *
 * @param lowerBound       inclusive lower edge of the confidence band
 * @param upperBound       exclusive upper edge, except for the topmost band, which includes 1.0
 * @param count            how many answers fell in this band
 * @param meanConfidence   mean confidence claimed within the band; 0 when the band is empty
 * @param observedAccuracy fraction of those answers that were right; 0 when the band is empty
 */
public record ReliabilityBin(double lowerBound, double upperBound, int count,
                             double meanConfidence, double observedAccuracy) {

    public ReliabilityBin {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative, got " + count);
        }
        if (lowerBound > upperBound) {
            throw new IllegalArgumentException(
                "lowerBound " + lowerBound + " exceeds upperBound " + upperBound);
        }
        requireRate("meanConfidence", meanConfidence);
        requireRate("observedAccuracy", observedAccuracy);
    }

    private static void requireRate(String name, double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1], got " + value);
        }
    }

    /** Whether any answer fell in this band. An empty band reports no gap, because it saw none. */
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * How far the claim was from the outcome, as an absolute difference.
     *
     * <p>Zero for an empty band. That is an absence of evidence rather than a perfect result, so
     * callers weighting bins must consult {@link #isEmpty()} rather than reading a zero gap as a
     * band that got it right.
     */
    public double gap() {
        return isEmpty() ? 0.0 : Math.abs(meanConfidence - observedAccuracy);
    }

    /**
     * Whether this band claimed more than it delivered.
     *
     * <p>The asymmetry matters: overconfidence makes a caller act on an answer it should have
     * checked, while underconfidence makes it check an answer it could have acted on. Only the
     * first produces a wrong action.
     */
    public boolean overconfident() {
        return !isEmpty() && meanConfidence > observedAccuracy;
    }
}
