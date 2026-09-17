package io.excalibur.config;

import java.util.Map;
import java.util.Objects;

/**
 * Health check configuration.
 *
 * <p>This class is IMMUTABLE and thread-safe.</p>
 */
public final class HealthConfig {

    // Readiness threshold for health endpoint
    public static final double DEFAULT_READINESS_THRESHOLD = 0.5;

    // Healthy threshold (higher coverage required)
    public static final double DEFAULT_HEALTHY_THRESHOLD = 0.9;

    // Maximum query latency in milliseconds
    public static final int DEFAULT_MAX_LATENCY_MS = 5000;

    /**
     * The share of volume capacity below which a node stops calling itself ready.
     *
     * <p>Deliberately small. It is not a "disk is filling up" warning - that is what the
     * volumes block on {@code /v1/stats} is for - it is the point past which the node can no
     * longer be relied on to write the replacement segment a compaction or a rolling reindex
     * produces, and taking it out of rotation is then better than letting it accept writes it
     * will fail. A deployment whose volumes sit deliberately full, or one that needs more
     * headroom than this, says so with the key rather than living with a compiled-in
     * number.</p>
     */
    public static final double DEFAULT_MIN_FREE_DISK_RATIO = 0.05;

    // Minimum and maximum values for validation (used by ConfigValidation)
    public static final double MIN_THRESHOLD = 0.0;
    public static final double MAX_THRESHOLD = 1.0;
    public static final int MIN_MAX_LATENCY_MS = 100;
    public static final int MAX_MAX_LATENCY_MS = 60000;

    // All fields final for immutability
    private final double readinessThreshold;
    private final double healthyThreshold;
    private final int maxLatencyMs;
    private final double minFreeDiskRatio;

    /**
     * Creates a Health config from properties.
     *
     * @param props The properties map
     */
    public HealthConfig(Map<String, String> props) {
        this.readinessThreshold = PropertyParser.parseDouble(props, "excalibur.health.readiness-threshold", DEFAULT_READINESS_THRESHOLD);
        this.healthyThreshold = PropertyParser.parseDouble(props, "excalibur.health.healthy-threshold", DEFAULT_HEALTHY_THRESHOLD);
        this.maxLatencyMs = PropertyParser.parseInt(props, "excalibur.health.max-latency-ms", DEFAULT_MAX_LATENCY_MS);
        this.minFreeDiskRatio = PropertyParser.parseDouble(props, "excalibur.health.min-free-disk-ratio", DEFAULT_MIN_FREE_DISK_RATIO);
    }

    /**
     * Gets the readiness threshold.
     *
     * @return Threshold below which application is considered not ready
     */
    public double getReadinessThreshold() {
        return readinessThreshold;
    }

    /**
     * Gets the healthy threshold.
     */
    public double getHealthyThreshold() {
        return healthyThreshold;
    }

    /**
     * Gets the maximum query latency.
     */
    public int getMaxLatencyMs() {
        return maxLatencyMs;
    }

    /**
     * Gets the free-disk share below which this node reports itself unready.
     *
     * @return The floor, in [0.0, 1.0]
     */
    public double getMinFreeDiskRatio() {
        return minFreeDiskRatio;
    }

    @Override
    public String toString() {
        return "Health{readinessThreshold=" + readinessThreshold + ", healthyThreshold=" + healthyThreshold
            + ", maxLatencyMs=" + maxLatencyMs + ", minFreeDiskRatio=" + minFreeDiskRatio + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HealthConfig other = (HealthConfig) o;
        return Double.compare(readinessThreshold, other.readinessThreshold) == 0
            && Double.compare(healthyThreshold, other.healthyThreshold) == 0
            && maxLatencyMs == other.maxLatencyMs
            && Double.compare(minFreeDiskRatio, other.minFreeDiskRatio) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(readinessThreshold, healthyThreshold, maxLatencyMs,
            minFreeDiskRatio);
    }
}
