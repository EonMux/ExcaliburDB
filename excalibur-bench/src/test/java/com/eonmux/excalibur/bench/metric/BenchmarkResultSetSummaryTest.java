package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how a result set summarises a run: the counts, the grouping and the console
 * report a person reads.
 *
 * <p>The summary is the last thing between a regression and someone deciding the run
 * was fine, so what it must get right is the arithmetic of "how many passed" and the
 * detail of what failed - not its box-drawing.</p>
 */
class BenchmarkResultSetSummaryTest {

    @Test
    @DisplayName("an empty run is a failure, not a vacuous pass")
    void emptyRunIsNotAPass() {
        BenchmarkResultSet results = BenchmarkResultSet.builder().name("nothing").build();

        assertThat(results.allPassed()).isFalse();
        assertThat(results.passRate()).isZero();
        assertThat(results.formatSummary()).contains("NO RESULTS CAPTURED");
    }

    @Test
    @DisplayName("the pass rate counts only benchmarks that carry a target")
    void passRateIgnoresUntargetedBenchmarks() {
        // One pass, one fail, and two results with no target at all: 50%, not 25%.
        BenchmarkResultSet results = setOf(passing(), failing(), untargeted("a"), untargeted("b"));

        assertThat(results.passCount()).isEqualTo(1);
        assertThat(results.failCount()).isEqualTo(1);
        assertThat(results.skipCount()).isEqualTo(2);
        assertThat(results.passRate()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("an unevaluated target counts against the pass rate and blocks allPassed")
    void unevaluatedTargetIsNeverAPass() {
        BenchmarkResultSet results = setOf(passing(), unevaluated());

        assertThat(results.unevaluatedCount()).isEqualTo(1);
        assertThat(results.allPassed()).isFalse();
        assertThat(results.passRate()).isEqualTo(50.0);
        assertThat(results.formatSummary()).contains("TARGETS NOT EVALUATED");
    }

    @Test
    @DisplayName("a run whose targets were all met reports as passed")
    void allMetIsAPass() {
        BenchmarkResultSet results = setOf(passing());

        assertThat(results.allPassed()).isTrue();
        assertThat(results.passRate()).isEqualTo(100.0);
        assertThat(results.formatSummary()).contains("ALL PASSED");
    }

    @Test
    @DisplayName("an errored combination blocks allPassed even when every measured target was met")
    void erroredCombinationIsNeverAPass() {
        BenchmarkResultSet results = BenchmarkResultSet.builder()
            .name("run")
            .addResult(passing())
            .addErrored(errored())
            .build();

        assertThat(results.erroredCount()).isEqualTo(1);
        assertThat(results.passCount()).isEqualTo(1);
        assertThat(results.allPassed()).isFalse();
        assertThat(results.passRate()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("the summary counts, labels and explains errored combinations")
    void summaryReportsErroredCombinations() {
        String summary = BenchmarkResultSet.builder()
            .name("run")
            .addResult(passing())
            .addErrored(errored())
            .build()
            .formatSummary();

        assertThat(summary).contains("Errored: 1");
        assertThat(summary).contains("Total Benchmarks: 2");
        assertThat(summary).contains("BENCHMARKS ERRORED");
        assertThat(summary).doesNotContain("ALL PASSED");
        assertThat(summary).contains("sequentialRead_bytes [bufferSize=131072] (thrpt)");
        assertThat(summary).contains("java.lang.IllegalStateException: bufferSize too large");
        assertThat(summary).contains("at Demo.setup(Demo.java:7)");
    }

    @Test
    @DisplayName("a run in which every combination errored reports the errors, not 'no results'")
    void allErroredRunNamesTheErrors() {
        BenchmarkResultSet results = BenchmarkResultSet.builder()
            .name("run")
            .addErrored(errored())
            .build();

        assertThat(results.allPassed()).isFalse();
        assertThat(results.formatSummary())
            .contains("BENCHMARKS ERRORED")
            .doesNotContain("NO RESULTS CAPTURED");
    }

    @Test
    @DisplayName("JSON counts errored combinations and lists them")
    void jsonCarriesErroredCombinations() {
        String json = BenchmarkResultSet.builder()
            .name("run")
            .addResult(passing())
            .addErrored(errored())
            .build()
            .toJson();

        assertThat(json).contains("\"errored\": 1");
        assertThat(json).contains("\"total\": 2");
        assertThat(json).contains("\"allPassed\": false");
        assertThat(json).contains("\"errored\": [");
        assertThat(json).contains("bufferSize too large");
    }

    @Test
    @DisplayName("category grouping is shared with errored combinations")
    void categoryOfMatchesResultGrouping() {
        assertThat(BenchmarkResultSet.categoryOf(
            "com.eonmux.excalibur.bench.benchmark.PointLookupBenchmark.lookup"))
            .isEqualTo("PointLookup");
        assertThat(BenchmarkResultSet.categoryOf("Benchmark")).isEqualTo("Other");
    }

    @Test
    @DisplayName("results are grouped by class with the Benchmark suffix stripped")
    void categoriesComeFromTheClassName() {
        BenchmarkResultSet results = setOf(passing(), latencyResult());

        assertThat(results.resultsByCategory()).containsOnlyKeys("Demo", "Query");
        assertThat(results.getResultsByCategory("Demo")).hasSize(1);
        assertThat(results.getResultsByCategory("Absent")).isEmpty();
    }

    @Test
    @DisplayName("a name that leaves no class behind is filed under Other")
    void unclassifiableNamesFallBackToOther() {
        BenchmarkResultSet results = setOf(BenchmarkResult.builder()
            .benchmarkName("Benchmark")
            .mode("thrpt")
            .primaryScore(1.0)
            .build());

        assertThat(results.resultsByCategory()).containsOnlyKeys("Other");
    }

    @Test
    @DisplayName("categories are listed in alphabetical order")
    void categoriesAreSorted() {
        String summary = setOf(latencyResult(), passing()).formatSummary();

        assertThat(summary.indexOf("[Demo]")).isLessThan(summary.indexOf("[Query]"));
    }

    @Test
    @DisplayName("a missed target is detailed with its gap in the target's own unit")
    void failedResultsAreDetailed() {
        String summary = setOf(failing()).formatSummary();

        assertThat(summary).contains("FAILED BENCHMARKS (did not meet target)");
        assertThat(summary).contains("Metric: primary");
        assertThat(summary).contains("Actual: 400000.00 ops/sec");
        assertThat(summary).contains("Target: 1000000.00 ops/sec");
        assertThat(summary).contains("Gap:    600000.00 ops/sec (60.0%)");
    }

    @Test
    @DisplayName("an unevaluated target is detailed with the reason it could not be compared")
    void unevaluatedResultsExplainThemselves() {
        String summary = setOf(unevaluated()).formatSummary();

        assertThat(summary).contains("TARGETS NOT EVALUATED (no measurement to compare against)");
        assertThat(summary).contains("was not captured");
    }

    @Test
    @DisplayName("the duration renders in the largest unit that fits")
    void durationScalesWithMagnitude() {
        assertThat(withDuration(1_500).formatSummary()).contains("Duration: 1.50s");
        assertThat(withDuration(125_000).formatSummary()).contains("Duration: 2m 5s");
        assertThat(withDuration(3_725_000).formatSummary()).contains("Duration: 1h 2m 5s");
    }

    @Test
    @DisplayName("the duration is derived from the timestamps when not given outright")
    void durationFallsBackToTheTimestamps() {
        BenchmarkResultSet results = BenchmarkResultSet.builder()
            .name("r")
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .endTime(Instant.parse("2024-01-01T00:00:45Z"))
            .addResult(passing())
            .build();

        assertThat(results.totalDurationMs()).isEqualTo(45_000);
    }

    @Test
    @DisplayName("JSON carries the summary counts and every result under both groupings")
    void jsonCarriesCountsAndResults() {
        String json = setOf(passing(), failing()).toJson();

        assertThat(json).contains("\"total\": 2");
        assertThat(json).contains("\"passed\": 1");
        assertThat(json).contains("\"failed\": 1");
        assertThat(json).contains("\"allPassed\": false");
        assertThat(json).contains("\"categories\"");
        assertThat(json).contains("\"results\"");
        assertThat(json).contains("\"lookup\"").contains("\"slowLookup\"");
    }

    @Test
    @DisplayName("a run name containing quotes does not break the JSON it is written into")
    void jsonEscapesTheRunName() {
        BenchmarkResultSet results = BenchmarkResultSet.builder()
            .name("say \"hi\"\tnow")
            .addResult(passing())
            .build();

        assertThat(results.toJson()).contains("\\\"hi\\\"\\tnow");
    }

    @Test
    @DisplayName("the failed, passed and unevaluated views select the right results")
    void resultViewsPartitionTheRun() {
        BenchmarkResultSet results = setOf(passing(), failing(), unevaluated(), untargeted("x"));

        assertThat(results.getPassedResults()).extracting(BenchmarkResult::methodName)
            .containsExactly("lookup");
        assertThat(results.getFailedResults()).extracting(BenchmarkResult::methodName)
            .containsExactly("slowLookup");
        assertThat(results.getUnevaluatedResults()).extracting(BenchmarkResult::methodName)
            .containsExactly("fullQueryPipeline");
    }

    @Test
    @DisplayName("the stored result list cannot be modified through its accessor")
    void resultsAreImmutable() {
        List<BenchmarkResult> results = setOf(passing()).results();

        assertThat(results).isUnmodifiable();
    }

    @Test
    @DisplayName("toString is the formatted summary")
    void toStringIsTheSummary() {
        BenchmarkResultSet results = setOf(passing());

        assertThat(results).hasToString(results.formatSummary());
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

    private static BenchmarkResultSet setOf(BenchmarkResult... results) {
        BenchmarkResultSet.Builder builder = BenchmarkResultSet.builder()
            .name("run")
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .endTime(Instant.parse("2024-01-01T00:00:10Z"))
            .results(List.of(results));
        return builder.build();
    }

    private static BenchmarkResultSet withDuration(long ms) {
        return BenchmarkResultSet.builder()
            .name("run")
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .totalDurationMs(ms)
            .addResult(passing())
            .build();
    }

    private static ErroredBenchmark errored() {
        return new ErroredBenchmark(
            "com.eonmux.excalibur.bench.benchmark.MappedFileBenchmark.sequentialRead_bytes",
            "thrpt",
            java.util.Map.of("bufferSize", "131072"),
            "java.lang.IllegalStateException: bufferSize too large",
            "java.lang.IllegalStateException: bufferSize too large\n\tat Demo.setup(Demo.java:7)");
    }

    private static BenchmarkResult passing() {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.lookup")
            .mode("thrpt")
            .primaryScore(2_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .target(1_000_000.0)
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

    private static BenchmarkResult untargeted(String method) {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark." + method)
            .mode("avgt")
            .primaryScore(1.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .build();
    }

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
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.QueryBenchmark.query")
            .mode("sample")
            .primaryScore(5.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .build();
    }
}
