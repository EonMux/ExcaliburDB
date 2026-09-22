package com.eonmux.excalibur.core.report;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import com.eonmux.excalibur.core.report.CoverageValidator.CoverageLevel;
import com.eonmux.excalibur.core.report.CoverageValidator.ValidationResult;

/**
 * Tests for {@link CoverageValidator}.
 *
 * <p>Verifies that coverage percentage calculations are bounded to [0.0, 100.0]
 * and threshold validation works correctly.</p>
 *
 * <p>Thread Safety: Each test is independent and operates on isolated inputs.</p>
 */
class CoverageValidatorTest {

    // ==================== calculatePercentage tests ====================

    @Test
    void calculatePercentage_shouldReturnZeroForEmptyCode() {
        // Domain correctness: No code to cover means 0% coverage
        assertThat(CoverageValidator.calculatePercentage(0, 0)).isEqualTo(0.0);
    }

    @Test
    void calculatePercentage_shouldReturn100ForFullyCovered() {
        assertThat(CoverageValidator.calculatePercentage(100, 0)).isEqualTo(100.0);
        assertThat(CoverageValidator.calculatePercentage(1000, 0)).isEqualTo(100.0);
        assertThat(CoverageValidator.calculatePercentage(1, 0)).isEqualTo(100.0);
    }

    @Test
    void calculatePercentage_shouldReturnZeroForNoCoverage() {
        assertThat(CoverageValidator.calculatePercentage(0, 100)).isEqualTo(0.0);
        assertThat(CoverageValidator.calculatePercentage(0, 1000)).isEqualTo(0.0);
        assertThat(CoverageValidator.calculatePercentage(0, 1)).isEqualTo(0.0);
    }

    @ParameterizedTest
    @CsvSource({
        "50, 50, 50.0",           // 50% coverage
        "80, 20, 80.0",           // 80% coverage (threshold)
        "75, 25, 75.0",           // 75% coverage
        "33, 67, 33.0",           // 33% coverage
        "99, 1, 99.0",            // 99% coverage
        "1, 99, 1.0",             // 1% coverage
        "1234, 5678, 17.8571",    // Realistic numbers
        "10000, 5000, 66.6667"    // 2/3 coverage
    })
    void calculatePercentage_shouldReturnCorrectValue(long covered, long missed, double expected) {
        double result = CoverageValidator.calculatePercentage(covered, missed);
        assertThat(result).isCloseTo(expected, within(0.01));
    }

    @Test
    void calculatePercentage_shouldAlwaysBeBoundedToValidRange() {
        // Test edge cases that could potentially produce out-of-range values
        assertThat(CoverageValidator.calculatePercentage(Long.MAX_VALUE, 0)).isEqualTo(100.0);
        assertThat(CoverageValidator.calculatePercentage(0, Long.MAX_VALUE)).isEqualTo(0.0);
        assertThat(CoverageValidator.calculatePercentage(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2))
            .isBetween(0.0, 100.0);
    }

