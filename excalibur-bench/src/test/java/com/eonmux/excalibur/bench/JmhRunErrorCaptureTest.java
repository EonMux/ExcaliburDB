package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.benchmark.MappedFileBenchmark;
import com.eonmux.excalibur.bench.metric.BenchmarkResult;
import com.eonmux.excalibur.bench.metric.BenchmarkResultSet;
import com.eonmux.excalibur.bench.metric.ErroredBenchmark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs real JMH, in a real fork, over a parameter combination whose {@code @Setup}
 * throws, and checks that the combination comes back as an error rather than vanishing.
 *
 * <p>{@link MappedFileBenchmark} rejects a transfer buffer larger than its file. Asking
 * JMH for {@code bufferSize=131072} against a 64 KiB file therefore makes exactly one of
 * the two requested combinations fail in setup, which is the situation
 * {@code Runner.run()} reports by omission: its result collection simply holds one entry
 * fewer.</p>
 *
 * <p>The run is kept to a single 50 ms measurement per combination, and the test JVM
 * ignores JMH's machine-wide lock (see the {@code test} task) so it neither waits on nor
 * blocks a benchmark run elsewhere on the host.</p>
 */
class JmhRunErrorCaptureTest {

    @Test
    @DisplayName("a combination whose @Setup throws is reported as errored, with the exception")
    void setupFailureIsCapturedFromARealFork() throws Exception {
        Options options = new OptionsBuilder()
            .include(MappedFileBenchmark.class.getName() + "\\.sequentialRead_bytes$")
            .param("fileSize", "65536")
            .param("bufferSize", "4096", "131072")
            .mode(Mode.Throughput)
            .warmupIterations(0)
            .measurementIterations(1)
            .measurementTime(TimeValue.milliseconds(50))
            .forks(1)
            .jvmArgsAppend(ForkedJvm.flags("-Xmx256m"))
            .shouldFailOnError(false)
            .build();

        BenchmarkResultSet run = JmhRun.execute(options, "error capture");

        assertThat(run.results())
            .singleElement()
            .extracting(BenchmarkResult::parameters)
            .isEqualTo(Map.of("fileSize", "65536", "bufferSize", "4096"));

        assertThat(run.erroredBenchmarks()).singleElement().satisfies(errored -> {
            assertThat(errored.benchmarkName()).endsWith("MappedFileBenchmark.sequentialRead_bytes");
            assertThat(errored.mode()).isEqualTo("thrpt");
            assertThat(errored.parameters())
                .isEqualTo(Map.of("fileSize", "65536", "bufferSize", "131072"));
            assertThat(errored.message())
                .contains("IllegalStateException")
                .contains("bufferSize must lie in");
        });
        assertThat(run.allPassed()).isFalse();
    }

    @Test
    @DisplayName("an in-process run records the failing combination the same way")
    void setupFailureIsCapturedInProcess() throws Exception {
        Options options = new OptionsBuilder()
            .include(MappedFileBenchmark.class.getName() + "\\.sequentialRead_bytes$")
            .param("fileSize", "65536")
            .param("bufferSize", "4096", "131072")
            .mode(Mode.Throughput)
            .warmupIterations(0)
            .measurementIterations(1)
            .measurementTime(TimeValue.milliseconds(50))
            .forks(0)
            .shouldFailOnError(false)
            .build();

        BenchmarkResultSet run = JmhRun.execute(options, "in-process");

        assertThat(run.results()).hasSize(1);
        assertThat(run.erroredBenchmarks()).singleElement()
            .extracting(ErroredBenchmark::message)
            .asString()
            .contains("bufferSize must lie in");
    }
}
