package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report this tool writes must be a report this tool can read.
 *
 * <p>It was not. {@code toJson} emits {@code "score": {value, error, unit}}; the parser looked
 * only for JMH's {@code "primaryMetric"} and returned null for every object without it. So
 * {@code --compare --baseline <a file this tool wrote>} loaded zero baselines, every measured
 * benchmark was reported as NEW rather than regressed, and the regression gate reported success
 * whatever the numbers did. A gate that cannot fail is worse than no gate: it is a gate everyone
 * believes.
 */
@DisplayName("Benchmark report round trip")
class BenchmarkResultRoundTripTest {

    private static BenchmarkResult result(String name, double score, String unit) {
        return BenchmarkResult.builder()
            .benchmarkName(name)
            .mode("thrpt")
            .timestamp(Instant.EPOCH)
            .primaryScore(score)
            .primaryScoreError(score / 100)
            .primaryScoreUnit(unit)
            .higherIsBetter(true)
            .build();
    }

    @Test
    @DisplayName("a result written by this tool parses back with its score intact")
    void ownFormatRoundTrips() {
        BenchmarkResult original = result("excalibur.PointLookup.get", 1234.5, "ops/sec");

        List<BenchmarkResult> parsed =
            BenchmarkResultCapture.parseJsonContent(original.toJson());

        assertThat(parsed).hasSize(1);
        BenchmarkResult back = parsed.get(0);
        assertThat(back.benchmarkName()).isEqualTo("excalibur.PointLookup.get");
        assertThat(back.primaryScore()).isEqualTo(1234.5);
        assertThat(back.primaryScoreError()).isEqualTo(12.345);
        assertThat(back.primaryScoreUnit()).isEqualTo("ops/sec");
    }

    @Test
    @DisplayName("a whole result set written to a report parses back")
    void resultSetRoundTrips() {
        BenchmarkResultSet set = BenchmarkResultSet.builder()
            .name("baseline")
            .startTime(Instant.EPOCH)
            .endTime(Instant.EPOCH.plusSeconds(60))
            .addResult(result("excalibur.PointLookup.get", 1000.0, "ops/sec"))
            .addResult(result("excalibur.Scan.range", 2000.0, "ops/sec"))
            .build();

        List<BenchmarkResult> parsed = BenchmarkResultCapture.parseJsonContent(set.toJson());

        assertThat(parsed)
            .as("both benchmarks must survive the write-then-read that --compare performs")
            .hasSize(2);
        assertThat(parsed).extracting(BenchmarkResult::benchmarkName)
            .containsExactlyInAnyOrder("excalibur.PointLookup.get", "excalibur.Scan.range");
        assertThat(parsed).extracting(BenchmarkResult::primaryScore)
            .containsExactlyInAnyOrder(1000.0, 2000.0);
    }

    @Test
    @DisplayName("JMH's own format is still read")
    void jmhFormatStillParses() {
        // --diff is documented to take JMH output, so the original shape has to keep working.
        String jmh = """
            [
              {
                "benchmark": "excalibur.PointLookup.get",
                "mode": "thrpt",
                "primaryMetric": {
                  "score": 4321.0,
                  "scoreError": 43.21,
                  "scoreUnit": "ops/s"
                }
              }
            ]
            """;

        List<BenchmarkResult> parsed = BenchmarkResultCapture.parseJsonContent(jmh);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).primaryScore()).isEqualTo(4321.0);
        assertThat(parsed.get(0).primaryScoreUnit()).isEqualTo("ops/s");
    }

    @Test
    @DisplayName("an object carrying no metric at all is skipped, not invented")
    void objectWithoutAMetricIsSkipped() {
        String json = "[{\"benchmark\": \"excalibur.Nothing.here\", \"mode\": \"thrpt\"}]";

        assertThat(BenchmarkResultCapture.parseJsonContent(json)).isEmpty();
    }
}
