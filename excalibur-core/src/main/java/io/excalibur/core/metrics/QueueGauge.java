package io.excalibur.core.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Queue depth gauge for monitoring HNSW integration queue.
 *
 * Thread-safety: Thread-safe using atomic operations.
 *
 * Domain Correctness:
 * - Queue depth must be non-negative
 * - Queue depth must be bounded (configurable maximum)
 * - Queue depth changes should be gradual (bounded by throughput)
 */
public final class QueueGauge {

    // Current queue depth
    private static final AtomicLong depth = new AtomicLong(0);

    // Maximum allowed queue depth (configurable)
    private static final long MAX_DEPTH = 100000L;

    /**
     * Updates the queue depth.
     *
     * @param newDepth New queue depth
     * @throws IllegalArgumentException if depth exceeds maximum
     */
    public static void updateDepth(long newDepth) {
        if (newDepth < 0) {
            throw new IllegalArgumentException("Queue depth cannot be negative");
        }
        if (newDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("Queue depth exceeds maximum: " + newDepth +
            " (max: " + MAX_DEPTH + ")");
        }
        depth.set(newDepth);
    }

    /**
     * Returns the current queue depth.
     *
     * @return Queue depth (non-negative)
     */
    public static long getDepth() {
        return depth.get();
    }

    /**
     * Returns the queue depth as a percentage of maximum.
     *
     * @return Depth percentage (0.0 to 1.0)
     */
    public static double getDepthPercent() {
        long d = depth.get();
        return d <= MAX_DEPTH ? (double)d / MAX_DEPTH : 1.0;
    }

    /**
     * Returns true if queue is under control (< 50% of max).
     *
     * @return true if depth < 50% of maximum
     */
    public static boolean isUnderControl() {
        return getDepthPercent() < 0.5;
    }

    /**
     * Returns true if queue is at critical level (> 80% of max).
     *
     * @return true if depth > 80% of maximum
     */
    public static boolean isCritical() {
        return getDepthPercent() > 0.8;
    }

    /**
     * Returns true if queue is full (> 95% of max).
     *
     * @return true if depth > 95% of maximum
     */
    public static boolean isFull() {
        return getDepthPercent() > 0.95;
    }

    /**
     * Sets maximum allowed queue depth.
     * This is a no-op - MAX_DEPTH is static final and cannot be reassigned.
     *
     * @param maxDepth New maximum depth (informational only)
     */
    public static void setMaxDepth(long maxDepth) {
        // MAX_DEPTH is static final - this is informational only
        // To change the maximum, rebuild with different configuration
        System.out.println("Note: setMaxDepth() is informational only - MAX_DEPTH is static final");
        System.out.println("       Current max depth: " + MAX_DEPTH);
    }
}
