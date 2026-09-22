package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for RecallValidator.
 *
 * <p>Validates that recall metrics are correctly validated for:</p>
 * <ul>
 *   <li>Range [0.0, 1.0]</li>
 *   <li>Target threshold ≥0.99 (99%)</li>
 *   <li>Domain correctness for HNSW recall@10</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since Phase 10
 */
@DisplayName("RecallValidator Tests")
class RecallValidatorTest {

    // ==================== Constants ====================

    @Test
    @DisplayName("TARGET_RECALL_10 should be 0.99 (99%)")
    void testTargetRecallConstant() {
        assertThat(RecallValidator.TARGET_RECALL_10)
            .as("Target recall@10 must be exactly 0.99")
            .isEqualTo(0.99);
    }

    @Test
    @DisplayName("MIN_RECALL should be 0.0")
    void testMinRecallConstant() {
        assertThat(RecallValidator.MIN_RECALL)
            .as("Minimum recall must be 0.0")
            .isEqualTo(0.0);
    }

    @Test
    @DisplayName("MAX_RECALL should be 1.0")
    void testMaxRecallConstant() {
        assertThat(RecallValidator.MAX_RECALL)
            .as("Maximum recall must be 1.0")
            .isEqualTo(1.0);
    }

    // ==================== Range Validation ====================

    @Nested
    @DisplayName("Range Validation")
    class RangeValidationTests {

        @ParameterizedTest
        @ValueSource(doubles = {0.0, 0.5, 0.99, 1.0, 0.9999, 0.0001})
        @DisplayName("valid recall values in [0.0, 1.0] should pass validation")
        void testValidRangeValues(double recall) {
            RecallValidator.ValidationResult result = RecallValidator.validateRange(recall);

            assertThat(result.valid())
                .as("Recall %.4f should be valid", recall)
                .isTrue();

            assertThat(result.errors())
                .as("Valid recall should have no errors")
                .isEmpty();
        }

        @ParameterizedTest
        @ValueSource(doubles = {-0.1, -1.0, -0.0001, -100.0})
        @DisplayName("negative recall values should fail validation")
        void testNegativeRecallValues(double recall) {
            RecallValidator.ValidationResult result = RecallValidator.validateRange(recall);

            assertThat(result.valid())
                .as("Negative recall %.4f should be invalid", recall)
                .isFalse();

            assertThat(result.errors())
                .as("Negative recall should have error message")
                .contains("below minimum");
        }

        @ParameterizedTest
        @ValueSource(doubles = {1.1, 1.5, 2.0, 100.0})
        @DisplayName("recall values > 1.0 should fail validation")
        void testRecallAboveOne(double recall) {
            RecallValidator.ValidationResult result = RecallValidator.validateRange(recall);

            assertThat(result.valid())
                .as("Recall %.4f > 1.0 should be invalid", recall)
                .isFalse();

            assertThat(result.errors())
                .as("Recall > 1.0 should have error message")
                .contains("exceeds maximum");
        }

        @Test
        @DisplayName("NaN should fail validation")
        void testNaNRecall() {
            RecallValidator.ValidationResult result = RecallValidator.validateRange(Double.NaN);

            assertThat(result.valid())
                .as("NaN recall should be invalid")
                .isFalse();

            assertThat(result.errors())
                .as("NaN recall should have error message")
                .contains("NaN");
        }

        @Test
        @DisplayName("positive infinity should fail validation")
        void testPositiveInfinityRecall() {
            RecallValidator.ValidationResult result = RecallValidator.validateRange(Double.POSITIVE_INFINITY);

            assertThat(result.valid())
                .as("Positive infinity recall should be invalid")
                .isFalse();

            assertThat(result.errors())
                .as("Positive infinity should have error message")
                .contains("infinite");
        }

        @Test
        @DisplayName("negative infinity should fail validation")
        void testNegativeInfinityRecall() {
            RecallValidator.ValidationResult result = RecallValidator.validateRange(Double.NEGATIVE_INFINITY);

            assertThat(result.valid())
                .as("Negative infinity recall should be invalid")
                .isFalse();

            assertThat(result.errors())
                .as("Negative infinity should have error message")
                .contains("infinite");
        }

