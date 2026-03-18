package io.excalibur.core.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Health gauge for system status monitoring.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is thread-safe using atomic operations.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Health score MUST be in range [0.0, 1.0]</li>
 *   <li>Score of 0.8 or higher = healthy</li>
 *   <li>Score below 0.8 = unhealthy</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class HealthGauge {

    // Current health score stored as fixed-point (score * 10000)
    private static final AtomicLong healthScore = new AtomicLong(10000);

    // Health state machine for preventing rapid transitions
    private static final AtomicBoolean lastHealthy = new AtomicBoolean(true);

    // Transition cooldown to prevent thrashing
    private static final AtomicInteger lastTransitionTime = new AtomicInteger(0);
    private static final long TRANSITION_COOLDOWN_MS = 1000;

    private HealthGauge() {
        // Utility class - no instantiation
    }

    /**
     * Sets the health score.
     *
     * @param newScore Health score between 0.0 and 1.0
     * @throws IllegalArgumentException if score is out of range
     */
    public static void setScore(double newScore) {
        if (newScore < 0.0) {
            throw new IllegalArgumentException("Health score cannot be negative: " + newScore);
        }
        if (newScore > 1.0) {
            throw new IllegalArgumentException("Health score cannot exceed 1.0: " + newScore);
        }

        long atomicScore = (long) (newScore * 10000);
        long current = healthScore.get();

        // Prevent rapid state transitions
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTransitionTime.get() < TRANSITION_COOLDOWN_MS) {
            return;
        }

        if (atomicScore != current) {
            if (current >= 8000 && atomicScore < 8000) {
                lastHealthy.set(false);
            } else if (current < 8000 && atomicScore >= 8000) {
                lastHealthy.set(true);
            }
            lastTransitionTime.set((int) currentTime);
            healthScore.set(atomicScore);
        }
    }

    /**
     * Returns the current health score.
     *
     * @return Health score in range [0.0, 1.0]
     */
    public static double getScore() {
        return Math.min(1.0, Math.max(0.0, healthScore.get() / 10000.0));
    }

    /**
     * Returns true if the system is healthy (score >= 0.8).
     *
     * @return true if healthy
     */
    public static boolean isHealthy() {
        return getScore() >= 0.8;
    }

    /**
     * Returns true if the system is unhealthy (score < 0.8).
     *
     * @return true if unhealthy
     */
    public static boolean isUnhealthy() {
        return getScore() < 0.8;
    }

    /**
     * Sets health to fully healthy.
     *
     * @return 1.0
     */
    public static double setFullyHealthy() {
        setScore(1.0);
        return 1.0;
    }

    /**
     * Sets health to fully unhealthy.
     *
     * @return 0.0
     */
    public static double setFullyUnhealthy() {
        setScore(0.0);
        return 0.0;
    }

    /**
     * Gets the previous health state.
     *
     * @return The last known health state
     */
    public static HealthState getLastState() {
        if (lastHealthy.get()) {
            return HealthState.HEALTHY;
        } else {
            return HealthState.UNHEALTHY;
        }
    }

    /**
     * Health state enumeration.
     */
    public enum HealthState {
        HEALTHY, UNHEALTHY
    }
}
