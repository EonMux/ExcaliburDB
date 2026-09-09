package excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three-percentile validation, which is what decides whether a benchmark run is believable.
 *
 * <p>This exists because the ways a latency measurement goes wrong all produce numbers that look
 * fine in isolation. Percentiles out of order mean the harness aggregated across runs it should
 * not have. A zero median means the operation was faster than the clock could see, so the figure
 * measures resolution rather than the code. A p99 of 40,000 "ms" is almost always nanoseconds
 * nobody converted - and the benchmark then passes a target it missed by six orders of
 * magnitude.</p>
 *
 * <p>The distinction between an error and a warning carries the design: an error means the numbers
 * cannot be true, a warning means they are suspicious but the caller may know better. Only the
 * first invalidates a run, and conflating them either blocks benchmarks that are fine or lets
 * impossible ones through.</p>
 */
@DisplayName("Latency percentile full validation")
class LatencyPercentileFullValidationTest {

    private static BenchmarkMetric percentile(int p, double valueMs) {
        return BenchmarkMetric.latencyPercentile(p, valueMs, 0.0);
    }

    private static BenchmarkResult resultWith(BenchmarkMetric... metrics) {
        BenchmarkResult.Builder builder = BenchmarkResult.builder()
            .benchmarkName("SomeBenchmark.method")
            .primaryScore(1.0)
            .primaryScoreUnit("ms/op");
        for (BenchmarkMetric metric : metrics) {
            builder.addMetric(metric);
        }
        return builder.build();
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("properly ordered percentiles are valid")
        void orderedPercentilesAreValid() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(0.5, 2.0, 9.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.errors()).isEmpty();
            assertThat(result.applicable()).isTrue();
        }

        @Test
        @DisplayName("a p0 above the median is impossible, not merely odd")
        void p0AboveP50IsAnError() {
            // A minimum larger than the median cannot come from one distribution; it means
            // figures from different runs were mixed.
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(5.0, 2.0, 9.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p0").contains("p50");
        }

        @Test
        @DisplayName("a median above the p99 is impossible")
        void p50AboveP99IsAnError() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(0.5, 9.0, 2.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p50").contains("p99");
        }

        @Test
        @DisplayName("every ordering breach is reported, not just the first")
        void allBreachesAreReported() {
            // Reporting one at a time turns fixing a harness into a guessing game.
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(9.0, 5.0, 1.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors())
                .contains("p0 (9.000ms) > p50")
                .contains("p50 (5.000ms) > p99")
                .contains("p0 (9.000ms) > p99");
        }
    }

    @Nested
    @DisplayName("impossible values")
    class ImpossibleValues {

        @Test
        @DisplayName("a zero percentile is an error, because operations take time")
        void zeroIsAnError() {
            // Zero means the measurement was below the clock's resolution: the number describes
            // the timer, not the code.
            assertThat(LatencyPercentileValidator.validateFull(0.0, 2.0, 9.0).valid()).isFalse();
            assertThat(LatencyPercentileValidator.validateFull(0.5, 0.0, 9.0).valid()).isFalse();
            assertThat(LatencyPercentileValidator.validateFull(0.5, 2.0, 0.0).valid()).isFalse();
        }

        @Test
        @DisplayName("a negative percentile is an error")
        void negativeIsAnError() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(-1.0, 2.0, 9.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("POSITIVE");
        }

        @Test
        @DisplayName("NaN and infinity are refused before anything is compared against them")
        void nonFiniteIsAnError() {
            // Every comparison against NaN is false, so an ordering check would pass it through
            // silently if it were not caught first.
            for (double bad : new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertThat(LatencyPercentileValidator.validateFull(bad, 2.0, 9.0).valid())
                    .as("p0 = %s", bad).isFalse();
                assertThat(LatencyPercentileValidator.validateFull(0.5, bad, 9.0).valid())
                    .as("p50 = %s", bad).isFalse();
                assertThat(LatencyPercentileValidator.validateFull(0.5, 2.0, bad).valid())
                    .as("p99 = %s", bad).isFalse();
            }
        }

