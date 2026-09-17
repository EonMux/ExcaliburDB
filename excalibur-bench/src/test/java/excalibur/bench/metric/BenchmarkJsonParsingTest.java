package excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests reading benchmark results back out of a file.
 *
 * <p>Two shapes arrive here: JMH's own JSON, and the reports this tool writes. Both
 * are baselines a comparison is run against, and a parser that silently recovers
 * nothing from one of them turns every benchmark into a NEW entry - which reports as
 * "no regressions" whatever the numbers did.</p>
 */
class BenchmarkJsonParsingTest {

    /** A cut-down JMH JSON document, in the shape JMH's {@code -rf json} writes. */
    private static final String JMH_JSON = """
        [
            {
                "benchmark" : "excalibur.bench.benchmark.HashBenchmark.hashByteArray",
                "mode" : "thrpt",
                "threads" : 1,
                "forks" : 1,
                "params" : {
                    "keySize" : "1024",
                    "distribution" : "uniform",
                    "threads" : 4
                },
                "primaryMetric" : {
                    "score" : 2000000.5,
                    "scoreError" : 1234.5,
                    "scoreUnit" : "ops/s",
                    "scorePercentiles" : {
                        "0.0" : 1900000.0,
                        "100.0" : 2100000.0
                    }
                },
                "secondaryMetrics" : { }
            },
            {
                "benchmark" : "excalibur.bench.benchmark.DemoBenchmark.query",
                "mode" : "avgt",
                "primaryMetric" : {
                    "score" : 4.5,
                    "scoreError" : 0.25,
                    "scoreUnit" : "ms/op"
                }
            }
        ]
        """;

    @Test
    @DisplayName("JMH's own JSON yields one result per benchmark object")
    void jmhJsonYieldsOneResultPerBenchmark() {
        List<BenchmarkResult> results = BenchmarkResultCapture.parseJsonContent(JMH_JSON);

        assertThat(results).extracting(BenchmarkResult::methodName)
            .containsExactly("hashByteArray", "query");
    }

    @Test
    @DisplayName("the primary metric's score, error and unit are read from JMH's field names")
    void jmhPrimaryMetricIsRead() {
        BenchmarkResult result = BenchmarkResultCapture.parseJsonContent(JMH_JSON).get(0);

        assertThat(result.primaryScore()).isEqualTo(2_000_000.5);
        assertThat(result.primaryScoreError()).isEqualTo(1_234.5);
        assertThat(result.primaryScoreUnit()).isEqualTo("ops/s");
        assertThat(result.mode()).isEqualTo("thrpt");
    }

    @Test
    @DisplayName("JMH parameters are recovered, string and numeric alike")
    void jmhParametersAreRecovered() {
        BenchmarkResult result = BenchmarkResultCapture.parseJsonContent(JMH_JSON).get(0);

        assertThat(result.parameters())
            .containsEntry("keySize", "1024")
            .containsEntry("distribution", "uniform")
            .containsEntry("threads", "4");
    }

    @Test
    @DisplayName("a recovered parameter is enough to evaluate a size-scaled target")
    void recoveredParametersDriveScaledTargets() {
        // The registry scales hashByteArray by keySize; without the parameter the
        // target could only be reported as unevaluated.
        BenchmarkResult result = BenchmarkResultCapture.parseJsonContent(JMH_JSON).get(0);

        assertThat(result.hasUnevaluatedTarget()).isFalse();
        assertThat(result.targetEvaluations()).singleElement()
            .satisfies(e -> assertThat(e.comparedValue()).isEqualTo(2_000_000.5 * 1024));
    }

    @Test
    @DisplayName("the measured unit decides the direction, not the JMH mode")
    void directionComesFromTheUnit() {
        List<BenchmarkResult> results = BenchmarkResultCapture.parseJsonContent(JMH_JSON);

        assertThat(results.get(0).higherIsBetter()).isTrue();
        assertThat(results.get(1).higherIsBetter()).isFalse();
    }

