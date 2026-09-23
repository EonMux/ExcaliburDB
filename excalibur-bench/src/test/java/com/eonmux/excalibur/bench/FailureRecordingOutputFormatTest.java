package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.metric.ErroredBenchmark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.results.ThroughputResult;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.runner.WorkloadParams;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the output format that turns JMH's console protocol into a record of which
 * benchmark-parameter combinations errored.
 *
 * <p>Each test replays the exact call sequence JMH's runner makes. For a combination
 * that throws, {@code Runner} calls {@code startBenchmark}, prints {@code <failure>}
 * followed by the stack traces, and never calls {@code endBenchmark}; the combination is
 * then simply absent from {@code Runner.run()}'s return value.</p>
 */
class FailureRecordingOutputFormatTest {

    private static final String BENCHMARK =
        "com.eonmux.excalibur.bench.benchmark.DemoBenchmark.lookup";

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final FailureRecordingOutputFormat format = new FailureRecordingOutputFormat(
        OutputFormatFactory.createFormatInstance(
            new PrintStream(console, true, StandardCharsets.UTF_8), VerboseMode.NORMAL));

    @Test
    @DisplayName("a combination whose setup threw is recorded with the exception's message")
    void setupFailureIsRecordedWithItsMessage() {
        BenchmarkParams failing = params(workload("size", "64"));

        format.startBenchmark(failing);
        format.println("");
        format.println("<failure>");
        format.println("");
        format.println("java.lang.IllegalStateException: bufferSize must lie in [8, fileSize]\n"
            + "\tat com.eonmux.excalibur.bench.benchmark.DemoBenchmark.setup(DemoBenchmark.java:42)\n");
        format.println("");

        List<ErroredBenchmark> errored = format.erroredBenchmarks();
        assertThat(errored).hasSize(1);
        ErroredBenchmark entry = errored.get(0);
        assertThat(entry.benchmarkName()).isEqualTo(BENCHMARK);
        assertThat(entry.mode()).isEqualTo("thrpt");
        assertThat(entry.parameters()).isEqualTo(Map.of("size", "64"));
        assertThat(entry.message())
            .isEqualTo("java.lang.IllegalStateException: bufferSize must lie in [8, fileSize]");
        assertThat(entry.detail()).contains("DemoBenchmark.setup(DemoBenchmark.java:42)");
    }

    @Test
    @DisplayName("a combination that completed is not recorded")
    void completedCombinationIsNotErrored() {
        BenchmarkParams passing = params(workload("size", "64"));

        format.startBenchmark(passing);
        format.println("# Warmup Iteration   1: 10.0 ops/s");
        format.endBenchmark(result(passing));

        assertThat(format.erroredBenchmarks()).isEmpty();
        assertThat(format.isErrored(passing)).isFalse();
    }

    @Test
    @DisplayName("only the combination that failed is recorded when its neighbours pass")
    void onlyTheFailingCombinationIsRecorded() {
        BenchmarkParams first = params(workload("size", "64"));
        BenchmarkParams second = params(workload("size", "1048576"));
        BenchmarkParams third = params(workload("size", "4096"));

        format.startBenchmark(first);
        format.endBenchmark(result(first));
        format.startBenchmark(second);
        format.println("<failure>");
        format.println("java.lang.ArithmeticException: / by zero\n\tat X.y(X.java:1)");
        format.startBenchmark(third);
        format.println("# Measurement Iteration 1: 5.0 ops/s");
        format.endBenchmark(result(third));

        assertThat(format.erroredBenchmarks())
            .singleElement()
            .satisfies(entry -> {
                assertThat(entry.parameters()).isEqualTo(Map.of("size", "1048576"));
                assertThat(entry.message()).isEqualTo("java.lang.ArithmeticException: / by zero");
                assertThat(entry.detail()).doesNotContain("Measurement Iteration");
            });
        assertThat(format.isErrored(second)).isTrue();
        assertThat(format.isErrored(first)).isFalse();
        assertThat(format.isErrored(third)).isFalse();
    }

