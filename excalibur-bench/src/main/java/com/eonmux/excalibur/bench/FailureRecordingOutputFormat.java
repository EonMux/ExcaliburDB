package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.bench.metric.ErroredBenchmark;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.format.OutputFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A JMH {@link OutputFormat} that forwards everything to the console format and, on the
 * side, records which benchmark-parameter combinations errored and why.
 *
 * <p>JMH reports a failed combination only on the console. For every combination it
 * calls {@link #startBenchmark}; when setup, an iteration or the forked JVM fails it
 * prints a marker such as {@code <failure>} or {@code <forked VM failed ...>} followed by
 * the stack traces, drops whatever was measured, and never calls {@link #endBenchmark}.
 * {@code Runner.run()} then returns results for the survivors only. This format turns
 * that protocol back into data:</p>
 *
 * <ul>
 *   <li>a combination that started and never ended is errored;</li>
 *   <li>a combination for which a failure marker was printed is errored even if it did
 *       end - an in-process run ends with the iterations that completed before the
 *       exception, and a partial measurement is not one to judge a target by;</li>
 *   <li>the text after the marker, up to JMH's next progress line or the next
 *       combination, is kept as the failure's detail, and its first line (normally
 *       {@code java.lang.SomeException: message}) as the message.</li>
 * </ul>
 *
 * <p>The start/end bracket is the authoritative signal and does not depend on console
 * text; the text only supplies the explanation. A combination that vanished without any
 * failure text is still recorded, with a message saying so.</p>
 *
 * <h2>Thread safety</h2>
 * <p>In a forked run JMH calls {@code println} from the binary-link reader thread while
 * the main thread calls {@code startBenchmark}/{@code endBenchmark}, so every method is
 * synchronized.</p>
 */
public final class FailureRecordingOutputFormat implements OutputFormat {

    /** Upper bound on the failure text kept per combination. */
    static final int MAX_DETAIL_CHARS = 16_384;

    private static final String TRUNCATED = "\n[truncated]";

    /**
     * Console lines with which JMH opens a failure report. The first is printed by the
     * benchmark JVM itself; the rest by the host when the fork could not be run or died.
     */
    private static final List<String> FAILURE_MARKERS = List.of(
        "<failure",
        "<forked VM failed",
        "<failed to invoke the VM",
        "<host VM has been interrupted",
        "<binary link had failed",
        "<shutdown timeout");

    /**
     * Console lines with which JMH resumes its own progress reporting, ending a failure
     * report. A bare {@code "# "} prefix is not enough: a crashed JVM's stderr, which
     * belongs in the detail, is full of such lines.
     */
    private static final List<String> PROGRESS_MARKERS = List.of(
        "# Run progress:",
        "# Fork:",
        "# Warmup Fork:",
        "# Warmup Iteration",
        "# Run complete.");

    /** One combination JMH started. */
    private static final class Trial {
        final BenchmarkParams params;
        final StringBuilder failureText = new StringBuilder();
        boolean failed;
        boolean capturing;
        boolean completed;
        boolean truncated;

        Trial(BenchmarkParams params) {
            this.params = params;
        }
    }

    private final OutputFormat delegate;
    private final Map<String, Trial> trials = new LinkedHashMap<>();
    private Trial current;

    /**
     * Creates a recording format.
     *
     * @param delegate the format that renders the console output
     */
    public FailureRecordingOutputFormat(OutputFormat delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * The combinations that errored, in the order JMH started them.
     *
     * @return one entry per errored combination
     */
    public synchronized List<ErroredBenchmark> erroredBenchmarks() {
        List<ErroredBenchmark> errored = new ArrayList<>();
        for (Trial trial : trials.values()) {
            if (isErrored(trial)) {
                errored.add(toErrored(trial));
            }
        }
        return errored;
    }

    /**
     * Whether a combination errored. Its result, if JMH returned one, is partial.
     *
     * @param params the combination
     * @return true if the combination started and either failed or never finished
     */
    public synchronized boolean isErrored(BenchmarkParams params) {
        Trial trial = trials.get(params.id());
        return trial != null && isErrored(trial);
    }

    private static boolean isErrored(Trial trial) {
        return trial.failed || !trial.completed;
    }

    private static ErroredBenchmark toErrored(Trial trial) {
        BenchmarkParams params = trial.params;
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String key : params.getParamsKeys()) {
            parameters.put(key, params.getParam(key));
        }
        String detail = trial.failureText.toString().strip();
        if (trial.truncated) {
            detail += TRUNCATED;
        }
        return new ErroredBenchmark(
            params.getBenchmark(),
            params.getMode().shortLabel(),
            parameters,
            summarize(detail),
            detail);
    }

    /**
     * The first informative line of a failure report: the exception line after a bare
     * {@code <failure>} marker, or the host's own marker with its brackets removed.
     */
    private static String summarize(String detail) {
        for (String raw : detail.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.equals("<failure>")) {
                continue;
            }
            if (line.startsWith("<") && line.endsWith(">")) {
                line = line.substring(1, line.length() - 1).strip();
            }
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "no result was produced and JMH printed no failure for this combination";
    }

    private void record(String text) {
        Trial trial = current;
        if (trial == null) {
            return;
        }
        for (String line : text.split("\\R", -1)) {
            if (!trial.capturing && startsWithAny(line.stripLeading(), FAILURE_MARKERS)) {
                trial.failed = true;
                trial.capturing = true;
            } else if (trial.capturing && startsWithAny(line, PROGRESS_MARKERS)) {
                trial.capturing = false;
            }
            if (trial.capturing) {
                append(trial, line);
            }
        }
    }

    private static boolean startsWithAny(String line, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void append(Trial trial, String line) {
        if (trial.truncated) {
            return;
        }
        int room = MAX_DETAIL_CHARS - trial.failureText.length();
        if (line.length() + 1 > room) {
            trial.failureText.append(line, 0, Math.max(0, room));
            trial.truncated = true;
            return;
        }
        trial.failureText.append(line).append('\n');
    }

    @Override
    public synchronized void startBenchmark(BenchmarkParams benchParams) {
        current = new Trial(benchParams);
        trials.put(benchParams.id(), current);
        delegate.startBenchmark(benchParams);
    }

    @Override
    public synchronized void endBenchmark(BenchmarkResult result) {
        // A forked combination whose every fork failed is still ended, with a null
        // result; only a non-null result means something was measured.
        Trial trial = result == null ? current : trials.get(result.getParams().id());
        if (trial != null) {
            trial.capturing = false;
            trial.completed |= result != null;
        }
        delegate.endBenchmark(result);
    }

    @Override
    public synchronized void println(String s) {
        record(s);
        delegate.println(s);
    }

    @Override
    public synchronized void print(String s) {
        record(s);
        delegate.print(s);
    }

    @Override
    public synchronized void verbosePrintln(String s) {
        delegate.verbosePrintln(s);
    }

    @Override
    public synchronized void iteration(BenchmarkParams benchParams, IterationParams params, int iteration) {
        delegate.iteration(benchParams, params, iteration);
    }

    @Override
    public synchronized void iterationResult(BenchmarkParams benchParams, IterationParams params,
                                             int iteration, IterationResult data) {
        delegate.iterationResult(benchParams, params, iteration, data);
    }

    @Override
    public synchronized void startRun() {
        delegate.startRun();
    }

    @Override
    public synchronized void endRun(Collection<RunResult> result) {
        current = null;
        delegate.endRun(result);
    }

    @Override
    public synchronized void flush() {
        delegate.flush();
    }

    @Override
    public synchronized void close() {
        delegate.close();
    }

    @Override
    public synchronized void write(int b) {
        delegate.write(b);
    }

    @Override
    public synchronized void write(byte[] b) throws IOException {
        delegate.write(b);
    }
}
