package com.eonmux.excalibur.bench.kernel;

/**
 * Accumulates false positives across batches of filter probes.
 *
 * <p>The Cuckoo filter's specification is a rate of at most 0.1%. One batch of a few
 * thousand probes cannot distinguish 0.09% from 0.11%, so the rate that matters is the
 * one accumulated over the whole run. Keeping a running numerator and denominator -
 * rather than averaging per-batch rates - is what makes batches of different sizes
 * weigh what they should: a mean of rates lets a handful of tiny clean batches cancel
 * out one large dirty one.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe. One instance belongs to one thread; JMH gives each benchmark
 * thread its own.</p>
 */
public final class FalsePositiveTally {

    /** Standard errors above the specification an observation must be to count as an excess. */
    private static final double SIGNIFICANCE_SIGMAS = 6.0;

    private final double specification;
    private long falsePositives;
    private long queries;

    /**
     * Creates a tally checked against a false positive rate specification.
     *
     * @param specification the maximum acceptable rate, a fraction in {@code (0, 1]}
     * @throws IllegalArgumentException if the specification is not a positive fraction
     */
    public FalsePositiveTally(double specification) {
        if (!(specification > 0.0) || specification > 1.0) {
            throw new IllegalArgumentException(
                "specification must be a fraction in (0, 1], got " + specification);
        }
        this.specification = specification;
    }

    /**
     * Records the outcome of one batch of probes.
     *
     * @param falsePositives how many probes for absent keys reported a hit
     * @param batchSize how many probes the batch made
     * @throws IllegalArgumentException if either count is negative, or more false
     *         positives are reported than probes were made
     */
    public void record(int falsePositives, int batchSize) {
        if (falsePositives < 0 || batchSize < 0) {
            throw new IllegalArgumentException(
                "counts cannot be negative: " + falsePositives + " of " + batchSize);
        }
        if (falsePositives > batchSize) {
            throw new IllegalArgumentException(
                "cannot record " + falsePositives + " false positives in a batch of " + batchSize);
        }
        this.falsePositives += falsePositives;
        this.queries += batchSize;
    }

    /**
     * The accumulated false positive rate.
     *
     * @return the rate in {@code [0, 1]}, or 0 when nothing has been probed
     */
    public double rate() {
        return queries == 0 ? 0.0 : (double) falsePositives / queries;
    }

    /**
     * Whether the accumulated rate satisfies the specification.
     *
     * <p>An empty tally fails: a run that probed nothing has not demonstrated the
     * specification, and reporting it as met would be the one answer that cannot be
     * checked.</p>
     *
     * @return true if probes were made and their rate is at or below the specification
     */
    public boolean meetsSpecification() {
        return queries > 0 && rate() <= specification;
    }

    /**
     * Whether the accumulated rate is above the specification by more than sampling error.
     *
     * <p>{@link #meetsSpecification()} compares the observed rate with the specification as it
     * stands, which is the right question for a run that has probed enough keys to pin the rate
     * down and the wrong one for a correctness check that has to hold on every run: a filter
     * whose true rate sits just under the specification exceeds it on a fair share of finite
     * samples. This asks instead whether the observation is inconsistent with the specification
     * - more than six standard errors above it, plus one collision's worth for a small sample -
     * which a correct filter essentially never is, while a defect that multiplies the rate is
     * caught as soon as the sample is large enough to see it.</p>
     *
     * <p>The standard error is taken over {@code independentProbes}, not over every recorded
     * probe. A benchmark that cycles through a fixed set of keys probes each one repeatedly, and
     * a key whose fingerprint collides collides again on every pass, so a million probes of ten
     * thousand keys know the rate only as well as ten thousand draws do.</p>
     *
     * @param independentProbes how many of the recorded probes were independent draws; values
     *        above the number of recorded probes are capped at it
     * @return true if probes were recorded and their rate is significantly above the
     *         specification
     * @throws IllegalArgumentException if probes were recorded and {@code independentProbes}
     *         is not positive
     */
    public boolean significantlyExceedsSpecification(long independentProbes) {
        if (queries == 0) {
            return false;
        }
        if (independentProbes <= 0) {
            throw new IllegalArgumentException(
                "independentProbes must be positive, got " + independentProbes);
        }
        double draws = Math.min(independentProbes, queries);
        double standardError = Math.sqrt(specification * (1.0 - specification) / draws);
        return rate() > specification + SIGNIFICANCE_SIGMAS * standardError + 1.0 / draws;
    }

    /**
     * The total number of false positives recorded.
     *
     * @return the accumulated false positive count
     */
    public long falsePositives() {
        return falsePositives;
    }

    /**
     * The total number of probes recorded.
     *
     * @return the accumulated probe count
     */
    public long queries() {
        return queries;
    }

    /**
     * The specification this tally is checked against.
     *
     * @return the maximum acceptable rate
     */
    public double specification() {
        return specification;
    }
}
