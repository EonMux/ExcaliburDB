package excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LatencyPercentileValidator}.
 *
 * <p>Validates domain correctness constraints for latency percentiles:</p>
 * <ul>
 *   <li>p50 &lt; p99 (strict ordering)</li>
 *   <li>Both values non-negative</li>
 *   <li>Both values in milliseconds</li>
 *   <li>Reasonable value ranges</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since Phase 10
 */
class LatencyPercentileValidatorTest {

    @Nested
    @DisplayName("Basic Validation")
    class BasicValidationTests {

        @Test
        @DisplayName("Valid percentiles should pass validation")
        void testValidPercentiles() {
            // Typical query latency: p50=5ms, p99=25ms
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(5.0, 25.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("p50 equal to p99 should pass with warning (constant distribution)")
        void testEqualPercentiles() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(10.0, 10.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.errors()).isEmpty();
            assertThat(result.warnings()).contains("constant distribution");
        }

        @Test
        @DisplayName("p50 greater than p99 should fail validation")
        void testP50GreaterThanP99() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(50.0, 10.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p50");
            assertThat(result.errors()).contains(">");
            assertThat(result.errors()).contains("p99");
        }

        @Test
        @DisplayName("Zero latency should FAIL validation (latency must be POSITIVE)")
        void testZeroLatency() {
            // Domain correctness: Zero latency indicates measurement error
            // Real operations take non-zero time; sub-ms operations need ns precision
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(0.0, 0.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p50 must be POSITIVE");
            assertThat(result.errors()).contains("p99 must be POSITIVE");
        }
    }

    @Nested
    @DisplayName("Positive Constraint")
    class PositiveConstraintTests {

        @Test
        @DisplayName("Negative p50 should fail validation")
        void testNegativeP50() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(-1.0, 10.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p50 must be POSITIVE");
        }

        @Test
        @DisplayName("Negative p99 should fail validation")
        void testNegativeP99() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(5.0, -1.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p99 must be POSITIVE");
        }

        @Test
        @DisplayName("Both negative should fail validation")
        void testBothNegative() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(-5.0, -1.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p50 must be POSITIVE");
            assertThat(result.errors()).contains("p99 must be POSITIVE");
        }

        @Test
        @DisplayName("Zero p50 should fail validation (must be strictly positive)")
        void testZeroP50() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(0.0, 10.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p50 must be POSITIVE");
        }

        @Test
        @DisplayName("Zero p99 should fail validation (must be strictly positive)")
        void testZeroP99() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(5.0, 0.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p99 must be POSITIVE");
        }
    }

    @Nested
    @DisplayName("NaN and Infinite Values")
    class SpecialValueTests {

        @Test
        @DisplayName("NaN p50 should fail validation")
        void testNaNP50() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(Double.NaN, 10.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p50 is not a finite number");
        }

        @Test
        @DisplayName("NaN p99 should fail validation")
        void testNaNP99() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(5.0, Double.NaN);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p99 is not a finite number");
        }

        @Test
        @DisplayName("Infinite p50 should fail validation")
        void testInfiniteP50() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(Double.POSITIVE_INFINITY, 10.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p50 is not a finite number");
        }

        @Test
        @DisplayName("Negative infinite p99 should fail validation")
        void testNegativeInfiniteP99() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(5.0, Double.NEGATIVE_INFINITY);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("p99 is not a finite number");
        }
    }

    @Nested
    @DisplayName("Unit Detection Warnings")
    class UnitDetectionTests {

        @Test
        @DisplayName("Very large p50 should generate warning (likely nanoseconds)")
        void testLikelyNanosecondsP50() {
            // 5000ms = 5 seconds - suspiciously high, likely ns not converted
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(5000.0, 6000.0);

            assertThat(result.valid()).isTrue(); // Still valid, just a warning
            assertThat(result.warnings()).contains("may be nanoseconds not converted");
        }

