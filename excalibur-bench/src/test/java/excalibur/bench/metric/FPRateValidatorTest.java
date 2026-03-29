package excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for FPRateValidator domain correctness validation.
 *
 * <p>Verifies that false positive rate values are in range [0.0, 1.0]
 * and meet the PROJECT GOAL specification of ≤0.1% (0.001).</p>
 *
 * @author ExcaliburDB
 * @since Phase 10
 */
class FPRateValidatorTest {

    // ==================== Domain Correctness: Range [0.0, 1.0] Tests ====================

    @Nested
    @DisplayName("Domain Correctness: Range [0.0, 1.0]")
    class RangeValidationTests {

        @Test
        @DisplayName("Valid FP rate at minimum (0.0) should pass")
        void validAtMinimum_shouldPass() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(0.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.fpRate()).isEqualTo(0.0);
            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("Valid FP rate at maximum (1.0) should pass")
        void validAtMaximum_shouldPass() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(1.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.fpRate()).isEqualTo(1.0);
            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("Valid FP rate in middle of range should pass")
        void validInMiddle_shouldPass() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(0.5);

            assertThat(result.valid()).isTrue();
            assertThat(result.fpRate()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("FP rate meeting spec (0.001) should pass")
        void fpRateMeetingSpec_shouldPass() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(0.001);

            assertThat(result.valid()).isTrue();
            assertThat(result.fpRate()).isEqualTo(0.001);
        }

        @Test
        @DisplayName("FP rate below spec (0.0005) should pass")
        void fpRateBelowSpec_shouldPass() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(0.0005);

            assertThat(result.valid()).isTrue();
            assertThat(result.fpRate()).isEqualTo(0.0005);
        }