    @Test
    void calculatePercentage_shouldThrowForNegativeCovered() {
        assertThatThrownBy(() -> CoverageValidator.calculatePercentage(-1, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    @Test
    void calculatePercentage_shouldThrowForNegativeMissed() {
        assertThatThrownBy(() -> CoverageValidator.calculatePercentage(100, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative");
    }

    // ==================== validate tests ====================

    @Test
    void validate_shouldPassWhenAtThreshold() {
        ValidationResult result = CoverageValidator.validate(80.0, 80.0);
        assertThat(result.passed()).isTrue();
        assertThat(result.gap()).isEqualTo(0.0);
    }

    @Test
    void validate_shouldPassWhenAboveThreshold() {
        ValidationResult result = CoverageValidator.validate(85.0, 80.0);
        assertThat(result.passed()).isTrue();
        assertThat(result.actualPercentage()).isEqualTo(85.0);
        assertThat(result.threshold()).isEqualTo(80.0);
    }

    @Test
    void validate_shouldFailWhenBelowThreshold() {
        ValidationResult result = CoverageValidator.validate(75.0, 80.0);
        assertThat(result.passed()).isFalse();
        assertThat(result.gap()).isEqualTo(5.0);
        assertThat(result.actualPercentage()).isEqualTo(75.0);
        assertThat(result.threshold()).isEqualTo(80.0);
    }

    @Test
    void validate_shouldUseDefaultThreshold() {
        ValidationResult result = CoverageValidator.validate(80.0);
        assertThat(result.passed()).isTrue();
        assertThat(result.threshold()).isEqualTo(80.0);
    }

    @Test
    void validate_shouldFailBelowDefaultThreshold() {
        ValidationResult result = CoverageValidator.validate(79.9);
        assertThat(result.passed()).isFalse();
        assertThat(result.threshold()).isEqualTo(80.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -0.1, 100.1, 101.0, Double.MAX_VALUE, -Double.MAX_VALUE})
    void validate_shouldThrowForInvalidPercentage(double percentage) {
        assertThatThrownBy(() -> CoverageValidator.validate(percentage))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("[0.0, 100.0]");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -0.1, 100.1, 101.0})
    void validate_shouldThrowForInvalidThreshold(double threshold) {
        assertThatThrownBy(() -> CoverageValidator.validate(50.0, threshold))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("[0.0, 100.0]");
    }

    @Test
    void validate_shouldHandleBoundaryValues() {
        // Test 0.0 and 100.0 boundaries
        assertThat(CoverageValidator.validate(0.0, 0.0).passed()).isTrue();
        assertThat(CoverageValidator.validate(100.0, 100.0).passed()).isTrue();
        assertThat(CoverageValidator.validate(0.0, 1.0).passed()).isFalse();
        assertThat(CoverageValidator.validate(100.0, 99.0).passed()).isTrue();
    }

    // ==================== validateModule tests ====================

    @Test
    void validateModule_shouldIncludeModuleNameInMessage() {
        ValidationResult result = CoverageValidator.validateModule("test-module", 80, 20);
        assertThat(result.message()).contains("test-module");
    }

    @Test
    void validateModule_shouldCalculatePercentageFromCounts() {
        ValidationResult result = CoverageValidator.validateModule("module", 80, 20);
        assertThat(result.actualPercentage()).isEqualTo(80.0);
    }

    @Test
    void validateModule_shouldPassAt80Percent() {
        ValidationResult result = CoverageValidator.validateModule("module", 80, 20);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void validateModule_shouldFailBelow80Percent() {
        ValidationResult result = CoverageValidator.validateModule("module", 70, 30);
        assertThat(result.passed()).isFalse();
        assertThat(result.gap()).isEqualTo(10.0);
    }

    // ==================== isInRange tests ====================

    @Test
    void isInRange_shouldReturnTrueForValidValues() {
        assertThat(CoverageValidator.isInRange(0.0)).isTrue();
        assertThat(CoverageValidator.isInRange(50.0)).isTrue();
        assertThat(CoverageValidator.isInRange(100.0)).isTrue();
        assertThat(CoverageValidator.isInRange(80.0)).isTrue();
    }

    @Test
    void isInRange_shouldReturnFalseForInvalidValues() {
        assertThat(CoverageValidator.isInRange(-0.1)).isFalse();
        assertThat(CoverageValidator.isInRange(100.1)).isFalse();
        assertThat(CoverageValidator.isInRange(-1.0)).isFalse();
        assertThat(CoverageValidator.isInRange(101.0)).isFalse();
    }

    // ==================== clampToRange tests ====================

    @Test
    void clampToRange_shouldNotModifyValidValues() {
        assertThat(CoverageValidator.clampToRange(0.0)).isEqualTo(0.0);
        assertThat(CoverageValidator.clampToRange(50.0)).isEqualTo(50.0);
        assertThat(CoverageValidator.clampToRange(100.0)).isEqualTo(100.0);
    }

    @Test
    void clampToRange_shouldClampNegativeToZero() {
        assertThat(CoverageValidator.clampToRange(-1.0)).isEqualTo(0.0);
        assertThat(CoverageValidator.clampToRange(-100.0)).isEqualTo(0.0);
        assertThat(CoverageValidator.clampToRange(-0.01)).isEqualTo(0.0);
    }

    @Test
    void clampToRange_shouldClampOverHundredToHundred() {
        assertThat(CoverageValidator.clampToRange(100.1)).isEqualTo(100.0);
        assertThat(CoverageValidator.clampToRange(101.0)).isEqualTo(100.0);
        assertThat(CoverageValidator.clampToRange(1000.0)).isEqualTo(100.0);
    }

    // ==================== getCoverageLevel tests ====================

    @ParameterizedTest
    @CsvSource({
        "100.0, EXCELLENT",
        "95.0, EXCELLENT",
        "90.0, EXCELLENT",
        "89.9, GOOD",
        "85.0, GOOD",
        "80.0, GOOD",
        "79.9, WARNING",
        "70.0, WARNING",
        "60.0, WARNING",
        "59.9, DANGER",
        "50.0, DANGER",
        "0.0, DANGER"
    })
    void getCoverageLevel_shouldReturnCorrectLevel(double percentage, CoverageLevel expected) {
        assertThat(CoverageValidator.getCoverageLevel(percentage)).isEqualTo(expected);
    }

    @Test
    void getCoverageLevel_shouldThrowForInvalidPercentage() {
        assertThatThrownBy(() -> CoverageValidator.getCoverageLevel(-1.0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CoverageValidator.getCoverageLevel(101.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== CoverageLevel enum tests ====================

    @Test
    void coverageLevel_shouldHaveCorrectNames() {
        assertThat(CoverageLevel.EXCELLENT.getName()).isEqualTo("excellent");
        assertThat(CoverageLevel.GOOD.getName()).isEqualTo("good");
        assertThat(CoverageLevel.WARNING.getName()).isEqualTo("warning");
        assertThat(CoverageLevel.DANGER.getName()).isEqualTo("danger");
    }

    @Test
    void coverageLevel_shouldHaveColors() {
        assertThat(CoverageLevel.EXCELLENT.getColor()).isNotBlank();
        assertThat(CoverageLevel.GOOD.getColor()).isNotBlank();
        assertThat(CoverageLevel.WARNING.getColor()).isNotBlank();
        assertThat(CoverageLevel.DANGER.getColor()).isNotBlank();
    }

    // ==================== ValidationResult record tests ====================

    @Test
    void validationResult_shouldValidatePercentageRange() {
        assertThatThrownBy(() -> new ValidationResult(true, -1.0, 80.0, 0.0, "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actualPercentage");
        assertThatThrownBy(() -> new ValidationResult(true, 101.0, 80.0, 0.0, "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actualPercentage");
    }

    @Test
    void validationResult_shouldValidateThresholdRange() {
        assertThatThrownBy(() -> new ValidationResult(true, 80.0, -1.0, 0.0, "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold");
        assertThatThrownBy(() -> new ValidationResult(true, 80.0, 101.0, 0.0, "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold");
    }

    @Test
    void validationResult_shouldValidateNonNegativeGap() {
        assertThatThrownBy(() -> new ValidationResult(false, 75.0, 80.0, -5.0, "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("gap");
    }

    @Test
    void validationResult_shouldAcceptValidInput() {
        ValidationResult result = new ValidationResult(true, 85.0, 80.0, 0.0, "PASS message");
        assertThat(result.passed()).isTrue();
        assertThat(result.actualPercentage()).isEqualTo(85.0);
        assertThat(result.threshold()).isEqualTo(80.0);
        assertThat(result.gap()).isEqualTo(0.0);
        assertThat(result.message()).isEqualTo("PASS message");
    }

    // ==================== Constants tests ====================

    @Test
    void constants_shouldHaveCorrectValues() {
        assertThat(CoverageValidator.MIN_COVERAGE).isEqualTo(0.0);
        assertThat(CoverageValidator.MAX_COVERAGE).isEqualTo(100.0);
        assertThat(CoverageValidator.DEFAULT_THRESHOLD).isEqualTo(80.0);
    }
}