        @Test
        @DisplayName("a non-finite value short-circuits, so no NaN reaches the ordering checks")
        void nonFiniteShortCircuits() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(Double.NaN, 2.0, 9.0);

            assertThat(result.errors()).contains("not a finite number");
            assertThat(result.errors())
                .as("the ordering checks are not reached, so they cannot report nonsense")
                .doesNotContain("must be ordered");
        }
    }

    @Nested
    @DisplayName("warnings that do not invalidate")
    class Warnings {

        @Test
        @DisplayName("a value that looks like unconverted nanoseconds warns but stays valid")
        void nanosecondsWarn() {
            // The caller may genuinely have a 2-second operation. The point is to say so, not
            // to refuse the run.
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(1500.0, 2000.0, 9000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.warnings()).contains("nanoseconds");
        }

        @Test
        @DisplayName("an unreasonably long latency warns")
        void unreasonableLatencyWarns() {
            double beyond = LatencyPercentileValidator.MAX_REASONABLE_LATENCY_MS + 1000;
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(beyond, beyond + 1, beyond + 2);

            assertThat(result.valid()).isTrue();
            assertThat(result.warnings()).contains("maximum reasonable latency");
        }

        @Test
        @DisplayName("too few samples to estimate a p99 warns")
        void tooFewSamplesWarn() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(0.5, 2.0, 9.0,
                    LatencyPercentileValidator.MIN_SAMPLES_FOR_PERCENTILES - 1);

            assertThat(result.valid()).isTrue();
            assertThat(result.warnings()).contains("samples");
        }

        @Test
        @DisplayName("an unknown sample count is not treated as too few")
        void zeroSampleCountIsUnknownNotSmall() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(0.5, 2.0, 9.0, 0);

            assertThat(result.warnings()).doesNotContain("samples");
        }

        @Test
        @DisplayName("identical percentiles are reported as a constant distribution, not an error")
        void constantDistributionIsInformational() {
            // Equality is legal - every sample was the same. It is worth saying, because it
            // usually means the workload was too small rather than too consistent.
            LatencyPercentileValidator.ValidationResult all =
                LatencyPercentileValidator.validateFull(2.0, 2.0, 2.0);

            assertThat(all.valid()).isTrue();
            assertThat(all.warnings()).contains("constant distribution");

            assertThat(LatencyPercentileValidator.validateFull(2.0, 2.0, 9.0).warnings())
                .contains("highly consistent");
            assertThat(LatencyPercentileValidator.validateFull(0.5, 2.0, 2.0).warnings())
                .contains("tight upper bound");
        }

        @Test
        @DisplayName("the combined message carries both halves")
        void combinedMessageCarriesBoth() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(2.0, 2.0, 2.0);

            assertThat(result.combinedMessage()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("validating a captured result")
    class FromResult {

        @Test
        @DisplayName("a result carrying all three percentiles in ms validates")
        void completeResultValidates() {
            BenchmarkResult result = resultWith(
                percentile(0, 0.5), percentile(50, 2.0), percentile(99, 9.0));

            assertThat(LatencyPercentileValidator.validateFull(result).valid()).isTrue();
        }

        @Test
        @DisplayName("a result with no percentiles at all is not applicable, not a failure")
        void noPercentilesIsNotApplicable() {
            // A throughput benchmark captures no latency. Reporting that as invalid would fail
            // every such benchmark; reporting it as valid would claim a check that never ran.
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(
                    resultWith(BenchmarkMetric.throughput(1000.0, 1.0)));

            assertThat(result.applicable()).isFalse();
            assertThat(result.errors()).contains("never captured");
        }

        @Test
        @DisplayName("a result missing one percentile is a failure, not not-applicable")
        void partialPercentilesAreAFailure() {
            // Having p50 but no p99 means the capture was incomplete - which is a real problem,
            // unlike a benchmark that measures no latency at all.
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(
                    resultWith(percentile(0, 0.5), percentile(50, 2.0)));

            assertThat(result.applicable()).isTrue();
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p99 metric missing");
        }

        @Test
        @DisplayName("percentiles recorded in the wrong unit are rejected by name")
        void wrongUnitsAreRejected() {
            // This is the failure the whole validator exists for: nanoseconds recorded as ms
            // make a benchmark appear to pass a target it missed by a million times.
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validateFull(resultWith(
                    new BenchmarkMetric("p0", 500.0, "ns"),
                    new BenchmarkMetric("p50", 2000.0, "ns"),
                    new BenchmarkMetric("p99", 9000.0, "ns")));

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("nanoseconds");
        }

        @Test
        @DisplayName("microseconds and seconds are rejected too")
        void otherWrongUnitsAreRejected() {
            assertThat(LatencyPercentileValidator.validateFull(resultWith(
                new BenchmarkMetric("p0", 1.0, "us"),
                new BenchmarkMetric("p50", 2.0, "us"),
                new BenchmarkMetric("p99", 3.0, "us"))).errors())
                .contains("microseconds");

            assertThat(LatencyPercentileValidator.validateFull(resultWith(
                new BenchmarkMetric("p0", 1.0, "s"),
                new BenchmarkMetric("p50", 2.0, "s"),
                new BenchmarkMetric("p99", 3.0, "s"))).errors())
                .contains("seconds");
        }

        @Test
        @DisplayName("the accepted spellings of milliseconds all pass")
        void millisecondSpellingsAreAccepted() {
            for (String unit : new String[] {"ms", "MS", "millis", "milliseconds"}) {
                LatencyPercentileValidator.ValidationResult result =
                    LatencyPercentileValidator.validateFull(resultWith(
                        new BenchmarkMetric("p0", 0.5, unit),
                        new BenchmarkMetric("p50", 2.0, unit),
                        new BenchmarkMetric("p99", 9.0, unit)));

                assertThat(result.valid()).as("unit %s", unit).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("the summary reports every percentile and the spread between them")
        void summaryReportsRatios() {
            String summary = LatencyPercentileValidator.formatSummaryFull(0.5, 2.0, 10.0);

            assertThat(summary)
                .contains("p0").contains("p50").contains("p99")
                .contains("0.500").contains("2.000").contains("10.000")
                .contains("ratio (p99/p0)")
                .contains("ratio (p99/p50)")
                .contains("VALID");
        }

        @Test
        @DisplayName("an invalid set is reported as invalid rather than formatted as if fine")
        void summaryReportsInvalid() {
            String summary = LatencyPercentileValidator.formatSummaryFull(9.0, 5.0, 1.0);

            assertThat(summary).contains("INVALID");
        }

        @Test
        @DisplayName("a summary of zero values does not divide by zero")
        void summaryHandlesZero() {
            String summary = LatencyPercentileValidator.formatSummaryFull(0.0, 0.0, 0.0);

            assertThat(summary)
                .doesNotContain("Infinity")
                .doesNotContain("NaN")
                .contains("INVALID");
        }

        @Test
        @DisplayName("warnings appear in the summary of an otherwise valid set")
        void summaryCarriesWarnings() {
            String summary = LatencyPercentileValidator.formatSummaryFull(2.0, 2.0, 2.0);

            assertThat(summary).contains("VALID").contains("warnings");
        }

        @Test
        @DisplayName("asserting on an impossible set throws with the reason")
        void assertionsThrowWithReasons() {
            assertThatThrownBy(
                () -> LatencyPercentileValidator.assertValidFull(9.0, 5.0, 1.0))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> LatencyPercentileValidator.assertValid(9.0, 1.0))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("asserting on a valid set is silent")
        void validAssertionsAreSilent() {
            LatencyPercentileValidator.assertValidFull(0.5, 2.0, 9.0);
            LatencyPercentileValidator.assertValid(2.0, 9.0);
        }
    }
}