        @Test
        @DisplayName("Negative FP rate should FAIL - physically impossible")
        void negativeFpRate_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(-0.001);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("must be >= 0.0");
            assertThat(result.errors()).contains("physically impossible");
        }

        @Test
        @DisplayName("FP rate above 1.0 should FAIL - probability cannot exceed 100%")
        void fpRateAboveOne_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(1.5);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("must be <= 1.0");
            assertThat(result.errors()).contains("probability");
        }

        @Test
        @DisplayName("NaN FP rate should FAIL")
        void nanFpRate_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(Double.NaN);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("NaN");
        }

        @Test
        @DisplayName("Positive infinity FP rate should FAIL")
        void positiveInfinityFpRate_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(Double.POSITIVE_INFINITY);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("infinite");
        }

        @Test
        @DisplayName("Negative infinity FP rate should FAIL")
        void negativeInfinityFpRate_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(Double.NEGATIVE_INFINITY);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("infinite");
        }

        @Test
        @DisplayName("FP rate just above 1.0 should FAIL")
        void fpRateJustAboveOne_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(1.000001);

            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("FP rate just below 0.0 should FAIL")
        void fpRateJustBelowZero_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(-0.000001);

            assertThat(result.valid()).isFalse();
        }
    }

    // ==================== Specification Target Tests ====================

    @Nested
    @DisplayName("Specification Target (≤0.1%)")
    class SpecificationTargetTests {

        @Test
        @DisplayName("FP rate exactly at spec (0.001 = 0.1%) should meet target")
        void fpRateAtSpec_shouldMeetTarget() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithSpec(0.001);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("FP rate below spec (0.0005 = 0.05%) should meet target")
        void fpRateBelowSpec_shouldMeetTarget() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithSpec(0.0005);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("FP rate well below spec (0.0001 = 0.01%) should meet target")
        void fpRateWellBelowSpec_shouldMeetTarget() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithSpec(0.0001);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("FP rate above spec (0.002 = 0.2%) should NOT meet target")
        void fpRateAboveSpec_shouldNotMeetTarget() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithSpec(0.002);

            assertThat(result.valid()).isFalse();
            assertThat(result.meetsTarget()).isFalse();
            assertThat(result.errors()).contains("exceeds target");
        }

        @Test
        @DisplayName("FP rate significantly above spec (0.01 = 1%) should fail with detailed message")
        void fpRateSignificantlyAboveSpec_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithSpec(0.01);

            assertThat(result.valid()).isFalse();
            assertThat(result.meetsTarget()).isFalse();
            assertThat(result.errors()).contains("0.010000");
            assertThat(result.errors()).contains("1.0000%");
        }

        @Test
        @DisplayName("Zero FP rate should meet target (perfect filter)")
        void zeroFpRate_shouldMeetTarget() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithSpec(0.0);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("FP rate close to spec (0.0009) should meet target")
        void fpRateCloseToSpec_shouldMeetTarget() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithSpec(0.0009);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }
    }

    // ==================== Custom Target Tests ====================

    @Nested
    @DisplayName("Custom Target Validation")
    class CustomTargetTests {

        @Test
        @DisplayName("FP rate meeting custom target should pass")
        void fpRateMeetingCustomTarget_shouldPass() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithTarget(0.005, 0.01);

            assertThat(result.valid()).isTrue();
            assertThat(result.meetsTarget()).isTrue();
        }

        @Test
        @DisplayName("FP rate exceeding custom target should fail")
        void fpRateExceedingCustomTarget_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithTarget(0.02, 0.01);

            assertThat(result.valid()).isFalse();
            assertThat(result.meetsTarget()).isFalse();
        }

        @Test
        @DisplayName("Invalid target threshold should fail validation")
        void invalidTarget_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithTarget(0.001, -0.01);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("Invalid target threshold");
        }

        @Test
        @DisplayName("NaN target threshold should fail validation")
        void nanTarget_shouldFail() {
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithTarget(0.001, Double.NaN);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("Invalid target threshold");
        }
    }

    // ==================== Warning Threshold Tests ====================

    @Nested
    @DisplayName("Warning Thresholds")
    class WarningThresholdTests {

        @Test
        @DisplayName("High FP rate (>50%) should generate warning")
        void highFpRate_shouldWarn() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(0.75);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.warnings()).contains("unusually high");
        }

        @Test
        @DisplayName("Low FP rate should not generate warnings")
        void lowFpRate_shouldNotWarn() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(0.001);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isFalse();
        }

        @Test
        @DisplayName("FP rate close to spec should generate info warning")
        void fpRateCloseToSpec_shouldWarn() {
            // 0.0006 is > 50% of target (0.001 * 0.5 = 0.0005)
            FPRateValidator.ValidationResult result = FPRateValidator.validateWithSpec(0.0006);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.warnings()).contains("close to target");
        }
    }

    // ==================== Utility Method Tests ====================

    @Nested
    @DisplayName("Utility Methods")
    class UtilityMethodTests {

        @Test
        @DisplayName("isValid should return true for values in [0.0, 1.0]")
        void isValid_inRange() {
            assertThat(FPRateValidator.isValid(0.0)).isTrue();
            assertThat(FPRateValidator.isValid(0.001)).isTrue();
            assertThat(FPRateValidator.isValid(0.5)).isTrue();
            assertThat(FPRateValidator.isValid(1.0)).isTrue();
        }

        @Test
        @DisplayName("isValid should return false for values outside [0.0, 1.0]")
        void isValid_outOfRange() {
            assertThat(FPRateValidator.isValid(-0.001)).isFalse();
            assertThat(FPRateValidator.isValid(1.001)).isFalse();
            assertThat(FPRateValidator.isValid(Double.NaN)).isFalse();
            assertThat(FPRateValidator.isValid(Double.POSITIVE_INFINITY)).isFalse();
            assertThat(FPRateValidator.isValid(Double.NEGATIVE_INFINITY)).isFalse();
        }

        @Test
        @DisplayName("meetsSpec should return true for FP rate ≤ 0.001")
        void meetsSpec_valid() {
            assertThat(FPRateValidator.meetsSpec(0.0)).isTrue();
            assertThat(FPRateValidator.meetsSpec(0.0005)).isTrue();
            assertThat(FPRateValidator.meetsSpec(0.001)).isTrue();
        }

        @Test
        @DisplayName("meetsSpec should return false for FP rate > 0.001")
        void meetsSpec_invalid() {
            assertThat(FPRateValidator.meetsSpec(0.002)).isFalse();
            assertThat(FPRateValidator.meetsSpec(0.01)).isFalse();
            assertThat(FPRateValidator.meetsSpec(1.0)).isFalse();
        }

        @Test
        @DisplayName("meetsSpec should return false for invalid values")
        void meetsSpec_invalidValue() {
            assertThat(FPRateValidator.meetsSpec(-0.001)).isFalse();
            assertThat(FPRateValidator.meetsSpec(Double.NaN)).isFalse();
        }

        @Test
        @DisplayName("meetsTarget should work with custom targets")
        void meetsTarget_customTarget() {
            assertThat(FPRateValidator.meetsTarget(0.005, 0.01)).isTrue();
            assertThat(FPRateValidator.meetsTarget(0.01, 0.01)).isTrue();
            assertThat(FPRateValidator.meetsTarget(0.015, 0.01)).isFalse();
        }

        @Test
        @DisplayName("clampToValidRange should clamp values to [0.0, 1.0]")
        void clampToValidRange() {
            assertThat(FPRateValidator.clampToValidRange(0.5)).isEqualTo(0.5);
            assertThat(FPRateValidator.clampToValidRange(-0.5)).isEqualTo(0.0);
            assertThat(FPRateValidator.clampToValidRange(1.5)).isEqualTo(1.0);
            assertThat(FPRateValidator.clampToValidRange(Double.NaN)).isEqualTo(0.0);
            assertThat(FPRateValidator.clampToValidRange(Double.POSITIVE_INFINITY)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("ensureValid should return value for valid input")
        void ensureValid_validInput() {
            double result = FPRateValidator.ensureValid(0.001, 0.0);
            assertThat(result).isEqualTo(0.001);
        }

        @Test
        @DisplayName("ensureValid should return default for invalid input")
        void ensureValid_invalidInput() {
            double result = FPRateValidator.ensureValid(-0.5, 0.0);
            assertThat(result).isEqualTo(0.0);

            result = FPRateValidator.ensureValid(Double.NaN, 0.5);
            assertThat(result).isEqualTo(0.5);
        }

        @Test
        @DisplayName("ensureValid should reject invalid default")
        void ensureValid_invalidDefault() {
            assertThatThrownBy(() -> FPRateValidator.ensureValid(0.5, -0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Default value must be in range");

            assertThatThrownBy(() -> FPRateValidator.ensureValid(0.5, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Default value must be in range");
        }
    }

    // ==================== Assertion Method Tests ====================

    @Nested
    @DisplayName("Assertion Methods")
    class AssertionMethodTests {

        @Test
        @DisplayName("assertValid should not throw for valid input")
        void assertValid_validInput() {
            assertThatCode(() -> FPRateValidator.assertValid(0.001))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertValid should throw for invalid input")
        void assertValid_invalidInput() {
            assertThatThrownBy(() -> FPRateValidator.assertValid(-0.001))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validation failed");

            assertThatThrownBy(() -> FPRateValidator.assertValid(1.5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validation failed");
        }

        @Test
        @DisplayName("assertMeetsSpec should not throw for compliant FP rate")
        void assertMeetsSpec_valid() {
            assertThatCode(() -> FPRateValidator.assertMeetsSpec(0.001))
                .doesNotThrowAnyException();

            assertThatCode(() -> FPRateValidator.assertMeetsSpec(0.0005))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertMeetsSpec should throw for non-compliant FP rate")
        void assertMeetsSpec_invalid() {
            assertThatThrownBy(() -> FPRateValidator.assertMeetsSpec(0.01))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validation failed");
        }
    }

    // ==================== Formatting Tests ====================

    @Nested
    @DisplayName("Formatting")
    class FormattingTests {

        @Test
        @DisplayName("Format typical FP rate")
        void formatTypical() {
            String formatted = FPRateValidator.format(0.001);
            assertThat(formatted).isEqualTo("0.1000%");
        }

        @Test
        @DisplayName("Format small FP rate")
        void formatSmall() {
            String formatted = FPRateValidator.format(0.0001);
            assertThat(formatted).isEqualTo("0.0100%");
        }

        @Test
        @DisplayName("Format zero")
        void formatZero() {
            String formatted = FPRateValidator.format(0.0);
            assertThat(formatted).isEqualTo("0.0000%");
        }

        @Test
        @DisplayName("Format one")
        void formatOne() {
            String formatted = FPRateValidator.format(1.0);
            assertThat(formatted).isEqualTo("100.0000%");
        }

        @Test
        @DisplayName("Format invalid values")
        void formatInvalid() {
            assertThat(FPRateValidator.format(-0.5)).isEqualTo("INVALID");
            assertThat(FPRateValidator.format(1.5)).isEqualTo("INVALID");
            assertThat(FPRateValidator.format(Double.NaN)).isEqualTo("INVALID");
        }

        @Test
        @DisplayName("Format precise")
        void formatPrecise() {
            String formatted = FPRateValidator.formatPrecise(0.001);
            assertThat(formatted).contains("0.001000");
            assertThat(formatted).contains("0.1000%");
        }

        @Test
        @DisplayName("Format summary")
        void formatSummary() {
            String summary = FPRateValidator.formatSummary(0.001);

            assertThat(summary).contains("FP Rate Validation");
            assertThat(summary).contains("0.1000%");
            assertThat(summary).contains("VALID");
        }

        @Test
        @DisplayName("Format summary with target")
        void formatSummaryWithTarget() {
            String summary = FPRateValidator.formatSummaryWithTarget(0.001, 0.002);

            assertThat(summary).contains("FP Rate Validation");
            assertThat(summary).contains("meets target: YES");
            assertThat(summary).contains("VALID");
        }

        @Test
        @DisplayName("Format summary with failed target")
        void formatSummaryWithFailedTarget() {
            String summary = FPRateValidator.formatSummaryWithTarget(0.01, 0.001);

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
            FPRateValidator.ValidationResult withWarning =
                FPRateValidator.validate(0.75);
            assertThat(withWarning.hasWarnings()).isTrue();
            assertThat(withWarning.hasErrors()).isFalse();

            FPRateValidator.ValidationResult withError =
                FPRateValidator.validate(-0.5);
            assertThat(withError.hasWarnings()).isFalse();
            assertThat(withError.hasErrors()).isTrue();
        }

        @Test
        @DisplayName("combinedMessage with no issues")
        void combinedMessageNoIssues() {
            FPRateValidator.ValidationResult result =
                FPRateValidator.validateWithSpec(0.0005);

            assertThat(result.combinedMessage()).contains("meets specification");
        }

        @Test
        @DisplayName("combinedMessage with errors")
        void combinedMessageWithErrors() {
            FPRateValidator.ValidationResult result =
                FPRateValidator.validate(-0.5);

            assertThat(result.combinedMessage()).contains("Errors:");
        }

        @Test
        @DisplayName("fpRateFormatted")
        void fpRateFormatted() {
            FPRateValidator.ValidationResult result = FPRateValidator.validate(0.001);
            assertThat(result.fpRateFormatted()).isEqualTo("0.1000%");
        }
    }

    // ==================== Parameterized Tests ====================

    @Nested
    @DisplayName("Parameterized Validation")
    class ParameterizedValidationTests {

        @ParameterizedTest
        @ValueSource(doubles = {
            0.0,
            0.0001,
            0.001,
            0.01,
            0.1,
            0.5,
            0.9,
            1.0
        })
        @DisplayName("Valid values in [0.0, 1.0] should pass")
        void validValues(double value) {
            assertThat(FPRateValidator.isValid(value)).isTrue();
            assertThat(FPRateValidator.validate(value).valid()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(doubles = {
            -0.0001,
            -0.001,
            -0.5,
            -1.0,
            1.0001,
            1.5,
            2.0
        })
        @DisplayName("Invalid values outside [0.0, 1.0] should fail")
        void invalidValues(double value) {
            assertThat(FPRateValidator.isValid(value)).isFalse();
            assertThat(FPRateValidator.validate(value).valid()).isFalse();
        }
    }

    // ==================== Constants Tests ====================

    @Nested
    @DisplayName("Constants")
    class ConstantsTests {

        @Test
        @DisplayName("MAX_FP_RATE should be 0.001 (0.1%)")
        void maxFpRateConstant() {
            assertThat(FPRateValidator.MAX_FP_RATE).isEqualTo(0.001);
        }

        @Test
        @DisplayName("MAX_FP_RATE_PERCENT should be 0.1")
        void maxFpRatePercentConstant() {
            assertThat(FPRateValidator.MAX_FP_RATE_PERCENT).isEqualTo(0.1);
        }

        @Test
        @DisplayName("MIN_FP_RATE should be 0.0")
        void minFpRateConstant() {
            assertThat(FPRateValidator.MIN_FP_RATE).isEqualTo(0.0);
        }

        @Test
        @DisplayName("MAX_VALID_FP_RATE should be 1.0")
        void maxValidFpRateConstant() {
            assertThat(FPRateValidator.MAX_VALID_FP_RATE).isEqualTo(1.0);
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Very small positive value (Double.MIN_VALUE)")
        void verySmallPositive() {
            FPRateValidator.ValidationResult result =
                FPRateValidator.validate(Double.MIN_VALUE);

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Scientific notation values")
        void scientificNotation() {
            FPRateValidator.ValidationResult result =
                FPRateValidator.validate(1e-4); // 0.0001

            assertThat(result.valid()).isTrue();
            assertThat(result.fpRate()).isEqualTo(0.0001);
        }

        @Test
        @DisplayName("-0.0 should be treated as valid (equals 0.0)")
        void negativeZero() {
            FPRateValidator.ValidationResult result =
                FPRateValidator.validate(-0.0);

            // -0.0 == 0.0 in IEEE 754
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Typical Cuckoo filter FP rates should all pass spec")
        void typicalCuckooFpRates() {
            // 13-bit fingerprint at various load factors
            assertThat(FPRateValidator.meetsSpec(0.0002)).isTrue(); // ~50% load
            assertThat(FPRateValidator.meetsSpec(0.0005)).isTrue(); // ~75% load
            assertThat(FPRateValidator.meetsSpec(0.0008)).isTrue(); // ~90% load
            assertThat(FPRateValidator.meetsSpec(0.00093)).isTrue(); // ~95% load (theoretical)
        }
    }

    // ==================== Integration with CuckooFilterFPRateBenchmark ====================

    @Nested
    @DisplayName("Integration with CuckooFilterFPRateBenchmark")
    class BenchmarkIntegrationTests {

        @Test
        @DisplayName("MAX_ACCEPTABLE_FP_RATE in benchmark should match validator constant")
        void benchmarkConstantMatches() {
            // This ensures consistency between the benchmark and validator
            assertThat(excalibur.bench.benchmark.CuckooFilterFPRateBenchmark.MAX_ACCEPTABLE_FP_RATE)
                .isEqualTo(FPRateValidator.MAX_FP_RATE);
        }
    }
}
