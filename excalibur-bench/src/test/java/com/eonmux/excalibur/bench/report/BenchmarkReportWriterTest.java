package com.eonmux.excalibur.bench.report;

import com.eonmux.excalibur.bench.metric.BenchmarkMetric;
import com.eonmux.excalibur.bench.metric.BenchmarkResult;
import com.eonmux.excalibur.bench.metric.BenchmarkResultSet;
import com.eonmux.excalibur.bench.metric.ErroredBenchmark;
import com.eonmux.excalibur.bench.metric.BenchmarkTargetRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the benchmark report formats.
 *
 * <p>These reports are how a benchmark run is read by a person. The risk they carry
 * is not a crash but a plausible-looking report that misstates the outcome - a missed
 * target rendered as met, a gap quoted in the wrong unit, or an empty run presented as
 * a clean one.</p>
 */
class BenchmarkReportWriterTest {

    @Test
    @DisplayName("the Markdown header carries the run name and its duration")
    void markdownHeaderNamesTheRun() {
        String markdown = BenchmarkReportWriter.generateMarkdown(
            resultSet("Nightly 20240101", 90_000, passing()));

        assertThat(markdown).startsWith("# ExcaliburDB Benchmark Results");
        assertThat(markdown).contains("**Run:** Nightly 20240101");
        assertThat(markdown).contains("**Duration:** 1m 30s");
    }

    @Test
    @DisplayName("durations render as seconds, minutes or hours as the size demands")
    void durationScalesWithMagnitude() {
        assertThat(BenchmarkReportWriter.generateMarkdown(resultSet("r", 1_500, passing())))
            .contains("**Duration:** 1.50s");
        assertThat(BenchmarkReportWriter.generateMarkdown(resultSet("r", 125_000, passing())))
            .contains("**Duration:** 2m 5s");
        assertThat(BenchmarkReportWriter.generateMarkdown(resultSet("r", 3_725_000, passing())))
            .contains("**Duration:** 1h 2m 5s");
    }

    @Test
    @DisplayName("the summary counts passes, failures and unevaluated targets separately")
    void summaryCountsEachOutcomeSeparately() {
        String markdown = BenchmarkReportWriter.generateMarkdown(
            resultSet("r", 1_000, passing(), failing(), unevaluated()));

        assertThat(markdown).contains("| Total Benchmarks | 3 |");
        assertThat(markdown).contains("| Passed | 1 |");
        assertThat(markdown).contains("| Failed | 1 |");
        assertThat(markdown).contains("| Not Evaluated | 1 |");
    }

    @Test
    @DisplayName("an all-clear run is the only one reported as passed")
    void statusReflectsTheWorstOutcome() {
        assertThat(BenchmarkReportWriter.generateMarkdown(resultSet("r", 1, passing())))
            .contains("ALL PASSED");
        assertThat(BenchmarkReportWriter.generateMarkdown(resultSet("r", 1, passing(), failing())))
            .contains("SOME FAILED");
        assertThat(BenchmarkReportWriter.generateMarkdown(resultSet("r", 1, passing(), unevaluated())))
            .contains("TARGETS NOT EVALUATED");
        assertThat(BenchmarkReportWriter.generateMarkdown(resultSet("r", 1)))
            .contains("NO RESULTS CAPTURED");
    }

    @Test
    @DisplayName("results are grouped by benchmark class with the Benchmark suffix dropped")
    void resultsAreGroupedByCategory() {
        String markdown = BenchmarkReportWriter.generateMarkdown(resultSet("r", 1, passing()));

        assertThat(markdown).contains("### Demo\n");
        assertThat(markdown).contains("| Benchmark | Score | Error | Unit | Target | Status |");
        assertThat(markdown).contains("| lookup ");
    }

    @Test
    @DisplayName("a benchmark with no target shows dashes rather than a fabricated one")
    void untargetedBenchmarkShowsNoTarget() {
        String markdown = BenchmarkReportWriter.generateMarkdown(resultSet("r", 1, untargeted()));

        assertThat(markdown).contains("| - | - |");
    }

    @Test
    @DisplayName("a missed target is detailed with the gap expressed in the target's unit")
    void failedBenchmarksAreDetailed() {
        String markdown = BenchmarkReportWriter.generateMarkdown(resultSet("r", 1, failing()));

        assertThat(markdown).contains("## Failed Benchmarks");
        assertThat(markdown).contains("- **Metric:** primary");
        assertThat(markdown).contains("- **Actual:** 400000.00 ops/sec");
        assertThat(markdown).contains("- **Target:** 1000000.00 ops/sec");
        assertThat(markdown).contains("- **Gap:** 600000.00 ops/sec (60.0%)");
    }

