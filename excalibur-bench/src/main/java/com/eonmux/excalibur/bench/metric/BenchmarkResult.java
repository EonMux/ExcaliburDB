package com.eonmux.excalibur.bench.metric;

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
    private final boolean higherIsBetter;
    private final long durationMs;
    private final int iterations;
    private final int forks;
    private final List<TargetEvaluation> targetEvaluations;

    private BenchmarkResult(Builder builder) {
        this.benchmarkName = Objects.requireNonNull(builder.benchmarkName, "benchmarkName required");
        this.mode = Objects.requireNonNull(builder.mode, "mode required");
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.parameters = Collections.unmodifiableMap(new HashMap<>(builder.parameters));
        this.metrics = Collections.unmodifiableList(new ArrayList<>(builder.metrics));
        this.primaryScore = builder.primaryScore;
        this.primaryScoreError = builder.primaryScoreError;
        this.primaryScoreUnit = builder.primaryScoreUnit;
        this.higherIsBetter = builder.higherIsBetter;
        this.durationMs = builder.durationMs;
        this.iterations = builder.iterations;
        this.forks = builder.forks;
        this.targetEvaluations = evaluateTargets(builder.targets);
    }

    /**
     * Outcome of comparing one measurement against one registered target.
     */
    public enum TargetStatus {
        /** The measurement satisfies the threshold. */
        MET,
        /** The measurement was compared and falls short of the threshold. */
        MISSED,
        /**
         * The comparison could not be made - the metric was absent, a required JMH
         * parameter was missing, or the measured unit is incompatible with the target
         * unit. This is a reporting defect and must never be read as a pass.
         */
        NOT_EVALUABLE
    }

    /**
     * The result of evaluating a single target against this benchmark.
     *
     * @param target the target that was evaluated
     * @param status the outcome
     * @param comparedValue the measurement converted into the target's unit, or NaN
     *        when the comparison could not be made
     * @param detail a human-readable explanation, empty when the comparison succeeded
     */
    public record TargetEvaluation(
        BenchmarkTargetRegistry.TargetConfig target,
        TargetStatus status,
        double comparedValue,
        String detail
    ) {
        /**
         * Formats this evaluation for display.
         *
         * @return a one-line summary of the comparison
         */
        public String format() {
            if (status == TargetStatus.NOT_EVALUABLE) {
                return String.format("%s target %s NOT EVALUATED - %s",
                    target.metric(), target.formatTarget(), detail);
            }
            return String.format("%s %.4f %s vs target %s %s",
                target.metric(), comparedValue, target.unit(), target.formatTarget(),
                status == TargetStatus.MET ? "PASS \u2713" : "FAIL \u2717");
        }
    }

    private List<TargetEvaluation> evaluateTargets(List<BenchmarkTargetRegistry.TargetConfig> targets) {
        List<TargetEvaluation> evaluations = new ArrayList<>(targets.size());
        for (BenchmarkTargetRegistry.TargetConfig target : targets) {
            evaluations.add(evaluateTarget(target));
        }
        return Collections.unmodifiableList(evaluations);
    }

    private TargetEvaluation evaluateTarget(BenchmarkTargetRegistry.TargetConfig target) {
        double value;
        String unit;
        if (BenchmarkTargetRegistry.PRIMARY_METRIC.equals(target.metric())) {
            value = primaryScore;
            unit = primaryScoreUnit;
        } else {
            BenchmarkMetric metric = getMetric(target.metric());
            if (metric == null) {
                return notEvaluable(target, "metric '" + target.metric() + "' was not captured");
            }
            value = metric.value();
            unit = metric.unit();
        }

        if (target.scaleByParameter() != null) {
            String raw = parameters.get(target.scaleByParameter());
            Double scale = parseScale(raw);
            if (scale == null) {
                return notEvaluable(target,
                    "JMH parameter '" + target.scaleByParameter() + "' is missing or not numeric");
            }
            if (MetricUnits.dimensionOf(unit) != MetricUnits.Dimension.THROUGHPUT) {
                return notEvaluable(target,
                    "scaling requires a throughput measurement but unit was '" + unit + "'");
            }
            MetricUnits.Dimension scaled = MetricUnits.dimensionOf(target.unit());
            if (scaled != MetricUnits.Dimension.THROUGHPUT && scaled != MetricUnits.Dimension.BYTES_PER_SEC) {
                return notEvaluable(target,
                    "target unit '" + target.unit() + "' is not a rate an operation count can scale into");
            }
            // The parameter says how many units of the target's work one operation performs.
            value = MetricUnits.convert(value, unit, MetricUnits.OPS_PER_SEC) * scale;
            unit = MetricUnits.canonicalUnit(scaled);
        }

        if (!MetricUnits.isCompatible(unit, target.unit())) {
            return notEvaluable(target,
                "measured unit '" + unit + "' is incompatible with target unit '" + target.unit() + "'");
        }

        double converted = MetricUnits.convert(value, unit, target.unit());
        return new TargetEvaluation(target,
            target.isMet(converted) ? TargetStatus.MET : TargetStatus.MISSED,
            converted, "");
    }

    private static TargetEvaluation notEvaluable(
            BenchmarkTargetRegistry.TargetConfig target, String detail) {
        return new TargetEvaluation(target, TargetStatus.NOT_EVALUABLE, Double.NaN, detail);
    }

    private static Double parseScale(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Creates a new builder for constructing BenchmarkResult instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the fully qualified benchmark name (e.g., "com.eonmux.excalibur.bench.benchmark.PointLookupBenchmark.sequentialLookupFast").
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
     * Gets the primary-score target value for this benchmark, if defined.
     *
     * @return the first registered target's threshold, or 0.0 when none is defined
     */
    public double targetValue() {
        return targetEvaluations.isEmpty() ? 0.0 : targetEvaluations.get(0).target().targetValue();
    }

    /**
     * Checks if at least one target is defined for this benchmark.
     *
     * @return true if any target applies
     */
    public boolean hasTarget() {
        return !targetEvaluations.isEmpty();
    }

    /**
     * Gets every target evaluated against this result.
     *
     * @return an immutable list of evaluations, possibly empty
     */
    public List<TargetEvaluation> targetEvaluations() {
        return targetEvaluations;
    }

    /**
     * Checks if any registered target could not be compared against a measurement.
     *
     * <p>An unevaluated target means the report has no evidence either way. Callers
     * must treat it as a failure of the reporting pipeline, not as a pass.</p>
     *
     * @return true if at least one target is {@link TargetStatus#NOT_EVALUABLE}
     */
    public boolean hasUnevaluatedTarget() {
        return targetEvaluations.stream().anyMatch(e -> e.status() == TargetStatus.NOT_EVALUABLE);
    }

    /**
     * Checks if higher values are better for this benchmark.
     *
     * @return true when the primary score is a throughput or transfer rate
     */
    public boolean higherIsBetter() {
        return higherIsBetter;
    }

    /**
     * Checks if every defined target was compared and met.
     *
     * <p>Returns true when no target is defined (nothing to fail) and false when any
     * target was missed or could not be evaluated.</p>
     *
     * @return true if all targets were evaluated and satisfied
     */
    public boolean meetsTarget() {
        return targetEvaluations.stream().allMatch(e -> e.status() == TargetStatus.MET);
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

        for (TargetEvaluation evaluation : targetEvaluations) {
            sb.append(" (").append(evaluation.format()).append(")");
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
        if (hasTarget()) {
            sb.append(",\n      \"target\": ").append(targetValue());
            sb.append(",\n      \"meetsTarget\": ").append(meetsTarget());
        }
        sb.append("\n    },\n");

        // Target evaluations
        if (!targetEvaluations.isEmpty()) {
            sb.append("    \"targets\": [\n");
            for (int i = 0; i < targetEvaluations.size(); i++) {
                TargetEvaluation evaluation = targetEvaluations.get(i);
                sb.append("      {\n");
                sb.append("        \"metric\": \"").append(escapeJson(evaluation.target().metric())).append("\",\n");
                sb.append("        \"target\": ").append(evaluation.target().targetValue()).append(",\n");
                sb.append("        \"unit\": \"").append(escapeJson(evaluation.target().unit())).append("\",\n");
                sb.append("        \"higherIsBetter\": ").append(evaluation.target().higherIsBetter()).append(",\n");
                sb.append("        \"status\": \"").append(evaluation.status()).append("\",\n");
                sb.append("        \"measured\": ").append(
                    Double.isNaN(evaluation.comparedValue()) ? "null" : evaluation.comparedValue()).append(",\n");
                sb.append("        \"detail\": \"").append(escapeJson(evaluation.detail())).append("\"\n");
                sb.append("      }");
                if (i < targetEvaluations.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("    ],\n");
        }

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
        private final List<BenchmarkTargetRegistry.TargetConfig> targets = new ArrayList<>();
        private Double explicitTarget;
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

        /**
         * Sets a target on the primary score, expressed in this result's own unit.
         *
         * <p>The unit and direction are resolved at {@link #build()} time from
         * {@link #primaryScoreUnit(String)} and {@link #higherIsBetter(boolean)},
         * so the call order does not matter.</p>
         *
         * @param target the threshold in {@link #primaryScoreUnit(String)} units
         * @return this builder
         */
        public Builder target(double target) {
            this.explicitTarget = target;
            return this;
        }

        /**
         * Adds a registered target to evaluate against this result.
         *
         * @param target the target configuration
         * @return this builder
         */
        public Builder addTarget(BenchmarkTargetRegistry.TargetConfig target) {
            this.targets.add(target);
            return this;
        }

        /**
         * Removes all targets from this builder.
         *
         * @return this builder
         */
        public Builder noTarget() {
            this.targets.clear();
            this.explicitTarget = null;
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
            if (explicitTarget != null) {
                targets.add(0, new BenchmarkTargetRegistry.TargetConfig(
                    ".*", BenchmarkTargetRegistry.PRIMARY_METRIC, explicitTarget,
                    higherIsBetter, primaryScoreUnit, null, "explicit target"));
                explicitTarget = null;
            }
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
