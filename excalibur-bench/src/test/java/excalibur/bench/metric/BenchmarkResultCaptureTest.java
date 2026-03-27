package excalibur.bench.metric;

import excalibur.bench.report.BenchmarkReportWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for benchmark result capture functionality.
 *
 * <p>Verifies that benchmark results (throughput, latency percentiles) can be
 * correctly captured, stored, and reported.</p>
 *
 * @author ExcaliburDB
 */
class BenchmarkResultCaptureTest {

    @TempDir
    Path tempDir;

    @Test
    void benchmarkMetric_shouldCreateThroughputMetric() {
        BenchmarkMetric metric = BenchmarkMetric.throughput(25_500_000.0, 500_000.0);

        assertThat(metric.name()).isEqualTo("throughput");
        assertThat(metric.value()).isEqualTo(25_500_000.0);
        assertThat(metric.unit()).isEqualTo("ops/sec");
        assertThat(metric.error()).isEqualTo(500_000.0);
        assertThat(metric.format()).contains("throughput").contains("ops/sec");
    }

    @Test
    void benchmarkMetric_shouldCreateLatencyPercentileMetric() {
        BenchmarkMetric p50 = BenchmarkMetric.latencyPercentile(50, 5.2, 0.3);
        BenchmarkMetric p99 = BenchmarkMetric.latencyPercentile(99, 25.8, 2.1);

        assertThat(p50.name()).isEqualTo("p50");
        assertThat(p50.value()).isEqualTo(5.2);
        assertThat(p50.unit()).isEqualTo("ms");

        assertThat(p99.name()).isEqualTo("p99");
        assertThat(p99.value()).isEqualTo(25.8);
        assertThat(p99.unit()).isEqualTo("ms");
    }

    @Test
    void benchmarkMetric_shouldCheckTargetCompliance() {
        BenchmarkMetric throughput = BenchmarkMetric.throughput(25_000_000.0, 0);

        // Higher is better
        assertThat(throughput.meetsTarget(10_000_000.0, true)).isTrue();
        assertThat(throughput.meetsTarget(30_000_000.0, true)).isFalse();

        // Lower is better (for latency)
        BenchmarkMetric latency = BenchmarkMetric.latencyPercentile(99, 25.0, 0);
        assertThat(latency.meetsTarget(50.0, false)).isTrue();
        assertThat(latency.meetsTarget(10.0, false)).isFalse();
    }

    @Test
    void benchmarkMetric_shouldConvertToJson() {
        BenchmarkMetric metric = BenchmarkMetric.throughput(10_000_000.0, 500_000.0);
        String json = metric.toJson();

        assertThat(json).contains("\"name\": \"throughput\"");
        // Accept either standard or scientific notation
        assertThat(json).containsAnyOf("\"value\": 10000000.0", "\"value\": 1.0E7", "\"value\": 1E7");
        assertThat(json).contains("\"unit\": \"ops/sec\"");
        assertThat(json).containsAnyOf("\"error\": 500000.0", "\"error\": 500000");
    }

    @Test
    void benchmarkResult_shouldBuildWithAllFields() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("excalibur.bench.benchmark.PointLookupBenchmark.sequentialLookupFast")
            .mode("thrpt")
            .timestamp(Instant.now())
            .parameter("entryCount", "5000")
            .parameter("keySize", "16")
            .primaryScore(25_500_000.0)
            .primaryScoreError(500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .iterations(5)
            .forks(1)
            .durationMs(15000)
            .addMetric(BenchmarkMetric.throughput(25_500_000.0, 500_000.0))
            .build();

        assertThat(result.benchmarkName()).isEqualTo("excalibur.bench.benchmark.PointLookupBenchmark.sequentialLookupFast");
        assertThat(result.methodName()).isEqualTo("sequentialLookupFast");
        assertThat(result.mode()).isEqualTo("thrpt");
        assertThat(result.parameters()).containsEntry("entryCount", "5000");
        assertThat(result.primaryScore()).isEqualTo(25_500_000.0);
        assertThat(result.hasTarget()).isTrue();
        assertThat(result.targetValue()).isEqualTo(10_000_000.0);
        assertThat(result.meetsTarget()).isTrue();
        assertThat(result.metrics()).hasSize(1);
        assertThat(result.getThroughput()).isNotNull();
    }

