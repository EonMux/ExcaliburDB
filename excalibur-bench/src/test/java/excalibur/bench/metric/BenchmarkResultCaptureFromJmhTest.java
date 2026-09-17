package excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.SampleTimeResult;
import org.openjdk.jmh.results.ThroughputResult;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.runner.WorkloadParams;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.util.SampleBuffer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the conversion of JMH's own results into this project's model.
 *
 * <p>This is the authoritative capture path: everything a report says, and every
 * target verdict, is derived from what this conversion produced. The results here are
 * synthesised from JMH's public result API rather than measured, so the conversion can
 * be checked against numbers that are known exactly instead of against a run whose
 * output moves every time.</p>
 */
class BenchmarkResultCaptureFromJmhTest {

    private static final String THROUGHPUT_BENCHMARK =
        "excalibur.bench.benchmark.DemoBenchmark.lookup";
    private static final String LATENCY_BENCHMARK =
        "excalibur.bench.benchmark.EndToEndQueryBenchmark.fullQueryPipeline";

    @Test
    @DisplayName("a throughput run keeps its name, mode, score and unit")
    void throughputRunIsCarriedOverIntact() {
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(
            throughputRun(THROUGHPUT_BENCHMARK, 1_000, 1));

        assertThat(result.benchmarkName()).isEqualTo(THROUGHPUT_BENCHMARK);
        assertThat(result.methodName()).isEqualTo("lookup");
        assertThat(result.mode()).isEqualTo(Mode.Throughput.shortLabel());
        assertThat(result.primaryScoreUnit()).isEqualTo("ops/s");
        assertThat(result.primaryScore()).isEqualTo(1_000.0);
    }

    @Test
    @DisplayName("a throughput unit means higher is better, without consulting the mode")
    void throughputIsHigherIsBetter() {
        assertThat(BenchmarkResultCapture.fromRunResult(
            throughputRun(THROUGHPUT_BENCHMARK, 1_000, 1)).higherIsBetter()).isTrue();
    }

    @Test
    @DisplayName("a time-per-operation unit means lower is better")
    void latencyIsLowerIsBetter() {
        assertThat(BenchmarkResultCapture.fromRunResult(
            sampleRun(LATENCY_BENCHMARK, sampleValuesMicros(1, 100))).higherIsBetter()).isFalse();
    }

    @Test
    @DisplayName("a throughput score is also attached as a named throughput metric")
    void throughputIsAttachedAsAMetric() {
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(
            throughputRun(THROUGHPUT_BENCHMARK, 2_500, 1));

        assertThat(result.getThroughput()).isNotNull();
        assertThat(result.getThroughput().value()).isEqualTo(2_500.0);
        assertThat(result.getThroughput().unit()).isEqualTo("ops/sec");
    }

    @Test
    @DisplayName("a latency run carries no throughput metric")
    void latencyRunHasNoThroughputMetric() {
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(
            sampleRun(LATENCY_BENCHMARK, sampleValuesMicros(1, 100)));

        assertThat(result.getThroughput()).isNull();
    }

    @Test
    @DisplayName("a sampling run's distribution becomes p0/p50/p90/p99 metrics in milliseconds")
    void samplingRunYieldsPercentilesInMilliseconds() {
        // 1000 samples of 1..1000 microseconds, so p50 is around 0.5 ms and p99 near 1 ms.
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(
            sampleRun(LATENCY_BENCHMARK, sampleValuesMicros(1, 1_000)));

        assertThat(result.getP0()).isNotNull();
        assertThat(result.getP50()).isNotNull();
        assertThat(result.getMetric("p90")).isNotNull();
        assertThat(result.getP99()).isNotNull();
        assertThat(result.getP50().unit()).isEqualTo("ms");
        assertThat(result.getP50().value()).isBetween(0.4, 0.6);
        assertThat(result.getP99().value()).isBetween(0.9, 1.1);
        assertThat(result.getP0().value()).isLessThanOrEqualTo(result.getP50().value());
        assertThat(result.getP50().value()).isLessThanOrEqualTo(result.getP99().value());
    }

