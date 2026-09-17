package excalibur.bench;

import excalibur.bench.metric.BenchmarkResult;
import excalibur.bench.metric.BenchmarkResultSet;
import excalibur.bench.report.BenchmarkReportWriter;
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
 * Tests the benchmark comparison command: argument handling, baseline loading,
 * regression detection and the exit code each outcome produces.
 *
 * <p>The exit code is the whole point of this command - CI reads it and nothing else.
 * An outcome that could not be evaluated must never leave it at zero, because zero is
 * indistinguishable from "measured, compared, and fine".</p>
 */
class BenchmarkComparisonCommandTest {

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private final List<String> patternsRun = new ArrayList<>();

    @BeforeEach
    void setUp() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        patternsRun.clear();
    }

    @Test
    @DisplayName("no arguments prints usage and exits non-zero")
    void noArgumentsIsAUsageError() throws Exception {
        int exit = command(resultSet("run", 1_000_000.0)).execute(new String[0]);

        assertThat(exit).isNotZero();
        assertThat(stdout()).contains("Usage:");
    }

    @Test
    @DisplayName("--help prints usage and succeeds")
    void helpSucceeds() throws Exception {
        int exit = command(resultSet("run", 1_000_000.0)).execute(new String[]{"--help"});

        assertThat(exit).isZero();
        assertThat(stdout()).contains("--diff").contains("--compare").contains("--run");
    }

    @Test
    @DisplayName("an unknown command is reported on stderr and exits non-zero")
    void unknownCommandIsAnError() throws Exception {
        int exit = command(resultSet("run", 1_000_000.0)).execute(new String[]{"--frobnicate"});

        assertThat(exit).isNotZero();
        assertThat(stderr()).contains("--frobnicate");
    }

    @Test
    @DisplayName("--run writes JSON, Markdown, CSV and text reports")
    void runWritesEveryReportFormat(@TempDir Path dir) throws Exception {
        int exit = command(resultSet("captured", 1_000_000.0))
            .execute(new String[]{"--run", "--output", dir.toString()});

        assertThat(exit).isZero();
        assertThat(dir.resolve("benchmark_results.json")).exists();
        assertThat(dir.resolve("benchmark_results.md")).exists();
        assertThat(dir.resolve("benchmark_results.csv")).exists();
        assertThat(dir.resolve("benchmark_results.txt")).exists();
        assertThat(Files.readString(dir.resolve("benchmark_results.json"))).contains("captured");
    }

    @Test
    @DisplayName("--run passes the requested pattern through to the benchmark run")
    void runHonoursThePattern(@TempDir Path dir) throws Exception {
        command(resultSet("captured", 1_000_000.0)).execute(
            new String[]{"--run", "--pattern", ".*PointLookup.*", "--output", dir.toString()});

        assertThat(patternsRun).containsExactly(".*PointLookup.*");
    }

    @Test
    @DisplayName("a run that measured nothing exits non-zero instead of reporting success")
    void anEmptyRunIsAFailure(@TempDir Path dir) throws Exception {
        int exit = command(emptyResultSet())
            .execute(new String[]{"--run", "--output", dir.toString()});

        assertThat(exit).isNotZero();
        assertThat(stderr()).containsIgnoringCase("no results");
    }

    @Test
    @DisplayName("--diff of two equal runs reports no regressions and exits zero")
    void diffOfEqualRunsIsClean(@TempDir Path dir) throws Exception {
        Path baseline = writeResults(dir.resolve("base.json"), resultSet("base", 1_000_000.0));
        Path current = writeResults(dir.resolve("cur.json"), resultSet("cur", 1_000_000.0));

        int exit = command(emptyResultSet()).execute(new String[]{
            "--diff", baseline.toString(), current.toString(), "--output", dir.toString()});

        assertThat(exit).isZero();
        assertThat(stdout()).contains("NO REGRESSIONS");
    }

    @Test
    @DisplayName("--diff detects a throughput drop and exits non-zero")
    void diffDetectsARegression(@TempDir Path dir) throws Exception {
        Path baseline = writeResults(dir.resolve("base.json"), resultSet("base", 1_000_000.0));
        Path current = writeResults(dir.resolve("cur.json"), resultSet("cur", 500_000.0));

        int exit = command(emptyResultSet()).execute(new String[]{
            "--diff", baseline.toString(), current.toString(), "--output", dir.toString()});

        assertThat(exit).isNotZero();
        assertThat(stdout()).contains("REGRESSIONS DETECTED");
        assertThat(Files.readString(dir.resolve("benchmark_comparison.json")))
            .contains("\"regressed\": 1");
        assertThat(dir.resolve("benchmark_comparison.txt")).exists();
    }

    @Test
    @DisplayName("--diff reads the baseline's own run name back out of the report it wrote")
    void diffRecoversTheBaselineName(@TempDir Path dir) throws Exception {
        Path baseline = writeResults(dir.resolve("base.json"), resultSet("Nightly 20240101", 1_000_000.0));
        Path current = writeResults(dir.resolve("cur.json"), resultSet("cur", 1_000_000.0));

        command(emptyResultSet()).execute(new String[]{
            "--diff", baseline.toString(), current.toString(), "--output", dir.toString()});

        assertThat(stdout()).contains("Nightly 20240101");
    }

    @Test
    @DisplayName("--diff with a missing baseline file exits non-zero rather than reporting success")
    void diffWithMissingBaselineIsAnError(@TempDir Path dir) throws Exception {
        Path current = writeResults(dir.resolve("cur.json"), resultSet("cur", 1_000_000.0));

        int exit = command(emptyResultSet()).execute(new String[]{
            "--diff", dir.resolve("absent.json").toString(), current.toString()});

        assertThat(exit).isNotZero();
        assertThat(stderr()).contains("absent.json");
    }

    @Test
    @DisplayName("--diff with only one path is a usage error")
    void diffNeedsTwoPaths(@TempDir Path dir) throws Exception {
        int exit = command(emptyResultSet())
            .execute(new String[]{"--diff", dir.resolve("only.json").toString()});

        assertThat(exit).isNotZero();
        assertThat(stderr()).containsIgnoringCase("two");
    }

    @Test
    @DisplayName("--compare without a baseline is a usage error, not a silent pass")
    void compareRequiresABaseline() throws Exception {
        int exit = command(resultSet("cur", 1_000_000.0)).execute(new String[]{"--compare"});

        assertThat(exit).isNotZero();
        assertThat(stderr()).contains("--baseline");
    }

    @Test
    @DisplayName("--compare runs the benchmarks and fails on a regression against the baseline")
    void compareDetectsARegression(@TempDir Path dir) throws Exception {
        Path baseline = writeResults(dir.resolve("base.json"), resultSet("base", 1_000_000.0));

        int exit = command(resultSet("cur", 400_000.0)).execute(new String[]{
            "--compare", "--baseline", baseline.toString(), "--output", dir.toString()});

        assertThat(exit).isNotZero();
        assertThat(patternsRun).hasSize(1);
        assertThat(dir.resolve("benchmark_results.json")).exists();
        assertThat(dir.resolve("benchmark_comparison.json")).exists();
    }

    @Test
    @DisplayName("--compare against an unchanged baseline exits zero")
    void compareOfUnchangedRunIsClean(@TempDir Path dir) throws Exception {
        Path baseline = writeResults(dir.resolve("base.json"), resultSet("base", 1_000_000.0));

        int exit = command(resultSet("cur", 1_000_000.0)).execute(new String[]{
            "--compare", "--baseline", baseline.toString(), "--output", dir.toString()});

        assertThat(exit).isZero();
    }

    @Test
    @DisplayName("a flag whose value is missing is a usage error rather than a crash")
    void aDanglingFlagIsAUsageError() throws Exception {
        int exit = command(resultSet("cur", 1_000_000.0)).execute(new String[]{"--run", "--output"});

        assertThat(exit).isNotZero();
        assertThat(stderr()).contains("--output");
    }

    // ---- helpers ----

    private BenchmarkComparisonCommand command(BenchmarkResultSet measured) {
        return new BenchmarkComparisonCommand(
            pattern -> {
                patternsRun.add(pattern);
                return measured;
            },
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static BenchmarkResultSet resultSet(String name, double opsPerSec) {
        return BenchmarkResultSet.builder()
            .name(name)
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .endTime(Instant.parse("2024-01-01T00:01:00Z"))
            .addResult(BenchmarkResult.builder()
                .benchmarkName("excalibur.bench.benchmark.DemoBenchmark.lookup")
                .mode("thrpt")
                .primaryScore(opsPerSec)
                .primaryScoreUnit("ops/s")
                .higherIsBetter(true)
                .build())
            .build();
    }

    private static BenchmarkResultSet emptyResultSet() {
        return BenchmarkResultSet.builder().name("empty").build();
    }

    private static Path writeResults(Path path, BenchmarkResultSet results) throws Exception {
        BenchmarkReportWriter.writeJson(results, path);
        return path;
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
