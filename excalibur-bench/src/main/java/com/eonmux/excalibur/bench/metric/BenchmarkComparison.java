package com.eonmux.excalibur.bench.metric;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Compares benchmark results across runs and against targets.
 *
 * <p>Provides utilities to:</p>
 * <ul>
 *   <li>Compare current results against historical results</li>
 *   <li>Identify performance regressions and improvements</li>
 *   <li>Generate comparison reports</li>
 *   <li>Track performance trends over time</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Percentage changes: can be positive (improvement) or negative (regression)</li>
 *   <li>For throughput (higher is better): positive % = faster, negative % = slower</li>
 *   <li>For latency (lower is better): negative % = faster, positive % = slower</li>
 *   <li>Comparison values are computed as: ((current - baseline) / baseline) * 100</li>
 * </ul>
 *
 * <h2>Identity</h2>
 * <p>A current result is compared with the baseline result of the same benchmark, mode
 * and {@code @Param} values. Keyed on the method name alone, every parameter combination
 * of a benchmark would collapse onto whichever baseline row was read last, and a
 * regression in one combination could be compared against - and hidden by - another.</p>
 *
 * <h2>Errored combinations</h2>
 * <p>Combinations the current run started but could not measure are carried through
 * from {@link BenchmarkResultSet#erroredBenchmarks()}. They have no score to compare, so
 * they are not comparison entries; {@link #hasErrors()} reports them, and a gate must
 * treat them as failures just as it treats regressions.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkComparison {

    private final BenchmarkResultSet currentResults;
    private final BenchmarkResultSet baselineResults;
    private final List<ComparisonEntry> comparisons;
    private final Instant comparisonTime;
    private final int improvedCount;
    private final int regressedCount;
    private final int unchangedCount;
    private final int newBenchmarksCount;

    private BenchmarkComparison(Builder builder) {
        this.currentResults = builder.currentResults;
        this.baselineResults = builder.baselineResults;
        this.comparisonTime = Instant.now();
        this.comparisons = Collections.unmodifiableList(new ArrayList<>(builder.comparisons));

        // Count improvements, regressions, unchanged
        int improved = 0, regressed = 0, unchanged = 0, newBench = 0;
        for (ComparisonEntry entry : comparisons) {
            switch (entry.changeType()) {
                case IMPROVEMENT -> improved++;
                case REGRESSION -> regressed++;
                case UNCHANGED -> unchanged++;
                case NEW -> newBench++;
            }
        }
        this.improvedCount = improved;
        this.regressedCount = regressed;
        this.unchangedCount = unchanged;
        this.newBenchmarksCount = newBench;
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Compares two benchmark result sets.
     *
     * @param current the current benchmark results
     * @param baseline the baseline/historical benchmark results
     * @return a comparison object containing the analysis
     */
    public static BenchmarkComparison compare(BenchmarkResultSet current, BenchmarkResultSet baseline) {
        return builder()
            .currentResults(current)
            .baselineResults(baseline)
            .build();
    }

    /**
     * Gets the current results being compared.
     */
    public BenchmarkResultSet currentResults() {
        return currentResults;
    }

    /**
     * Gets the baseline results being compared against.
     */
    public BenchmarkResultSet baselineResults() {
        return baselineResults;
    }

    /**
     * Gets all comparison entries.
     */
    public List<ComparisonEntry> comparisons() {
        return comparisons;
    }

    /**
     * Gets the time when this comparison was performed.
     */
    public Instant comparisonTime() {
        return comparisonTime;
    }

    /**
     * Gets the number of benchmarks that improved.
     */
    public int improvedCount() {
        return improvedCount;
    }

    /**
     * Gets the number of benchmarks that regressed.
     */
    public int regressedCount() {
        return regressedCount;
    }

    /**
     * Gets the number of benchmarks that are unchanged (within threshold).
     */
    public int unchangedCount() {
        return unchangedCount;
    }

    /**
     * Gets the number of new benchmarks (not present in baseline).
     */
    public int newBenchmarksCount() {
        return newBenchmarksCount;
    }

    /**
     * Gets the current run's combinations that errored and so could not be compared.
     *
     * @return the errored combinations
     */
    public List<ErroredBenchmark> erroredBenchmarks() {
        return currentResults.erroredBenchmarks();
    }

    /**
     * Checks whether any combination of the current run errored.
     *
     * @return true if the comparison is incomplete because a combination threw
     */
    public boolean hasErrors() {
        return currentResults.erroredCount() > 0;
    }

    /**
     * Checks if there are any regressions.
     */
    public boolean hasRegressions() {
        return regressedCount > 0;
    }

    /**
     * Gets comparisons that show improvements.
     */
    public List<ComparisonEntry> getImprovements() {
        return comparisons.stream()
            .filter(e -> e.changeType() == ChangeType.IMPROVEMENT)
            .sorted(Comparator.comparingDouble(ComparisonEntry::percentChange).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Gets comparisons that show regressions.
     */
    public List<ComparisonEntry> getRegressions() {
        return comparisons.stream()
            .filter(e -> e.changeType() == ChangeType.REGRESSION)
            .sorted(Comparator.comparingDouble(ComparisonEntry::percentChange))
            .collect(Collectors.toList());
    }

    /**
     * Gets comparisons for new benchmarks (not in baseline).
     */
    public List<ComparisonEntry> getNewBenchmarks() {
        return comparisons.stream()
            .filter(e -> e.changeType() == ChangeType.NEW)
            .collect(Collectors.toList());
    }

    /**
     * Generates a summary of the comparison.
     */
    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════════════════\n");
        sb.append("                      BENCHMARK COMPARISON RESULTS                          \n");
        sb.append("═══════════════════════════════════════════════════════════════════════════\n\n");

        sb.append("Comparison Time: ").append(comparisonTime).append("\n");
        sb.append("Current Run:     ").append(currentResults.name()).append("\n");
        sb.append("Baseline Run:    ").append(baselineResults.name()).append("\n\n");

        sb.append("───────────────────────────────────────────────────────────────────────────\n");
        sb.append("OVERVIEW\n");
        sb.append("───────────────────────────────────────────────────────────────────────────\n");
        sb.append(String.format("  Total Compared:  %d\n", comparisons.size()));
        sb.append(String.format("  Improved:        %d\n", improvedCount));
        sb.append(String.format("  Regressed:       %d\n", regressedCount));
        sb.append(String.format("  Unchanged:       %d\n", unchangedCount));
        sb.append(String.format("  New:             %d\n", newBenchmarksCount));
        sb.append(String.format("  Errored:         %d\n", currentResults.erroredCount()));
        sb.append(String.format("  Status:          %s\n\n", statusLabel()));

        if (hasErrors()) {
            sb.append("───────────────────────────────────────────────────────────────────────────\n");
            sb.append("ERRORED (threw before producing a measurement; not compared)\n");
            sb.append("───────────────────────────────────────────────────────────────────────────\n");
            for (ErroredBenchmark entry : erroredBenchmarks()) {
                sb.append(String.format("\n  [%s] %s\n", entry.category(), entry.label()));
                sb.append(String.format("    %s\n", entry.message()));
            }
            sb.append("\n");
        }

        // Show regressions if any
        List<ComparisonEntry> regressions = getRegressions();
        if (!regressions.isEmpty()) {
            sb.append("───────────────────────────────────────────────────────────────────────────\n");
            sb.append("REGRESSIONS (performance degraded)\n");
            sb.append("───────────────────────────────────────────────────────────────────────────\n");

            for (ComparisonEntry entry : regressions) {
                sb.append(String.format("\n  %s\n", qualifiedLabel(entry)));
                sb.append(String.format("    Baseline: %.2f %s\n", entry.baselineScore(), entry.unit()));
                sb.append(String.format("    Current:  %.2f %s\n", entry.currentScore(), entry.unit()));
                sb.append(String.format("    Change:   %.2f%% %s\n",
                    Math.abs(entry.percentChange()),
                    entry.percentChange() > 0 ? "slower ↑" : "faster ↓"));
            }
            sb.append("\n");
        }

        // Show top improvements
        List<ComparisonEntry> improvements = getImprovements();
        if (!improvements.isEmpty()) {
            sb.append("───────────────────────────────────────────────────────────────────────────\n");
            sb.append("TOP IMPROVEMENTS (up to 5)\n");
            sb.append("───────────────────────────────────────────────────────────────────────────\n");

            int count = 0;
            for (ComparisonEntry entry : improvements) {
                if (count++ >= 5) break;
                sb.append(String.format("\n  %s\n", qualifiedLabel(entry)));
                sb.append(String.format("    Baseline: %.2f %s\n", entry.baselineScore(), entry.unit()));
                sb.append(String.format("    Current:  %.2f %s\n", entry.currentScore(), entry.unit()));
                sb.append(String.format("    Change:   %.2f%% %s\n",
                    Math.abs(entry.percentChange()),
                    entry.percentChange() > 0 ? "faster ↑" : "slower ↓"));
            }
            sb.append("\n");
        }

        sb.append("═══════════════════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    private String statusLabel() {
        if (hasErrors()) {
            return hasRegressions()
                ? "✗ BENCHMARKS ERRORED, REGRESSIONS DETECTED"
                : "✗ BENCHMARKS ERRORED";
        }
        return hasRegressions() ? "⚠ REGRESSIONS DETECTED" : "✓ NO REGRESSIONS";
    }

    /**
     * The benchmark's class-qualified name with the entry's parameters, which is what
     * tells two combinations of one method apart.
     */
    private static String qualifiedLabel(ComparisonEntry entry) {
        String name = entry.benchmarkName();
        int lastDot = name.lastIndexOf('.');
        return lastDot < 0 ? entry.methodName() : name.substring(0, lastDot + 1) + entry.methodName();
    }

    /**
     * Converts the comparison to JSON format.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        sb.append("  \"comparisonTime\": \"").append(comparisonTime).append("\",\n");
        sb.append("  \"currentRun\": \"").append(escapeJson(currentResults.name())).append("\",\n");
        sb.append("  \"baselineRun\": \"").append(escapeJson(baselineResults.name())).append("\",\n");

        sb.append("  \"summary\": {\n");
        sb.append("    \"total\": ").append(comparisons.size()).append(",\n");
        sb.append("    \"improved\": ").append(improvedCount).append(",\n");
        sb.append("    \"regressed\": ").append(regressedCount).append(",\n");
        sb.append("    \"unchanged\": ").append(unchangedCount).append(",\n");
        sb.append("    \"new\": ").append(newBenchmarksCount).append(",\n");
        sb.append("    \"errored\": ").append(currentResults.erroredCount()).append(",\n");
        sb.append("    \"hasRegressions\": ").append(hasRegressions()).append(",\n");
        sb.append("    \"hasErrors\": ").append(hasErrors()).append("\n");
        sb.append("  },\n");

        sb.append("  \"comparisons\": [\n");
        for (int i = 0; i < comparisons.size(); i++) {
            sb.append(comparisons.get(i).toJson());
            if (i < comparisons.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ],\n");

        List<ErroredBenchmark> errored = erroredBenchmarks();
        sb.append("  \"errored\": [\n");
        for (int i = 0; i < errored.size(); i++) {
            sb.append(errored.get(i).toJson());
            if (i < errored.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");

        sb.append("}\n");
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
     * Type of performance change.
     */
    public enum ChangeType {
        /** Performance improved (for throughput: higher, for latency: lower) */
        IMPROVEMENT,
        /** Performance regressed (for throughput: lower, for latency: higher) */
        REGRESSION,
        /** No significant change */
        UNCHANGED,
        /** New benchmark not present in baseline */
        NEW
    }

    /**
     * A single comparison entry between current and baseline results.
     */
    public static final class ComparisonEntry {
        private final String benchmarkName;
        private final String methodName;
        private final double currentScore;
        private final double baselineScore;
        private final String unit;
        private final double percentChange;
        private final ChangeType changeType;
        private final boolean higherIsBetter;
        private final boolean meetsTarget;
        private final Double targetValue;

        public ComparisonEntry(String benchmarkName, String methodName,
                               double currentScore, double baselineScore, String unit,
                               double percentChange, ChangeType changeType,
                               boolean higherIsBetter, boolean meetsTarget, Double targetValue) {
            this.benchmarkName = benchmarkName;
            this.methodName = methodName;
            this.currentScore = currentScore;
            this.baselineScore = baselineScore;
            this.unit = unit;
            this.percentChange = percentChange;
            this.changeType = changeType;
            this.higherIsBetter = higherIsBetter;
            this.meetsTarget = meetsTarget;
            this.targetValue = targetValue;
        }

        public String benchmarkName() {
            return benchmarkName;
        }

        public String methodName() {
            return methodName;
        }

        public double currentScore() {
            return currentScore;
        }

        public double baselineScore() {
            return baselineScore;
        }

        public String unit() {
            return unit;
        }

        /**
         * Gets the percentage change from baseline to current.
         * Positive = higher value, Negative = lower value.
         * Interpretation depends on higherIsBetter.
         */
        public double percentChange() {
            return percentChange;
        }

        /**
         * Gets the type of change (improvement, regression, unchanged, new).
         */
        public ChangeType changeType() {
            return changeType;
        }

        /**
         * Returns true if higher values are better for this benchmark.
         */
        public boolean higherIsBetter() {
            return higherIsBetter;
        }

        /**
         * Returns true if the current score meets the target (if defined).
         */
        public boolean meetsTarget() {
            return meetsTarget;
        }

        /**
         * Gets the target value, or null if no target is defined.
         */
        public Double targetValue() {
            return targetValue;
        }

        /**
         * Formats the entry for display.
         */
        public String format() {
            String arrow = switch (changeType) {
                case IMPROVEMENT -> "↑";
                case REGRESSION -> "↓";
                case UNCHANGED -> "=";
                case NEW -> "+";
            };

            return String.format("%s: %.2f vs %.2f %s (%.1f%% %s)",
                methodName, currentScore, baselineScore, unit,
                Math.abs(percentChange), arrow);
        }

        /**
         * Converts to JSON format.
         */
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("    {\n");
            sb.append("      \"benchmark\": \"").append(escapeJson(benchmarkName)).append("\",\n");
            sb.append("      \"method\": \"").append(escapeJson(methodName)).append("\",\n");
            sb.append("      \"currentScore\": ").append(currentScore).append(",\n");
            sb.append("      \"baselineScore\": ").append(baselineScore).append(",\n");
            sb.append("      \"unit\": \"").append(escapeJson(unit)).append("\",\n");
            sb.append("      \"percentChange\": ").append(percentChange).append(",\n");
            sb.append("      \"changeType\": \"").append(changeType).append("\",\n");
            sb.append("      \"higherIsBetter\": ").append(higherIsBetter).append(",\n");
            sb.append("      \"meetsTarget\": ").append(meetsTarget);
            if (targetValue != null) {
                sb.append(",\n      \"target\": ").append(targetValue);
            }
            sb.append("\n    }");
            return sb.toString();
        }

        private String escapeJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }

    /**
     * Builder for BenchmarkComparison.
     */
    public static class Builder {
        private BenchmarkResultSet currentResults;
        private BenchmarkResultSet baselineResults;
        private List<ComparisonEntry> comparisons = new ArrayList<>();
        private double changeThreshold = 5.0; // 5% threshold for "unchanged"

        /**
         * Sets the current results.
         */
        public Builder currentResults(BenchmarkResultSet results) {
            this.currentResults = results;
            return this;
        }

        /**
         * Sets the baseline results.
         */
        public Builder baselineResults(BenchmarkResultSet results) {
            this.baselineResults = results;
            return this;
        }

        /**
         * Sets the threshold percentage for considering a change "significant".
         * Default is 5%.
         */
        public Builder changeThreshold(double thresholdPercent) {
            this.changeThreshold = thresholdPercent;
            return this;
        }

        /**
         * Builds the comparison.
         */
        public BenchmarkComparison build() {
            if (currentResults == null) {
                throw new IllegalStateException("currentResults required");
            }
            if (baselineResults == null) {
                throw new IllegalStateException("baselineResults required");
            }

            Map<String, BenchmarkResult> baselineMap = new HashMap<>();
            for (BenchmarkResult result : baselineResults.results()) {
                baselineMap.put(identity(result), result);
            }

            for (BenchmarkResult current : currentResults.results()) {
                BenchmarkResult baseline = baselineMap.get(identity(current));

                if (baseline == null) {
                    // New benchmark not in baseline
                    comparisons.add(new ComparisonEntry(
                        current.benchmarkName(),
                        combination(current),
                        current.primaryScore(),
                        0.0,
                        current.primaryScoreUnit(),
                        0.0,
                        ChangeType.NEW,
                        current.higherIsBetter(),
                        current.meetsTarget(),
                        current.hasTarget() ? current.targetValue() : null
                    ));
                } else {
                    // Compute percentage change
                    double percentChange = computePercentChange(
                        current.primaryScore(),
                        baseline.primaryScore(),
                        baseline.primaryScore()
                    );

                    // Determine change type
                    ChangeType changeType = determineChangeType(
                        percentChange,
                        current.higherIsBetter(),
                        changeThreshold
                    );

                    comparisons.add(new ComparisonEntry(
                        current.benchmarkName(),
                        combination(current),
                        current.primaryScore(),
                        baseline.primaryScore(),
                        current.primaryScoreUnit(),
                        percentChange,
                        changeType,
                        current.higherIsBetter(),
                        current.meetsTarget(),
                        current.hasTarget() ? current.targetValue() : null
                    ));
                }
            }

            return new BenchmarkComparison(this);
        }

        /**
         * What makes a result the same measurement across runs: benchmark, mode and
         * parameter values.
         */
        private static String identity(BenchmarkResult result) {
            return result.benchmarkName() + '\u0000' + result.mode() + '\u0000'
                + new TreeMap<>(result.parameters());
        }

        /**
         * The method name with its parameters, e.g. {@code lookup [size=64]}.
         */
        private static String combination(BenchmarkResult result) {
            if (result.parameters().isEmpty()) {
                return result.methodName();
            }
            StringJoiner joiner = new StringJoiner(", ", result.methodName() + " [", "]");
            new TreeMap<>(result.parameters()).forEach((k, v) -> joiner.add(k + "=" + v));
            return joiner.toString();
        }

        private double computePercentChange(double current, double baseline, double reference) {
            if (reference == 0.0) {
                return 0.0; // Avoid division by zero
            }
            return ((current - baseline) / Math.abs(reference)) * 100.0;
        }

        private ChangeType determineChangeType(double percentChange, boolean higherIsBetter, double threshold) {
            double absChange = Math.abs(percentChange);

            if (absChange < threshold) {
                return ChangeType.UNCHANGED;
            }

            // For throughput (higher is better):
            //   positive change = improvement
            //   negative change = regression
            // For latency (lower is better):
            //   positive change = regression (value went up)
            //   negative change = improvement (value went down)

            if (higherIsBetter) {
                return percentChange > 0 ? ChangeType.IMPROVEMENT : ChangeType.REGRESSION;
            } else {
                return percentChange < 0 ? ChangeType.IMPROVEMENT : ChangeType.REGRESSION;
            }
        }
    }
}
