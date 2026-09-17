package excalibur.bench;

import excalibur.bench.metric.BenchmarkMetric;
import excalibur.bench.metric.BenchmarkResult;
import excalibur.bench.metric.BenchmarkResultSet;
import excalibur.bench.metric.BenchmarkTargetRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the capture command: how a run's outcome becomes an exit code, and what it
 * writes on the way.
 *
 * <p>The distinction this suite exists to protect is between a target that was
 * compared and met, and one that was never compared at all. Both used to leave the
 * process at zero.</p>
 */
class BenchmarkCaptureCommandTest {

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private final List<String> patternsRun = new ArrayList<>();
    private boolean capturedReports;

    @BeforeEach
    void setUp() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        patternsRun.clear();
        capturedReports = false;
    }

    @Test
    @DisplayName("--help prints usage and does not run anything")
    void helpRunsNothing() throws Exception {
        int exit = command(passingRun()).execute(new String[]{"--help"});

        assertThat(exit).isZero();
        assertThat(patternsRun).isEmpty();
        assertThat(stdout()).contains("--capture").contains("--pattern");
    }

    @Test
    @DisplayName("--capture writes all four report formats")
    void captureWritesEveryFormat(@TempDir Path dir) throws Exception {
        int exit = command(passingRun())
            .execute(new String[]{"--capture", "--output", dir.toString()});

        assertThat(exit).isZero();
        assertThat(dir.resolve("benchmark_results.json")).exists();
        assertThat(dir.resolve("benchmark_results.md")).exists();
        assertThat(dir.resolve("benchmark_results.csv")).exists();
        assertThat(dir.resolve("benchmark_results.txt")).exists();
    }

    @Test
    @DisplayName("a run whose targets were all met exits zero and says how many")
    void allTargetsMetPasses(@TempDir Path dir) throws Exception {
        int exit = command(passingRun())
            .execute(new String[]{"--capture", "--output", dir.toString()});

        assertThat(exit).isZero();
        assertThat(stdout()).contains("PASSED").contains("1");
    }

    @Test
    @DisplayName("a missed target exits non-zero")
    void missedTargetFails(@TempDir Path dir) throws Exception {
        int exit = command(failingRun())
            .execute(new String[]{"--capture", "--output", dir.toString()});

        assertThat(exit).isNotZero();
        assertThat(stderr()).contains("FAILED").containsIgnoringCase("target");
    }

    @Test
    @DisplayName("a target that could not be evaluated exits non-zero, not zero")
    void unevaluatedTargetFails(@TempDir Path dir) throws Exception {
        int exit = command(unevaluatedRun())
            .execute(new String[]{"--capture", "--output", dir.toString()});

        assertThat(exit).isNotZero();
        assertThat(stderr()).containsIgnoringCase("could not be evaluated");
    }

    @Test
    @DisplayName("a run that measured nothing exits non-zero")
    void emptyRunFails(@TempDir Path dir) throws Exception {
        int exit = command(BenchmarkResultSet.builder().name("empty").build())
            .execute(new String[]{"--capture", "--output", dir.toString()});

        assertThat(exit).isNotZero();
        assertThat(stderr()).containsIgnoringCase("no results");
    }

    @Test
    @DisplayName("--pattern selects which benchmarks run")
    void patternIsPassedThrough(@TempDir Path dir) throws Exception {
        command(passingRun()).execute(
            new String[]{"--capture", "--pattern", ".*Hash.*", "--output", dir.toString()});

        assertThat(patternsRun).containsExactly(".*Hash.*");
    }

    @Test
    @DisplayName("without --capture the benchmarks still run but nothing is written")
    void standardModeWritesNoReports(@TempDir Path dir) throws Exception {
        int exit = command(passingRun()).execute(new String[]{"--output", dir.toString()});

        assertThat(exit).isZero();
        assertThat(patternsRun).hasSize(1);
        assertThat(capturedReports).isFalse();
        assertThat(Files.list(dir)).isEmpty();
    }

    @Test
    @DisplayName("an unknown option is a usage error rather than being ignored")
    void unknownOptionIsAUsageError() throws Exception {
        int exit = command(passingRun()).execute(new String[]{"--capture", "--nonsense"});

        assertThat(exit).isNotZero();
        assertThat(stderr()).contains("--nonsense");
        assertThat(patternsRun).isEmpty();
    }

    @Test
    @DisplayName("a flag whose value is missing is a usage error rather than a crash")
    void danglingFlagIsAUsageError() throws Exception {
        int exit = command(passingRun()).execute(new String[]{"--capture", "--pattern"});

        assertThat(exit).isNotZero();
        assertThat(stderr()).contains("--pattern");
    }

    // ---- helpers ----

    private BenchmarkCaptureCommand command(BenchmarkResultSet measured) {
        return new BenchmarkCaptureCommand(
            (pattern, capture) -> {
                patternsRun.add(pattern);
                capturedReports = capture;
                return measured;
            },
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static BenchmarkResultSet passingRun() {
        return setOf(BenchmarkResult.builder()
            .benchmarkName("excalibur.bench.benchmark.DemoBenchmark.lookup")
            .mode("thrpt")
            .primaryScore(2_000_000.0)
            .primaryScoreUnit("ops/s")
            .higherIsBetter(true)
            .target(1_000_000.0)
            .build());
    }

    private static BenchmarkResultSet failingRun() {
        return setOf(BenchmarkResult.builder()
            .benchmarkName("excalibur.bench.benchmark.DemoBenchmark.lookup")
            .mode("thrpt")
            .primaryScore(10.0)
            .primaryScoreUnit("ops/s")
            .higherIsBetter(true)
            .target(1_000_000.0)
            .build());
    }

    /** Carries a registry target on p99, a metric this run never captured. */
    private static BenchmarkResultSet unevaluatedRun() {
        String name = "excalibur.bench.benchmark.EndToEndQueryBenchmark.fullQueryPipeline";
        BenchmarkResult.Builder builder = BenchmarkResult.builder()
            .benchmarkName(name)
            .mode("sample")
            .primaryScore(5.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false)
            .addMetric(new BenchmarkMetric("p50", 1.0, "ms"));
        for (BenchmarkTargetRegistry.TargetConfig target
                : BenchmarkTargetRegistry.getTargets(name)) {
            builder.addTarget(target);
        }
        return setOf(builder.build());
    }

    private static BenchmarkResultSet setOf(BenchmarkResult result) {
        return BenchmarkResultSet.builder()
            .name("run")
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .endTime(Instant.parse("2024-01-01T00:00:30Z"))
            .addResult(result)
            .build();
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