    @Test
    void benchmarkResult_shouldDetectTargetMiss() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.slowLookup")
            .mode("thrpt")
            .primaryScore(5_000_000.0)  // Below target
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        assertThat(result.meetsTarget()).isFalse();
    }

    @Test
    void benchmarkResult_shouldFormatSummary() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.sequentialLookupFast")
            .mode("thrpt")
            .parameter("entryCount", "5000")
            .primaryScore(25_500_000.0)
            .primaryScoreError(500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        String summary = result.formatSummary();

        assertThat(summary).contains("sequentialLookupFast");
        assertThat(summary).contains("entryCount=5000");
        assertThat(summary).contains("ops/sec");
        assertThat(summary).contains("target");
        assertThat(summary).contains("✓");  // Passed
    }

    @Test
    void benchmarkResult_shouldConvertToJson() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.sequentialLookupFast")
            .mode("thrpt")
            .parameter("entryCount", "5000")
            .primaryScore(25_500_000.0)
            .primaryScoreError(500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        String json = result.toJson();

        assertThat(json).contains("\"benchmark\":");
        assertThat(json).contains("\"method\": \"sequentialLookupFast\"");
        assertThat(json).contains("\"mode\": \"thrpt\"");
        assertThat(json).contains("\"parameters\":");
        assertThat(json).contains("\"score\":");
        // Accept either standard or scientific notation for target value
        assertThat(json).containsAnyOf("\"target\": 10000000.0", "\"target\": 1.0E7", "\"target\": 1E7");
        assertThat(json).contains("\"meetsTarget\": true");
    }

    @Test
    void benchmarkResultSet_shouldBuildFromMultipleResults() {
        BenchmarkResult result1 = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.sequentialLookupFast")
            .mode("thrpt")
            .primaryScore(25_500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        BenchmarkResult result2 = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.randomLookupFast")
            .mode("thrpt")
            .primaryScore(8_000_000.0)  // Below target
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        BenchmarkResult result3 = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.CuckooFilter.lookup")
            .mode("thrpt")
            .primaryScore(15_000_000.0)
            .primaryScoreUnit("ops/sec")
            // No target defined
            .build();

        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .name("Test Benchmark Run")
            .startTime(Instant.now().minusSeconds(60))
            .endTime(Instant.now())
            .addResult(result1)
            .addResult(result2)
            .addResult(result3)
            .build();

        assertThat(resultSet.resultCount()).isEqualTo(3);
        assertThat(resultSet.passCount()).isEqualTo(1);  // result1 passed
        assertThat(resultSet.failCount()).isEqualTo(1);  // result2 failed
        assertThat(resultSet.skipCount()).isEqualTo(1);  // result3 no target
        assertThat(resultSet.allPassed()).isFalse();
        assertThat(resultSet.getFailedResults()).hasSize(1);
        assertThat(resultSet.getPassedResults()).hasSize(1);
    }

    @Test
    void benchmarkResultSet_shouldGroupByCategory() {
        BenchmarkResult r1 = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.fast")
            .mode("thrpt").primaryScore(10.0).primaryScoreUnit("ops/sec").build();
        BenchmarkResult r2 = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.slow")
            .mode("thrpt").primaryScore(10.0).primaryScoreUnit("ops/sec").build();
        BenchmarkResult r3 = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.CuckooFilter.lookup")
            .mode("thrpt").primaryScore(10.0).primaryScoreUnit("ops/sec").build();

        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .addResult(r1).addResult(r2).addResult(r3)
            .build();

        Map<String, List<BenchmarkResult>> categories = resultSet.resultsByCategory();

        assertThat(categories).containsKey("PointLookup");
        assertThat(categories).containsKey("CuckooFilter");
        assertThat(categories.get("PointLookup")).hasSize(2);
        assertThat(categories.get("CuckooFilter")).hasSize(1);
    }

    @Test
    void benchmarkResultSet_shouldFormatSummary() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.sequentialLookupFast")
            .mode("thrpt")
            .primaryScore(25_500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .name("Test Run")
            .addResult(result)
            .build();

        String summary = resultSet.formatSummary();

        assertThat(summary).contains("BENCHMARK RESULTS SUMMARY");
        assertThat(summary).contains("Total Benchmarks: 1");
        assertThat(summary).contains("Passed: 1");
        assertThat(summary).contains("ALL PASSED");
    }

    @Test
    void benchmarkResultSet_shouldConvertToJson() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.sequentialLookupFast")
            .mode("thrpt")
            .primaryScore(25_500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .name("Test Run")
            .addResult(result)
            .build();

        String json = resultSet.toJson();

        assertThat(json).contains("\"name\": \"Test Run\"");
        assertThat(json).contains("\"summary\":");
        assertThat(json).contains("\"categories\":");
        assertThat(json).contains("\"results\":");
    }

    @Test
    void benchmarkReportWriter_shouldWriteJsonFile() throws IOException {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.sequentialLookupFast")
            .mode("thrpt")
            .primaryScore(25_500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .name("Test Run")
            .addResult(result)
            .build();

        Path jsonPath = tempDir.resolve("results.json");
        BenchmarkReportWriter.writeJson(resultSet, jsonPath);

        assertThat(Files.exists(jsonPath)).isTrue();
        String content = Files.readString(jsonPath);
        assertThat(content).contains("\"name\": \"Test Run\"");
    }

    @Test
    void benchmarkReportWriter_shouldWriteMarkdownFile() throws IOException {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.sequentialLookupFast")
            .mode("thrpt")
            .primaryScore(25_500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .name("Test Run")
            .addResult(result)
            .build();

        Path mdPath = tempDir.resolve("results.md");
        BenchmarkReportWriter.writeMarkdown(resultSet, mdPath);

        assertThat(Files.exists(mdPath)).isTrue();
        String content = Files.readString(mdPath);
        assertThat(content).contains("# ExcaliburDB Benchmark Results");
        assertThat(content).contains("## Summary");
        assertThat(content).contains("## Results by Category");
    }

    @Test
    void benchmarkReportWriter_shouldWriteCsvFile() throws IOException {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.sequentialLookupFast")
            .mode("thrpt")
            .primaryScore(25_500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .build();

        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .name("Test Run")
            .addResult(result)
            .build();

        Path csvPath = tempDir.resolve("results.csv");
        BenchmarkReportWriter.writeCsv(resultSet, csvPath);

        assertThat(Files.exists(csvPath)).isTrue();
        String content = Files.readString(csvPath);
        assertThat(content).contains("category,benchmark,mode,score,error,unit,target,meets_target");
        assertThat(content).contains("PointLookup");
    }

    @Test
    void benchmarkReportWriter_shouldGenerateMetricsSummary() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("test.benchmark.PointLookup.sequentialLookupFast")
            .mode("thrpt")
            .primaryScore(25_500_000.0)
            .primaryScoreUnit("ops/sec")
            .target(10_000_000.0)
            .higherIsBetter(true)
            .addMetric(BenchmarkMetric.throughput(25_500_000.0, 0))
            .build();

        BenchmarkResultSet resultSet = BenchmarkResultSet.builder()
            .name("Test Run")
            .addResult(result)
            .build();

        String summary = BenchmarkReportWriter.generateMetricsSummary(resultSet);

        assertThat(summary).contains("KEY METRICS SUMMARY");
        assertThat(summary).contains("THROUGHPUT");
        assertThat(summary).contains("sequentialLookupFast");
        assertThat(summary).contains("ops/sec");
    }

    @Test
    void benchmarkResultCapture_shouldParseJmhOutputLine() {
        List<String> lines = List.of(
            "# JMH version: 1.37",
            "# VM version: OpenJDK 64-Bit Server VM",
            "excalibur.bench.benchmark.PointLookupBenchmark.sequentialLookupFast  thrpt   5  25500000.123 ± 500000.456  ops/sec"
        );

        List<BenchmarkResult> results = BenchmarkResultCapture.parseConsoleOutput(lines);

        assertThat(results).hasSize(1);
        BenchmarkResult result = results.get(0);
        assertThat(result.methodName()).isEqualTo("sequentialLookupFast");
        assertThat(result.primaryScore()).isEqualTo(25_500_000.123);
        assertThat(result.primaryScoreError()).isEqualTo(500_000.456);
        assertThat(result.primaryScoreUnit()).isEqualTo("ops/sec");
    }

    @Test
    void benchmarkResultCapture_shouldParseLineWithParameters() {
        List<String> lines = List.of(
            "excalibur.bench.benchmark.PointLookupBenchmark.sequentialLookupFast[entryCount=5000, keySize=16]  thrpt   5  25500000.123 ± 500000.456  ops/sec"
        );

        List<BenchmarkResult> results = BenchmarkResultCapture.parseConsoleOutput(lines);

        assertThat(results).hasSize(1);
        BenchmarkResult result = results.get(0);
        assertThat(result.parameters()).containsEntry("entryCount", "5000");
        assertThat(result.parameters()).containsEntry("keySize", "16");
    }

    @Test
    void benchmarkTargetRegistry_shouldHavePointLookupTarget() {
        BenchmarkTargetRegistry.TargetConfig target =
            BenchmarkTargetRegistry.getTarget("excalibur.bench.benchmark.PointLookupBenchmark.sequentialLookupFast");

        assertThat(target).isNotNull();
        assertThat(target.targetValue()).isEqualTo(10_000_000.0);
        assertThat(target.higherIsBetter()).isTrue();
        assertThat(target.isMet(15_000_000.0)).isTrue();
        assertThat(target.isMet(5_000_000.0)).isFalse();
    }

    @Test
    void benchmarkTargetRegistry_shouldHaveCuckooFilterFPRateTarget() {
        BenchmarkTargetRegistry.TargetConfig target =
            BenchmarkTargetRegistry.getTarget("excalibur.bench.benchmark.CuckooFilterFPRateBenchmark.measureFpRate");

        assertThat(target).isNotNull();
        assertThat(target.higherIsBetter()).isFalse();  // Lower is better for FP rate
        assertThat(target.isMet(0.0005)).isTrue();      // 0.05% < 0.1%
        assertThat(target.isMet(0.002)).isFalse();      // 0.2% > 0.1%
    }

    @Test
    void benchmarkTargetRegistry_shouldHaveHnswRecallTarget() {
        BenchmarkTargetRegistry.TargetConfig target =
            BenchmarkTargetRegistry.getTarget("excalibur.bench.benchmark.HnswRecallBenchmark.measureRecall");

        assertThat(target).isNotNull();
        assertThat(target.targetValue()).isEqualTo(0.99);  // 99% recall
        assertThat(target.higherIsBetter()).isTrue();
        assertThat(target.isMet(0.995)).isTrue();  // 99.5% >= 99%
        assertThat(target.isMet(0.95)).isFalse();  // 95% < 99%
    }

    @Test
    void domainCorrectness_throughputValuesShouldBePositive() {
        BenchmarkMetric throughput = BenchmarkMetric.throughput(25_500_000.0, 500_000.0);

        // Domain correctness: throughput must be positive
        assertThat(throughput.value()).isPositive();
        assertThat(throughput.value()).isGreaterThan(1_000_000.0);  // At least 1M ops/sec expected
        assertThat(throughput.value()).isLessThan(1_000_000_000.0);  // Realistic upper bound
    }

    @Test
    void domainCorrectness_throughputShouldRejectNegativeValues() {
        // Negative throughput should be rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BenchmarkMetric.throughput(-1_000_000.0, 0)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("positive");
    }

    @Test
    void domainCorrectness_throughputShouldRejectZero() {
        // Zero throughput should be rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BenchmarkMetric.throughput(0.0, 0)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("positive");
    }

    @Test
    void domainCorrectness_throughputShouldRejectNaN() {
        // NaN throughput should be rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BenchmarkMetric.throughput(Double.NaN, 0)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("finite");
    }

    @Test
    void domainCorrectness_throughputShouldRejectInfinite() {
        // Infinite throughput should be rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BenchmarkMetric.throughput(Double.POSITIVE_INFINITY, 0)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("finite");
    }

    @Test
    void domainCorrectness_benchmarkResultShouldRejectNegativeThroughput() {
        // BenchmarkResult with negative throughput score should be rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BenchmarkResult.builder()
                .benchmarkName("test.benchmark.PointLookup.negative")
                .mode("thrpt")
                .primaryScore(-1_000_000.0)  // Negative!
                .primaryScoreUnit("ops/sec")
                .higherIsBetter(true)
                .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("positive");
    }

    @Test
    void domainCorrectness_benchmarkResultShouldRejectNegativeThroughputMetric() {
        // BenchmarkResult with negative throughput metric should be rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BenchmarkResult.builder()
                .benchmarkName("test.benchmark.PointLookup.negative")
                .mode("thrpt")
                .primaryScore(10_000_000.0)
                .primaryScoreUnit("ops/sec")
                .higherIsBetter(true)
                .addMetric(new BenchmarkMetric("throughput", -5_000_000.0, "ops/sec", 0, 0))  // Negative!
                .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("positive");
    }

    @Test
    void domainCorrectness_latencyPercentileOrderShouldBeValidated() {
        // p50 > p99 should be rejected
        BenchmarkMetric p50 = new BenchmarkMetric("p50", 50.0, "ms", 0, 0);  // High p50
        BenchmarkMetric p99 = new BenchmarkMetric("p99", 10.0, "ms", 0, 0);  // Low p99 - invalid!

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BenchmarkResult.builder()
                .benchmarkName("test.benchmark.Latency.invalid")
                .mode("sample")
                .primaryScore(25.0)
                .primaryScoreUnit("ms")
                .higherIsBetter(false)
                .addMetric(p50)
                .addMetric(p99)
                .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("p50");
    }

    @Test
    void domainCorrectness_latencyPercentilesShouldBeOrdered() {
        // p50 < p99 for latency
        BenchmarkMetric p50 = BenchmarkMetric.latencyPercentile(50, 5.0, 0.5);
        BenchmarkMetric p99 = BenchmarkMetric.latencyPercentile(99, 25.0, 2.5);

        assertThat(p50.value()).isLessThan(p99.value());
        assertThat(p50.value()).isGreaterThanOrEqualTo(0.0);
        assertThat(p99.value()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void domainCorrectness_recallShouldBeBetweenZeroAndOne() {
        BenchmarkTargetRegistry.TargetConfig target =
            BenchmarkTargetRegistry.getTarget("excalibur.bench.benchmark.HnswRecallBenchmark.measureRecall");

        // Recall target should be in [0, 1]
        assertThat(target.targetValue()).isBetween(0.0, 1.0);
    }

    @Test
    void domainCorrectness_fpRateShouldBeLow() {
        BenchmarkTargetRegistry.TargetConfig target =
            BenchmarkTargetRegistry.getTarget("excalibur.bench.benchmark.CuckooFilterFPRateBenchmark.measureFpRate");

        // FP rate target should be low (≤0.1% = 0.001)
        assertThat(target.targetValue()).isLessThanOrEqualTo(0.001);
    }
}
