package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.metric.BenchmarkResult;
import com.eonmux.excalibur.bench.metric.BenchmarkResultCapture;
import com.eonmux.excalibur.bench.metric.BenchmarkResultSet;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Defaults;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.Options;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Runs JMH and returns everything the run established: the measurements, and the
 * combinations that errored instead of producing one.
 *
 * <p>Both runners in this module go through here, so neither can report a run from
 * {@code Runner.run()}'s return value alone. That value lists only the combinations that
 * finished; one whose setup or iteration threw is not in it, and a report built from it
 * shows a clean table with a row missing. {@link FailureRecordingOutputFormat} watches
 * the run and supplies the missing rows as {@link com.eonmux.excalibur.bench.metric.ErroredBenchmark
 * errored} entries.</p>
 *
 * <p>A result JMH did return for a combination that also printed a failure is dropped:
 * it holds only the iterations completed before the exception, so it is reported as
 * errored rather than judged against a target.</p>
 */
final class JmhRun {

    /**
     * Runs the benchmarks the options select.
     *
     * @param options the JMH options, including the include pattern
     * @param runName the name recorded on the result set
     * @return the measurements and the errored combinations
     * @throws RunnerException if JMH could not run at all, e.g. no benchmark matched
     */
    static BenchmarkResultSet execute(Options options, String runName) throws RunnerException {
        FailureRecordingOutputFormat output = new FailureRecordingOutputFormat(
            OutputFormatFactory.createFormatInstance(
                System.out, options.verbosity().orElse(Defaults.VERBOSITY)));

        Instant startTime = Instant.now();
        Collection<RunResult> runResults = new Runner(options, output).run();
        Instant endTime = Instant.now();

        List<RunResult> completed = new ArrayList<>(runResults.size());
        for (RunResult runResult : runResults) {
            if (!output.isErrored(runResult.getParams())) {
                completed.add(runResult);
            }
        }
        List<BenchmarkResult> results = BenchmarkResultCapture.fromRunResults(completed);

        return BenchmarkResultSet.builder()
            .name(runName)
            .startTime(startTime)
            .endTime(endTime)
            .results(results)
            .errored(output.erroredBenchmarks())
            .build();
    }

    private JmhRun() {
        // Static entry point only
    }
}
