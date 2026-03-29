package excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ThroughputValidator domain correctness validation.
 *
 * <p>Verifies that throughput (ops/sec) values are always positive numbers,
 * which is a critical domain correctness requirement for benchmarks.</p>
 *
 * @author ExcaliburDB
 * @since Phase 10
 */
class ThroughputValidatorTest {

    // ==================== Basic Validation Tests ====================

    @Nested
    @DisplayName("Basic Validation")
    class BasicValidationTests {

        @Test
        @DisplayName("Valid positive throughput should pass validation")
        void validPositiveThroughput_shouldPass() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(25_500_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.throughput()).isEqualTo(25_500_000.0);
            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("Small positive throughput should pass validation")
        void smallPositiveThroughput_shouldPass() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(0.001);

            assertThat(result.valid()).isTrue();
            assertThat(result.throughput()).isEqualTo(0.001);
        }

        @Test
        @DisplayName("Very large positive throughput should pass validation")
        void veryLargePositiveThroughput_shouldPass() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(1_000_000_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.throughput()).isEqualTo(1_000_000_000.0);
        }

        @Test
        @DisplayName("Zero throughput should FAIL validation")
        void zeroThroughput_shouldFail() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(0.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("must be POSITIVE");
            assertThat(result.errors()).contains("0");
        }

        @Test
        @DisplayName("Negative throughput should FAIL validation")
        void negativeThroughput_shouldFail() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(-1_000_000.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("must be POSITIVE");
            assertThat(result.errors()).contains("-1");
        }

        @Test
        @DisplayName("NaN throughput should FAIL validation")
        void nanThroughput_shouldFail() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(Double.NaN);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("NaN");
        }

        @Test
        @DisplayName("Positive infinity throughput should FAIL validation")
        void positiveInfinityThroughput_shouldFail() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(Double.POSITIVE_INFINITY);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("infinite");
        }

        @Test
        @DisplayName("Negative infinity throughput should FAIL validation")
        void negativeInfinityThroughput_shouldFail() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(Double.NEGATIVE_INFINITY);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("infinite");
        }
    }

    // ==================== Warning Threshold Tests ====================

    @Nested
    @DisplayName("Warning Thresholds")
    class WarningThresholdTests {

        @Test
        @DisplayName("Very low throughput should generate warning")
        void veryLowThroughput_shouldWarn() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(100.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.warnings()).contains("below reasonable minimum");
        }

        @Test
        @DisplayName("Very high throughput should generate warning")
        void veryHighThroughput_shouldWarn() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(5_000_000_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.warnings()).contains("exceeds reasonable maximum");
        }

        @Test
        @DisplayName("Reasonable throughput should not generate warnings")
        void reasonableThroughput_shouldNotWarn() {
            ThroughputValidator.ValidationResult result = ThroughputValidator.validate(10_000_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isFalse();
        }
    }

    // ==================== Target Validation Tests ====================

    @Nested
    @DisplayName("Target Validation")
    class TargetValidationTests {

        @Test
        @DisplayName("Throughput meeting target should pass")
        void throughputMeetingTarget_shouldPass() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validateWithTarget(25_000_000.0, 10_000_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("Throughput exactly at target should pass")
        void throughputExactlyAtTarget_shouldPass() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validateWithTarget(10_000_000.0, 10_000_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("Throughput below target should fail")
        void throughputBelowTarget_shouldFail() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validateWithTarget(5_000_000.0, 10_000_000.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.meetsTarget()).isFalse();
            assertThat(result.errors()).contains("below target");
        }

        @Test
        @DisplayName("Throughput close to target (within 10%) should warn but pass")
        void throughputCloseToTarget_shouldWarnButPass() {
            // 9.5M is 5% below 10M target - should be accepted within tolerance
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validateWithTarget(9_500_000.0, 10_000_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
        }
    }

    // ==================== Specific Benchmark Type Tests ====================

    @Nested
    @DisplayName("Specific Benchmark Types")
    class SpecificBenchmarkTypeTests {

        @Test
        @DisplayName("Point lookup throughput validation")
        void pointLookupValidation() {
            // Valid point lookup throughput
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validatePointLookup(35_000_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("Point lookup below target should fail")
        void pointLookupBelowTarget_shouldFail() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validatePointLookup(5_000_000.0);

            assertThat(result.valid()).isFalse();
            assertThat(result.meetsTarget()).isFalse();
        }

        @Test
        @DisplayName("Cuckoo filter lookup validation")
        void cuckooLookupValidation() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validateCuckooLookup(10_000_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("Distance computation validation")
        void distanceComputationValidation() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validateDistanceComputation(75_000_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("End-to-end query validation")
        void e2eQueryValidation() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validateE2EQuery(5_000.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }
    }

    // ==================== Utility Method Tests ====================

    @Nested
    @DisplayName("Utility Methods")
    class UtilityMethodTests {

        @Test
        @DisplayName("isValid should return true for positive values")
        void isValid_positive() {
            assertThat(ThroughputValidator.isValid(1.0)).isTrue();
            assertThat(ThroughputValidator.isValid(1_000_000.0)).isTrue();
            assertThat(ThroughputValidator.isValid(Double.MIN_VALUE)).isTrue();
        }

        @Test
        @DisplayName("isValid should return false for invalid values")
        void isValid_invalid() {
            assertThat(ThroughputValidator.isValid(0.0)).isFalse();
            assertThat(ThroughputValidator.isValid(-1.0)).isFalse();
            assertThat(ThroughputValidator.isValid(Double.NaN)).isFalse();
            assertThat(ThroughputValidator.isValid(Double.POSITIVE_INFINITY)).isFalse();
            assertThat(ThroughputValidator.isValid(Double.NEGATIVE_INFINITY)).isFalse();
        }

        @Test
        @DisplayName("meetsTarget should work correctly")
        void meetsTarget() {
            assertThat(ThroughputValidator.meetsTarget(15_000_000.0, 10_000_000.0)).isTrue();
            assertThat(ThroughputValidator.meetsTarget(10_000_000.0, 10_000_000.0)).isTrue();
            assertThat(ThroughputValidator.meetsTarget(5_000_000.0, 10_000_000.0)).isFalse();
            assertThat(ThroughputValidator.meetsTarget(0.0, 10_000_000.0)).isFalse();
        }

        @Test
        @DisplayName("ensurePositive should return value for valid input")
        void ensurePositive_validInput() {
            double result = ThroughputValidator.ensurePositive(25_000_000.0, 1_000_000.0);
            assertThat(result).isEqualTo(25_000_000.0);
        }

        @Test
        @DisplayName("ensurePositive should return default for invalid input")
        void ensurePositive_invalidInput() {
            double result = ThroughputValidator.ensurePositive(0.0, 1_000_000.0);
            assertThat(result).isEqualTo(1_000_000.0);

            result = ThroughputValidator.ensurePositive(-1.0, 1_000_000.0);
            assertThat(result).isEqualTo(1_000_000.0);

            result = ThroughputValidator.ensurePositive(Double.NaN, 1_000_000.0);
            assertThat(result).isEqualTo(1_000_000.0);
        }

        @Test
        @DisplayName("ensurePositive should reject invalid default")
        void ensurePositive_invalidDefault() {
            assertThatThrownBy(() -> ThroughputValidator.ensurePositive(1.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Default value must be positive");

            assertThatThrownBy(() -> ThroughputValidator.ensurePositive(1.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Default value must be positive");
        }

        @Test
        @DisplayName("assertValid should not throw for valid input")
        void assertValid_validInput() {
            assertThatCode(() -> ThroughputValidator.assertValid(25_000_000.0))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertValid should throw for invalid input")
        void assertValid_invalidInput() {
            assertThatThrownBy(() -> ThroughputValidator.assertValid(0.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validation failed");

            assertThatThrownBy(() -> ThroughputValidator.assertValid(-1.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validation failed");
        }
    }

    // ==================== Formatting Tests ====================

    @Nested
    @DisplayName("Formatting")
    class FormattingTests {

        @Test
        @DisplayName("Format billions")
        void formatBillions() {
            String formatted = ThroughputValidator.format(1_500_000_000.0);
            assertThat(formatted).contains("1.50B ops/sec");
        }

        @Test
        @DisplayName("Format millions")
        void formatMillions() {
            String formatted = ThroughputValidator.format(25_500_000.0);
            assertThat(formatted).contains("25.50M ops/sec");
        }

        @Test
        @DisplayName("Format thousands")
        void formatThousands() {
            String formatted = ThroughputValidator.format(5_500.0);
            assertThat(formatted).contains("5.50K ops/sec");
        }

        @Test
        @DisplayName("Format small values")
        void formatSmall() {
            String formatted = ThroughputValidator.format(500.0);
            assertThat(formatted).contains("500.00 ops/sec");
        }

        @Test
        @DisplayName("Format invalid")
        void formatInvalid() {
            assertThat(ThroughputValidator.format(0.0)).isEqualTo("INVALID");
            assertThat(ThroughputValidator.format(-1.0)).isEqualTo("INVALID");
            assertThat(ThroughputValidator.format(Double.NaN)).isEqualTo("INVALID");
        }

        @Test
        @DisplayName("Format precise")
        void formatPrecise() {
            String formatted = ThroughputValidator.formatPrecise(25_500_000.0);
            // Scientific notation format: 2.55E+07 or 2.55e+07
            assertThat(formatted).containsAnyOf("2.55E+07", "2.55e+07");
            assertThat(formatted).contains("ops/sec");
        }

        @Test
        @DisplayName("Format summary")
        void formatSummary() {
            String summary = ThroughputValidator.formatSummary(25_000_000.0);

            assertThat(summary).contains("Throughput Validation");
            assertThat(summary).contains("25.00M ops/sec");
            assertThat(summary).contains("VALID");
        }

        @Test
        @DisplayName("Format summary with target")
        void formatSummaryWithTarget() {
            String summary = ThroughputValidator.formatSummaryWithTarget(25_000_000.0, 10_000_000.0);

            assertThat(summary).contains("Throughput Validation");
            assertThat(summary).contains("25.00M ops/sec");
            assertThat(summary).contains("meets target: YES");
            assertThat(summary).contains("VALID");
        }

        @Test
        @DisplayName("Format summary with failed target")
        void formatSummaryWithFailedTarget() {
            String summary = ThroughputValidator.formatSummaryWithTarget(5_000_000.0, 10_000_000.0);

            assertThat(summary).contains("meets target: NO");
            assertThat(summary).contains("INVALID");
        }
    }

    // ==================== ValidationResult Record Tests ====================

    @Nested
    @DisplayName("ValidationResult Record")
    class ValidationResultTests {

        @Test
        @DisplayName("hasWarnings and hasErrors")
        void hasWarningsAndErrors() {
            ThroughputValidator.ValidationResult withWarning =
                ThroughputValidator.validate(100.0);
            assertThat(withWarning.hasWarnings()).isTrue();
            assertThat(withWarning.hasErrors()).isFalse();

            ThroughputValidator.ValidationResult withError =
                ThroughputValidator.validate(-1.0);
            assertThat(withError.hasWarnings()).isFalse();
            assertThat(withError.hasErrors()).isTrue();
        }

        @Test
        @DisplayName("combinedMessage with no issues")
        void combinedMessageNoIssues() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validateWithTarget(25_000_000.0, 10_000_000.0);

            assertThat(result.combinedMessage()).contains("meets target");
        }

        @Test
        @DisplayName("combinedMessage with errors")
        void combinedMessageWithErrors() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validate(-1.0);

            assertThat(result.combinedMessage()).contains("Errors:");
        }
    }

    // ==================== Parameterized Tests ====================

    @Nested
    @DisplayName("Parameterized Validation")
    class ParameterizedValidationTests {

        @ParameterizedTest
        @ValueSource(doubles = {
            Double.MIN_VALUE,
            0.001,
            1.0,
            1000.0,
            1_000_000.0,
            10_000_000.0,
            100_000_000.0,
            500_000_000.0
        })
        @DisplayName("Valid positive values should pass")
        void validPositiveValues(double value) {
            assertThat(ThroughputValidator.isValid(value)).isTrue();
            assertThat(ThroughputValidator.validate(value).valid()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(doubles = {
            -Double.MIN_VALUE,
            -0.001,
            -1.0,
            -1000.0,
            -1_000_000.0,
            -Double.MAX_VALUE
        })
        @DisplayName("Negative values should fail")
        void negativeValues(double value) {
            assertThat(ThroughputValidator.isValid(value)).isFalse();
            assertThat(ThroughputValidator.validate(value).valid()).isFalse();
        }
    }

    // ==================== Integration with BenchmarkMetric ====================

    @Nested
    @DisplayName("Integration with BenchmarkMetric")
    class BenchmarkMetricIntegrationTests {

        @Test
        @DisplayName("BenchmarkMetric.throughput should reject invalid values")
        void benchmarkMetricThroughput_rejectsInvalid() {
            assertThatThrownBy(() -> BenchmarkMetric.throughput(0.0, 0))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> BenchmarkMetric.throughput(-1.0, 0))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> BenchmarkMetric.throughput(Double.NaN, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Valid throughput metric should be created")
        void benchmarkMetricThroughput_valid() {
            BenchmarkMetric metric = BenchmarkMetric.throughput(25_000_000.0, 500_000.0);

            assertThat(metric.name()).isEqualTo("throughput");
            assertThat(metric.value()).isEqualTo(25_000_000.0);
            assertThat(metric.unit()).isEqualTo("ops/sec");
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Very small positive value (Double.MIN_VALUE)")
        void verySmallPositive() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validate(Double.MIN_VALUE);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isTrue(); // Below reasonable minimum
        }

        @Test
        @DisplayName("Double.MAX_VALUE")
        void doubleMaxValue() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validate(Double.MAX_VALUE);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isTrue(); // Exceeds reasonable maximum
        }

        @Test
        @DisplayName("-0.0 should be treated as invalid")
        void negativeZero() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validate(-0.0);

            // -0.0 == 0.0 in IEEE 754, so this should fail
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("Scientific notation values")
        void scientificNotation() {
            ThroughputValidator.ValidationResult result =
                ThroughputValidator.validate(2.5e7);

            assertThat(result.valid()).isTrue();
            assertThat(result.throughput()).isEqualTo(25_000_000.0);
        }
    }
}
