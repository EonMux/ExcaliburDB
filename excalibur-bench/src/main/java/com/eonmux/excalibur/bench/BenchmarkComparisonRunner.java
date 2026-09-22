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
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

/**
 * Entry point for running benchmarks and comparing them against a baseline.
 *
 * <p>This class is the JMH half of the job: it knows how to configure and start a
 * run. Everything decided around that run - argument handling, baseline loading,
 * regression detection and the exit code - lives in
 * {@link BenchmarkComparisonCommand}, where it can be tested without waiting minutes
 * for a measurement.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run benchmarks, capture results, and save as a new baseline
 * java -jar excalibur-bench.jar --run --output ./baseline/
 *
 * # Run benchmarks and compare against a saved baseline
 * java -jar excalibur-bench.jar --compare --baseline ./baseline/benchmark_results.json
 *
 * # Compare two saved result files
 * java -jar excalibur-bench.jar --diff ./old_results.json ./new_results.json
 * </pre>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkComparisonRunner {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Command-line entry point.
     *
     * @param args the command-line arguments
     * @throws Exception if the benchmark run or a report write fails
     */
    public static void main(String[] args) throws Exception {
        int exitCode = new BenchmarkComparisonCommand(
            BenchmarkComparisonRunner::runBenchmarks, System.out, System.err).execute(args);
        if (exitCode != BenchmarkComparisonCommand.EXIT_OK) {
            System.exit(exitCode);
        }
    }

    /**
     * Runs the benchmarks matching a pattern and returns what JMH measured.
     *
     * <p>Results are taken from JMH's own return value. A failure inside JMH
     * propagates to the caller: an earlier implementation returned from inside a
     * {@code finally} block, which discarded the exception and compared an empty
     * result set against the baseline, reporting "no regressions" for a run in which
     * nothing executed.</p>
     *
     * @param pattern the JMH include pattern
     * @return the structured results of the run
     * @throws RunnerException if JMH fails to run the benchmarks
     */
    private static BenchmarkResultSet runBenchmarks(String pattern) throws RunnerException {
        Options options = new OptionsBuilder()
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

        Instant startTime = Instant.now();
        Collection<RunResult> runResults = new Runner(options).run();
        Instant endTime = Instant.now();

        List<BenchmarkResult> results = BenchmarkResultCapture.fromRunResults(runResults);
        return BenchmarkResultSet.builder()
            .name("ExcaliburDB Benchmark Run " + TIMESTAMP_FORMAT.format(startTime))
            .startTime(startTime)
            .endTime(endTime)
            .results(results)
            .build();
    }

    private BenchmarkComparisonRunner() {
        // Entry point only
    }
}
