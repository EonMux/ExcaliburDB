package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the direction and the domain of the system health signal.
 *
 * <p>The health score is "higher is better" and the healthy flag is derived from it. An
 * inverted flag is worse than no flag at all: it reports a system as healthy at the exact
 * moment it has lost the corpus it serves. These tests pin the direction so the comparison
 * cannot be flipped without a failure.</p>
 *
 * @author ExcaliburDB
 */
class MetricsHealthTest {

    /** The threshold the {@code ready} attribute documents. */
    private static final double READY_THRESHOLD = 0.5;

    @Test
    @DisplayName("A score below the threshold makes the system unhealthy, not healthy")
    void lowScoreIsUnhealthy() {
        Metrics.setHealthScore(0.1);
        assertFalse(Metrics.isHealthy(),
            "isHealthy must be false when the score is below the ready threshold");
    }

    @Test
    @DisplayName("A score well above the threshold keeps the system healthy")
    void highScoreIsHealthy() {
        Metrics.setHealthScore(0.9);
        assertTrue(Metrics.isHealthy(),
            "isHealthy must be true when the score is at or above the ready threshold");
    }

    @Test
    @DisplayName("The healthy flag follows the score back up on recovery")
    void healthyFlagRecovers() {
        Metrics.setHealthScore(0.0);
        assertFalse(Metrics.isHealthy(), "precondition: degraded");

        Metrics.setHealthScore(0.95);
        assertTrue(Metrics.isHealthy(),
            "the flag must be re-evaluated on every update, not only on a drop");
    }

    @Test
    @DisplayName("isHealthy() and the score agree across the whole score range")
    void flagAndScoreNeverDisagree() {
        for (int step = 0; step <= 20; step++) {
            double score = step / 20.0;
            Metrics.setHealthScore(score);
            assertEquals(Metrics.getHealthScore() >= READY_THRESHOLD, Metrics.isHealthy(),
                "flag and score disagree at score " + score);
            assertEquals(Metrics.isHealthy(), readyAttribute(),
                "the ready attribute disagrees with isHealthy at score " + score);
        }
    }

    @Test
    @DisplayName("An unmeasured health state is not healthy")
    void unmeasuredStateIsNotHealthy() {
        HealthState fresh = new HealthState();

        assertEquals(0.0, fresh.score(), 0.0,
            "the score is coverage, and nothing has been measured to be visible yet");
        assertFalse(fresh.isHealthy());
        assertEquals(0.0, fresh.coverage(), 0.0);
    }

    @Test
    @DisplayName("A score set directly drives the healthy flag too")
    void setHealthScoreDrivesTheFlag() throws Exception {
        Metrics.setHealthScore(0.2);
        assertFalse(Metrics.isHealthy(),
            "isHealthy must be false whenever the score is below the ready threshold, "
                + "however the score was set");
        assertEquals(Metrics.isHealthy(), readyAttribute(),
            "isHealthy and ready must never contradict each other");

        Metrics.setHealthScore(0.8);
        assertTrue(Metrics.isHealthy(), "a healthy score means a healthy system");
        assertEquals(Metrics.isHealthy(), readyAttribute(),
            "isHealthy and ready must never contradict each other");

        assertEquals(Metrics.isHealthy(), Metrics.getSystemHealthMBean().getAttribute("isHealthy"),
            "the MBean attribute must report the same flag");
    }

    @Test
    @DisplayName("setHealthScore clamps out-of-domain values, NaN included")
    void setHealthScoreClamps() {
        Metrics.setHealthScore(2.0);
        assertEquals(1.0, Metrics.getHealthScore(), 0.0, "above 1.0 clamps to 1.0");

        Metrics.setHealthScore(-1.0);
        assertEquals(0.0, Metrics.getHealthScore(), 0.0, "below 0.0 clamps to 0.0");

        Metrics.setHealthScore(Double.NaN);
        assertFalse(Double.isNaN(Metrics.getHealthScore()),
            "NaN must not reach the published score");
        assertEquals(0.0, Metrics.getHealthScore(), 0.0, "NaN reads as no health");
    }

    private boolean readyAttribute() {
        try {
            return (Boolean) Metrics.getSystemHealthMBean().getAttribute("ready");
        } catch (Exception e) {
            throw new AssertionError("ready attribute should be readable", e);
        }
    }
}