        @Test
        @DisplayName("Very large p99 should generate warning")
        void testLikelyNanosecondsP99() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(100.0, 5000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.warnings()).contains("may be nanoseconds not converted");
        }
    }

    @Nested
    @DisplayName("Sample Count Validation")
    class SampleCountTests {

        @Test
        @DisplayName("Low sample count should generate warning")
        void testLowSampleCount() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(5.0, 25.0, 5);

            assertThat(result.valid()).isTrue();
            assertThat(result.warnings()).contains("percentile estimates may be unreliable");
        }

        @Test
        @DisplayName("Sufficient sample count should not generate warning")
        void testSufficientSampleCount() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(5.0, 25.0, 100);

            assertThat(result.valid()).isTrue();
            assertThat(result.warnings()).doesNotContain("percentile estimates may be unreliable");
        }

        @Test
        @DisplayName("Zero sample count should not generate warning (not provided)")
        void testZeroSampleCount() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(5.0, 25.0, 0);

            assertThat(result.valid()).isTrue();
            assertThat(result.warnings()).doesNotContain("percentile estimates may be unreliable");
        }
    }

    @Nested
    @DisplayName("Assert Method")
    class AssertMethodTests {

        @Test
        @DisplayName("assertValid should not throw for valid percentiles")
        void testAssertValidPasses() {
            assertThatCode(() -> LatencyPercentileValidator.assertValid(5.0, 25.0))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertValid should throw for invalid percentiles")
        void testAssertValidThrows() {
            assertThatThrownBy(() -> LatencyPercentileValidator.assertValid(50.0, 10.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Latency percentile validation failed");
        }
    }

    @Nested
    @DisplayName("Format Summary")
    class FormatSummaryTests {

        @Test
        @DisplayName("Format summary should show valid status")
        void testFormatSummaryValid() {
            String summary = LatencyPercentileValidator.formatSummary(5.0, 25.0);

            assertThat(summary).contains("p50: 5.000 ms");
            assertThat(summary).contains("p99: 25.000 ms");
            assertThat(summary).contains("ratio (p99/p50): 5.00x");
            assertThat(summary).contains("status: VALID");
        }

        @Test
        @DisplayName("Format summary should show invalid status")
        void testFormatSummaryInvalid() {
            String summary = LatencyPercentileValidator.formatSummary(50.0, 10.0);

            assertThat(summary).contains("status: INVALID");
        }

        @Test
        @DisplayName("Format summary should show warnings")
        void testFormatSummaryWithWarnings() {
            String summary = LatencyPercentileValidator.formatSummary(5000.0, 6000.0);

            assertThat(summary).contains("with warnings");
        }
    }

    @Nested
    @DisplayName("BenchmarkResult Validation")
    class BenchmarkResultValidationTests {

        @Test
        @DisplayName("Should validate BenchmarkResult with correct units")
        void testValidBenchmarkResult() {
            BenchmarkResult result = BenchmarkResult.builder()
                .benchmarkName("test.benchmark")
                .mode("sample")
                .primaryScore(100.0)
                .primaryScoreUnit("ms")
                .addMetric(BenchmarkMetric.latencyPercentile(50, 5.0, 0.5))
                .addMetric(BenchmarkMetric.latencyPercentile(99, 25.0, 2.0))
                .build();

            LatencyPercentileValidator.ValidationResult validation =
                LatencyPercentileValidator.validate(result);

            assertThat(validation.valid()).isTrue();
        }

        @Test
        @DisplayName("Should fail when p50 metric missing")
        void testMissingP50Metric() {
            BenchmarkResult result = BenchmarkResult.builder()
                .benchmarkName("test.benchmark")
                .mode("sample")
                .primaryScore(100.0)
                .primaryScoreUnit("ms")
                .addMetric(BenchmarkMetric.latencyPercentile(99, 25.0, 2.0))
                .build();

            LatencyPercentileValidator.ValidationResult validation =
                LatencyPercentileValidator.validate(result);

            assertThat(validation.valid()).isFalse();
            assertThat(validation.errors()).contains("p50 metric missing");
        }

        @Test
        @DisplayName("Should fail when p99 metric missing")
        void testMissingP99Metric() {
            BenchmarkResult result = BenchmarkResult.builder()
                .benchmarkName("test.benchmark")
                .mode("sample")
                .primaryScore(100.0)
                .primaryScoreUnit("ms")
                .addMetric(BenchmarkMetric.latencyPercentile(50, 5.0, 0.5))
                .build();

            LatencyPercentileValidator.ValidationResult validation =
                LatencyPercentileValidator.validate(result);

            assertThat(validation.valid()).isFalse();
            assertThat(validation.errors()).contains("p99 metric missing");
        }

        @Test
        @DisplayName("Should pass when no percentile metrics present")
        void testNoPercentileMetrics() {
            BenchmarkResult result = BenchmarkResult.builder()
                .benchmarkName("test.benchmark")
                .mode("thrpt")
                .primaryScore(100.0)
                .primaryScoreUnit("ops/sec")
                .build();

            LatencyPercentileValidator.ValidationResult validation =
                LatencyPercentileValidator.validate(result);

            assertThat(validation.valid()).isTrue();
            assertThat(validation.warnings()).contains("No p50/p99 metrics present");
        }
    }

    @Nested
    @DisplayName("Domain Correctness Scenarios")
    class DomainCorrectnessScenarios {

        @ParameterizedTest
        @CsvSource({
            "1.0, 2.0",      // Very fast queries
            "5.0, 25.0",     // Typical small dataset
            "10.0, 50.0",    // Target latency
            "50.0, 200.0",   // Larger dataset
            "100.0, 500.0"   // Heavy workload
        })
        @DisplayName("Typical latency ranges should be valid")
        void testTypicalLatencyRanges(double p50, double p99) {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(p50, p99);

            assertThat(result.valid())
                .as("p50=%.1fms, p99=%.1fms should be valid", p50, p99)
                .isTrue();
        }

        @Test
        @DisplayName("Should handle very small latencies (microsecond scale)")
        void testVerySmallLatencies() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(0.001, 0.005);

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Should handle edge case: p50 just below p99")
        void testEdgeCaseP50JustBelowP99() {
            LatencyPercentileValidator.ValidationResult result =
                LatencyPercentileValidator.validate(9.999, 10.0);

            assertThat(result.valid()).isTrue();
        }
    }
}