        @Test
        @DisplayName("low recall (< 50%) should generate warning")
        void testLowRecallWarning() {
            RecallValidator.ValidationResult result = RecallValidator.validateRange(0.3);

            assertThat(result.valid())
                .as("Low recall should still be valid in range")
                .isTrue();

            assertThat(result.warnings())
                .as("Low recall should generate quality warning")
                .contains("WARNING");
        }
    }

    // ==================== Target Validation ====================

    @Nested
    @DisplayName("Target Validation")
    class TargetValidationTests {

        @ParameterizedTest
        @ValueSource(doubles = {0.99, 0.995, 0.999, 1.0})
        @DisplayName("recall ≥ 0.99 should meet target")
        void testRecallMeetsTarget(double recall) {
            RecallValidator.ValidationResult result = RecallValidator.validateWithTarget(recall);

            assertThat(result.valid())
                .as("Recall %.4f should be valid", recall)
                .isTrue();

            assertThat(result.meetsTarget())
                .as("Recall %.4f should meet target ≥ 0.99", recall)
                .isTrue();
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.98, 0.95, 0.90, 0.50, 0.0})
        @DisplayName("recall < 0.99 should not meet target")
        void testRecallBelowTarget(double recall) {
            RecallValidator.ValidationResult result = RecallValidator.validateWithTarget(recall);

            assertThat(result.valid())
                .as("Recall %.4f should still be valid (in range)", recall)
                .isTrue();

            assertThat(result.meetsTarget())
                .as("Recall %.4f should not meet target ≥ 0.99", recall)
                .isFalse();
        }

        @Test
        @DisplayName("recall just below target (0.985) should generate warning but pass")
        void testRecallJustBelowTarget() {
            // Recall within 1% of target should be accepted with warning
            RecallValidator.ValidationResult result = RecallValidator.validateWithTarget(0.985);

            assertThat(result.valid())
                .as("Recall close to target should be valid")
                .isTrue();

            assertThat(result.warnings())
                .as("Recall close to target should have warning")
                .isNotEmpty();
        }

