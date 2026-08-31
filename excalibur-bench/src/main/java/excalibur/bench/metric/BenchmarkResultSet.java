package excalibur.bench.metric;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents the complete results from a benchmark run.
 *
 * <p>Immutable data class holding all benchmark results, summary statistics,
 * and pass/fail information.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Results are organized by benchmark category</li>
 *   <li>Pass/fail status reflects actual vs target comparison</li>
 *   <li>Summary statistics are computed from actual results</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkResultSet {

    private final String name;
    private final Instant startTime;
    private final Instant endTime;
    private final long totalDurationMs;
    private final List<BenchmarkResult> results;
    private final Map<String, List<BenchmarkResult>> resultsByCategory;
    private final int passCount;
    private final int failCount;
    private final int skipCount;
    private final int unevaluatedCount;
    private final boolean allPassed;

    private BenchmarkResultSet(Builder builder) {
        this.name = builder.name;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime != null ? builder.endTime : Instant.now();
        this.totalDurationMs = builder.totalDurationMs > 0 ? builder.totalDurationMs :
            java.time.Duration.between(startTime, endTime).toMillis();
        this.results = Collections.unmodifiableList(new ArrayList<>(builder.results));
        this.resultsByCategory = categorizeResults(builder.results);

        // Compute pass/fail counts. A target that could not be compared against a
        // measurement is counted separately and never as a pass: it means the report
        // has no evidence either way.
        int passes = 0;
        int fails = 0;
        int skips = 0;
        int unevaluated = 0;
        for (BenchmarkResult result : results) {
            if (!result.hasTarget()) {
                skips++;
            } else if (result.hasUnevaluatedTarget()) {
                unevaluated++;
            } else if (result.meetsTarget()) {
                passes++;
            } else {
                fails++;
            }
        }
        this.passCount = passes;
        this.failCount = fails;
        this.skipCount = skips;
        this.unevaluatedCount = unevaluated;
        // An empty run proves nothing, so it is a failure rather than a vacuous pass.
        this.allPassed = !results.isEmpty() && fails == 0 && unevaluated == 0;
    }

    private Map<String, List<BenchmarkResult>> categorizeResults(List<BenchmarkResult> results) {
        Map<String, List<BenchmarkResult>> categories = new HashMap<>();
        for (BenchmarkResult result : results) {
            String category = extractCategory(result.benchmarkName());
            categories.computeIfAbsent(category, k -> new ArrayList<>()).add(result);
        }
        // Make immutable
        Map<String, List<BenchmarkResult>> immutable = new HashMap<>();
        for (Map.Entry<String, List<BenchmarkResult>> entry : categories.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private String extractCategory(String benchmarkName) {
        // Extract category from benchmark class name
        // e.g., "excalibur.bench.benchmark.PointLookupBenchmark.sequentialLookupFast"
        //       -> "PointLookup"
        String className = benchmarkName;
        int lastDot = benchmarkName.lastIndexOf('.');
        if (lastDot > 0) {
            // Remove method name
            className = benchmarkName.substring(0, lastDot);
            // Get class name
            int classDot = className.lastIndexOf('.');
            if (classDot >= 0) {
                className = className.substring(classDot + 1);
            }
        }
        // Remove "Benchmark" suffix if present
        if (className.endsWith("Benchmark")) {
            className = className.substring(0, className.length() - "Benchmark".length());
        }
        return className.isEmpty() ? "Other" : className;
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the name of this benchmark run.
     */
    public String name() {
        return name;
    }

    /**
     * Gets the start time of the benchmark run.
     */
    public Instant startTime() {
        return startTime;
    }

    /**
     * Gets the end time of the benchmark run.
     */
    public Instant endTime() {
        return endTime;
    }

    /**
     * Gets the total duration in milliseconds.
     */
    public long totalDurationMs() {
        return totalDurationMs;
    }

    /**
     * Gets all benchmark results.
     */
    public List<BenchmarkResult> results() {
        return results;
    }

    /**
     * Gets the number of results.
     */
    public int resultCount() {
        return results.size();
    }

    /**
     * Gets results grouped by category.
     */
    public Map<String, List<BenchmarkResult>> resultsByCategory() {
        return resultsByCategory;
    }

    /**
     * Gets results for a specific category.
     */
    public List<BenchmarkResult> getResultsByCategory(String category) {
        return resultsByCategory.getOrDefault(category, Collections.emptyList());
    }

    /**
     * Gets the count of benchmarks that met their targets.
     */
    public int passCount() {
        return passCount;
    }

    /**
     * Gets the count of benchmarks that failed to meet their targets.
     */
    public int failCount() {
        return failCount;
    }

    /**
     * Gets the count of benchmarks without defined targets.
     */
    public int skipCount() {
        return skipCount;
    }

    /**
     * Gets the count of benchmarks whose target could not be compared against a
     * measurement (metric absent, or incompatible units).
     *
     * @return the number of results carrying an unevaluated target
     */
    public int unevaluatedCount() {
        return unevaluatedCount;
    }

    /**
     * Checks if the run produced at least one result and every target was compared
     * and met.
     *
     * <p>An empty result set returns false: a run that measured nothing cannot have
     * demonstrated that any target was met.</p>
     *
     * @return true if the run is a genuine pass
     */
    public boolean allPassed() {
        return allPassed;
    }

    /**
     * Gets the pass rate as a percentage of targets that were compared and met.
     *
     * @return the pass rate, or 0.0 when nothing was evaluated
     */
    public double passRate() {
        int total = passCount + failCount + unevaluatedCount;
        if (total == 0) return 0.0;
        return (passCount * 100.0) / total;
    }

    /**
     * Gets results that failed to meet targets.
     */
    public List<BenchmarkResult> getFailedResults() {
        return results.stream()
            .filter(r -> r.hasTarget() && !r.hasUnevaluatedTarget() && !r.meetsTarget())
            .collect(Collectors.toList());
    }

    /**
     * Gets results carrying a target that could not be evaluated.
     *
     * @return the results whose targets were not compared against a measurement
     */
    public List<BenchmarkResult> getUnevaluatedResults() {
        return results.stream()
            .filter(BenchmarkResult::hasUnevaluatedTarget)
            .collect(Collectors.toList());
    }

    /**
     * Gets results that met their targets.
     */
    public List<BenchmarkResult> getPassedResults() {
        return results.stream()
            .filter(r -> r.hasTarget() && r.meetsTarget())
            .collect(Collectors.toList());
    }

    /**
     * Formats a summary of the benchmark run.
     */
    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════════════════\n");
        sb.append("                        BENCHMARK RESULTS SUMMARY                          \n");
        sb.append("═══════════════════════════════════════════════════════════════════════════\n\n");

        sb.append("Run: ").append(name).append("\n");
        sb.append("Start: ").append(startTime).append("\n");
        sb.append("End: ").append(endTime).append("\n");
        sb.append("Duration: ").append(formatDuration(totalDurationMs)).append("\n\n");

        sb.append("───────────────────────────────────────────────────────────────────────────\n");
        sb.append("OVERVIEW\n");
        sb.append("───────────────────────────────────────────────────────────────────────────\n");
        sb.append(String.format("  Total Benchmarks: %d\n", results.size()));
        sb.append(String.format("  Passed: %d (%.1f%%)\n", passCount, passRate()));
        sb.append(String.format("  Failed: %d\n", failCount));
        sb.append(String.format("  Not Evaluated: %d\n", unevaluatedCount));
        sb.append(String.format("  No Target: %d\n", skipCount));
        sb.append(String.format("  Status: %s\n\n", statusLabel()));

        // Results by category
        if (!resultsByCategory.isEmpty()) {
            sb.append("───────────────────────────────────────────────────────────────────────────\n");
            sb.append("RESULTS BY CATEGORY\n");
            sb.append("───────────────────────────────────────────────────────────────────────────\n");

            List<String> sortedCategories = new ArrayList<>(resultsByCategory.keySet());
            Collections.sort(sortedCategories);

            for (String category : sortedCategories) {
                List<BenchmarkResult> categoryResults = resultsByCategory.get(category);
                sb.append(String.format("\n[%s] (%d benchmarks)\n", category, categoryResults.size()));

                for (BenchmarkResult result : categoryResults) {
                    sb.append("  ").append(result.formatSummary()).append("\n");
                }
            }
        }

        // Failed results detail
        List<BenchmarkResult> failed = getFailedResults();
        if (!failed.isEmpty()) {
            sb.append("\n───────────────────────────────────────────────────────────────────────────\n");
            sb.append("FAILED BENCHMARKS (did not meet target)\n");
            sb.append("───────────────────────────────────────────────────────────────────────────\n");

            for (BenchmarkResult result : failed) {
                sb.append("\n  ").append(result.methodName()).append(":\n");
                for (BenchmarkResult.TargetEvaluation evaluation : result.targetEvaluations()) {
                    if (evaluation.status() != BenchmarkResult.TargetStatus.MISSED) {
                        continue;
                    }
                    // Report the comparison in the TARGET's unit: the raw score may be in
                    // a different one (ops/s scaled to bytes/sec, ns/op shown as ms).
                    double target = evaluation.target().targetValue();
                    double measured = evaluation.comparedValue();
                    String unit = evaluation.target().unit();
                    double gap = evaluation.target().higherIsBetter()
                        ? target - measured
                        : measured - target;
                    sb.append(String.format("    Metric: %s\n", evaluation.target().metric()));
                    sb.append(String.format("    Actual: %.2f %s\n", measured, unit));
                    sb.append(String.format("    Target: %.2f %s\n", target, unit));
                    sb.append(String.format("    Gap:    %.2f %s (%.1f%%)\n",
                        gap, unit, target == 0.0 ? 0.0 : (gap / target) * 100));
                }
            }
        }

        List<BenchmarkResult> unevaluatedResults = getUnevaluatedResults();
        if (!unevaluatedResults.isEmpty()) {
            sb.append("\n──────────────────────────────────────────────────────────────────────────\n");
            sb.append("TARGETS NOT EVALUATED (no measurement to compare against)\n");
            sb.append("──────────────────────────────────────────────────────────────────────────\n");
            for (BenchmarkResult result : unevaluatedResults) {
                sb.append("\n  ").append(result.methodName()).append(":\n");
                for (BenchmarkResult.TargetEvaluation evaluation : result.targetEvaluations()) {
                    if (evaluation.status() == BenchmarkResult.TargetStatus.NOT_EVALUABLE) {
                        sb.append("    ").append(evaluation.format()).append("\n");
                    }
                }
            }
        }

        sb.append("\n═══════════════════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    /**
     * Converts the result set to JSON format.
     */
    private String statusLabel() {
        if (results.isEmpty()) {
            return "✗ NO RESULTS CAPTURED";
        }
        if (allPassed) {
            return "✓ ALL PASSED";
        }
        if (failCount == 0) {
            return "✗ TARGETS NOT EVALUATED";
        }
        return "✗ SOME FAILED";
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // Metadata
        sb.append("  \"name\": \"").append(escapeJson(name)).append("\",\n");
        sb.append("  \"startTime\": \"").append(startTime).append("\",\n");
        sb.append("  \"endTime\": \"").append(endTime).append("\",\n");
        sb.append("  \"durationMs\": ").append(totalDurationMs).append(",\n");

        // Summary
        sb.append("  \"summary\": {\n");
        sb.append("    \"total\": ").append(results.size()).append(",\n");
        sb.append("    \"passed\": ").append(passCount).append(",\n");
        sb.append("    \"failed\": ").append(failCount).append(",\n");
        sb.append("    \"unevaluated\": ").append(unevaluatedCount).append(",\n");
        sb.append("    \"skipped\": ").append(skipCount).append(",\n");
        sb.append("    \"passRate\": ").append(String.format("%.2f", passRate())).append(",\n");
        sb.append("    \"allPassed\": ").append(allPassed).append("\n");
        sb.append("  },\n");

        // Results by category
        sb.append("  \"categories\": {\n");
        List<String> sortedCategories = new ArrayList<>(resultsByCategory.keySet());
        Collections.sort(sortedCategories);
        for (int i = 0; i < sortedCategories.size(); i++) {
            String category = sortedCategories.get(i);
            List<BenchmarkResult> categoryResults = resultsByCategory.get(category);
            sb.append("    \"").append(escapeJson(category)).append("\": [\n");
            for (int j = 0; j < categoryResults.size(); j++) {
                sb.append(categoryResults.get(j).toJson());
                if (j < categoryResults.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("    ]");
            if (i < sortedCategories.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  },\n");

        // Flat results list
        sb.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append(results.get(i).toJson());
            if (i < results.size() - 1) {
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

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%.2fs", ms / 1000.0);
        }
    }

    @Override
    public String toString() {
        return formatSummary();
    }

    /**
     * Builder for constructing BenchmarkResultSet instances.
     */
    public static class Builder {
        private String name = "Benchmark Run";
        private Instant startTime = Instant.now();
        private Instant endTime;
        private long totalDurationMs;
        private List<BenchmarkResult> results = new ArrayList<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder totalDurationMs(long ms) {
            this.totalDurationMs = ms;
            return this;
        }

        public Builder addResult(BenchmarkResult result) {
            this.results.add(result);
            return this;
        }

        public Builder results(List<BenchmarkResult> results) {
            this.results.addAll(results);
            return this;
        }

        public BenchmarkResultSet build() {
            return new BenchmarkResultSet(this);
        }
    }
}
