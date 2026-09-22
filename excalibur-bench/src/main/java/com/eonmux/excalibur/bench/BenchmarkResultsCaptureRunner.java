package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.metric.BenchmarkResult;
import com.eonmux.excalibur.bench.metric.BenchmarkResultCapture;
import com.eonmux.excalibur.bench.metric.BenchmarkResultSet;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Entry point for running benchmarks with structured result capture.
 *
 * <p>This class configures and starts JMH. What the resulting numbers mean - which
 * targets were met, which could not be evaluated, and what exit code follows - lives
 * in {@link BenchmarkCaptureCommand}.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all benchmarks and capture results
 * java -jar excalibur-bench.jar --capture
 *
 * # Run a specific benchmark pattern
 * java -jar excalibur-bench.jar --capture --pattern ".*PointLookup.*"
 *
 * # Output to a specific directory
 * java -jar excalibur-bench.jar --capture --output ./benchmark-results/
 * </pre>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkResultsCaptureRunner {

    /**
     * Command-line entry point.
     *
     * @param args the command-line arguments
     * @throws Exception if the benchmark run or a report write fails
     */
    public static void main(String[] args) throws Exception {
        int exitCode = new BenchmarkCaptureCommand(
            BenchmarkResultsCaptureRunner::runBenchmarks, System.out, System.err).execute(args);
        if (exitCode != BenchmarkCaptureCommand.EXIT_OK) {
            System.exit(exitCode);
        }
    }

    /**
     * Runs the benchmarks matching a pattern.
     *
     * <p>Results come from JMH's own {@link Runner#run()} return value rather than
     * from scraped console output: the benchmarks execute in a forked JVM whose stdout
     * this process never sees, so no amount of stream redirection here could have
     * captured them.</p>
     *
     * @param pattern the JMH include pattern
     * @param capture whether the caller will report on the results
     * @return the structured results of the run
     * @throws RunnerException if JMH fails to run the benchmarks
     */
    private static BenchmarkResultSet runBenchmarks(String pattern, boolean capture)
            throws RunnerException {
        Instant startTime = Instant.now();
        Collection<RunResult> runResults = new Runner(buildOptions(pattern)).run();
        Instant endTime = Instant.now();

        if (!capture) {
            // A standard run reports through JMH's own console output; nothing here
            // reads the structured form, so it is not worth building.
            return BenchmarkResultSet.builder()
                .name("ExcaliburDB Benchmark Run")
                .startTime(startTime)
                .endTime(endTime)
                .build();
        }

        List<BenchmarkResult> results = BenchmarkResultCapture.fromRunResults(runResults);
        return BenchmarkResultSet.builder()
            .name("ExcaliburDB Benchmark Run")
            .startTime(startTime)
            .endTime(endTime)
            .results(results)
            .build();
    }

    /**
     * Builds the JMH options shared by both run modes.
     *
     * <p>{@code --add-modules=jdk.incubator.vector} must reach the forked JVM: several
     * benchmarks use the Vector API and fail to start without it, exactly as the Gradle
     * benchmark tasks already declare.</p>
     */
    private static Options buildOptions(String pattern) {
        return new OptionsBuilder()
            .include(pattern)
            .warmupIterations(2)
            .warmupTime(TimeValue.seconds(1))
            .measurementIterations(3)
            .measurementTime(TimeValue.seconds(1))
            .forks(1)
            .jvmArgsAppend(
                "--enable-preview",
                "--enable-native-access=ALL-UNNAMED",
                "--add-modules=jdk.incubator.vector",
                "-Xms1g",
                "-Xmx1g"
            )
            .shouldDoGC(true)
            .build();
    }

    private BenchmarkResultsCaptureRunner() {
        // Entry point only
    }
}