    @Test
    @DisplayName("a distribution JMH reports in microseconds is converted, not relabelled")
    void samplingRunInMicrosecondsIsConverted() {
        // JMH picks its own output time unit. Copying its numbers across while calling
        // them milliseconds would read a 0.5 ms median as 500 ms -- or, the other way
        // round, let a run that missed a 10 ms target report as if it met it.
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(
            sampleRun(LATENCY_BENCHMARK, sampleValuesMicros(1, 1_000), TimeUnit.MICROSECONDS));

        assertThat(result.getP50().unit()).isEqualTo("ms");
        assertThat(result.getP50().value()).isBetween(0.4, 0.6);
        assertThat(result.getP99().value()).isBetween(0.9, 1.1);
    }

    @Test
    @DisplayName("percentiles carry the number of samples they were computed from")
    void percentilesCarryTheirSampleCount() {
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(
            sampleRun(LATENCY_BENCHMARK, sampleValuesMicros(1, 500)));

        assertThat(result.getP50().sampleCount()).isEqualTo(500);
    }

    @Test
    @DisplayName("a throughput run gets no percentile metrics, because it measured no distribution")
    void throughputRunHasNoPercentiles() {
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(
            throughputRun(THROUGHPUT_BENCHMARK, 1_000, 1));

        assertThat(result.getP50()).isNull();
        assertThat(result.getP99()).isNull();
    }

    @Test
    @DisplayName("JMH's own parameters reach the result, so scaled targets can be evaluated")
    void workloadParametersAreCarriedOver() {
        BenchmarkParams params = params(
            "excalibur.bench.benchmark.HashBenchmark.hashByteArray",
            Mode.Throughput, 2, 3, 2, workload("keySize", "1024"));

        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(
            throughputRun(params, 1_000_000, 1));

        assertThat(result.parameters()).containsEntry("keySize", "1024");
    }

    @Test
    @DisplayName("the registry's targets are attached to the benchmarks they name")
    void registeredTargetsAreAttached() {
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(
            sampleRun(LATENCY_BENCHMARK, sampleValuesMicros(1, 1_000)));

        assertThat(result.hasTarget()).isTrue();
        assertThat(result.targetEvaluations()).extracting(e -> e.target().metric())
            .containsExactly("p50", "p99");
        assertThat(result.meetsTarget())
            .as("sub-millisecond latencies clear the 10 ms and 50 ms targets")
            .isTrue();
    }

    @Test
    @DisplayName("iterations and forks are taken from the run's own configuration")
    void iterationsAndForksComeFromTheRun() {
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(throughputRun(
            params(THROUGHPUT_BENCHMARK, Mode.Throughput, 2, 5, 3, new WorkloadParams()),
            1_000, 1));

        assertThat(result.iterations()).isEqualTo(5);
        assertThat(result.forks()).isEqualTo(3);
    }

    @Test
    @DisplayName("the estimated duration counts warmup and measurement across every fork")
    void durationCoversWarmupMeasurementAndForks() {
        // 2 warmup and 5 measurement iterations of one second each, over 3 forks.
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(throughputRun(
            params(THROUGHPUT_BENCHMARK, Mode.Throughput, 2, 5, 3, new WorkloadParams()),
            1_000, 1));

        assertThat(result.durationMs()).isEqualTo((2 + 5) * 1_000L * 3);
    }

