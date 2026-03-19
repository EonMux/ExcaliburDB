package io.excalibur.core.config;

import java.util.Map;

/**
 * Configuration data class for ExcaliburDB.
 *
 * This config contains all runtime parameters needed by the system:
 * - Visibility epoch timing
 * - Query settings
 * - Ingestion thresholds
 * - Health monitoring
 *
 * @see ConfigLoader
 */
public final class Config {

    private final VisibilityConfig visibility;
    private final QueryConfig query;
    private final IngestConfig ingest;
    private final HealthConfig health;
    private final boolean enabled;

    public Config(Map<String, String> props) {
        this.enabled = parseBoolean(props.get("excalibur.enabled"), true);
        this.visibility = new VisibilityConfig(props);
        this.query = new QueryConfig(props);
        this.ingest = new IngestConfig(props);
        this.health = new HealthConfig(props);
    }

    /**
     * Visibility configuration for query consistency.
     *
     * @see ConfigLoader#load(String, Map)
     */
    public static class VisibilityConfig {

        private final long epochTickMs;

        public VisibilityConfig(Map<String, String> props) {
            // Default: 100ms epoch tick
            this.epochTickMs = parseLong(props.get("excalibur.visibility.epoch-tick-ms"), 100L);
        }

        /**
         * Returns the epoch tick interval in milliseconds.
         * Queries started at the same epoch will see a consistent snapshot.
         *
         * @return Epoch tick interval in milliseconds (must be positive)
         */
        public long getEpochTickMs() {
            return epochTickMs;
        }

        /**
         * Returns the current visibility epoch.
         * Epochs start at 0 and increment every epochTickMs.
         *
         * @return Current epoch number (starts at 0)
         */
        public long getEpoch() {
            return System.currentTimeMillis() / epochTickMs;
        }

        /**
         * Returns the time until next epoch tick.
         *
         * @return Time remaining until epoch tick (non-negative)
         */
        public long timeUntilEpochTick() {
            long elapsed = System.currentTimeMillis() % epochTickMs;
            return epochTickMs - elapsed;
        }

        /**
         * Returns the epoch at which the current tick will complete.
         *
         * @return Next epoch number (current epoch + 1)
         */
        public long getNextEpoch() {
            return getEpoch() + 1;
        }

        private long parseLong(String value, long defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }

    /**
     * Query configuration.
     *
     * @see ConfigLoader#load(String, Map)
     */
    public static class QueryConfig {

        private final boolean includePartialSegments;
        private final int defaultTopK;
        private final double defaultThreshold;

        public QueryConfig(Map<String, String> props) {
            this.includePartialSegments = parseBoolean(props.get("excalibur.query.include-partial-segments"), true);
            this.defaultTopK = parseInt(props.get("excalibur.query.default-top-k"), 10);
            this.defaultThreshold = parseDouble(props.get("excalibur.query.default-threshold"), 0.3);
        }

        public boolean includePartialSegments() {
            return includePartialSegments;
        }

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public double getDefaultThreshold() {
            return defaultThreshold;
        }

        private int parseInt(String value, int defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        private double parseDouble(String value, double defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }

    /**
     * Ingestion configuration.
     *
     * @see ConfigLoader#load(String, Map)
     */
    public static class IngestConfig {

        private final int hnswQueueDepthLimit;
        private final boolean documentAtomicity;

        public IngestConfig(Map<String, String> props) {
            this.hnswQueueDepthLimit = parseInt(props.get("excalibur.ingest.hnsw-queue-depth-limit"), 100000);
            this.documentAtomicity = parseBoolean(props.get("excalibur.ingest.document-atomicity"), true);
        }

        public int getHnswQueueDepthLimit() {
            return hnswQueueDepthLimit;
        }

        public boolean isDocumentAtomicityEnabled() {
            return documentAtomicity;
        }

        private int parseInt(String value, int defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        private boolean parseBoolean(String value, boolean defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            return value.trim().equalsIgnoreCase("true") ||
                   value.trim().equalsIgnoreCase("1") ||
                   value.trim().equalsIgnoreCase("yes");
        }
    }

    /**
     * Health monitoring configuration.
     *
     * @see ConfigLoader#load(String, Map)
     */
    public static class HealthConfig {

        private final double readinessThreshold;

        public HealthConfig(Map<String, String> props) {
            // Default: 50% coverage to be ready
            this.readinessThreshold = parseDouble(props.get("excalibur.health.readiness-threshold"), 0.5);
        }

        /**
         * Returns the minimum corpus coverage required to be considered "ready".
         *
         * @return Readiness threshold (0.0 to 1.0)
         */
        public double getReadinessThreshold() {
            return readinessThreshold;
        }

        /**
         * Checks if the system is ready based on current coverage.
         *
         * @param coverage Current corpus coverage (0.0 to 1.0)
         * @return true if coverage >= readinessThreshold
         */
        public boolean isReady(double coverage) {
            return coverage >= readinessThreshold;
        }

        private double parseDouble(String value, double defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }

    // Getters
    public VisibilityConfig visibility() {
        return visibility;
    }

    public QueryConfig query() {
        return query;
    }

    public IngestConfig ingest() {
        return ingest;
    }

    public HealthConfig health() {
        return health;
    }

    public boolean enabled() {
        return enabled;
    }

    // Static helper methods used by nested config classes
    static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value.trim().equalsIgnoreCase("true") ||
               value.trim().equalsIgnoreCase("1") ||
               value.trim().equalsIgnoreCase("yes");
    }
}
