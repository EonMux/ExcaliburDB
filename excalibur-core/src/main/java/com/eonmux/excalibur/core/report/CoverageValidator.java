package com.eonmux.excalibur.core.report;

/**
 * Validates code coverage metrics against defined thresholds.
 *
 * <p>This utility provides consistent coverage calculation and validation
 * across all modules, ensuring coverage percentages are always in the
 * valid range of 0.0 to 100.0.</p>
 *
 * <p>Thread Safety: This class is immutable and thread-safe. All methods
 * are static and operate only on their input parameters.</p>
 *
 * <p>Domain Constraints:</p>
 * <ul>
 *   <li>Coverage percentage MUST be in range [0.0, 100.0]</li>
 *   <li>Default threshold is 80.0% as per project requirements</li>
 *   <li>Empty code (zero instructions) is treated as 0.0% coverage</li>
 * </ul>
 */
public final class CoverageValidator {

    /** Minimum valid coverage percentage */
    public static final double MIN_COVERAGE = 0.0;

    /** Maximum valid coverage percentage */
    public static final double MAX_COVERAGE = 100.0;

    /** Default coverage threshold for the project (80%) */
    public static final double DEFAULT_THRESHOLD = 80.0;

    /** Epsilon for floating-point comparisons */
    private static final double EPSILON = 1e-9;

    private CoverageValidator() {
        // Utility class - no instantiation
    }

    /**
     * Calculates coverage percentage from covered and missed counts.
     *
     * <p>The result is guaranteed to be in the range [0.0, 100.0].</p>
     *
     * <p>Domain correctness:</p>
     * <ul>
     *   <li>If total (covered + missed) is 0, returns 0.0 (no code to cover)</li>
     *   <li>Otherwise returns (covered * 100.0) / total, clamped to [0.0, 100.0]</li>
     * </ul>
     *
     * @param covered the number of covered instructions/lines/branches
     * @param missed the number of missed instructions/lines/branches
     * @return coverage percentage in range [0.0, 100.0]
     */
    public static double calculatePercentage(long covered, long missed) {
        if (covered < 0 || missed < 0) {
            throw new IllegalArgumentException(
                "Coverage counts must be non-negative: covered=" + covered + ", missed=" + missed);
        }

        long total = covered + missed;

        // No code means 0% coverage (no code to test)
        if (total == 0) {
            return MIN_COVERAGE;
        }

        double rawPercentage = (covered * 100.0) / total;

        // Clamp to valid range to handle any floating-point edge cases
        return clampToRange(rawPercentage);
    }

    /**
     * Validates that a coverage percentage meets the default threshold (80%).
     *
     * @param percentage the coverage percentage to validate
     * @return validation result with pass/fail status and details
     * @throws IllegalArgumentException if percentage is outside [0.0, 100.0]
     */
    public static ValidationResult validate(double percentage) {
        return validate(percentage, DEFAULT_THRESHOLD);
    }

    /**
     * Validates that a coverage percentage meets a specified threshold.
     *
     * @param percentage the coverage percentage to validate (must be in [0.0, 100.0])
     * @param threshold the minimum required coverage percentage (must be in [0.0, 100.0])
     * @return validation result with pass/fail status and details
     * @throws IllegalArgumentException if percentage or threshold is outside [0.0, 100.0]
     */
    public static ValidationResult validate(double percentage, double threshold) {
        // Validate inputs are in valid range
        if (!isInRange(percentage)) {
            throw new IllegalArgumentException(
                "Coverage percentage must be in range [0.0, 100.0], got: " + percentage);
        }
        if (!isInRange(threshold)) {
            throw new IllegalArgumentException(
                "Threshold must be in range [0.0, 100.0], got: " + threshold);
        }

        boolean passed = percentage >= threshold - EPSILON;
        double gap = passed ? 0.0 : threshold - percentage;

        return new ValidationResult(
            passed,
            percentage,
            threshold,
            gap,
            formatMessage(passed, percentage, threshold, gap)
        );
    }

    /**
     * Validates coverage for a module using covered and missed counts.
     *
     * @param moduleName the name of the module being validated
     * @param covered the number of covered instructions
     * @param missed the number of missed instructions
     * @return validation result with pass/fail status and details
     */
    public static ValidationResult validateModule(String moduleName, long covered, long missed) {
        return validateModule(moduleName, covered, missed, DEFAULT_THRESHOLD);
    }