    @Test
    @DisplayName("a run with no forks declared still reports a positive duration")
    void zeroForksStillGivesADuration() {
        BenchmarkResult result = BenchmarkResultCapture.fromRunResult(throughputRun(
            params(THROUGHPUT_BENCHMARK, Mode.Throughput, 1, 1, 0, new WorkloadParams()),
            1_000, 1));

        assertThat(result.durationMs()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("a collection of runs converts one result per run")
    void everyRunIsConverted() {
        List<BenchmarkResult> results = BenchmarkResultCapture.fromRunResults(List.of(
            throughputRun("excalibur.bench.benchmark.DemoBenchmark.a", 1_000, 1),
            throughputRun("excalibur.bench.benchmark.DemoBenchmark.b", 2_000, 1)));

        assertThat(results).extracting(BenchmarkResult::methodName).containsExactly("a", "b");
    }

    @Test
    @DisplayName("an empty run collection converts to an empty list rather than failing")
    void anEmptyCollectionConvertsToNothing() {
        assertThat(BenchmarkResultCapture.fromRunResults(List.of())).isEmpty();
    }

    @Test
    @DisplayName("a captured result set carries the results it was built from")
    void createResultSetWrapsTheResults() {
        List<BenchmarkResult> results = BenchmarkResultCapture.fromRunResults(
            List.of(throughputRun(THROUGHPUT_BENCHMARK, 1_000, 1)));

        BenchmarkResultSet set = BenchmarkResultCapture.createResultSet("captured", results);

        assertThat(set.name()).isEqualTo("captured");
        assertThat(set.resultCount()).isEqualTo(1);
        assertThat(set.startTime()).isNotNull();
    }

    // ---- JMH result synthesis ----

    private static long[] sampleValuesMicros(int from, int to) {
        long[] values = new long[to - from + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = (from + i) * 1_000L;
        }
        return values;
    }

    private static RunResult throughputRun(String benchmark, long opsPerSecond, int iterations) {
        return throughputRun(
            params(benchmark, Mode.Throughput, 2, Math.max(iterations, 1), 1, new WorkloadParams()),
            opsPerSecond, iterations);
    }

    private static RunResult throughputRun(
            BenchmarkParams params, long opsPerSecond, int iterations) {
        IterationParams measurement = params.getMeasurement();
        IterationResult iteration = new IterationResult(params, measurement, null);
        iteration.addResult(new ThroughputResult(
            ResultRole.PRIMARY, params.getBenchmark(),
            opsPerSecond, TimeUnit.SECONDS.toNanos(1), TimeUnit.SECONDS));
        return new RunResult(params,
            List.of(new org.openjdk.jmh.results.BenchmarkResult(params, List.of(iteration))));
    }

    private static RunResult sampleRun(String benchmark, long[] sampleNanos) {
        return sampleRun(benchmark, sampleNanos, TimeUnit.MILLISECONDS);
    }

    private static RunResult sampleRun(String benchmark, long[] sampleNanos, TimeUnit outputUnit) {
        BenchmarkParams params =
            params(benchmark, Mode.SampleTime, 2, 3, 1, new WorkloadParams());
        SampleBuffer buffer = new SampleBuffer();
        for (long value : sampleNanos) {
            buffer.add(value);
        }
        IterationResult iteration = new IterationResult(params, params.getMeasurement(), null);
        iteration.addResult(new SampleTimeResult(
            ResultRole.PRIMARY, benchmark, buffer, outputUnit));
        return new RunResult(params,
            List.of(new org.openjdk.jmh.results.BenchmarkResult(params, List.of(iteration))));
    }

    private static WorkloadParams workload(String key, String value) {
        WorkloadParams workload = new WorkloadParams();
        workload.put(key, value, 0);
        return workload;
    }

    private static BenchmarkParams params(String benchmark, Mode mode, int warmupIterations,
                                          int measurementIterations, int forks,
                                          WorkloadParams workload) {
        return new BenchmarkParams(
            benchmark,
            benchmark + "_jmhTest",
            false,
            1,
            new int[]{1},
            List.of("group"),
            forks,
            0,
            new IterationParams(IterationType.WARMUP, warmupIterations, TimeValue.seconds(1), 1),
            new IterationParams(
                IterationType.MEASUREMENT, measurementIterations, TimeValue.seconds(1), 1),
            mode,
            workload,
            mode == Mode.Throughput ? TimeUnit.SECONDS : TimeUnit.MILLISECONDS,
            1,
            "jvm",
            List.of(),
            "21",
            "vm",
            "21",
            "1.37",
            TimeValue.minutes(10));
    }
}