    @Test
    @DisplayName("an iteration that threw after earlier iterations completed is still an error")
    void partialResultAfterAFailureIsStillAnError() {
        BenchmarkParams params = params(workload("size", "64"));

        format.startBenchmark(params);
        format.println("# Measurement Iteration 1: 5.0 ops/s");
        format.println("<failure>");
        format.println("java.lang.IllegalStateException: iteration 2 exploded");
        // In-process runs still end the benchmark with whatever iterations completed.
        format.endBenchmark(result(params));

        assertThat(format.isErrored(params)).isTrue();
        assertThat(format.erroredBenchmarks()).singleElement()
            .extracting(ErroredBenchmark::message)
            .isEqualTo("java.lang.IllegalStateException: iteration 2 exploded");
    }

    @Test
    @DisplayName("a forked VM that died is recorded, with its captured stderr as the detail")
    void forkedVmFailureIsRecorded() {
        BenchmarkParams params = params(new WorkloadParams());

        format.startBenchmark(params);
        format.println("# Fork: 1 of 1");
        format.println("<forked VM failed with exit code 134>");
        format.println("<stdout last='20 lines'>");
        format.println("</stdout>");
        format.println("<stderr last='20 lines'>");
        format.println("# A fatal error has been detected by the Java Runtime Environment");
        format.println("</stderr>");

        assertThat(format.erroredBenchmarks()).singleElement().satisfies(entry -> {
            assertThat(entry.message()).isEqualTo("forked VM failed with exit code 134");
            assertThat(entry.detail()).contains("A fatal error has been detected");
            assertThat(entry.parameters()).isEmpty();
        });
    }

    @Test
    @DisplayName("a combination that started and silently produced nothing is still an error")
    void startedWithoutResultOrFailureTextIsAnError() {
        BenchmarkParams params = params(workload("size", "64"));

        format.startBenchmark(params);
        format.println("# Fork: 1 of 1");

        assertThat(format.erroredBenchmarks()).singleElement()
            .extracting(ErroredBenchmark::message)
            .asString()
            .contains("no result");
    }

    @Test
    @DisplayName("every call is still forwarded to the console format")
    void outputIsForwarded() {
        BenchmarkParams params = params(workload("size", "64"));

        format.startBenchmark(params);
        format.println("<failure>");
        format.println("java.lang.IllegalStateException: visible on the console");
        format.flush();

        assertThat(console.toString(StandardCharsets.UTF_8))
            .contains("DemoBenchmark.lookup")
            .contains("<failure>")
            .contains("visible on the console");
    }

    @Test
    @DisplayName("a runaway stack trace is truncated rather than held in full")
    void detailIsBounded() {
        BenchmarkParams params = params(workload("size", "64"));

        format.startBenchmark(params);
        format.println("<failure>");
        format.println("java.lang.StackOverflowError");
        for (int i = 0; i < 100_000; i++) {
            format.println("\tat Recursive.call(Recursive.java:" + i + ")");
        }

        ErroredBenchmark entry = format.erroredBenchmarks().get(0);
        assertThat(entry.detail().length())
            .isLessThanOrEqualTo(FailureRecordingOutputFormat.MAX_DETAIL_CHARS + 64);
        assertThat(entry.detail()).endsWith("[truncated]");
    }

    // ---- helpers ----

    private static WorkloadParams workload(String key, String value) {
        WorkloadParams workload = new WorkloadParams();
        workload.put(key, value, 0);
        return workload;
    }

    private static BenchmarkResult result(BenchmarkParams params) {
        IterationResult iteration = new IterationResult(params, params.getMeasurement(), null);
        iteration.addResult(new ThroughputResult(
            ResultRole.PRIMARY, params.getBenchmark(), 1_000, TimeUnit.SECONDS.toNanos(1),
            TimeUnit.SECONDS));
        return new BenchmarkResult(params, List.of(iteration));
    }

    private static BenchmarkParams params(WorkloadParams workload) {
        return new BenchmarkParams(
            BENCHMARK,
            BENCHMARK + "_jmhTest",
            false,
            1,
            new int[]{1},
            List.of("group"),
            1,
            0,
            new IterationParams(IterationType.WARMUP, 1, TimeValue.seconds(1), 1),
            new IterationParams(IterationType.MEASUREMENT, 1, TimeValue.seconds(1), 1),
            Mode.Throughput,
            workload,
            TimeUnit.SECONDS,
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
