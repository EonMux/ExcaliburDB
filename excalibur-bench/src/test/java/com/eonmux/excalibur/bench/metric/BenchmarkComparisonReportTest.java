package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the comparison report: what it says about a run relative to its baseline.
 *
 * <p>The report is the human-readable half of the regression gate. What it has to get
 * right is which direction counts as worse for the metric in hand, and that a
 * regression is never left out of the part of the report a person actually reads.</p>
 */
class BenchmarkComparisonReportTest {

    @Test
    @DisplayName("the overview counts every change category and states the verdict")
    void overviewCountsEveryCategory() {
        BenchmarkComparison comparison = BenchmarkComparison.compare(
            set("current",
                throughput("improved", 2_000_000.0),
                throughput("regressed", 500_000.0),
                throughput("steady", 1_000_000.0),
                throughput("brandNew", 1_000_000.0)),
            set("baseline",
                throughput("improved", 1_000_000.0),
                throughput("regressed", 1_000_000.0),
                throughput("steady", 1_000_000.0)));

        String summary = comparison.formatSummary();

        assertThat(summary).contains("Total Compared:  4");
        assertThat(summary).contains("Improved:        1");
        assertThat(summary).contains("Regressed:       1");
        assertThat(summary).contains("Unchanged:       1");
        assertThat(summary).contains("New:             1");
        assertThat(summary).contains("REGRESSIONS DETECTED");
    }

    @Test
    @DisplayName("a clean comparison says so")
    void cleanComparisonSaysSo() {
        BenchmarkComparison comparison = BenchmarkComparison.compare(
            set("current", throughput("steady", 1_000_000.0)),
            set("baseline", throughput("steady", 1_000_000.0)));

        assertThat(comparison.hasRegressions()).isFalse();
        assertThat(comparison.formatSummary()).contains("NO REGRESSIONS");
        assertThat(comparison.formatSummary()).doesNotContain("REGRESSIONS (performance degraded)");
    }

    @Test
    @DisplayName("a regression is detailed with both scores and the direction of travel")
    void regressionsAreDetailed() {
        String summary = BenchmarkComparison.compare(
            set("current", throughput("lookup", 500_000.0)),
            set("baseline", throughput("lookup", 1_000_000.0))).formatSummary();

        assertThat(summary).contains("REGRESSIONS (performance degraded)");
        assertThat(summary).contains("Baseline: 1000000.00 ops/sec");
        assertThat(summary).contains("Current:  500000.00 ops/sec");
        assertThat(summary).contains("50.00%");
    }

    @Test
    @DisplayName("a rising latency is a regression, not an improvement")
    void risingLatencyIsARegression() {
        BenchmarkComparison comparison = BenchmarkComparison.compare(
            set("current", latency("query", 20.0)),
            set("baseline", latency("query", 10.0)));

        assertThat(comparison.regressedCount()).isEqualTo(1);
        assertThat(comparison.formatSummary()).contains("slower");
    }

    @Test
    @DisplayName("a falling latency is an improvement")
    void fallingLatencyIsAnImprovement() {
        BenchmarkComparison comparison = BenchmarkComparison.compare(
            set("current", latency("query", 5.0)),
            set("baseline", latency("query", 10.0)));

        assertThat(comparison.improvedCount()).isEqualTo(1);
        assertThat(comparison.formatSummary()).contains("TOP IMPROVEMENTS");
    }

    @Test
    @DisplayName("only the five largest improvements are listed")
    void improvementsAreCappedAtFive() {
        List<BenchmarkResult> current = new ArrayList<>();
        List<BenchmarkResult> baseline = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            current.add(throughput("b" + i, 1_000_000.0 * (i + 2)));
            baseline.add(throughput("b" + i, 1_000_000.0));
        }

        String summary = BenchmarkComparison.compare(
            set("current", current.toArray(new BenchmarkResult[0])),
            set("baseline", baseline.toArray(new BenchmarkResult[0]))).formatSummary();

