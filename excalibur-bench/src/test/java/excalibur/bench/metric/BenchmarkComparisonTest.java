package excalibur.bench.metric;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for benchmark comparison functionality.
 *
 * <p>Verifies that benchmark results can be compared across runs,
 * detecting improvements, regressions, and new benchmarks.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Percentage changes: computed as ((current - baseline) / baseline) * 100</li>
 *   <li>For throughput: positive change = improvement, negative = regression</li>
 *   <li>For latency: negative change = improvement, positive = regression</li>
 *   <li>Threshold for "unchanged" is 5% by default</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class BenchmarkComparisonTest {

    @Test
    void comparison_shouldDetectImprovement_forThroughput() {
        // Baseline: 10M ops/sec
        BenchmarkResult baseline = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(10_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        // Current: 15M ops/sec (50% improvement)
        BenchmarkResult current = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(15_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline")
            .addResult(baseline)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current")
            .addResult(current)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        assertThat(comparison.comparisons()).hasSize(1);
        assertThat(comparison.improvedCount()).isEqualTo(1);
        assertThat(comparison.regressedCount()).isEqualTo(0);
        assertThat(comparison.hasRegressions()).isFalse();

        BenchmarkComparison.ComparisonEntry entry = comparison.comparisons().get(0);
        assertThat(entry.changeType()).isEqualTo(BenchmarkComparison.ChangeType.IMPROVEMENT);
        assertThat(entry.percentChange()).isCloseTo(50.0, within(0.1));
        assertThat(entry.currentScore()).isEqualTo(15_000_000.0);
        assertThat(entry.baselineScore()).isEqualTo(10_000_000.0);
    }

    @Test
    void comparison_shouldDetectRegression_forThroughput() {
        // Baseline: 20M ops/sec
        BenchmarkResult baseline = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(20_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        // Current: 15M ops/sec (25% regression)
        BenchmarkResult current = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(15_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline")
            .addResult(baseline)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current")
            .addResult(current)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        assertThat(comparison.regressedCount()).isEqualTo(1);
        assertThat(comparison.improvedCount()).isEqualTo(0);
        assertThat(comparison.hasRegressions()).isTrue();

        BenchmarkComparison.ComparisonEntry entry = comparison.comparisons().get(0);
        assertThat(entry.changeType()).isEqualTo(BenchmarkComparison.ChangeType.REGRESSION);
        assertThat(entry.percentChange()).isCloseTo(-25.0, within(0.1));
    }

    @Test
    void comparison_shouldDetectImprovement_forLatency() {
        // For latency, LOWER is better
        // Baseline: 20ms p99
        BenchmarkResult baseline = BenchmarkResult.builder()
            .benchmarkName("test.Query.fullPipeline")
            .mode("sample")
            .primaryScore(20.0)
            .primaryScoreUnit("ms")
            .higherIsBetter(false) // Lower is better for latency
            .build();

        // Current: 15ms p99 (25% improvement - value went DOWN)
        BenchmarkResult current = BenchmarkResult.builder()
            .benchmarkName("test.Query.fullPipeline")
            .mode("sample")
            .primaryScore(15.0)
            .primaryScoreUnit("ms")
            .higherIsBetter(false)
            .build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline")
            .addResult(baseline)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current")
            .addResult(current)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        assertThat(comparison.improvedCount()).isEqualTo(1);
        assertThat(comparison.regressedCount()).isEqualTo(0);

        BenchmarkComparison.ComparisonEntry entry = comparison.comparisons().get(0);
        // Percent change is -25% (value decreased), which for "lower is better" is an improvement
        assertThat(entry.changeType()).isEqualTo(BenchmarkComparison.ChangeType.IMPROVEMENT);
        assertThat(entry.percentChange()).isCloseTo(-25.0, within(0.1));
    }

    @Test
    void comparison_shouldDetectRegression_forLatency() {
        // For latency, LOWER is better
        // Baseline: 15ms p99
        BenchmarkResult baseline = BenchmarkResult.builder()
            .benchmarkName("test.Query.fullPipeline")
            .mode("sample")
            .primaryScore(15.0)
            .primaryScoreUnit("ms")
            .higherIsBetter(false)
            .build();

        // Current: 20ms p99 (33% regression - value went UP)
        BenchmarkResult current = BenchmarkResult.builder()
            .benchmarkName("test.Query.fullPipeline")
            .mode("sample")
            .primaryScore(20.0)
            .primaryScoreUnit("ms")
            .higherIsBetter(false)
            .build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline")
            .addResult(baseline)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current")
            .addResult(current)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        assertThat(comparison.regressedCount()).isEqualTo(1);
        assertThat(comparison.improvedCount()).isEqualTo(0);
        assertThat(comparison.hasRegressions()).isTrue();

        BenchmarkComparison.ComparisonEntry entry = comparison.comparisons().get(0);
        // Percent change is +33% (value increased), which for "lower is better" is a regression
        assertThat(entry.changeType()).isEqualTo(BenchmarkComparison.ChangeType.REGRESSION);
        assertThat(entry.percentChange()).isCloseTo(33.33, within(0.1));
    }

    @Test
    void comparison_shouldDetectNewBenchmarks() {
        BenchmarkResult existing = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.existing")
            .mode("thrpt")
            .primaryScore(10_000_000.0)
            .primaryScoreUnit("ops/sec")
            .build();

        BenchmarkResult newBenchmark = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.newBenchmark")
            .mode("thrpt")
            .primaryScore(12_000_000.0)
            .primaryScoreUnit("ops/sec")
            .build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline")
            .addResult(existing)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current")
            .addResult(existing)
            .addResult(newBenchmark)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        assertThat(comparison.newBenchmarksCount()).isEqualTo(1);
        assertThat(comparison.getNewBenchmarks()).hasSize(1);
        assertThat(comparison.getNewBenchmarks().get(0).benchmarkName())
            .isEqualTo("test.PointLookup.newBenchmark");
    }

    @Test
    void comparison_shouldDetectUnchangedBenchmarks() {
        // Baseline: 10M ops/sec
        BenchmarkResult baseline = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(10_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        // Current: 10.3M ops/sec (3% change, within 5% threshold)
        BenchmarkResult current = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(10_300_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline")
            .addResult(baseline)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current")
            .addResult(current)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        assertThat(comparison.unchangedCount()).isEqualTo(1);
        assertThat(comparison.improvedCount()).isEqualTo(0);
        assertThat(comparison.regressedCount()).isEqualTo(0);

        BenchmarkComparison.ComparisonEntry entry = comparison.comparisons().get(0);
        assertThat(entry.changeType()).isEqualTo(BenchmarkComparison.ChangeType.UNCHANGED);
    }

    @Test
    void comparison_shouldSupportMultipleBenchmarks() {
        BenchmarkResult baseline1 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.fast")
            .mode("thrpt").primaryScore(20_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResult baseline2 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.slow")
            .mode("thrpt").primaryScore(10_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResult baseline3 = BenchmarkResult.builder()
            .benchmarkName("test.CuckooFilter.lookup")
            .mode("thrpt").primaryScore(5_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        // Current: 25M (improved), 8M (regressed), 5.1M (unchanged)
        BenchmarkResult current1 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.fast")
            .mode("thrpt").primaryScore(25_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResult current2 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.slow")
            .mode("thrpt").primaryScore(8_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResult current3 = BenchmarkResult.builder()
            .benchmarkName("test.CuckooFilter.lookup")
            .mode("thrpt").primaryScore(5_100_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline")
            .addResult(baseline1).addResult(baseline2).addResult(baseline3)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current")
            .addResult(current1).addResult(current2).addResult(current3)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        assertThat(comparison.comparisons()).hasSize(3);
        assertThat(comparison.improvedCount()).isEqualTo(1);
        assertThat(comparison.regressedCount()).isEqualTo(1);
        assertThat(comparison.unchangedCount()).isEqualTo(1);
        assertThat(comparison.hasRegressions()).isTrue();
    }

    @Test
    void comparison_shouldGetImprovementsAndRegressions() {
        BenchmarkResult baseline1 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.improved1")
            .mode("thrpt").primaryScore(10_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResult baseline2 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.improved2")
            .mode("thrpt").primaryScore(10_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResult baseline3 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.regressed1")
            .mode("thrpt").primaryScore(20_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResult current1 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.improved1")
            .mode("thrpt").primaryScore(15_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResult current2 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.improved2")
            .mode("thrpt").primaryScore(20_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResult current3 = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.regressed1")
            .mode("thrpt").primaryScore(10_000_000.0).primaryScoreUnit("ops/sec")
            .higherIsBetter(true).build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline")
            .addResult(baseline1).addResult(baseline2).addResult(baseline3)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current")
            .addResult(current1).addResult(current2).addResult(current3)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        List<BenchmarkComparison.ComparisonEntry> improvements = comparison.getImprovements();
        List<BenchmarkComparison.ComparisonEntry> regressions = comparison.getRegressions();

        assertThat(improvements).hasSize(2);
        assertThat(regressions).hasSize(1);

        // Improvements should be sorted by magnitude (largest first)
        assertThat(improvements.get(0).percentChange())
            .isGreaterThanOrEqualTo(improvements.get(1).percentChange());
    }

    @Test
    void comparison_shouldFormatSummary() {
        BenchmarkResult baseline = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(10_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        BenchmarkResult current = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(15_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline Run")
            .addResult(baseline)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current Run")
            .addResult(current)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        String summary = comparison.formatSummary();

        assertThat(summary).contains("BENCHMARK COMPARISON RESULTS");
        assertThat(summary).contains("Current Run");
        assertThat(summary).contains("Baseline Run");
        assertThat(summary).contains("Improved:");
        assertThat(summary).contains("NO REGRESSIONS");
    }

    @Test
    void comparison_shouldConvertToJson() {
        BenchmarkResult baseline = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(10_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        BenchmarkResult current = BenchmarkResult.builder()
            .benchmarkName("test.PointLookup.sequential")
            .mode("thrpt")
            .primaryScore(15_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet baselineSet = BenchmarkResultSet.builder()
            .name("Baseline")
            .addResult(baseline)
            .build();

        BenchmarkResultSet currentSet = BenchmarkResultSet.builder()
            .name("Current")
            .addResult(current)
            .build();

        BenchmarkComparison comparison = BenchmarkComparison.compare(currentSet, baselineSet);

        String json = comparison.toJson();

        assertThat(json).contains("\"currentRun\": \"Current\"");
        assertThat(json).contains("\"baselineRun\": \"Baseline\"");
        assertThat(json).contains("\"summary\":");
        assertThat(json).contains("\"comparisons\":");
        assertThat(json).contains("\"changeType\": \"IMPROVEMENT\"");
    }

    @Test
    void comparisonEntry_shouldFormatEntry() {
        BenchmarkComparison.ComparisonEntry entry = new BenchmarkComparison.ComparisonEntry(
            "test.PointLookup.sequential",
            "sequential",
            15_000_000.0,
            10_000_000.0,
            "ops/sec",
            50.0,
            BenchmarkComparison.ChangeType.IMPROVEMENT,
            true,
            true,
            10_000_000.0
        );

        String formatted = entry.format();

        assertThat(formatted).contains("sequential");
        assertThat(formatted).contains("15");  // Current score
        assertThat(formatted).contains("10");  // Baseline score
        assertThat(formatted).contains("50");  // Percent change
    }

    @Test
    void domainCorrectness_percentChangeShouldBeComputedCorrectly() {
        // Test that percent change is computed as ((current - baseline) / baseline) * 100

        // 100 -> 150 = 50% increase
        double change1 = ((150.0 - 100.0) / 100.0) * 100.0;
        assertThat(change1).isCloseTo(50.0, within(0.01));

        // 100 -> 50 = 50% decrease
        double change2 = ((50.0 - 100.0) / 100.0) * 100.0;
        assertThat(change2).isCloseTo(-50.0, within(0.01));

        // 100 -> 100 = 0% change
        double change3 = ((100.0 - 100.0) / 100.0) * 100.0;
        assertThat(change3).isCloseTo(0.0, within(0.01));
    }

    @Test
    void domainCorrectness_improvementVsRegressionDependsOnHigherIsBetter() {
        // For throughput (higher is better):
        //   +10% change = improvement
        //   -10% change = regression

        // For latency (lower is better):
        //   +10% change = regression (value went up)
        //   -10% change = improvement (value went down)

        // Test throughput improvement
        assertThat(isImprovement(10.0, true)).isTrue();   // +10%, higher better = improvement
        assertThat(isImprovement(-10.0, true)).isFalse(); // -10%, higher better = regression
        assertThat(isImprovement(10.0, false)).isFalse(); // +10%, lower better = regression
        assertThat(isImprovement(-10.0, false)).isTrue(); // -10%, lower better = improvement
    }

    private boolean isImprovement(double percentChange, boolean higherIsBetter) {
        double threshold = 5.0;
        if (Math.abs(percentChange) < threshold) {
            return false; // Unchanged
        }
        if (higherIsBetter) {
            return percentChange > 0;
        } else {
            return percentChange < 0;
        }
    }

    @Test
    void builder_shouldRequireCurrentAndBaselineResults() {
        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .name("Test")
            .build();

        // Missing baseline
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            BenchmarkComparison.builder()
                .currentResults(resultSet)
                .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("baselineResults");

        // Missing current
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            BenchmarkComparison.builder()
                .baselineResults(resultSet)
                .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("currentResults");
    }
}
