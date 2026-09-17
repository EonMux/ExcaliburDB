package io.excalibur.metrics;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The two fractions an operator reads to decide whether a node is worth sending traffic to:
 * how healthy it believes itself to be, and how much of the corpus it believes it can see.
 *
 * <h2>Why they sit together</h2>
 * <p>Both are published by the same {@code SystemHealth} MBean and by the same stats endpoint,
 * both are clamped by the same rule, and both are read together whenever a caller is deciding
 * about a node rather than measuring one. Nothing derived from them is stored: readiness is
 * computed from the score on every read, because a stored flag can be left behind by a score
 * update and then the {@code isHealthy} and {@code ready} attributes of one MBean contradict
 * each other.</p>
 *
 * <h2>Thread-Safety</h2>
 * <p>Each value is a single atomic reference. Writes are plain sets rather than
 * compare-and-set: the last writer wins by design, because these are the current estimate of a
 * condition, not an accumulation, and an estimate has no increment to lose.</p>
 *
 * @author ExcaliburDB
 */
final class HealthState {

    /** Health score at or above which the system counts as healthy and ready. */
    static final double HEALTHY_SCORE_THRESHOLD = 0.5;

    private final AtomicReference<Double> healthScore = new AtomicReference<>(0.5);
    private final AtomicReference<Double> estimatedCoverage = new AtomicReference<>(0.0);

    /**
     * Creates a health state that starts at the threshold, neither healthy-with-margin nor
     * unhealthy, and at zero known coverage.
     */
    HealthState() {
    }

    /**
     * Gets the system health score.
     *
     * @return Health score in [0.0, 1.0]
     */
    double score() {
        return healthScore.get();
    }

    /**
     * Sets the system health score, clamping it into the valid domain.
     *
     * @param score Raw health score
     * @return The value actually stored, so a caller can publish it without re-reading
     */
    double setScore(double score) {
        double clamped = UnitInterval.clamp(score);
        healthScore.set(clamped);
        return clamped;
    }

    /**
     * Gets whether the system is healthy.
     *
     * @return true when the health score is at or above the healthy threshold
     */
    boolean isHealthy() {
        return score() >= HEALTHY_SCORE_THRESHOLD;
    }

    /**
     * Gets the estimated corpus coverage.
     *
     * @return Coverage in [0.0, 1.0]
     */
    double coverage() {
        return estimatedCoverage.get();
    }

    /**
     * Sets the estimated corpus coverage, clamping it into the valid domain.
     *
     * @param coverage Raw coverage value
     */
    void setCoverage(double coverage) {
        estimatedCoverage.set(UnitInterval.clamp(coverage));
    }
}
