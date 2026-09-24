package com.eonmux.excalibur.bench.metric;

/**
 * Validates false positive (FP) rate metrics for domain correctness and specification compliance.
 *
 * <p>Ensures that FP rate values follow the mathematical properties required for valid
 * Cuckoo filter benchmark results:</p>
 * <ul>
 *   <li>FP rate must be in range [0.0, 1.0] (probability value)</li>
 *   <li>FP rate must be finite (not NaN or Infinite)</li>
 *   <li>FP rate should be ≤ 0.001 (0.1%), the Cuckoo filter FP target</li>
 * </ul>
 *
 * <h2>Domain Correctness Rules</h2>
 * <ul>
 *   <li>FP rate represents a probability: the fraction of non-existent items incorrectly reported as present</li>
 *   <li>FP rate = false_positives / total_negative_queries</li>
 *   <li>Valid range: 0.0 (no false positives) to 1.0 (all queries are false positives)</li>
 *   <li>Target: ≤ 0.001 (0.1%) at 95% load factor with 13-bit fingerprints</li>
 * </ul>
 *
 * <h2>Mathematical Background</h2>
 * <p>The theoretical FP rate for a Cuckoo filter is approximately:</p>
 * <pre>
 *   FP_rate ≈ 1 - (1 - 2^-f)^(2 * b * load_factor)
 * </pre>
 * <p>Where f = fingerprint bits, b = fingerprints per bucket (4).</p>
 * <p>With 13-bit fingerprints at 95% load factor: FP_rate ≈ 0.093% (meets ≤0.1% target)</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and all methods are thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class FPRateValidator {

    /**
     * Maximum acceptable FP rate for the Cuckoo filter (0.1%).
     */
    public static final double MAX_FP_RATE = 0.001; // 0.1%

    /**
     * Maximum FP rate expressed as percentage for display purposes.
     */
    public static final double MAX_FP_RATE_PERCENT = 0.1; // 0.1%

    /**
     * Minimum valid FP rate (impossible to have negative false positives).
     */
    public static final double MIN_FP_RATE = 0.0;

    /**
     * Maximum valid FP rate (probability cannot exceed 1.0).
     */
    public static final double MAX_VALID_FP_RATE = 1.0;

    /**
     * Epsilon for floating-point comparisons.
     */
    private static final double EPSILON = 1e-9;

    /**
     * Validates an FP rate value for domain correctness (must be in range [0.0, 1.0]).
     *
     * @param fpRate the false positive rate value (0.0 to 1.0)
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validate(double fpRate) {
        return validate(fpRate, "FP rate");
    }

    /**
     * Validates an FP rate value with a custom metric name.
     *
     * @param fpRate the false positive rate value (0.0 to 1.0)
     * @param name the name of the metric for error messages
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validate(double fpRate, String name) {
        StringBuilder warnings = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        // Check for NaN
        if (Double.isNaN(fpRate)) {
            errors.append(name).append(" is NaN - FP rate must be a finite number in range [0.0, 1.0]; ");
            return new ValidationResult(false, errors.toString(), warnings.toString(), fpRate, false);
        }

        // Check for Infinite
        if (Double.isInfinite(fpRate)) {
            errors.append(name).append(" is infinite - FP rate must be a finite number in range [0.0, 1.0]; ");
            return new ValidationResult(false, errors.toString(), warnings.toString(), fpRate, false);
        }

        // Check for negative values - CRITICAL: FP rate cannot be negative
        if (fpRate < MIN_FP_RATE) {
            errors.append(String.format(
                "%s (%.6f) must be >= 0.0 - negative FP rate is physically impossible; ",
                name, fpRate));
            return new ValidationResult(false, errors.toString(), warnings.toString(), fpRate, false);
        }

        // Check for values greater than 1.0 - CRITICAL: FP rate is a probability
        if (fpRate > MAX_VALID_FP_RATE) {
            errors.append(String.format(
                "%s (%.6f) must be <= 1.0 - FP rate is a probability and cannot exceed 100%%; ",
                name, fpRate));
            return new ValidationResult(false, errors.toString(), warnings.toString(), fpRate, false);
        }

        // Domain correctness check passed - value is in valid range [0.0, 1.0]

        // Check for suspiciously high FP rate (may indicate implementation issue)
        if (fpRate > 0.5) {
            warnings.append(String.format(
                "WARNING: %s (%.2f%%) is unusually high for a Cuckoo filter - may indicate implementation issue; ",
                name, fpRate * 100));
        }

        // The value is in range, so the result is valid; whether it meets the ≤0.1%
        // specification is a separate question and must be answered honestly here.
        // Reporting meetsTarget=true for any in-range value made a 90% FP rate look
        // like a filter within spec.
        boolean meetsSpec = fpRate <= MAX_FP_RATE + EPSILON;

        return new ValidationResult(true, errors.toString(), warnings.toString(), fpRate, meetsSpec);
    }

    /**
     * Validates an FP rate value against the 0.1% target.
     *
     * <p>This is the primary validation method for Cuckoo filter benchmarks.</p>
     *
     * @param fpRate the false positive rate value (0.0 to 1.0)
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateWithSpec(double fpRate) {
        return validateWithTarget(fpRate, MAX_FP_RATE);
    }

    /**
     * Validates an FP rate value against a custom target threshold.
     *
     * @param fpRate the false positive rate value (0.0 to 1.0)
     * @param target the maximum acceptable FP rate (e.g., 0.001 for 0.1%)
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateWithTarget(double fpRate, double target) {
        // First validate basic domain correctness (range [0.0, 1.0])
        ValidationResult baseResult = validate(fpRate);
        if (!baseResult.valid()) {
            return baseResult;
        }

        // Also validate the target is reasonable
        if (target < MIN_FP_RATE || target > MAX_VALID_FP_RATE || Double.isNaN(target) || Double.isInfinite(target)) {
            return new ValidationResult(false,
                "Invalid target threshold: " + target + " (must be in range [0.0, 1.0]); ",
                baseResult.warnings(), fpRate, false);
        }

        StringBuilder warnings = new StringBuilder(baseResult.warnings());
        StringBuilder errors = new StringBuilder();

        // Check specification compliance
        boolean meetsTarget = fpRate <= target + EPSILON;

        if (!meetsTarget) {
            double excess = fpRate - target;
            double excessPercent = (excess / target) * 100;

            errors.append(String.format(
                "%s (%.6f = %.4f%%) exceeds target (%.6f = %.4f%%) by %.2f%%; ",
                "FP rate", fpRate, fpRate * 100, target, target * 100, excessPercent));
        }

        // Add info warning if close to target (within 50% of target)
        if (meetsTarget && fpRate > target * 0.5 && target > 0) {
            warnings.append(String.format(
                "INFO: FP rate (%.4f%%) is close to target (%.4f%%); ",
                fpRate * 100, target * 100));
        }

        boolean valid = errors.length() == 0;
        return new ValidationResult(valid, errors.toString(), warnings.toString(), fpRate, meetsTarget);
    }

    /**
     * Checks if an FP rate value is valid (in range [0.0, 1.0]).
     *
     * @param fpRate the FP rate value to check
     * @return true if FP rate is valid
     */
    public static boolean isValid(double fpRate) {
        return !Double.isNaN(fpRate) &&
               !Double.isInfinite(fpRate) &&
               fpRate >= MIN_FP_RATE &&
               fpRate <= MAX_VALID_FP_RATE;
    }

    /**
     * Checks if an FP rate value meets the 0.1% target.
     *
     * @param fpRate the FP rate value to check
     * @return true if FP rate meets specification
     */
    public static boolean meetsSpec(double fpRate) {
        if (!isValid(fpRate)) {
            return false;
        }
        return fpRate <= MAX_FP_RATE + EPSILON;
    }

    /**
     * Checks if an FP rate value meets a custom target threshold.
     *
     * @param fpRate the FP rate value to check
     * @param target the maximum acceptable FP rate
     * @return true if FP rate meets or is below target
     */
    public static boolean meetsTarget(double fpRate, double target) {
        if (!isValid(fpRate)) {
            return false;
        }
        return fpRate <= target + EPSILON;
    }

    /**
     * Clamps an FP rate value to valid range [0.0, 1.0].
     *
     * <p>For out-of-range numeric values (e.g., 1.5, -0.5), clamps to the nearest valid bound.
     * For truly invalid values (NaN, infinite), returns 0.0.</p>
     *
     * @param fpRate the FP rate value to clamp
     * @return the value clamped to valid range
     */
    public static double clampToValidRange(double fpRate) {
        // Handle truly invalid values (NaN, Infinity)
        if (Double.isNaN(fpRate) || Double.isInfinite(fpRate)) {
            return MIN_FP_RATE;
        }
        // Clamp out-of-range numeric values to valid bounds
        return Math.max(MIN_FP_RATE, Math.min(MAX_VALID_FP_RATE, fpRate));
    }

    /**
     * Ensures an FP rate value is valid, returning a default if not.
     *
     * <p>This is useful for ensuring domain correctness when parsing external data.</p>
     *
     * @param fpRate the raw FP rate value
     * @param defaultValue the default value to return if invalid (must be in valid range)
     * @return the original value if valid, or the default if invalid
     * @throws IllegalArgumentException if defaultValue is not in valid range
     */
    public static double ensureValid(double fpRate, double defaultValue) {
        if (!isValid(defaultValue)) {
            throw new IllegalArgumentException(
                "Default value must be in range [0.0, 1.0], got: " + defaultValue);
        }
        if (!isValid(fpRate)) {
            return defaultValue;
        }
        return fpRate;
    }

    /**
     * Asserts that an FP rate value is valid, throwing an exception if not.
     *
     * @param fpRate the FP rate value to validate
     * @throws IllegalStateException if validation fails
     */
    public static void assertValid(double fpRate) {
        ValidationResult result = validate(fpRate);
        if (!result.valid()) {
            throw new IllegalStateException(
                "FP rate validation failed: " + result.errors());
        }
    }

    /**
     * Asserts that an FP rate value meets the 0.1% target, throwing if not.
     *
     * @param fpRate the FP rate value to validate
     * @throws IllegalStateException if validation fails or spec is not met
     */
    public static void assertMeetsSpec(double fpRate) {
        ValidationResult result = validateWithSpec(fpRate);
        if (!result.valid()) {
            throw new IllegalStateException(
                "FP rate validation failed: " + result.combinedMessage());
        }
    }

    /**
     * Formats an FP rate value as a human-readable string with percentage.
     *
     * @param fpRate the FP rate value
     * @return formatted string (e.g., "0.093%")
     */
    public static String format(double fpRate) {
        if (!isValid(fpRate)) {
            return "INVALID";
        }
        return String.format("%.4f%%", fpRate * 100);
    }

    /**
     * Formats an FP rate value with full precision.
     *
     * @param fpRate the FP rate value
     * @return formatted string with full precision
     */
    public static String formatPrecise(double fpRate) {
        if (!isValid(fpRate)) {
            return "INVALID";
        }
        return String.format("%.6f (%.4f%%)", fpRate, fpRate * 100);
    }

    /**
     * Creates a formatted summary of FP rate validation.
     *
     * @param fpRate the FP rate value
     * @return a human-readable summary string
     */
    public static String formatSummary(double fpRate) {
        ValidationResult validation = validateWithSpec(fpRate);

        StringBuilder sb = new StringBuilder();
        sb.append("FP Rate Validation:\n");
        sb.append(String.format("  FP rate: %s\n", formatPrecise(fpRate)));
        sb.append(String.format("  spec target: %.4f%% (≤0.1%%)\n", MAX_FP_RATE * 100));

        if (validation.valid()) {
            sb.append("  status: VALID - meets spec");
            if (!validation.warnings().isEmpty()) {
                sb.append(" (with warnings)");
            }
        } else {
            sb.append("  status: INVALID - fails spec");
        }

        if (!validation.errors().isEmpty()) {
            sb.append(String.format("\n  errors: %s", validation.errors().trim()));
        }

        if (!validation.warnings().isEmpty()) {
            sb.append(String.format("\n  warnings: %s", validation.warnings().trim()));
        }

        return sb.toString();
    }

    /**
     * Creates a formatted summary with target comparison.
     *
     * @param fpRate the FP rate value
     * @param target the target threshold
     * @return a human-readable summary string
     */
    public static String formatSummaryWithTarget(double fpRate, double target) {
        ValidationResult validation = validateWithTarget(fpRate, target);

        StringBuilder sb = new StringBuilder();
        sb.append("FP Rate Validation:\n");
        sb.append(String.format("  FP rate: %s\n", formatPrecise(fpRate)));
        sb.append(String.format("  target: %.4f%%\n", target * 100));
        sb.append(String.format("  meets target: %s\n", validation.meetsTarget() ? "YES" : "NO"));

        if (validation.valid()) {
            sb.append("  status: VALID");
            if (!validation.warnings().isEmpty()) {
                sb.append(" (with warnings)");
            }
        } else {
            sb.append("  status: INVALID");
        }

        if (!validation.warnings().isEmpty()) {
            sb.append(String.format("\n  warnings: %s", validation.warnings().trim()));
        }

        if (!validation.errors().isEmpty()) {
            sb.append(String.format("\n  errors: %s", validation.errors().trim()));
        }

        return sb.toString();
    }

    /**
     * Result of an FP rate validation.
     *
     * @param valid whether the validation passed
     * @param errors error messages if validation failed (empty string if valid)
     * @param warnings warning messages (may be non-empty even if valid)
     * @param fpRate the FP rate value that was validated
     * @param meetsTarget whether the FP rate meets the target threshold
     */
    public record ValidationResult(
        boolean valid,
        String errors,
        String warnings,
        double fpRate,
        boolean meetsTarget
    ) {

        /**
         * Checks if there are any warnings.
         */
        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }

        /**
         * Checks if there are any errors.
         */
        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }

        /**
         * Gets a combined message of errors and warnings.
         */
        public String combinedMessage() {
            if (errors.isEmpty() && warnings.isEmpty()) {
                return meetsTarget
                    ? "Validation passed - FP rate meets specification (≤0.1%)"
                    : "Validation passed - FP rate above target";
            }
            if (errors.isEmpty()) {
                return "Warnings: " + warnings.trim();
            }
            if (warnings.isEmpty()) {
                return "Errors: " + errors.trim();
            }
            return "Errors: " + errors.trim() + "; Warnings: " + warnings.trim();
        }

        /**
         * Gets the FP rate as a formatted string.
         */
        public String fpRateFormatted() {
            return format(fpRate);
        }
    }

    private FPRateValidator() {
        // Utility class - no instantiation
    }
}
