package excalibur.bench.report;

import excalibur.bench.metric.BenchmarkMetric;
import excalibur.bench.metric.BenchmarkResult;
import excalibur.bench.metric.BenchmarkResultSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Writes benchmark reports in various formats.
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>JSON - Machine-readable format for analysis tools</li>
 *   <li>Markdown - Human-readable format for documentation</li>
 *   <li>HTML - Web-viewable format with charts (optional)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are stateless and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkReportWriter {

    /**
     * Writes a benchmark result set to a JSON file.
     *
     * @param results the benchmark results
     * @param outputPath path to write the JSON file
     * @throws IOException if the file cannot be written
     */
    public static void writeJson(BenchmarkResultSet results, Path outputPath) throws IOException {
        String json = results.toJson();
        Files.writeString(outputPath, json);
    }

    /**
     * Writes a benchmark result set to a Markdown file.
     *
     * @param results the benchmark results
     * @param outputPath path to write the Markdown file
     * @throws IOException if the file cannot be written
     */
    public static void writeMarkdown(BenchmarkResultSet results, Path outputPath) throws IOException {
        String markdown = generateMarkdown(results);
        Files.writeString(outputPath, markdown);
    }

    /**
     * Generates a Markdown report from benchmark results.
     */
    public static String generateMarkdown(BenchmarkResultSet results) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("# ExcaliburDB Benchmark Results\n\n");
        sb.append("**Run:** ").append(results.name()).append("\n");
        sb.append("**Date:** ").append(results.startTime()).append("\n");
        sb.append("**Duration:** ").append(formatDuration(results.totalDurationMs())).append("\n\n");

        // Summary
        sb.append("## Summary\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append(String.format("| Total Benchmarks | %d |\n", results.resultCount()));
        sb.append(String.format("| Passed | %d |\n", results.passCount()));
        sb.append(String.format("| Failed | %d |\n", results.failCount()));
        sb.append(String.format("| Not Evaluated | %d |\n", results.unevaluatedCount()));
        sb.append(String.format("| No Target | %d |\n", results.skipCount()));
        sb.append(String.format("| Pass Rate | %.1f%% |\n", results.passRate()));
        sb.append(String.format("| Status | %s |\n\n", markdownStatus(results)));

        // Results by category
        sb.append("## Results by Category\n\n");

        for (Map.Entry<String, List<BenchmarkResult>> entry :
                results.resultsByCategory().entrySet()) {
            String category = entry.getKey();
            List<BenchmarkResult> categoryResults = entry.getValue();

            sb.append("### ").append(category).append("\n\n");
            sb.append("| Benchmark | Score | Error | Unit | Target | Status |\n");
            sb.append("|-----------|-------|-------|------|--------|--------|\n");

            for (BenchmarkResult result : categoryResults) {
                sb.append("| ").append(result.methodName()).append(" ");
                sb.append("| ").append(String.format("%.2f", result.primaryScore())).append(" ");
                sb.append("| ").append(String.format("±%.2f", result.primaryScoreError())).append(" ");
                sb.append("| ").append(result.primaryScoreUnit()).append(" ");

                if (result.hasTarget()) {
                    sb.append("| ").append(String.format("%.2f", result.targetValue())).append(" ");
                    sb.append("| ").append(statusIcon(result)).append(" ");
                } else {
                    sb.append("| - | - ");
                }
                sb.append("|\n");
            }
            sb.append("\n");
        }

        // Targets that could not be evaluated at all
        List<BenchmarkResult> unevaluated = results.getUnevaluatedResults();
        if (!unevaluated.isEmpty()) {
            sb.append("## Targets Not Evaluated\n\n");
            sb.append("These benchmarks carry a target that could not be compared against a ")
              .append("measurement. Treat them as failures of the reporting pipeline:\n\n");
            for (BenchmarkResult result : unevaluated) {
                sb.append("### ").append(result.methodName()).append("\n\n");
                for (BenchmarkResult.TargetEvaluation evaluation : result.targetEvaluations()) {
                    if (evaluation.status() == BenchmarkResult.TargetStatus.NOT_EVALUABLE) {
                        sb.append("- ").append(evaluation.format()).append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        // Failed benchmarks detail
        List<BenchmarkResult> failed = results.getFailedResults();
        if (!failed.isEmpty()) {
            sb.append("## Failed Benchmarks\n\n");
            sb.append("The following benchmarks did not meet their targets:\n\n");

            for (BenchmarkResult result : failed) {
                sb.append("### ").append(result.methodName()).append("\n\n");
                for (BenchmarkResult.TargetEvaluation evaluation : result.targetEvaluations()) {
                    if (evaluation.status() != BenchmarkResult.TargetStatus.MISSED) {
                        continue;
                    }
                    // Reported in the target's unit, which is not always the raw score's.
                    double target = evaluation.target().targetValue();
                    double measured = evaluation.comparedValue();
                    String unit = evaluation.target().unit();
                    double gap = evaluation.target().higherIsBetter()
                        ? target - measured
                        : measured - target;
                    double gapPercent = target == 0.0 ? 0.0 : (gap / target) * 100;

                    sb.append("- **Metric:** ").append(evaluation.target().metric()).append("\n");
                    sb.append("- **Actual:** ").append(String.format("%.2f", measured))
                        .append(" ").append(unit).append("\n");
                    sb.append("- **Target:** ").append(String.format("%.2f", target))
                        .append(" ").append(unit).append("\n");
                    sb.append("- **Gap:** ").append(String.format("%.2f", gap))
                        .append(" ").append(unit)
                        .append(" (").append(String.format("%.1f%%", gapPercent)).append(")\n\n");
                }
            }
        }

        // Footer
        sb.append("---\n");
        sb.append("*Generated by ExcaliburDB Benchmark Framework*\n");

        return sb.toString();
    }

    /**
     * Generates a simple text report for console output.
     */
    public static String generateTextReport(BenchmarkResultSet results) {
        return results.formatSummary();
    }

    /**
     * Writes benchmark results to a plain text file.
     *
     * @param results the benchmark results
     * @param outputPath path to write the text file
     * @throws IOException if the file cannot be written
     */
    public static void writeTxt(BenchmarkResultSet results, Path outputPath) throws IOException {
        String text = generateTextReport(results);
        Files.writeString(outputPath, text);
    }

    /**
     * Generates a CSV export of benchmark results.
     */
    public static String generateCsv(BenchmarkResultSet results) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("category,benchmark,mode,score,error,unit,target,meets_target\n");

        // Data rows
        for (Map.Entry<String, List<BenchmarkResult>> entry :
                results.resultsByCategory().entrySet()) {
            String category = entry.getKey();

            for (BenchmarkResult result : entry.getValue()) {
                sb.append(escapeCsv(category)).append(",");
                sb.append(escapeCsv(result.methodName())).append(",");
                sb.append(escapeCsv(result.mode())).append(",");
                sb.append(result.primaryScore()).append(",");
                sb.append(result.primaryScoreError()).append(",");
                sb.append(escapeCsv(result.primaryScoreUnit())).append(",");

                if (result.hasTarget()) {
                    sb.append(result.targetValue()).append(",");
                    sb.append(result.hasUnevaluatedTarget()
                        ? "not-evaluated"
                        : Boolean.toString(result.meetsTarget()));
                } else {
                    sb.append(",n/a");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Writes benchmark results to CSV file.
     */
    public static void writeCsv(BenchmarkResultSet results, Path outputPath) throws IOException {
        String csv = generateCsv(results);
        Files.writeString(outputPath, csv);
    }

    /**
     * Generates a metrics summary focusing on throughput and latency.
     */
    public static String generateMetricsSummary(BenchmarkResultSet results) {
        StringBuilder sb = new StringBuilder();

        sb.append("═══════════════════════════════════════════════════════════════════════════\n");
        sb.append("                      KEY METRICS SUMMARY                                  \n");
        sb.append("═══════════════════════════════════════════════════════════════════════════\n\n");

        // Throughput metrics
        sb.append("THROUGHPUT (ops/sec)\n");
        sb.append("───────────────────────────────────────────────────────────────────────────\n");

        boolean foundThroughput = false;
        for (BenchmarkResult result : results.results()) {
            BenchmarkMetric throughput = result.getThroughput();
            if (throughput != null) {
                sb.append(String.format("  %-40s %12.2f ops/sec", result.methodName(), throughput.value()));
                if (result.hasTarget()) {
                    sb.append(" (target: ≥").append(String.format("%.0f", result.targetValue())).append(")");
                    sb.append(result.hasUnevaluatedTarget() ? " ?"
                        : result.meetsTarget() ? " ✓" : " ✗");
                }
                sb.append("\n");
                foundThroughput = true;
            }
        }
        if (!foundThroughput) {
            sb.append("  No throughput metrics found\n");
        }
        sb.append("\n");

        // Latency percentiles
        sb.append("LATENCY PERCENTILES (ms)\n");
        sb.append("───────────────────────────────────────────────────────────────────────────\n");

        boolean foundLatency = false;
        for (BenchmarkResult result : results.results()) {
            BenchmarkMetric p50 = result.getP50();
            BenchmarkMetric p99 = result.getP99();

            if (p50 != null || p99 != null) {
                sb.append(String.format("  %-40s", result.methodName()));
                if (p50 != null) {
                    sb.append(" p50: ").append(String.format("%.2f", p50.value())).append(" ms");
                }
                if (p99 != null) {
                    sb.append(" p99: ").append(String.format("%.2f", p99.value())).append(" ms");
                }
                sb.append("\n");
                foundLatency = true;
            }
        }
        if (!foundLatency) {
            sb.append("  No latency percentile metrics found\n");
        }
        sb.append("\n");

        sb.append("═══════════════════════════════════════════════════════════════════════════\n");

        return sb.toString();
    }

    private static String escapeCsv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String formatDuration(long ms) {
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

    private BenchmarkReportWriter() {
        // Utility class
    }

    /**
     * Status icon for one result: pass, fail, or "could not be evaluated".
     */
    private static String statusIcon(BenchmarkResult result) {
        if (result.hasUnevaluatedTarget()) {
            return "⚠️";
        }
        return result.meetsTarget() ? "✅" : "❌";
    }

    /**
     * Overall run status for the Markdown summary table.
     */
    private static String markdownStatus(BenchmarkResultSet results) {
        if (results.resultCount() == 0) {
            return "❌ NO RESULTS CAPTURED";
        }
        if (results.allPassed()) {
            return "✅ ALL PASSED";
        }
        if (results.failCount() == 0) {
            return "⚠️ TARGETS NOT EVALUATED";
        }
        return "❌ SOME FAILED";
    }
}
