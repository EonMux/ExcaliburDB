package io.excalibur.config;

import java.util.Map;
import java.util.Objects;

/**
 * Visibility configuration for epoch-based query isolation.
 *
 * <p>This class is IMMUTABLE and thread-safe.</p>
 */
public final class VisibilityConfig {

    // Default epoch tick interval in milliseconds
    public static final long DEFAULT_EPOCH_TICK_MS = 100;

    // Query timeout in milliseconds
    public static final int DEFAULT_QUERY_TIMEOUT_MS = 30000;

    // Minimum and maximum values for validation (used by ConfigValidation)
    public static final long MIN_EPOCH_TICK_MS = 10;
    public static final long MAX_EPOCH_TICK_MS = 10000;
    public static final int MIN_QUERY_TIMEOUT_MS = 1000;
    public static final int MAX_QUERY_TIMEOUT_MS = 300000;

    // All fields final for immutability
    private final long epochTickMs;
    private final int queryTimeoutMs;

    /**
     * Creates a Visibility config from properties.
     *
     * @param props The properties map
     */
    public VisibilityConfig(Map<String, String> props) {
        this.epochTickMs = PropertyParser.parseLong(props, "excalibur.visibility.epoch-tick-ms", DEFAULT_EPOCH_TICK_MS);
        this.queryTimeoutMs = PropertyParser.parseInt(props, "excalibur.visibility.query-timeout-ms", DEFAULT_QUERY_TIMEOUT_MS);
    }

    /**
     * Gets the epoch tick interval in milliseconds.
     *
     * @return The interval between visibility epoch ticks
     */
    public long getEpochTickMs() {
        return epochTickMs;
    }

    /**
     * Gets the visibility epoch number.
     *
     * @return Current epoch number
     */
    public long getEpoch() {
        return System.currentTimeMillis() / epochTickMs;
    }

    /**
     * Gets the time until the next epoch tick in milliseconds.
     *
     * @return Time remaining until next epoch
     */
    public long timeUntilEpochTick() {
        long now = System.currentTimeMillis();
        long elapsed = now % epochTickMs;
        return epochTickMs - elapsed;
    }

    /**
     * Gets the query timeout in milliseconds.
     *
     * @return Query timeout in milliseconds
     */
    public int getQueryTimeoutMs() {
        return queryTimeoutMs;
    }

    @Override
    public String toString() {
        return "Visibility{epochTickMs=" + epochTickMs + ", queryTimeoutMs=" + queryTimeoutMs + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        VisibilityConfig other = (VisibilityConfig) o;
        return epochTickMs == other.epochTickMs
            && queryTimeoutMs == other.queryTimeoutMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(epochTickMs, queryTimeoutMs);
    }
}
