package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how a measurement is compared against a registered target.
 *
 * <p>This is where a benchmark is judged. The outcomes that matter are the three-way
 * distinction between met, missed, and could-not-be-compared: a target evaluated in
 * the wrong unit, or against a metric that was never captured, must be reported as
 * unevaluated rather than quietly resolved one way or the other.</p>
 */
class BenchmarkResultTargetEvaluationTest {

    private static final String PRIMARY = BenchmarkTargetRegistry.PRIMARY_METRIC;

    private static final String HASH_BYTE_ARRAY =
        "com.eonmux.excalibur.bench.benchmark.HashBenchmark.hashByteArray";

    @Test
    @DisplayName("a throughput at or above its target is met")
    void throughputAtTargetIsMet() {
        BenchmarkResult result = throughput("lookup", 1_000_000.0, 1_000_000.0);

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.MET);
        assertThat(result.meetsTarget()).isTrue();
        assertThat(result.hasUnevaluatedTarget()).isFalse();
    }

    @Test
    @DisplayName("a throughput below its target is missed")
    void throughputBelowTargetIsMissed() {
        BenchmarkResult result = throughput("lookup", 999_999.0, 1_000_000.0);

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.MISSED);
        assertThat(result.meetsTarget()).isFalse();
    }

    @Test
    @DisplayName("the measurement is converted into the target's unit before comparing")
    void measurementIsConvertedIntoTheTargetUnit() {
        // Measured in ops/ms; the target is in ops/sec. 2000 ops/ms is 2M ops/sec.
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.lookup")
            .mode("thrpt")
            .primaryScore(2_000.0)
            .primaryScoreUnit("ops/ms")
            .higherIsBetter(true)
            .addTarget(new BenchmarkTargetRegistry.TargetConfig(
                ".*", PRIMARY, 1_000_000.0, true, MetricUnits.OPS_PER_SEC, null, "target"))
            .build();

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.MET);
        assertThat(result.targetEvaluations().get(0).comparedValue()).isEqualTo(2_000_000.0);
    }

    @Test
    @DisplayName("a target whose unit measures a different dimension is not evaluated")
    void incompatibleUnitsAreNotEvaluated() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.lookup")
            .mode("thrpt")
            .primaryScore(1_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .addTarget(new BenchmarkTargetRegistry.TargetConfig(
                ".*", PRIMARY, 10.0, false, "ms", null, "a latency target on a throughput"))
            .build();

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.NOT_EVALUABLE);
        assertThat(result.meetsTarget()).isFalse();
        assertThat(result.targetEvaluations().get(0).detail()).contains("incompatible");
        assertThat(result.targetEvaluations().get(0).comparedValue()).isNaN();
    }

    @Test
    @DisplayName("a target on a metric that was never captured is not evaluated")
    void missingMetricIsNotEvaluated() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.query")
            .mode("sample")
            .primaryScore(5.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .addTarget(new BenchmarkTargetRegistry.TargetConfig(
                ".*", "p99", 50.0, false, "ms", null, "p99 target"))
            .build();

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.NOT_EVALUABLE);
        assertThat(result.targetEvaluations().get(0).detail()).contains("'p99' was not captured");
    }

    @Test
    @DisplayName("a target on a captured secondary metric is compared against that metric")
    void secondaryMetricTargetUsesTheMetricNotTheScore() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.query")
            .mode("sample")
            .primaryScore(5_000.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .addMetric(new BenchmarkMetric("p99", 40.0, "ms"))
            .addTarget(new BenchmarkTargetRegistry.TargetConfig(
                ".*", "p99", 50.0, false, "ms", null, "p99 target"))
            .build();

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.MET);
        assertThat(result.targetEvaluations().get(0).comparedValue()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("a size-scaled target multiplies the rate by the swept parameter")
    void scaledTargetMultipliesByTheParameter() {
        // 2M ops/sec over a 1 KiB key is 2.048 GB/sec, which clears the 1 GB/sec target.
        BenchmarkResult result = scaledHashResult("1024", 2_000_000.0, "ops/sec");

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.MET);
        assertThat(result.targetEvaluations().get(0).comparedValue())
            .isEqualTo(2_000_000.0 * 1024);
    }

    @Test
    @DisplayName("a scaled target counts in the unit of work it declares, not always in bytes")
    void scaledTargetCountsInItsOwnUnitOfWork() {
        // One op is one distance over 1536 components: 1M ops/sec is 1.536G components/sec.
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.distance")
            .mode("thrpt")
            .primaryScore(1_000_000.0)
            .primaryScoreUnit("ops/s")
            .higherIsBetter(true)
            .parameter("dimension", "1536")
            .addTarget(new BenchmarkTargetRegistry.TargetConfig(".*", PRIMARY, 1_000_000_000.0,
                true, MetricUnits.COMPONENTS_PER_SEC, "dimension", "per-component rate"))
            .build();

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.MET);
        assertThat(result.targetEvaluations().get(0).comparedValue()).isEqualTo(1_536_000_000.0);
    }

    @Test
    @DisplayName("a scaled target whose unit is not a rate is not evaluated")
    void scalingIntoANonRateUnitIsNotEvaluated() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.distance")
            .mode("thrpt")
            .primaryScore(1_000_000.0)
            .primaryScoreUnit("ops/s")
            .higherIsBetter(true)
            .parameter("dimension", "1536")
            .addTarget(new BenchmarkTargetRegistry.TargetConfig(".*", PRIMARY, 10.0,
                false, "ms", "dimension", "a latency cannot be a scaled rate"))
            .build();

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.NOT_EVALUABLE);
        assertThat(result.targetEvaluations().get(0).detail()).contains("'ms' is not a rate");
    }

    @Test
    @DisplayName("a distance is judged per component, so one kernel is judged alike at every dimension")
    void distanceTargetsAreDimensionIndependent() {
        String cosine = "com.eonmux.excalibur.bench.benchmark.DistanceBenchmark.cosineSimilarity_single";

        // The same 2.56G components/sec kernel at both ends of the sweep: 40M ops/sec at 64
        // components and 1.67M ops/sec at 1536. A flat ops/sec target passed one and failed the other.
        assertThat(status(registered(cosine, "dimension", "64", 40_000_000.0)))
            .isEqualTo(BenchmarkResult.TargetStatus.MET);
        assertThat(status(registered(cosine, "dimension", "1536", 1_666_667.0)))
            .isEqualTo(BenchmarkResult.TargetStatus.MET);
        // What the reflective "Vector API" path measured: about 0.1G components/sec.
        assertThat(status(registered(cosine, "dimension", "1536", 68_000.0)))
            .isEqualTo(BenchmarkResult.TargetStatus.MISSED);
    }

    @Test
    @DisplayName("a tail scan is judged per entry scanned, not per scan")
    void tailScanTargetsAreJudgedPerEntry() {
        String l2 = "com.eonmux.excalibur.bench.benchmark.TailScanBenchmark.tailScan_l2";

        BenchmarkResult result = registered(l2, "tailSize", "50000", 30.0);

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.MET);
        assertThat(result.targetEvaluations().get(0).comparedValue()).isEqualTo(1_500_000.0);
        assertThat(result.targetEvaluations().get(0).target().unit()).isEqualTo(MetricUnits.ENTRIES_PER_SEC);
    }

    @Test
    @DisplayName("a scaled target whose parameter is missing is not evaluated")
    void scaledTargetWithoutItsParameterIsNotEvaluated() {
        BenchmarkResult result = withRegisteredTargets(BenchmarkResult.builder()
            .benchmarkName(HASH_BYTE_ARRAY)
            .mode("thrpt")
            .primaryScore(2_000_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true), HASH_BYTE_ARRAY)
            .build();

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.NOT_EVALUABLE);
        assertThat(result.targetEvaluations().get(0).detail()).contains("keySize");
    }

    @Test
    @DisplayName("a scaled target whose parameter is not a number is not evaluated")
    void scaledTargetWithNonNumericParameterIsNotEvaluated() {
        BenchmarkResult result = scaledHashResult("large", 2_000_000.0, "ops/sec");

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.NOT_EVALUABLE);
        assertThat(result.targetEvaluations().get(0).detail()).contains("not numeric");
    }

    @Test
    @DisplayName("a scaled target whose parameter is blank is not evaluated")
    void scaledTargetWithBlankParameterIsNotEvaluated() {
        BenchmarkResult result = scaledHashResult("   ", 2_000_000.0, "ops/sec");

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.NOT_EVALUABLE);
    }

    @Test
    @DisplayName("scaling a measurement that is not a throughput is not evaluated")
    void scalingANonThroughputIsNotEvaluated() {
        BenchmarkResult result = scaledHashResult("1024", 5.0, "ms/op");

        assertThat(status(result)).isEqualTo(BenchmarkResult.TargetStatus.NOT_EVALUABLE);
        assertThat(result.targetEvaluations().get(0).detail())
            .contains("scaling requires a throughput measurement");
    }

    @Test
    @DisplayName("a benchmark may carry several targets, each judged separately")
    void severalTargetsAreJudgedIndependently() {
        String name = "com.eonmux.excalibur.bench.benchmark.EndToEndQueryBenchmark.fullQueryPipeline";
        BenchmarkResult result = withRegisteredTargets(BenchmarkResult.builder()
            .benchmarkName(name)
            .mode("sample")
            .primaryScore(5.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .addMetric(new BenchmarkMetric("p50", 4.0, "ms"))
            .addMetric(new BenchmarkMetric("p99", 900.0, "ms")), name)
            .build();

        assertThat(result.targetEvaluations()).hasSize(2);
        assertThat(result.targetEvaluations()).extracting(e -> e.target().metric())
            .containsExactly("p50", "p99");
        assertThat(result.targetEvaluations()).extracting(BenchmarkResult.TargetEvaluation::status)
            .containsExactly(BenchmarkResult.TargetStatus.MET,
                BenchmarkResult.TargetStatus.MISSED);
        assertThat(result.meetsTarget()).isFalse();
    }

    @Test
    @DisplayName("a benchmark with no registered target has nothing to fail")
    void noTargetMeansNothingToFail() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.unregistered")
            .mode("thrpt")
            .primaryScore(1.0)
            .build();

        assertThat(result.hasTarget()).isFalse();
        assertThat(result.meetsTarget()).isTrue();
        assertThat(result.targetValue()).isZero();
    }

    @Test
    @DisplayName("noTarget() clears both explicit and registered targets")
    void noTargetClearsEverything() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.HashBenchmark.hashLong")
            .mode("thrpt")
            .primaryScore(1.0)
            .primaryScoreUnit("ops/sec")
            .target(999.0)
            .addTarget(new BenchmarkTargetRegistry.TargetConfig(
                ".*", PRIMARY, 1.0, true, "ops/sec", null, "x"))
            .noTarget()
            .build();

        assertThat(result.hasTarget()).isFalse();
    }

    @Test
    @DisplayName("metrics added in bulk are all retained and retrievable by name")
    void bulkMetricsAreRetained() {
        BenchmarkResult result = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.query")
            .mode("sample")
            .primaryScore(1.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .metrics(List.of(
                new BenchmarkMetric("p0", 1.0, "ms"),
                new BenchmarkMetric("p50", 2.0, "ms"),
                new BenchmarkMetric("p99", 3.0, "ms")))
            .build();

        assertThat(result.metrics()).hasSize(3);
        assertThat(result.getP0().value()).isEqualTo(1.0);
        assertThat(result.getP50().value()).isEqualTo(2.0);
        assertThat(result.getP99().value()).isEqualTo(3.0);
        assertThat(result.getMetric("absent")).isNull();
    }

    @Test
    @DisplayName("a met evaluation formats as a pass with the value and the threshold")
    void metEvaluationFormatsAsPass() {
        String formatted = throughput("lookup", 1_500_000.0, 1_000_000.0)
            .targetEvaluations().get(0).format();

        assertThat(formatted).contains("primary").contains("1500000.0000 ops/sec")
            .contains(">= 1000000.00 ops/sec").contains("PASS");
    }

    @Test
    @DisplayName("a missed evaluation formats as a failure")
    void missedEvaluationFormatsAsFail() {
        assertThat(throughput("lookup", 1.0, 1_000_000.0).targetEvaluations().get(0).format())
            .contains("FAIL");
    }

    @Test
    @DisplayName("an unevaluated evaluation says so and gives the reason instead of a verdict")
    void unevaluatedEvaluationFormatsWithItsReason() {
        String formatted = scaledHashResult("large", 2_000_000.0, "ops/sec")
            .targetEvaluations().get(0).format();

        assertThat(formatted).contains("NOT EVALUATED").contains("not numeric");
        assertThat(formatted).doesNotContain("PASS").doesNotContain("FAIL");
    }

    @Test
    @DisplayName("JSON records every target with its status, measurement and reason")
    void jsonRecordsEveryTarget() {
        String json = scaledHashResult("large", 2_000_000.0, "ops/sec").toJson();

        assertThat(json).contains("\"targets\": [");
        assertThat(json).contains("\"status\": \"NOT_EVALUABLE\"");
        assertThat(json).contains("\"measured\": null");
        assertThat(json).contains("\"detail\": \"");
        assertThat(json).contains("\"higherIsBetter\": true");
    }

    @Test
    @DisplayName("JSON records the parameters, metrics and execution shape of a run")
    void jsonRecordsTheRunShape() {
        String json = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.query")
            .mode("sample")
            .primaryScore(5.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .parameter("dimension", "128")
            .addMetric(new BenchmarkMetric("p50", 4.0, "ms"))
            .durationMs(1234)
            .iterations(5)
            .forks(2)
            .build()
            .toJson();

        assertThat(json).contains("\"parameters\": {").contains("\"dimension\": \"128\"");
        assertThat(json).contains("\"metrics\": [").contains("\"name\": \"p50\"");
        assertThat(json).contains("\"durationMs\": 1234");
        assertThat(json).contains("\"iterations\": 5");
        assertThat(json).contains("\"forks\": 2");
    }

    @Test
    @DisplayName("the summary line carries the parameters, the score and each verdict")
    void summaryLineCarriesEverythingNeededToReadTheRow() {
        String summary = BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark.lookup")
            .mode("thrpt")
            .primaryScore(1_500_000.0)
            .primaryScoreError(1_000.0)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .parameter("loadFactor", "0.9")
            .target(1_000_000.0)
            .build()
            .formatSummary();

        assertThat(summary).startsWith("lookup [loadFactor=0.9]: 1500000.00 ops/sec ± 1000.00");
        assertThat(summary).contains("PASS");
    }

    // ---- fixtures ----

    private static BenchmarkResult.TargetStatus status(BenchmarkResult result) {
        return result.targetEvaluations().get(0).status();
    }

    private static BenchmarkResult throughput(String method, double score, double target) {
        return BenchmarkResult.builder()
            .benchmarkName("com.eonmux.excalibur.bench.benchmark.DemoBenchmark." + method)
            .mode("thrpt")
            .primaryScore(score)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .target(target)
            .build();
    }

    /** Matches the registry's size-scaled hash target, which scales by {@code keySize}. */
    private static BenchmarkResult scaledHashResult(String keySize, double score, String unit) {
        return withRegisteredTargets(BenchmarkResult.builder()
            .benchmarkName(HASH_BYTE_ARRAY)
            .mode("thrpt")
            .primaryScore(score)
            .primaryScoreUnit(unit)
            .higherIsBetter(!unit.contains("/op"))
            .parameter("keySize", keySize), HASH_BYTE_ARRAY)
            .build();
    }

    private static BenchmarkResult registered(String name, String parameter, String value, double opsPerSec) {
        return withRegisteredTargets(BenchmarkResult.builder()
            .benchmarkName(name)
            .mode("thrpt")
            .primaryScore(opsPerSec)
            .primaryScoreUnit("ops/s")
            .higherIsBetter(true)
            .parameter(parameter, value), name)
            .build();
    }

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
}
