package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the direction and the domain of the system health signal.
 *
 * <p>The health score is "higher is better" and the healthy flag is derived from it. An
 * inverted flag is worse than no flag at all: it reports a system as healthy at the exact
 * moment it stops serving within its latency budget. These tests pin the direction so the
 * comparison cannot be flipped again without a failure.</p>
 *
 * @author ExcaliburDB
 */
class MetricsHealthTest {

    /** The threshold the {@code ready} attribute documents. */
    private static final double READY_THRESHOLD = 0.5;

    @Test
    @DisplayName("Latency far above the threshold makes the system unhealthy, not healthy")
    void latencyAboveThresholdIsUnhealthy() {
        Metrics.updateHealth(900, 1000);
        assertTrue(Metrics.getHealthScore() < READY_THRESHOLD,
            "score should drop below the ready threshold at 90% of the latency budget");
        assertFalse(Metrics.isHealthy(),
            "isHealthy must be false when the score is below the ready threshold");
    }

    @Test
    @DisplayName("Latency well inside the threshold keeps the system healthy")
    void latencyInsideThresholdIsHealthy() {
        Metrics.updateHealth(100, 1000);
        assertTrue(Metrics.getHealthScore() >= READY_THRESHOLD,
            "score should stay at or above the ready threshold at 10% of the latency budget");
        assertTrue(Metrics.isHealthy(),
            "isHealthy must be true when the score is at or above the ready threshold");
    }

    @Test
    @DisplayName("The healthy flag follows the score back up on recovery")
    void healthyFlagRecovers() {
        Metrics.updateHealth(2000, 1000);
        assertFalse(Metrics.isHealthy(), "precondition: degraded");

        Metrics.updateHealth(10, 1000);
        assertTrue(Metrics.isHealthy(),
            "the flag must be re-evaluated on every update, not only on a drop");
    }

    @Test
    @DisplayName("isHealthy() and the score agree across the whole latency range")
    void flagAndScoreNeverDisagree() {
        for (long latencyMs = 0; latencyMs <= 2000; latencyMs += 50) {
            Metrics.updateHealth(latencyMs, 1000);
            assertEquals(Metrics.getHealthScore() >= READY_THRESHOLD, Metrics.isHealthy(),
                "flag and score disagree at latency " + latencyMs + "ms");
            assertEquals(Metrics.isHealthy(), readyAttribute(),
                "the ready attribute disagrees with isHealthy at latency " + latencyMs + "ms");
        }
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
    @DisplayName("Health score stays inside [0.0, 1.0] whatever the latency")
    void scoreStaysInDomain() {
        Metrics.updateHealth(0, 1000);
        assertEquals(1.0, Metrics.getHealthScore(), 1e-9, "zero latency is perfect health");

        Metrics.updateHealth(10_000, 1000);
        assertEquals(0.0, Metrics.getHealthScore(), 1e-9,
            "ten times over budget clamps at zero rather than going negative");
    }

    @Test
    @DisplayName("A non-positive threshold is rejected instead of producing NaN")
    void nonPositiveThresholdIsRejected() {
        double before = Metrics.getHealthScore();

        assertThrows(IllegalArgumentException.class, () -> Metrics.updateHealth(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Metrics.updateHealth(5, -1));

        assertEquals(before, Metrics.getHealthScore(), 0.0,
            "a rejected update must not disturb the published score");
        assertFalse(Double.isNaN(Metrics.getHealthScore()), "score must never become NaN");
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
