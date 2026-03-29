package excalibur.bench.metric;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the results of a single benchmark execution.
 *
 * <p>Immutable data class holding all metrics from one benchmark method.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values: positive ops/sec, typically 1M-100M for single-threaded</li>
 *   <li>Latency percentiles: p50 &lt; p99, both non-negative</li>
 *   <li>Sample time mode: latency values in nanoseconds or milliseconds</li>
 *   <li>Throughput mode: ops/sec values positive</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkResult {

    private final String benchmarkName;
    private final String mode;
    private final Instant timestamp;
    private final Map<String, String> parameters;
    private final List<BenchmarkMetric> metrics;
    private final double primaryScore;
    private final double primaryScoreError;
    private final String primaryScoreUnit;
    private final double targetValue;
    private final boolean hasTarget;
    private final boolean higherIsBetter;
    private final long durationMs;
    private final int iterations;
    private final int forks;

    private BenchmarkResult(Builder builder) {
        this.benchmarkName = Objects.requireNonNull(builder.benchmarkName, "benchmarkName required");
        this.mode = Objects.requireNonNull(builder.mode, "mode required");
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.parameters = Collections.unmodifiableMap(new HashMap<>(builder.parameters));
        this.metrics = Collections.unmodifiableList(new ArrayList<>(builder.metrics));
        this.primaryScore = builder.primaryScore;
        this.primaryScoreError = builder.primaryScoreError;
        this.primaryScoreUnit = builder.primaryScoreUnit;
        this.targetValue = builder.targetValue;
        this.hasTarget = builder.hasTarget;
        this.higherIsBetter = builder.higherIsBetter;
        this.durationMs = builder.durationMs;
        this.iterations = builder.iterations;
        this.forks = builder.forks;
    }

    /**
     * Creates a new builder for constructing BenchmarkResult instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the fully qualified benchmark name (e.g., "excalibur.bench.benchmark.PointLookupBenchmark.sequentialLookupFast").
     */
    public String benchmarkName() {
        return benchmarkName;
    }

    /**
     * Gets the short benchmark method name.
     */
    public String methodName() {
        int lastDot = benchmarkName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < benchmarkName.length() - 1) {
            return benchmarkName.substring(lastDot + 1);
        }
        return benchmarkName;
    }

    /**
     * Gets the JMH benchmark mode (e.g., "thrpt", "avgt", "sample").
     */
    public String mode() {
        return mode;
    }

    /**
     * Gets the timestamp when the benchmark was executed.
     */
    public Instant timestamp() {
        return timestamp;
    }

    /**
     * Gets the benchmark parameters (e.g., segmentCount=4, dimension=128).
     */
    public Map<String, String> parameters() {
        return parameters;
    }

    /**
     * Gets all collected metrics.
     */
    public List<BenchmarkMetric> metrics() {
        return metrics;
    }

    /**
     * Gets a metric by name.
     */
    public BenchmarkMetric getMetric(String name) {
        for (BenchmarkMetric metric : metrics) {
            if (metric.name().equals(name)) {
                return metric;
            }
        }
        return null;
    }

    /**
     * Gets the primary score (throughput or average time depending on mode).
     */
    public double primaryScore() {
        return primaryScore;
    }

    /**
     * Gets the error margin for the primary score.
     */
    public double primaryScoreError() {
        return primaryScoreError;
    }

    /**
     * Gets the unit for the primary score.
     */
    public String primaryScoreUnit() {
        return primaryScoreUnit;
    }

    /**
     * Gets the target value for this benchmark, if defined.
     */
    public double targetValue() {
        return targetValue;
    }

    /**
     * Checks if a target value is defined for this benchmark.
     */
    public boolean hasTarget() {
        return hasTarget;
    }

    /**
     * Checks if higher values are better for this benchmark.
     */
    public boolean higherIsBetter() {
        return higherIsBetter;
    }

    /**
     * Checks if this benchmark meets its target (if defined).
     */
    public boolean meetsTarget() {
        if (!hasTarget) {
            return true; // No target defined
        }
        if (higherIsBetter) {
            return primaryScore >= targetValue;
        } else {
            return primaryScore <= targetValue;
        }
    }

    /**
     * Gets the duration of the benchmark in milliseconds.
     */
    public long durationMs() {
        return durationMs;
    }

    /**
     * Gets the number of measurement iterations.
     */
    public int iterations() {
        return iterations;
    }

    /**
     * Gets the number of JVM forks.
     */
    public int forks() {
        return forks;
    }

    /**
     * Gets the throughput metric, if available.
     */
    public BenchmarkMetric getThroughput() {
        return getMetric("throughput");
    }

    /**
     * Gets the p50 latency metric, if available.
     */
    public BenchmarkMetric getP50() {
        return getMetric("p50");
    }

    /**
     * Gets the p99 latency metric, if available.
     */
    public BenchmarkMetric getP99() {
        return getMetric("p99");
    }

    /**
     * Gets the p0 (minimum) latency metric, if available.
     */
    public BenchmarkMetric getP0() {
        return getMetric("p0");
    }

    /**
     * Formats the result as a summary string.
     */
    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(methodName());

        if (!parameters.isEmpty()) {
            sb.append(" [");
            boolean first = true;
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
            sb.append("]");
        }

        sb.append(": ");
        sb.append(String.format("%.2f", primaryScore));
        sb.append(" ");
        sb.append(primaryScoreUnit);

        if (primaryScoreError > 0) {
            sb.append(" ± ").append(String.format("%.2f", primaryScoreError));
        }

        if (hasTarget) {
            sb.append(" (target: ");
            if (higherIsBetter) {
                sb.append("≥");
            } else {
                sb.append("≤");
            }
            sb.append(String.format("%.2f", targetValue));
            sb.append(" ");
            sb.append(primaryScoreUnit);

            if (meetsTarget()) {
                sb.append(" ✓");
            } else {
                sb.append(" ✗");
            }
            sb.append(")");
        }

        return sb.toString();
    }

    /**
     * Converts the result to a JSON representation.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("  {\n");
        sb.append("    \"benchmark\": \"").append(escapeJson(benchmarkName)).append("\",\n");
        sb.append("    \"method\": \"").append(escapeJson(methodName())).append("\",\n");
        sb.append("    \"mode\": \"").append(escapeJson(mode)).append("\",\n");
        sb.append("    \"timestamp\": \"").append(timestamp).append("\",\n");

        // Primary score
        sb.append("    \"score\": {\n");
        sb.append("      \"value\": ").append(primaryScore).append(",\n");
        sb.append("      \"error\": ").append(primaryScoreError).append(",\n");
        sb.append("      \"unit\": \"").append(escapeJson(primaryScoreUnit)).append("\"");
        if (hasTarget) {
            sb.append(",\n      \"target\": ").append(targetValue);
            sb.append(",\n      \"meetsTarget\": ").append(meetsTarget());
        }
        sb.append("\n    },\n");

        // Parameters
        if (!parameters.isEmpty()) {
            sb.append("    \"parameters\": {\n");
            boolean first = true;
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                if (!first) sb.append(",\n");
                sb.append("      \"").append(escapeJson(entry.getKey())).append("\": \"")
                    .append(escapeJson(entry.getValue())).append("\"");
                first = false;
            }
            sb.append("\n    },\n");
        }

        // Metrics
        if (!metrics.isEmpty()) {
            sb.append("    \"metrics\": [\n");
            for (int i = 0; i < metrics.size(); i++) {
                sb.append(metrics.get(i).toJson());
                if (i < metrics.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("    ],\n");
        }

        // Execution info
        sb.append("    \"execution\": {\n");
        sb.append("      \"durationMs\": ").append(durationMs).append(",\n");
        sb.append("      \"iterations\": ").append(iterations).append(",\n");
        sb.append("      \"forks\": ").append(forks).append("\n");
        sb.append("    }\n");

        sb.append("  }");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    @Override
    public String toString() {
        return formatSummary();
    }

    /**
     * Builder for constructing BenchmarkResult instances.
     */
    public static class Builder {
        private String benchmarkName;
        private String mode = "thrpt";
        private Instant timestamp;
        private Map<String, String> parameters = new HashMap<>();
        private List<BenchmarkMetric> metrics = new ArrayList<>();
        private double primaryScore;
        private double primaryScoreError;
        private String primaryScoreUnit = "ops/sec";
        private double targetValue;
        private boolean hasTarget;
        private boolean higherIsBetter = true;
        private long durationMs;
        private int iterations;
        private int forks = 1;

        public Builder benchmarkName(String name) {
            this.benchmarkName = name;
            return this;
        }

        public Builder mode(String mode) {
            this.mode = mode;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder parameter(String key, String value) {
            this.parameters.put(key, value);
            return this;
        }

        public Builder parameters(Map<String, String> params) {
            this.parameters.putAll(params);
            return this;
        }

        public Builder addMetric(BenchmarkMetric metric) {
            this.metrics.add(metric);
            return this;
        }

        public Builder metrics(List<BenchmarkMetric> metrics) {
            this.metrics.addAll(metrics);
            return this;
        }

        public Builder primaryScore(double score) {
            this.primaryScore = score;
            return this;
        }

        public Builder primaryScoreError(double error) {
            this.primaryScoreError = error;
            return this;
        }

        public Builder primaryScoreUnit(String unit) {
            this.primaryScoreUnit = unit;
            return this;
        }

        public Builder target(double target) {
            this.targetValue = target;
            this.hasTarget = true;
            return this;
        }

        public Builder noTarget() {
            this.hasTarget = false;
            return this;
        }

        public Builder higherIsBetter(boolean higher) {
            this.higherIsBetter = higher;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder iterations(int iterations) {
            this.iterations = iterations;
            return this;
        }

        public Builder forks(int forks) {
            this.forks = forks;
            return this;
        }

        public BenchmarkResult build() {
            validateDomainCorrectness();
            return new BenchmarkResult(this);
        }

        /**
         * Validates domain correctness constraints before building the result.
         *
         * <p>Domain correctness rules:</p>
         * <ul>
         *   <li>Throughput values (ops/sec) must be positive - validated via ThroughputValidator</li>
         *   <li>Latency percentiles: p50 &lt; p99 (strict ordering), both in milliseconds</li>
         *   <li>Latency values must be non-negative</li>
         * </ul>
         *
         * @throws IllegalStateException if domain constraints are violated
         */
        private void validateDomainCorrectness() {
            // Validate throughput values are positive using ThroughputValidator
            boolean isThroughputMode = "thrpt".equalsIgnoreCase(mode) ||
                                       primaryScoreUnit.contains("ops/sec") ||
                                       primaryScoreUnit.contains("op/s");

            if (isThroughputMode && higherIsBetter) {
                ThroughputValidator.ValidationResult validation =
                    ThroughputValidator.validate(primaryScore, "throughput");
                if (!validation.valid()) {
                    throw new IllegalStateException(
                        "Throughput validation failed for benchmark '" + benchmarkName +
                        "': " + validation.errors());
                }
            }

            // Validate throughput metrics are positive using ThroughputValidator
            for (BenchmarkMetric metric : metrics) {
                if ("throughput".equals(metric.name())) {
                    ThroughputValidator.ValidationResult validation =
                        ThroughputValidator.validate(metric.value(), "throughput metric");
                    if (!validation.valid()) {
                        throw new IllegalStateException(
                            "Throughput metric validation failed for benchmark '" + benchmarkName +
                            "': " + validation.errors());
                    }
                }
            }

            // Validate latency percentiles using LatencyPercentileValidator
            // Enforces: p50 < p99, both non-negative, both in milliseconds
            BenchmarkMetric p50 = null;
            BenchmarkMetric p99 = null;
            for (BenchmarkMetric metric : metrics) {
                if ("p50".equals(metric.name())) p50 = metric;
                if ("p99".equals(metric.name())) p99 = metric;
            }
            if (p50 != null && p99 != null) {
                LatencyPercentileValidator.ValidationResult validation =
                    LatencyPercentileValidator.validate(p50.value(), p99.value());
                if (!validation.valid()) {
                    throw new IllegalStateException(
                        "Latency percentile validation failed for benchmark '" + benchmarkName +
                        "': " + validation.errors());
                }
            }
        }
    }
}