        @Test
        @DisplayName("custom target should be respected")
        void testCustomTarget() {
            double customTarget = 0.95;

            RecallValidator.ValidationResult result = RecallValidator.validateWithTarget(0.96, customTarget);

            assertThat(result.valid())
                .as("Recall 0.96 should be valid")
                .isTrue();

            assertThat(result.meetsTarget())
                .as("Recall 0.96 should meet custom target 0.95", customTarget)
                .isTrue();
        }
    }

    // ==================== Recall@k Validation ====================

    @Nested
    @DisplayName("Recall@k Validation")
    class RecallAtKTests {

        @Test
        @DisplayName("recall@10 with value 0.99 should pass and meet target")
        void testRecallAt10MeetsTarget() {
            RecallValidator.ValidationResult result = RecallValidator.validateRecallAtK(0.99, 10);

            assertThat(result.valid())
                .as("Recall@10 = 0.99 should be valid")
                .isTrue();

            assertThat(result.meetsTarget())
                .as("Recall@10 = 0.99 should meet target")
                .isTrue();
        }

        @Test
        @DisplayName("recall@10 with value 0.95 should be valid but not meet target")
        void testRecallAt10BelowTarget() {
            RecallValidator.ValidationResult result = RecallValidator.validateRecallAtK(0.95, 10);

            assertThat(result.valid())
                .as("Recall@10 = 0.95 should be valid (in range)")
                .isTrue();

            assertThat(result.meetsTarget())
                .as("Recall@10 = 0.95 should not meet target ≥ 0.99")
                .isFalse();
        }

        @Test
        @DisplayName("recall@5 with value 0.95 should meet default target")
        void testRecallAt5WithGoodValue() {
            RecallValidator.ValidationResult result = RecallValidator.validateRecallAtK(0.96, 5);

            assertThat(result.valid())
                .as("Recall@5 = 0.96 should be valid")
                .isTrue();

            // For k != 10, default target is 0.95
            assertThat(result.meetsTarget())
                .as("Recall@5 = 0.96 should meet default target (0.95)")
                .isTrue();
        }
    }

    // ==================== Clamping ====================

    @Nested
    @DisplayName("Clamping")
    class ClampingTests {

        @Test
        @DisplayName("negative recall should be clamped to 0.0")
        void testClampNegative() {
            double clamped = RecallValidator.clamp(-0.5);

            assertThat(clamped)
                .as("Negative recall should be clamped to 0.0")
                .isEqualTo(0.0);
        }

        @Test
        @DisplayName("recall > 1.0 should be clamped to 1.0")
        void testClampAboveOne() {
            double clamped = RecallValidator.clamp(1.5);

            assertThat(clamped)
                .as("Recall > 1.0 should be clamped to 1.0")
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("valid recall should not be modified")
        void testClampValidValue() {
            double clamped = RecallValidator.clamp(0.75);

            assertThat(clamped)
                .as("Valid recall should not be modified")
                .isEqualTo(0.75);
        }

        @Test
        @DisplayName("NaN should be clamped to 0.0")
        void testClampNaN() {
            double clamped = RecallValidator.clamp(Double.NaN);

            assertThat(clamped)
                .as("NaN should be clamped to 0.0")
                .isEqualTo(0.0);
        }

        @Test
        @DisplayName("infinity should be clamped to 0.0")
        void testClampInfinity() {
            double clamped = RecallValidator.clamp(Double.POSITIVE_INFINITY);

            assertThat(clamped)
                .as("Infinity should be clamped to 0.0")
                .isEqualTo(0.0);
        }
    }

    // ==================== Utility Methods ====================

    @Nested
    @DisplayName("Utility Methods")
    class UtilityMethodTests {

        @Test
        @DisplayName("isValidRange should return correct boolean")
        void testIsValidRange() {
            assertThat(RecallValidator.isValidRange(0.5)).isTrue();
            assertThat(RecallValidator.isValidRange(0.0)).isTrue();
            assertThat(RecallValidator.isValidRange(1.0)).isTrue();
            assertThat(RecallValidator.isValidRange(-0.1)).isFalse();
            assertThat(RecallValidator.isValidRange(1.1)).isFalse();
            assertThat(RecallValidator.isValidRange(Double.NaN)).isFalse();
            assertThat(RecallValidator.isValidRange(Double.POSITIVE_INFINITY)).isFalse();
        }

        @Test
        @DisplayName("meetsTarget should return correct boolean")
        void testMeetsTarget() {
            assertThat(RecallValidator.meetsTarget(0.99)).isTrue();
            assertThat(RecallValidator.meetsTarget(1.0)).isTrue();
            assertThat(RecallValidator.meetsTarget(0.98)).isFalse();
            assertThat(RecallValidator.meetsTarget(0.95)).isFalse();
        }

        @Test
        @DisplayName("meetsTarget with custom target should work correctly")
        void testMeetsTargetCustom() {
            assertThat(RecallValidator.meetsTarget(0.95, 0.90)).isTrue();
            assertThat(RecallValidator.meetsTarget(0.85, 0.90)).isFalse();
        }

        @Test
        @DisplayName("formatPercent should format correctly")
        void testFormatPercent() {
            assertThat(RecallValidator.formatPercent(0.9923))
                .isEqualTo("99.23%");

            assertThat(RecallValidator.formatPercent(0.5))
                .isEqualTo("50.00%");

            assertThat(RecallValidator.formatPercent(1.0))
                .isEqualTo("100.00%");

            assertThat(RecallValidator.formatPercent(0.0))
                .isEqualTo("0.00%");
        }

        @Test
        @DisplayName("formatDecimal should format correctly")
        void testFormatDecimal() {
            assertThat(RecallValidator.formatDecimal(0.9923))
                .isEqualTo("0.9923");

            assertThat(RecallValidator.formatDecimal(0.5))
                .isEqualTo("0.5000");

            assertThat(RecallValidator.formatDecimal(1.0))
                .isEqualTo("1.0000");
        }

        @Test
        @DisplayName("formatPercent should clamp out-of-range values")
        void testFormatPercentClamping() {
            assertThat(RecallValidator.formatPercent(-0.1))
                .isEqualTo("0.00%");

            assertThat(RecallValidator.formatPercent(1.5))
                .isEqualTo("100.00%");
        }
    }

    // ==================== Assertion Methods ====================

    @Nested
    @DisplayName("Assertion Methods")
    class AssertionMethodTests {

        @Test
        @DisplayName("assertValidRange should not throw for valid recall")
        void testAssertValidRangeSuccess() {
            assertThatCode(() -> RecallValidator.assertValidRange(0.99))
                .as("Valid recall should not throw")
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertValidRange should throw for invalid recall")
        void testAssertValidRangeFailure() {
            assertThatThrownBy(() -> RecallValidator.assertValidRange(1.5))
                .as("Invalid recall should throw")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validation failed");
        }

        @Test
        @DisplayName("assertValidAndMeetsTarget should not throw for valid recall meeting target")
        void testAssertValidAndMeetsTargetSuccess() {
            assertThatCode(() -> RecallValidator.assertValidAndMeetsTarget(0.99))
                .as("Recall meeting target should not throw")
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertValidAndMeetsTarget should throw for recall below target")
        void testAssertValidAndMeetsTargetFailure() {
            assertThatThrownBy(() -> RecallValidator.assertValidAndMeetsTarget(0.95))
                .as("Recall below target should throw")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validation failed");
        }
    }

    // ==================== Format Summary ====================

    @Nested
    @DisplayName("Format Summary")
    class FormatSummaryTests {

        @Test
        @DisplayName("formatSummary should include recall value and target")
        void testFormatSummary() {
            String summary = RecallValidator.formatSummary(0.995);

            assertThat(summary)
                .as("Summary should contain recall value")
                .contains("0.9950")
                .contains("99.50%")
                .contains("target")
                .contains("meets target");
        }

        @Test
        @DisplayName("formatSummaryAtK should include k value")
        void testFormatSummaryAtK() {
            String summary = RecallValidator.formatSummaryAtK(0.99, 10);

            assertThat(summary)
                .as("Summary should contain k=10")
                .contains("recall@10")
                .contains("0.9900")
                .contains("99.00%");
        }
    }

    // ==================== ValidationResult Record ====================

    @Nested
    @DisplayName("ValidationResult Record")
    class ValidationResultTests {

        @Test
        @DisplayName("hasWarnings should return correct boolean")
        void testHasWarnings() {
            RecallValidator.ValidationResult withWarnings = new RecallValidator.ValidationResult(
                true, "", "some warning", 0.99, true);
            RecallValidator.ValidationResult withoutWarnings = new RecallValidator.ValidationResult(
                true, "", "", 0.99, true);

            assertThat(withWarnings.hasWarnings()).isTrue();
            assertThat(withoutWarnings.hasWarnings()).isFalse();
        }

        @Test
        @DisplayName("combinedMessage should combine errors and warnings")
        void testCombinedMessage() {
            RecallValidator.ValidationResult withBoth = new RecallValidator.ValidationResult(
                false, "error message", "warning message", 0.95, false);

            String combined = withBoth.combinedMessage();

            assertThat(combined)
                .as("Combined message should contain both error and warning")
                .contains("error message")
                .contains("warning message");
        }

        @Test
        @DisplayName("recallPercent should format recall as percentage")
        void testRecallPercent() {
            RecallValidator.ValidationResult result = new RecallValidator.ValidationResult(
                true, "", "", 0.9923, true);

            assertThat(result.recallPercent())
                .isEqualTo("99.23%");
        }
    }
}