    @Test
    @DisplayName("a target that could not be evaluated gets its own section and says why")
    void unevaluatedTargetsAreCalledOut() {
        String markdown = BenchmarkReportWriter.generateMarkdown(resultSet("r", 1, unevaluated()));

        assertThat(markdown).contains("## Targets Not Evaluated");
        assertThat(markdown).contains("failures of the reporting pipeline");
        assertThat(markdown).contains("NOT EVALUATED");
        assertThat(markdown).contains("p99");
    }

    @Test
    @DisplayName("a clean run has neither a failure nor an unevaluated section")
    void cleanRunHasNoProblemSections() {
        String markdown = BenchmarkReportWriter.generateMarkdown(resultSet("r", 1, passing()));

        assertThat(markdown).doesNotContain("## Failed Benchmarks");
        assertThat(markdown).doesNotContain("## Targets Not Evaluated");
    }

    @Test
    @DisplayName("Markdown counts errored combinations, fails the status, and explains each one")
    void markdownReportsErroredCombinations() {
        String markdown = BenchmarkReportWriter.generateMarkdown(withErrored(passing()));

        assertThat(markdown).contains("| Total Benchmarks | 2 |");
        assertThat(markdown).contains("| Errored | 1 |");
        assertThat(markdown).contains("BENCHMARKS ERRORED");
        assertThat(markdown).doesNotContain("ALL PASSED");
        assertThat(markdown).contains("## Errored Benchmarks");
        assertThat(markdown).contains("sequentialRead_bytes [bufferSize=131072] (thrpt)");
        // A pipe in the message must not split the table row.
        assertThat(markdown).contains("size \\| limit");
        assertThat(markdown).contains("at Demo.setup(Demo.java:7)");
    }

    @Test
    @DisplayName("CSV carries a row per errored combination, marked as errored with its message")
    void csvCarriesErroredCombinations() {
        String csv = BenchmarkReportWriter.generateCsv(withErrored(passing()));

        assertThat(csv.lines()).hasSize(3);
        assertThat(csv).contains("MappedFile,sequentialRead_bytes [bufferSize=131072],thrpt,,,,,errored,"
            + "java.lang.IllegalStateException: size | limit");
    }

    @Test
    @DisplayName("the text report carries the errored combinations")
    void textReportCarriesErroredCombinations() {
        assertThat(BenchmarkReportWriter.generateTextReport(withErrored(passing())))
            .contains("Errored: 1")
            .contains("size | limit");
    }

    @Test
    @DisplayName("CSV carries one header and one row per result")
    void csvHasAHeaderAndARowPerResult() {
        String csv = BenchmarkReportWriter.generateCsv(resultSet("r", 1, passing(), failing()));

        assertThat(csv.lines()).hasSize(3);
        assertThat(csv).startsWith("category,benchmark,mode,score,error,unit,target,meets_target,failure\n");
        assertThat(csv).contains(",lookup,thrpt,");
        assertThat(csv).contains(",true");
        assertThat(csv).contains(",false");
    }

    @Test
    @DisplayName("CSV distinguishes 'no target' from 'target not evaluated'")
    void csvDistinguishesMissingAndUnevaluatedTargets() {
        assertThat(BenchmarkReportWriter.generateCsv(resultSet("r", 1, untargeted())))
            .contains(",n/a");
        assertThat(BenchmarkReportWriter.generateCsv(resultSet("r", 1, unevaluated())))
            .contains("not-evaluated");
    }

    @Test
    @DisplayName("a field containing a comma or a quote is escaped rather than splitting the row")
    void csvEscapesSeparatorsAndQuotes() {
        String csv = BenchmarkReportWriter.generateCsv(resultSet("r", 1, BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.lookup")
            .mode("thrpt")
            .primaryScore(1.0)
            .primaryScoreUnit("a,b\"c")
            .build()));

        assertThat(csv).contains("\"a,b\"\"c\"");
        assertThat(csv.lines()).hasSize(2);
    }

    @Test
    @DisplayName("the metrics summary lists throughput and latency percentiles")
    void metricsSummaryListsThroughputAndLatency() {
        String summary = BenchmarkReportWriter.generateMetricsSummary(
            resultSet("r", 1, passing(), latencyResult()));

        assertThat(summary).contains("THROUGHPUT (ops/sec)");
        assertThat(summary).contains("lookup").contains("2000000.00 ops/sec");
        assertThat(summary).contains("LATENCY PERCENTILES (ms)");
        assertThat(summary).contains("p50: 4.00 ms").contains("p99: 9.00 ms");
    }

    @Test
    @DisplayName("the metrics summary says so when a category produced nothing")
    void metricsSummaryReportsMissingMetrics() {
        String summary = BenchmarkReportWriter.generateMetricsSummary(resultSet("r", 1, untargeted()));

        assertThat(summary).contains("No throughput metrics found");
        assertThat(summary).contains("No latency percentile metrics found");
    }

