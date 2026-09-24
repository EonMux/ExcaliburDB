package com.eonmux.excalibur.bench.metric;

/**
 * Validates recall metrics for domain correctness and target compliance.
 *
 * <p>Ensures that recall values follow the mathematical properties required
 * for valid benchmark results:</p>
 * <ul>
 *   <li>Recall must be in range [0.0, 1.0] (represents a percentage)</li>
 *   <li>Recall must meet the target threshold (default: 0.99 = 99%)</li>
 *   <li>Recall values outside [0.0, 1.0] indicate calculation errors</li>
 * </ul>
 *
 * <h2>Domain Correctness Rules</h2>
 * <ul>
 *   <li>Recall represents the fraction of true positives retrieved</li>
 *   <li>Recall = |retrieved ∩ relevant| / |relevant|</li>
 *   <li>Must be between 0.0 (no relevant items found) and 1.0 (all relevant items found)</li>
 *   <li>Target recall@10: ≥0.99 (99%)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and all methods are thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class RecallValidator {

    /**
     * Target recall@10 value for HNSW search.
     * Default: 0.99 (99%)
     */
    public static final double TARGET_RECALL_10 = 0.99;

    /**
     * Minimum valid recall value (0% recall).
     */
    public static final double MIN_RECALL = 0.0;

    /**
     * Maximum valid recall value (100% recall).
     */
    public static final double MAX_RECALL = 1.0;

    /**
     * Epsilon for floating-point comparisons.
     */
    private static final double EPSILON = 1e-9;

    /**
     * Validates a recall value for domain correctness (range [0.0, 1.0]).
     *
     * @param recall the recall value to validate
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateRange(double recall) {
        return validateRange(recall, "recall");
    }

    /**
     * Validates a recall value for domain correctness with a custom name.
     *
     * @param recall the recall value to validate
     * @param name the name of the metric for error messages
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateRange(double recall, String name) {
        StringBuilder warnings = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        // Check for NaN or infinite values
        if (Double.isNaN(recall)) {
            errors.append(name).append(" is NaN; ");
            return new ValidationResult(false, errors.toString(), warnings.toString(), recall, false);
        }
        if (Double.isInfinite(recall)) {
            errors.append(name).append(" is infinite; ");
            return new ValidationResult(false, errors.toString(), warnings.toString(), recall, false);
        }

        // Check range [0.0, 1.0]
        if (recall < MIN_RECALL - EPSILON) {
            errors.append(String.format(
                "%s (%.6f) is below minimum valid value (0.0); ",
                name, recall));
        } else if (recall < MIN_RECALL) {
            // Within epsilon of 0.0 - acceptable but note it
            warnings.append(String.format(
                "%s (%.6f) is slightly below 0.0 but within tolerance; ",
                name, recall));
        }

        if (recall > MAX_RECALL + EPSILON) {
            errors.append(String.format(
                "%s (%.6f) exceeds maximum valid value (1.0); ",
                name, recall));
        } else if (recall > MAX_RECALL) {
            // Within epsilon of 1.0 - acceptable but note it
            warnings.append(String.format(
                "%s (%.6f) is slightly above 1.0 but within tolerance; ",
                name, recall));
        }

        // Check for suspicious values
        if (recall < 0.5 && recall >= MIN_RECALL) {
            warnings.append(String.format(
                "WARNING: %s (%.2f%%) is below 50%% - may indicate search quality issues; ",
                name, recall * 100));
        }

        boolean valid = errors.length() == 0;
        return new ValidationResult(valid, errors.toString(), warnings.toString(), recall, false);
    }

    /**
     * Validates a recall value against the target threshold (≥0.99).
     *
     * <p>Note: This method validates that recall is in range [0.0, 1.0].
     * Being below target does NOT make the value invalid - it just means
     * the target is not met. Use {@link ValidationResult#meetsTarget()} to check target compliance.</p>
     *
     * @param recall the recall value to validate
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateWithTarget(double recall) {
        return validateWithTarget(recall, TARGET_RECALL_10);
    }

    /**
     * Validates a recall value against a custom target threshold.
     *
     * <p>Note: This method validates that recall is in range [0.0, 1.0].
     * Being below target does NOT make the value invalid - it just means
     * the target is not met. Use {@link ValidationResult#meetsTarget()} to check target compliance.</p>
     *
     * @param recall the recall value to validate
     * @param target the target recall threshold
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateWithTarget(double recall, double target) {
        // First validate range
        ValidationResult rangeResult = validateRange(recall, "recall");
        if (!rangeResult.valid()) {
            return rangeResult;
        }

        StringBuilder warnings = new StringBuilder(rangeResult.warnings());

        // Check target - being below target is NOT a validation error, just a target miss
        // Use strict comparison: recall must be >= target to meet it
        boolean meetsTarget = recall >= target - EPSILON;

        if (!meetsTarget) {
            // Below target - add info message but don't fail validation
            warnings.append(String.format(
                "recall (%.4f = %.2f%%) is below target (%.4f = %.2f%%); ",
                recall, recall * 100, target, target * 100));
        }

        // Validation passes if range is valid (target miss is not an error)
        return new ValidationResult(true, "", warnings.toString(), recall, meetsTarget);
    }

    /**
     * Validates recall@k for HNSW search.
     *
     * <p>This is the primary validation method for HNSW recall targets.</p>
     *
     * @param recall the recall@k value
     * @param k the number of nearest neighbors (typically 10)
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateRecallAtK(double recall, int k) {
        String metricName = "recall@" + k;
        ValidationResult rangeResult = validateRange(recall, metricName);

        if (!rangeResult.valid()) {
            return new ValidationResult(
                false,
                rangeResult.errors(),
                rangeResult.warnings(),
                recall,
                false
            );
        }

        // For recall@10, validate against target
        if (k == 10) {
            return validateWithTarget(recall, TARGET_RECALL_10);
        }

        // For other k values, just validate range
        return new ValidationResult(true, "", rangeResult.warnings(), recall, recall >= 0.95);
    }

    /**
     * Clamps a recall value to the valid range [0.0, 1.0].
     *
     * <p>This ensures domain correctness: recall represents a percentage
     * of correctly retrieved items and must be between 0% and 100%.</p>
     *
     * @param recall the raw recall value
     * @return recall clamped to [0.0, 1.0]
     */
    public static double clamp(double recall) {
        if (Double.isNaN(recall) || Double.isInfinite(recall)) {
            return 0.0; // Default to 0 for invalid values
        }
        return Math.max(MIN_RECALL, Math.min(MAX_RECALL, recall));
    }

    /**
     * Checks if a recall value is in the valid range [0.0, 1.0].
     *
     * @param recall the recall value to check
     * @return true if recall is in valid range
     */
    public static boolean isValidRange(double recall) {
        return !Double.isNaN(recall) && !Double.isInfinite(recall) &&
               recall >= MIN_RECALL - EPSILON && recall <= MAX_RECALL + EPSILON;
    }

    /**
     * Checks if a recall value meets the target threshold (≥0.99).
     *
     * @param recall the recall value to check
     * @return true if recall meets or exceeds target
     */
    public static boolean meetsTarget(double recall) {
        return meetsTarget(recall, TARGET_RECALL_10);
    }

    /**
     * Checks if a recall value meets a custom target threshold.
     *
     * @param recall the recall value to check
     * @param target the target threshold
     * @return true if recall meets or exceeds target
     */
    public static boolean meetsTarget(double recall, double target) {
        if (!isValidRange(recall)) {
            return false;
        }
        return recall >= target - EPSILON;
    }

    /**
     * Asserts that a recall value is valid and meets target, throwing if not.
     *
     * <p>This method enforces BOTH conditions:</p>
     * <ul>
     *   <li>Recall must be in valid range [0.0, 1.0]</li>
     *   <li>Recall must meet or exceed target (≥0.99)</li>
     * </ul>
     *
     * @param recall the recall value to validate
     * @throws IllegalStateException if validation fails OR target is not met
     */
    public static void assertValidAndMeetsTarget(double recall) {
        ValidationResult result = validateWithTarget(recall);
        if (!result.valid()) {
            throw new IllegalStateException(
                "Recall validation failed: " + result.errors());
        }
        if (!result.meetsTarget()) {
            throw new IllegalStateException(
                String.format("Recall validation failed: %.4f (%.2f%%) does not meet target %.4f (%.2f%%)",
                    recall, recall * 100, TARGET_RECALL_10, TARGET_RECALL_10 * 100));
        }
    }

    /**
     * Asserts that a recall value is in valid range, throwing if not.
     *
     * @param recall the recall value to validate
     * @throws IllegalStateException if validation fails
     */
    public static void assertValidRange(double recall) {
        ValidationResult result = validateRange(recall);
        if (!result.valid()) {
            throw new IllegalStateException(
                "Recall range validation failed: " + result.errors());
        }
    }

    /**
     * Formats a recall value as a percentage string.
     *
     * @param recall the recall value (0.0 to 1.0)
     * @return formatted percentage string (e.g., "99.23%")
     */
    public static String formatPercent(double recall) {
        return String.format("%.2f%%", clamp(recall) * 100);
    }

    /**
     * Formats a recall value as a decimal string.
     *
     * @param recall the recall value (0.0 to 1.0)
     * @return formatted decimal string (e.g., "0.9923")
     */
    public static String formatDecimal(double recall) {
        return String.format("%.4f", clamp(recall));
    }

    /**
     * Creates a formatted summary of recall validation.
     *
     * @param recall the recall value
     * @return a human-readable summary string
     */
    public static String formatSummary(double recall) {
        ValidationResult validation = validateWithTarget(recall);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Recall Validation:%n"));
        sb.append(String.format("  recall: %s (%s)%n", formatDecimal(recall), formatPercent(recall)));
        sb.append(String.format("  target: %s (%.0f%%)%n", formatDecimal(TARGET_RECALL_10), TARGET_RECALL_10 * 100));
        sb.append(String.format("  meets target: %s%n", validation.meetsTarget() ? "YES" : "NO"));

        if (validation.valid()) {
            sb.append("  status: VALID");
            if (!validation.warnings().isEmpty()) {
                sb.append(" (with warnings)");
            }
        } else {
            sb.append("  status: INVALID");
        }

        if (!validation.warnings().isEmpty()) {
            sb.append(String.format("%n  warnings: %s", validation.warnings().trim()));
        }

        return sb.toString();
    }

    /**
     * Creates a formatted summary for recall@k validation.
     *
     * @param recall the recall value
     * @param k the number of nearest neighbors
     * @return a human-readable summary string
     */
    public static String formatSummaryAtK(double recall, int k) {
        ValidationResult validation = validateRecallAtK(recall, k);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Recall@%d Validation:%n", k));
        sb.append(String.format("  recall@%d: %s (%s)%n", k, formatDecimal(recall), formatPercent(recall)));

        double target = (k == 10) ? TARGET_RECALL_10 : 0.95;
        sb.append(String.format("  target: %s (%.0f%%)%n", formatDecimal(target), target * 100));
        sb.append(String.format("  meets target: %s%n", validation.meetsTarget() ? "YES" : "NO"));

        if (validation.valid()) {
            sb.append("  status: VALID");
            if (!validation.warnings().isEmpty()) {
                sb.append(" (with warnings)");
            }
        } else {
            sb.append("  status: INVALID");
        }

        if (!validation.warnings().isEmpty()) {
            sb.append(String.format("%n  warnings: %s", validation.warnings().trim()));
        }

        return sb.toString();
    }

    /**
     * Result of a recall validation.
     *
     * @param valid whether the validation passed
     * @param errors error messages if validation failed (empty string if valid)
     * @param warnings warning messages (may be non-empty even if valid)
     * @param recall the recall value that was validated (clamped to valid range)
     * @param meetsTarget whether the recall meets the target threshold
     */
    public record ValidationResult(
        boolean valid,
        String errors,
        String warnings,
        double recall,
        boolean meetsTarget
    ) {

        /**
         * Checks if there are any warnings.
         */
        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }

        /**
         * Gets a combined message of errors and warnings.
         */
        public String combinedMessage() {
            if (errors.isEmpty() && warnings.isEmpty()) {
                return meetsTarget
                    ? "Validation passed - recall meets target"
                    : "Validation passed - recall below target";
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
         * Gets the recall as a formatted percentage.
         */
        public String recallPercent() {
            return formatPercent(recall);
        }
    }

    private RecallValidator() {
        // Utility class - no instantiation
    }
}
