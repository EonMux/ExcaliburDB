package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.metric.BenchmarkResultSet;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

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
     * <p>Results come from JMH's own {@link org.openjdk.jmh.runner.Runner#run()} return
     * value rather than from scraped console output: the benchmarks execute in a forked
     * JVM whose stdout this process never sees. The combinations that value omits
     * because they threw are recovered by {@link JmhRun} and reported as errored.</p>
     *
     * <p>A standard run is gated on errors too, so the full set is built whether or not
     * the caller writes reports from it.</p>
     *
     * @param pattern the JMH include pattern
     * @param capture whether the caller will report on the results
     * @return the structured results of the run
     * @throws RunnerException if JMH fails to run the benchmarks
     */
    private static BenchmarkResultSet runBenchmarks(String pattern, boolean capture)
            throws RunnerException {
        return JmhRun.execute(buildOptions(pattern), "ExcaliburDB Benchmark Run");
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
            .jvmArgsAppend(ForkedJvm.flags(
                "-Xms1g",
                "-Xmx1g"
            ))
            .shouldDoGC(true)
            .build();
    }

    private BenchmarkResultsCaptureRunner() {
        // Entry point only
    }
}