    @Test
    @DisplayName("a lower-is-better target is not printed as a lower bound")
    void metricsSummaryPrintsTheTargetDirection() {
        // A 10 ms p50 target means "at most 10 ms". Printing it as the >= form says
        // the opposite of what the gate enforces.
        String summary = BenchmarkReportWriter.generateMetricsSummary(
            resultSet("r", 1, latencyResultWithTarget()));

        assertThat(summary).contains("target: ≤");
        assertThat(summary).doesNotContain("target: ≥");
    }

    @Test
    @DisplayName("the text report is the result set's own summary")
    void textReportIsTheResultSetSummary() {
        BenchmarkResultSet results = resultSet("r", 1, passing());

        assertThat(BenchmarkReportWriter.generateTextReport(results))
            .isEqualTo(results.formatSummary());
    }

    @Test
    @DisplayName("each writer puts its generated text on disk unchanged")
    void writersPersistWhatTheyGenerate(@TempDir Path dir) throws IOException {
        BenchmarkResultSet results = resultSet("r", 1, passing());

        BenchmarkReportWriter.writeJson(results, dir.resolve("r.json"));
        BenchmarkReportWriter.writeMarkdown(results, dir.resolve("r.md"));
        BenchmarkReportWriter.writeCsv(results, dir.resolve("r.csv"));
        BenchmarkReportWriter.writeTxt(results, dir.resolve("r.txt"));

        assertThat(Files.readString(dir.resolve("r.json"))).isEqualTo(results.toJson());
        assertThat(Files.readString(dir.resolve("r.md")))
            .isEqualTo(BenchmarkReportWriter.generateMarkdown(results));
        assertThat(Files.readString(dir.resolve("r.csv")))
            .isEqualTo(BenchmarkReportWriter.generateCsv(results));
        assertThat(Files.readString(dir.resolve("r.txt")))
            .isEqualTo(BenchmarkReportWriter.generateTextReport(results));
    }

    // ---- fixtures ----

    /**
     * Applies the targets the registry defines for a benchmark, which is what the
     * capture path does before a result is ever reported on.
     */
    private static BenchmarkResult.Builder withRegisteredTargets(
            BenchmarkResult.Builder builder, String benchmarkName) {
        for (BenchmarkTargetRegistry.TargetConfig target
                : BenchmarkTargetRegistry.getTargets(benchmarkName)) {
            builder.addTarget(target);
        }
        return builder;
    }

    private static BenchmarkResultSet resultSet(String name, long durationMs, BenchmarkResult... results) {
        BenchmarkResultSet.Builder builder = BenchmarkResultSet.builder()
            .name(name)
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .totalDurationMs(durationMs);
        for (BenchmarkResult result : results) {
            builder.addResult(result);
        }
        return builder.build();
    }

    private static BenchmarkResultSet withErrored(BenchmarkResult... results) {
        BenchmarkResultSet.Builder builder = BenchmarkResultSet.builder()
            .name("r")
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .totalDurationMs(1_000)
            .addErrored(new ErroredBenchmark(
                "com.eonmux.excalibur.bench.benchmark.MappedFileBenchmark.sequentialRead_bytes",
                "thrpt",
                java.util.Map.of("bufferSize", "131072"),
                "java.lang.IllegalStateException: size | limit",
                "java.lang.IllegalStateException: size | limit\n\tat Demo.setup(Demo.java:7)"));
        for (BenchmarkResult result : results) {
            builder.addResult(result);
        }
        return builder.build();
    }

    private static BenchmarkResult passing() {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.lookup")
            .mode("thrpt")
            .primaryScore(2_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .target(1_000_000.0)
            .addMetric(BenchmarkMetric.throughput(2_000_000.0, 0.0))
            .build();
    }

    private static BenchmarkResult failing() {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.slowLookup")
            .mode("thrpt")
            .primaryScore(400_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .target(1_000_000.0)
            .build();
    }

    private static BenchmarkResult untargeted() {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.unmeasured")
            .mode("avgt")
            .primaryScore(1.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .build();
    }

    /** Carries registry targets on p50 and p99, neither of which was captured. */
    private static BenchmarkResult unevaluated() {
        String name = "com.eonmux.excalibur.bench.benchmark.EndToEndQueryBenchmark.fullQueryPipeline";
        return withRegisteredTargets(BenchmarkResult.builder()
            .benchmarkName(name)
            .mode("sample")
            .primaryScore(5.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false), name)
            .build();
    }

    private static BenchmarkResult latencyResult() {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.query")
            .mode("sample")
            .primaryScore(5.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .addMetric(new BenchmarkMetric("p50", 4.0, "ms"))
            .addMetric(new BenchmarkMetric("p99", 9.0, "ms"))
            .build();
    }

    private static BenchmarkResult latencyResultWithTarget() {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.query")
            .mode("sample")
            .primaryScore(5.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .target(10.0)
            .addMetric(BenchmarkMetric.throughput(1_000.0, 0.0))
            .addMetric(new BenchmarkMetric("p50", 4.0, "ms"))
            .addMetric(new BenchmarkMetric("p99", 9.0, "ms"))
            .build();
    }
}
