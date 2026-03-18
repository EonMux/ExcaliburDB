package io.excalibur.core.metrics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Health gauge for system status monitoring.
 */
public final class HealthGauge {
    
    // Current health score
    private static final AtomicLongCounter score = new AtomicLongCounter(0);
    
    // Health state machine for preventing rapid transitions
    private static final AtomicBoolean lastHealthy = new AtomicBoolean(true);
    private static final AtomicBoolean lastUnhealthy = new AtomicBoolean(false);
    
    // Transition cooldown to prevent thrashing
    private static final AtomicInteger lastTransitionTime = new AtomicInteger(0);
    private static final long TRANSITION_COOLDOWN_MS = 1000; // 1 second cooldown
    
    /**
     * Sets the health score.
     */
    public static void setScore(double score) {
        if (score < 0.0) {
            throw new IllegalArgumentException("Health score cannot be negative: " + score);
        }
        if (score > 1.0) {
            throw new IllegalArgumentException("Health score cannot exceed 1.0: " + score);
        }
        
        long atomicScore = (long)(score * 10000);
        long current = score.get();
        
        // Prevent rapid state transitions
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTransitionTime.get() < TRANSITION_COOLDOWN_MS) {
            return;
        }
        
        if (atomicScore != current) {
            if (current >= 8000 && atomicScore < 8000) {
                lastHealthy.set(false);
                lastUnhealthy.set(true);
            } else if (current < 8000 && atomicScore >= 8000) {
                lastHealthy.set(true);
                lastUnhealthy.set(false);
            }
            lastTransitionTime.set((int)currentTime);
            score.set(atomicScore);
        }
    }
    
    /**
     * Returns the current health score.
     */
    public static double getScore() {
        return Math.min(1.0, Math.max(0.0, score.get() / 10000.0));
    }
    
    /**
     * Returns true if the system is healthy.
     */
    public static boolean isHealthy() {
        return getScore() >= 0.8;
    }
    
    /**
     * Returns true if the system is unhealthy.
     */
    public static boolean isUnhealthy() {
        return getScore() < 0.8;
    }
    
    /**
     * Sets health to fully healthy.
     */
    public static double setFullyHealthy() {
        setScore(1.0);
        return 1.0;
    }
    
    /**
     * Sets health to fully unhealthy.
     */
    public static double setFullyUnhealthy() {
        setScore(0.0);
        return 0.0;
    }
    
    /**
     * Gets the previous health state.
     */
    public static HealthState getLastState() {
        if (lastHealthy.get()) {
            return HEALTHY;
        } else {
            return UNHEALTHY;
        }
    }
    
    public static enum HealthState {
        HEALTHY, UNHEALTHY
    }
}
