package excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the validation of latency percentiles read off a captured benchmark result.
 *
 * <p>The values reaching a latency target must be in milliseconds. A result carrying
 * nanoseconds in a field labelled milliseconds passes every ordering check and then
 * misses its target by a factor of a million - or, worse, appears to meet it. These
 * tests pin the unit check and the "there was nothing to validate" case, which must
 * never read as a pass.</p>
 */
class LatencyPercentileFromResultTest {

    @Test
    @DisplayName("a result with millisecond percentiles in order validates")
    void orderedMillisecondPercentilesValidate() {
        LatencyPercentileValidator.ValidationResult result =
            LatencyPercentileValidator.validate(resultWith(
                metric("p50", 4.0, "ms"), metric("p99", 9.0, "ms")));

        assertThat(result.valid()).isTrue();
        assertThat(result.applicable()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("a result with no percentiles at all is inapplicable, not a pass")
    void missingPercentilesAreNotAPass() {
        LatencyPercentileValidator.ValidationResult result =
            LatencyPercentileValidator.validate(resultWith());

        assertThat(result.applicable()).isFalse();
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("never captured");
    }

    @Test
    @DisplayName("half a pair of percentiles is an error naming the missing one")
    void halfAPairIsAnError() {
        assertThat(LatencyPercentileValidator.validate(resultWith(metric("p99", 9.0, "ms")))
            .errors()).isEqualTo("p50 metric missing");
        assertThat(LatencyPercentileValidator.validate(resultWith(metric("p50", 4.0, "ms")))
            .errors()).isEqualTo("p99 metric missing");
    }

    @Test
    @DisplayName("percentiles in the wrong time unit are rejected by name")
    void wrongTimeUnitsAreRejected() {
        assertThat(errorsFor("ns")).contains("p50 unit is nanoseconds");
        assertThat(errorsFor("nanos")).contains("p50 unit is nanoseconds");
        assertThat(errorsFor("us")).contains("p50 unit is microseconds");
        assertThat(errorsFor("micros")).contains("p50 unit is microseconds");
        assertThat(errorsFor("s")).contains("p50 unit is seconds");
    }

    @Test
    @DisplayName("a wrong unit on p99 alone is caught too")
    void wrongUnitOnP99IsCaught() {
        LatencyPercentileValidator.ValidationResult result =
            LatencyPercentileValidator.validate(resultWith(
                metric("p50", 4.0, "ms"), metric("p99", 9.0, "ns")));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("p99 unit is nanoseconds");
    }

    @Test
    @DisplayName("the spellings of millisecond are all accepted")
    void everySpellingOfMillisecondIsAccepted() {
        for (String unit : new String[]{"ms", "MS", "millis", "milliseconds"}) {
            assertThat(LatencyPercentileValidator.validate(resultWith(
                metric("p50", 4.0, unit), metric("p99", 9.0, unit))).valid())
                .as(unit)
                .isTrue();
        }
    }

    @Test
    @DisplayName("a result cannot even be built with its percentiles out of order")
    void outOfOrderPercentilesAreRefusedAtConstruction() {
        // The ordering check runs in the builder, so an impossible distribution never
        // reaches a report in the first place.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resultWith(
                metric("p50", 90.0, "ms"), metric("p99", 9.0, "ms")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("percentiles must be ordered");
    }

    @Test
    @DisplayName("an error and a warning are reported side by side")
    void errorsAndWarningsAreReportedTogether() {
        // p50 is impossible, and p99 is large enough to look like unconverted nanoseconds.
        LatencyPercentileValidator.ValidationResult result =
            LatencyPercentileValidator.validate(0.0, 5_000.0);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.combinedMessage()).startsWith("Errors: ").contains("; Warnings: ");
    }

    @Test
    @DisplayName("the sample count travels from the metric into the validation")
    void sampleCountReachesTheValidation() {
        LatencyPercentileValidator.ValidationResult result =
            LatencyPercentileValidator.validate(resultWith(
                new BenchmarkMetric("p50", 4.0, "ms", 0.0, 3),
                new BenchmarkMetric("p99", 9.0, "ms", 0.0, 3)));

        assertThat(result.warnings()).contains("Only 3 samples");
    }

    @Test
    @DisplayName("the full check needs p0 as well and says which is absent")
    void fullValidationNeedsEveryPercentile() {
        assertThat(LatencyPercentileValidator.validateFull(resultWith(
            metric("p50", 4.0, "ms"), metric("p99", 9.0, "ms"))).errors())
            .contains("p0 metric missing");
        assertThat(LatencyPercentileValidator.validateFull(resultWith()).applicable()).isFalse();
    }

    @Test
    @DisplayName("the full check validates a complete, ordered triple")
    void fullValidationAcceptsAnOrderedTriple() {
        assertThat(LatencyPercentileValidator.validateFull(resultWith(
            metric("p0", 1.0, "ms"), metric("p50", 4.0, "ms"), metric("p99", 9.0, "ms")))
            .valid()).isTrue();
    }

    @Test
    @DisplayName("the full check rejects a p0 in the wrong unit")
    void fullValidationChecksP0Units() {
        assertThat(LatencyPercentileValidator.validateFull(resultWith(
            metric("p0", 1.0, "us"), metric("p50", 4.0, "ms"), metric("p99", 9.0, "ms")))
            .errors()).contains("p0 unit is microseconds");
    }

    @Test
    @DisplayName("a combined message with neither errors nor warnings reads as a pass")
    void cleanValidationReadsAsAPass() {
        assertThat(LatencyPercentileValidator.validate(4.0, 9.0).combinedMessage())
            .isEqualTo("Validation passed");
    }

    @Test
    @DisplayName("the summary reports the p99/p50 ratio and the validation status")
    void summaryReportsTheRatio() {
        assertThat(LatencyPercentileValidator.formatSummary(4.0, 8.0))
            .contains("ratio (p99/p50): 2.00x")
            .contains("status: VALID");
        assertThat(LatencyPercentileValidator.formatSummary(0.0, 8.0))
            .contains("status: INVALID");
    }

    // ---- fixtures ----

    private static String errorsFor(String p50Unit) {
        return LatencyPercentileValidator.validate(resultWith(
            metric("p50", 4.0, p50Unit), metric("p99", 9.0, "ms"))).errors();
    }

    private static BenchmarkMetric metric(String name, double value, String unit) {
        return new BenchmarkMetric(name, value, unit);
    }

    private static BenchmarkResult resultWith(BenchmarkMetric... metrics) {
        BenchmarkResult.Builder builder = BenchmarkResult.builder()
            .benchmarkName("excalibur.bench.benchmark.DemoBenchmark.query")
            .mode("sample")
            .primaryScore(5.0)
            .primaryScoreUnit("ms/op")
            .higherIsBetter(false);
        for (BenchmarkMetric metric : metrics) {
            builder.addMetric(metric);
        }
        return builder.build();
    }
}