        assertThat(summary.split("Baseline: ", -1)).hasSize(6);
    }

    @Test
    @DisplayName("improvements are listed largest first and regressions worst first")
    void changesAreSortedByMagnitude() {
        BenchmarkComparison comparison = BenchmarkComparison.compare(
            set("current",
                throughput("small", 1_100_000.0),
                throughput("large", 5_000_000.0),
                throughput("mild", 900_000.0),
                throughput("severe", 100_000.0)),
            set("baseline",
                throughput("small", 1_000_000.0),
                throughput("large", 1_000_000.0),
                throughput("mild", 1_000_000.0),
                throughput("severe", 1_000_000.0)));

        assertThat(comparison.getImprovements())
            .extracting(BenchmarkComparison.ComparisonEntry::methodName)
            .containsExactly("large", "small");
        assertThat(comparison.getRegressions())
            .extracting(BenchmarkComparison.ComparisonEntry::methodName)
            .containsExactly("severe", "mild");
    }

    @Test
    @DisplayName("the threshold decides what counts as a change at all")
    void thresholdDecidesWhatCounts() {
        BenchmarkResultSet current = set("current", throughput("lookup", 1_030_000.0));
        BenchmarkResultSet baseline = set("baseline", throughput("lookup", 1_000_000.0));

        assertThat(BenchmarkComparison.compare(current, baseline).unchangedCount())
            .as("a 3% move is inside the default 5% threshold")
            .isEqualTo(1);
        assertThat(BenchmarkComparison.builder()
            .currentResults(current)
            .baselineResults(baseline)
            .changeThreshold(1.0)
            .build()
            .improvedCount())
            .as("with a 1% threshold the same move is an improvement")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a benchmark absent from the baseline is new, not a regression")
    void newBenchmarksAreNotRegressions() {
        BenchmarkComparison comparison = BenchmarkComparison.compare(
            set("current", throughput("fresh", 1_000_000.0)),
            set("baseline"));

        assertThat(comparison.newBenchmarksCount()).isEqualTo(1);
        assertThat(comparison.hasRegressions()).isFalse();
        assertThat(comparison.getNewBenchmarks())
            .singleElement()
            .satisfies(entry -> {
                assertThat(entry.baselineScore()).isZero();
                assertThat(entry.format()).contains("+");
            });
    }

    @Test
    @DisplayName("an entry renders its verdict as an arrow matching its change type")
    void entryArrowsMatchTheChangeType() {
        assertThat(entry("lookup", 2_000_000.0, 1_000_000.0).format()).contains("↑");
        assertThat(entry("lookup", 500_000.0, 1_000_000.0).format()).contains("↓");
        assertThat(entry("lookup", 1_000_000.0, 1_000_000.0).format()).contains("=");
    }

    @Test
    @DisplayName("an entry carries the target it was judged against, when there is one")
    void entriesCarryTheirTarget() {
        BenchmarkComparison comparison = BenchmarkComparison.compare(
            set("current", targeted("lookup", 2_000_000.0, 1_000_000.0)),
            set("baseline", targeted("lookup", 1_000_000.0, 1_000_000.0)));

        BenchmarkComparison.ComparisonEntry entry = comparison.comparisons().get(0);

        assertThat(entry.targetValue()).isEqualTo(1_000_000.0);
        assertThat(entry.meetsTarget()).isTrue();
        assertThat(entry.higherIsBetter()).isTrue();
        assertThat(entry.benchmarkName()).endsWith("DemoBenchmark.lookup");
        assertThat(entry.toJson()).contains("\"target\": 1000000.0");
    }

    @Test
    @DisplayName("an entry without a target omits it rather than inventing one")
    void untargetedEntriesOmitTheTarget() {
        BenchmarkComparison.ComparisonEntry entry =
            entry("lookup", 2_000_000.0, 1_000_000.0);

        assertThat(entry.targetValue()).isNull();
        assertThat(entry.toJson()).doesNotContain("\"target\"");
    }

    @Test
    @DisplayName("a zero baseline yields no percent change rather than an infinity")
    void zeroBaselineDoesNotDivideByZero() {
        BenchmarkComparison comparison = BenchmarkComparison.compare(
            set("current", throughput("lookup", 1_000_000.0)),
            set("baseline", BenchmarkResult.builder()
                .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.lookup")
                .mode("avgt")
                .primaryScore(0.0)
                .primaryScoreUnit("ms/op")
                .higherIsBetter(false)
                .build()));

        assertThat(comparison.comparisons().get(0).percentChange()).isZero();
        assertThat(comparison.unchangedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("JSON carries the run names, the counts and every entry")
    void jsonCarriesTheWholeComparison() {
        String json = BenchmarkComparison.compare(
            set("current", throughput("lookup", 500_000.0)),
            set("baseline", throughput("lookup", 1_000_000.0))).toJson();

        assertThat(json).contains("\"currentRun\": \"current\"");
        assertThat(json).contains("\"baselineRun\": \"baseline\"");
        assertThat(json).contains("\"regressed\": 1");
        assertThat(json).contains("\"hasRegressions\": true");
        assertThat(json).contains("\"changeType\": \"REGRESSION\"");
        assertThat(json).contains("\"percentChange\": -50.0");
    }

    @Test
    @DisplayName("the compared result sets and the comparison time are retained")
    void comparedSetsAreRetained() {
        BenchmarkResultSet current = set("current", throughput("lookup", 1_000_000.0));
        BenchmarkResultSet baseline = set("baseline", throughput("lookup", 1_000_000.0));

        BenchmarkComparison comparison = BenchmarkComparison.compare(current, baseline);

        assertThat(comparison.currentResults()).isSameAs(current);
        assertThat(comparison.baselineResults()).isSameAs(baseline);
        assertThat(comparison.comparisonTime()).isNotNull();
        assertThat(comparison).hasToString(comparison.formatSummary());
    }

    // ---- fixtures ----

    private static BenchmarkComparison.ComparisonEntry entry(
            String method, double current, double baseline) {
        return BenchmarkComparison.compare(
            set("current", throughput(method, current)),
            set("baseline", throughput(method, baseline))).comparisons().get(0);
    }

    private static BenchmarkResultSet set(String name, BenchmarkResult... results) {
        BenchmarkResultSet.Builder builder = BenchmarkResultSet.builder()
            .name(name)
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .endTime(Instant.parse("2024-01-01T00:00:10Z"));
        for (BenchmarkResult result : results) {
            builder.addResult(result);
        }
        return builder.build();
    }

    private static BenchmarkResult throughput(String method, double opsPerSec) {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark." + method)
            .mode("thrpt")
            .primaryScore(opsPerSec)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();
    }

    private static BenchmarkResult targeted(String method, double opsPerSec, double target) {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark." + method)
            .mode("thrpt")
            .primaryScore(opsPerSec)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .target(target)
            .build();
    }

    private static BenchmarkResult latency(String method, double ms) {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark." + method)
            .mode("avgt")
            .primaryScore(ms)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .build();
    }
}