    /**
     * Validates coverage for a module using covered and missed counts with a custom threshold.
     *
     * @param moduleName the name of the module being validated
     * @param covered the number of covered instructions
     * @param missed the number of missed instructions
     * @param threshold the minimum required coverage percentage
     * @return validation result with pass/fail status and details
     */
    public static ValidationResult validateModule(String moduleName, long covered, long missed, double threshold) {
        double percentage = calculatePercentage(covered, missed);
        ValidationResult baseResult = validate(percentage, threshold);

        return new ValidationResult(
            baseResult.passed(),
            baseResult.actualPercentage(),
            baseResult.threshold(),
            baseResult.gap(),
            String.format("[%s] %s", moduleName, baseResult.message())
        );
    }

    /**
     * Checks if a percentage value is within the valid range [0.0, 100.0].
     *
     * @param percentage the percentage to check
     * @return true if in valid range, false otherwise
     */
    public static boolean isInRange(double percentage) {
        return percentage >= MIN_COVERAGE - EPSILON && percentage <= MAX_COVERAGE + EPSILON;
    }

    /**
     * Clamps a value to the valid coverage range [0.0, 100.0].
     *
     * @param value the value to clamp
     * @return the clamped value in range [0.0, 100.0]
     */
    public static double clampToRange(double value) {
        return Math.max(MIN_COVERAGE, Math.min(MAX_COVERAGE, value));
    }

    /**
     * Formats a validation message based on the result.
     */
    private static String formatMessage(boolean passed, double percentage, double threshold, double gap) {
        if (passed) {
            return String.format("PASS: Coverage %.1f%% meets threshold %.1f%%", percentage, threshold);
        } else {
            return String.format("FAIL: Coverage %.1f%% is %.1f%% below threshold %.1f%%",
                percentage, gap, threshold);
        }
    }

    /**
     * Gets the coverage level classification based on percentage.
     *
     * @param percentage the coverage percentage
     * @return the coverage level
     */
    public static CoverageLevel getCoverageLevel(double percentage) {
        if (!isInRange(percentage)) {
            throw new IllegalArgumentException(
                "Coverage percentage must be in range [0.0, 100.0], got: " + percentage);
        }

        if (percentage >= 90.0) return CoverageLevel.EXCELLENT;
        if (percentage >= 80.0) return CoverageLevel.GOOD;
        if (percentage >= 60.0) return CoverageLevel.WARNING;
        return CoverageLevel.DANGER;
    }

    /**
     * Result of a coverage validation check.
     *
     * @param passed whether the coverage meets the threshold
     * @param actualPercentage the actual coverage percentage (0.0-100.0)
     * @param threshold the threshold that was checked against (0.0-100.0)
     * @param gap the gap between actual and threshold (0.0 if passed)
     * @param message human-readable result message
     */
    public record ValidationResult(
        boolean passed,
        double actualPercentage,
        double threshold,
        double gap,
        String message
    ) {
        /**
         * Validates that all fields are in valid ranges.
         */
        public ValidationResult {
            if (!isInRange(actualPercentage)) {
                throw new IllegalArgumentException(
                    "actualPercentage must be in [0.0, 100.0], got: " + actualPercentage);
            }
            if (!isInRange(threshold)) {
                throw new IllegalArgumentException(
                    "threshold must be in [0.0, 100.0], got: " + threshold);
            }
            if (gap < 0) {
                throw new IllegalArgumentException("gap must be non-negative, got: " + gap);
            }
        }
    }

    /**
     * Coverage level classification.
     */
    public enum CoverageLevel {
        /** 90%+ coverage - excellent */
        EXCELLENT("excellent", "#00c853"),
        /** 80-89% coverage - meets threshold */
        GOOD("good", "#64dd17"),
        /** 60-79% coverage - below threshold, needs improvement */
        WARNING("warning", "#ffc107"),
        /** Below 60% coverage - significantly below threshold */
        DANGER("danger", "#ff5252");

        private final String name;
        private final String color;

        CoverageLevel(String name, String color) {
            this.name = name;
            this.color = color;
        }

        public String getName() {
            return name;
        }

        public String getColor() {
            return color;
        }
    }
}