    @Test
    @DisplayName("a report this tool wrote round-trips back into the same scores")
    void ownReportRoundTrips() {
        BenchmarkResultSet original = BenchmarkResultSet.builder()
            .name("Nightly")
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .addResult(throughput("lookup", 1_500_000.0))
            .addResult(throughput("scan", 250_000.0))
            .build();

        List<BenchmarkResult> parsed =
            BenchmarkResultCapture.parseJsonContent(original.toJson());

        assertThat(parsed).extracting(BenchmarkResult::methodName)
            .containsExactlyInAnyOrder("lookup", "scan");
        assertThat(parsed).extracting(BenchmarkResult::primaryScore)
            .containsExactlyInAnyOrder(1_500_000.0, 250_000.0);
    }

    @Test
    @DisplayName("a report listing results twice is not read twice")
    void ownReportIsNotDoubleCounted() {
        // The report lists each result under "categories" and again under "results".
        // Reading both would double every baseline, and a comparison against a doubled
        // baseline is not a comparison.
        BenchmarkResultSet original = BenchmarkResultSet.builder()
            .name("Nightly")
            .addResult(throughput("lookup", 1_500_000.0))
            .build();

        assertThat(BenchmarkResultCapture.parseJsonContent(original.toJson())).hasSize(1);
    }

    @Test
    @DisplayName("a document containing no results yields none rather than failing")
    void anEmptyDocumentYieldsNothing() {
        assertThat(BenchmarkResultCapture.parseJsonContent("[]")).isEmpty();
        assertThat(BenchmarkResultCapture.parseJsonContent("{}")).isEmpty();
        assertThat(BenchmarkResultCapture.parseJsonContent("")).isEmpty();
    }

    @Test
    @DisplayName("an object with a benchmark name but no metric is skipped, not half-read")
    void anObjectWithoutAMetricIsSkipped() {
        assertThat(BenchmarkResultCapture.parseJsonContent(
            "[ { \"benchmark\" : \"a.B.c\", \"mode\" : \"thrpt\" } ]")).isEmpty();
    }

    @Test
    @DisplayName("JSON on disk parses the same as JSON in memory")
    void jsonParsesFromAFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("jmh.json");
        Files.writeString(file, JMH_JSON);

        assertThat(BenchmarkResultCapture.parseJsonOutput(file))
            .extracting(BenchmarkResult::methodName)
            .containsExactly("hashByteArray", "query");
    }

    @Test
    @DisplayName("JMH console output on disk parses into results")
    void consoleOutputParsesFromAFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("jmh.txt");
        Files.writeString(file, """
            # JMH version: 1.37
            Benchmark                                  Mode  Cnt        Score      Error  Units
            excalibur.bench.benchmark.DemoBenchmark.a thrpt    5  1500000.000 ± 1000.000 ops/s

            """);

        List<BenchmarkResult> results = BenchmarkResultCapture.parseConsoleOutput(file);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.methodName()).isEqualTo("a");
            assertThat(result.primaryScore()).isEqualTo(1_500_000.0);
            assertThat(result.primaryScoreError()).isEqualTo(1_000.0);
            assertThat(result.primaryScoreUnit()).isEqualTo("ops/s");
        });
    }

    @Test
    @DisplayName("console lines that are not results are ignored")
    void nonResultConsoleLinesAreIgnored() {
        assertThat(BenchmarkResultCapture.parseConsoleOutput(List.of(
            "", "# Warmup: 2 iterations", "Benchmark  Mode  Cnt  Score  Error  Units",
            "Iteration   1: 1500000.000 ops/s"))).isEmpty();
    }

    private static BenchmarkResult throughput(String method, double opsPerSec) {
        return BenchmarkResult.builder()
            .benchmarkName("excalibur.bench.benchmark.DemoBenchmark." + method)
            .mode("thrpt")
            .primaryScore(opsPerSec)
            .primaryScoreUnit("ops/sec")
            .higherIsBetter(true)
            .build();
    }
}
