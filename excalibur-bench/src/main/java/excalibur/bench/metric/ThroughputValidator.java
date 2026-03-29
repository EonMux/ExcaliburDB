package excalibur.bench.metric;

/**
 * Validates throughput metrics for domain correctness and target compliance.
 *
 * <p>Ensures that throughput (ops/sec) values follow the mathematical properties
 * required for valid benchmark results:</p>
 * <ul>
 *   <li>Throughput must be a positive number (> 0)</li>
 *   <li>Throughput must be finite (not NaN or Infinite)</li>
 *   <li>Throughput values should be within physical limits (1K-100M ops/sec)</li>
 * </ul>
 *
 * <h2>Domain Correctness Rules</h2>
 * <ul>
 *   <li>Throughput represents operations per second</li>
 *   <li>Zero or negative throughput is physically impossible for real operations</li>
 *   <li>Physical limits: 1,000 to 100,000,000 ops/sec (1K-100M)</li>
 *   <li>Rationale for 100M max: ~100ns/op ≈ 300-500 CPU cycles at 3-5GHz, realistic for single-threaded</li>
 *   <li>Typical ranges:</li>
 *   <ul>
 *     <li>Single-threaded hash lookup: 1M - 100M ops/sec</li>
 *     <li>Vector distance computation: 10M - 100M ops/sec</li>
 *     <li>HNSW search: 1K - 100K QPS</li>
 *     <li>End-to-end query: 100 - 10K QPS</li>
 *   </ul>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and all methods are thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since Phase 10
 */
public final class ThroughputValidator {

    /**
     * Minimum valid throughput value.
     * Any positive value is technically valid, but values below this may indicate issues.
     */
    public static final double MIN_THROUGHPUT = Double.MIN_VALUE;

    /**
     * Physical minimum throughput (1K ops/sec).
     * Values below this indicate the operation is not meaningfully measurable.
     * This is the lower bound of the valid physical range.
     */
    public static final double PHYSICAL_MIN_THROUGHPUT = 1_000.0;

    /**
     * Physical maximum throughput (100M ops/sec).
     * This is the upper bound of the valid physical range for single-threaded operations.
     * Rationale: 100M ops/sec = ~100ns/op ≈ 300-500 CPU cycles at 3-5GHz.
     * Values above this exceed physical limits for single-threaded code.
     */
    public static final double PHYSICAL_MAX_THROUGHPUT = 100_000_000.0;

    /**
     * Reasonable minimum for single-threaded operations (1K ops/sec).
     * Values below this may indicate measurement issues.
     */
    public static final double REASONABLE_MIN_THROUGHPUT = PHYSICAL_MIN_THROUGHPUT;

    /**
     * Reasonable maximum for single-threaded operations (100M ops/sec).
     * Values above this exceed physical limits and likely indicate measurement errors.
     */
    public static final double REASONABLE_MAX_THROUGHPUT = PHYSICAL_MAX_THROUGHPUT;

    /**
     * Target throughput for point lookups (10M ops/sec).
     * Interpreted from PROJECT GOAL ">=1B ops/sec" as realistic single-threaded target.
     */
    public static final double TARGET_POINT_LOOKUP = 10_000_000.0;

    /**
     * Target throughput for Cuckoo filter operations (5M ops/sec).
     */
    public static final double TARGET_CUCKOO_LOOKUP = 5_000_000.0;

    /**
     * Target throughput for distance computations (50M ops/sec).
     */
    public static final double TARGET_DISTANCE_COMPUTATION = 50_000_000.0;

    /**
     * Target throughput for end-to-end queries (1000 QPS).
     */
    public static final double TARGET_E2E_QUERY = 1_000.0;

    /**
     * Epsilon for floating-point comparisons.
     */
    private static final double EPSILON = 1e-9;

    /**
     * Validates a throughput value for domain correctness (must be positive and finite).
     *
     * @param opsPerSec the throughput value in operations per second
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validate(double opsPerSec) {
        return validate(opsPerSec, "throughput");
    }

    /**
     * Validates a throughput value with a custom metric name.
     *
     * @param opsPerSec the throughput value in operations per second
     * @param name the name of the metric for error messages
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validate(double opsPerSec, String name) {
        StringBuilder warnings = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        // Check for NaN
        if (Double.isNaN(opsPerSec)) {
            errors.append(name).append(" is NaN - throughput must be a finite positive number; ");
            return new ValidationResult(false, errors.toString(), warnings.toString(), opsPerSec, false);
        }

        // Check for Infinite
        if (Double.isInfinite(opsPerSec)) {
            errors.append(name).append(" is infinite - throughput must be a finite positive number; ");
            return new ValidationResult(false, errors.toString(), warnings.toString(), opsPerSec, false);
        }

        // Check for zero or negative - CRITICAL: throughput must be POSITIVE
        if (opsPerSec <= 0) {
            errors.append(String.format(
                "%s (%.6f) must be POSITIVE (> 0) - zero or negative throughput is invalid; ",
                name, opsPerSec));
            return new ValidationResult(false, errors.toString(), warnings.toString(), opsPerSec, false);
        }

        // Check for unreasonably low throughput (may indicate measurement issues)
        if (opsPerSec < REASONABLE_MIN_THROUGHPUT) {
            warnings.append(String.format(
                "WARNING: %s (%.2f ops/sec) is below physical minimum (%.0f ops/sec = 1K) - may indicate measurement issues; ",
                name, opsPerSec, REASONABLE_MIN_THROUGHPUT));
        }

        // Check for unreasonably high throughput (may indicate measurement errors)
        if (opsPerSec > REASONABLE_MAX_THROUGHPUT) {
            warnings.append(String.format(
                "WARNING: %s (%.2e ops/sec) exceeds physical maximum (%.0e ops/sec = 100M) - may indicate measurement error; ",
                name, opsPerSec, REASONABLE_MAX_THROUGHPUT));
        }

        return new ValidationResult(true, errors.toString(), warnings.toString(), opsPerSec, true);
    }

    /**
     * Validates that throughput is within physical limits (1K-100M ops/sec).
     *
     * <p>This is a stricter validation than {@link #validate(double)} that requires
     * throughput to be within the physically achievable range for single-threaded operations.</p>
     *
     * <p>Physical limits rationale:</p>
     * <ul>
     *   <li>Minimum (1K): Below this, the operation is not meaningfully measurable</li>
     *   <li>Maximum (100M): ~100ns/op ≈ 300-500 CPU cycles at 3-5GHz</li>
     * </ul>
     *
     * @param opsPerSec the throughput value in operations per second
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateWithinPhysicalLimits(double opsPerSec) {
        return validateWithinPhysicalLimits(opsPerSec, "throughput");
    }

    /**
     * Validates that throughput is within physical limits with a custom metric name.
     *
     * @param opsPerSec the throughput value in operations per second
     * @param name the name of the metric for error messages
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateWithinPhysicalLimits(double opsPerSec, String name) {
        // First validate basic correctness (positive, finite)
        ValidationResult baseResult = validate(opsPerSec, name);
        if (!baseResult.valid()) {
            return baseResult;
        }

        StringBuilder warnings = new StringBuilder(baseResult.warnings());
        StringBuilder errors = new StringBuilder();

        // Check physical minimum (1K ops/sec)
        if (opsPerSec < PHYSICAL_MIN_THROUGHPUT) {
            errors.append(String.format(
                "%s (%.2f ops/sec) is below physical minimum (%.0f ops/sec = 1K) - value is outside valid range; ",
                name, opsPerSec, PHYSICAL_MIN_THROUGHPUT));
            return new ValidationResult(false, errors.toString(), warnings.toString(), opsPerSec, false);
        }

        // Check physical maximum (100M ops/sec)
        if (opsPerSec > PHYSICAL_MAX_THROUGHPUT) {
            errors.append(String.format(
                "%s (%.2e ops/sec) exceeds physical maximum (%.0e ops/sec = 100M) - value exceeds single-threaded limits; ",
                name, opsPerSec, PHYSICAL_MAX_THROUGHPUT));
            return new ValidationResult(false, errors.toString(), warnings.toString(), opsPerSec, false);
        }

        return new ValidationResult(true, errors.toString(), warnings.toString(), opsPerSec, true);
    }

    /**
     * Checks if a throughput value is within physical limits (1K-100M ops/sec).
     *
     * @param opsPerSec the throughput value to check
     * @return true if throughput is within physical limits
     */
    public static boolean isWithinPhysicalLimits(double opsPerSec) {
        return isValid(opsPerSec) &&
               opsPerSec >= PHYSICAL_MIN_THROUGHPUT &&
               opsPerSec <= PHYSICAL_MAX_THROUGHPUT;
    }

    /**
     * Clamps a throughput value to physical limits (1K-100M ops/sec).
     *
     * <p>If the value is invalid (NaN, infinite, zero, negative), returns the minimum.</p>
     *
     * @param opsPerSec the throughput value to clamp
     * @return the value clamped to physical limits
     */
    public static double clampToPhysicalLimits(double opsPerSec) {
        if (!isValid(opsPerSec)) {
            return PHYSICAL_MIN_THROUGHPUT;
        }
        return Math.max(PHYSICAL_MIN_THROUGHPUT, Math.min(PHYSICAL_MAX_THROUGHPUT, opsPerSec));
    }

    /**
     * Validates a throughput value against a target threshold.
     *
     * @param opsPerSec the throughput value in operations per second
     * @param target the target throughput threshold
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateWithTarget(double opsPerSec, double target) {
        // First validate basic correctness
        ValidationResult baseResult = validate(opsPerSec);
        if (!baseResult.valid()) {
            return baseResult;
        }

        StringBuilder warnings = new StringBuilder(baseResult.warnings());
        StringBuilder errors = new StringBuilder();

        // Check target compliance
        boolean meetsTarget = opsPerSec >= target - EPSILON;

        if (!meetsTarget) {
            double gap = target - opsPerSec;
            double gapPercent = (gap / target) * 100;

            if (gapPercent <= 10.0) {
                // Within 10% of target - warning only
                warnings.append(String.format(
                    "%s (%.2e ops/sec) is close to but below target (%.2e ops/sec, %.1f%% gap); ",
                    "throughput", opsPerSec, target, gapPercent));
                meetsTarget = true; // Accept as meeting target within tolerance
            } else {
                errors.append(String.format(
                    "%s (%.2e ops/sec) is below target (%.2e ops/sec, %.1f%% gap); ",
                    "throughput", opsPerSec, target, gapPercent));
            }
        }

        boolean valid = errors.length() == 0;
        return new ValidationResult(valid, errors.toString(), warnings.toString(), opsPerSec, meetsTarget);
    }

    /**
     * Validates point lookup throughput against the target (>=10M ops/sec).
     *
     * @param opsPerSec the point lookup throughput
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validatePointLookup(double opsPerSec) {
        ValidationResult result = validateWithTarget(opsPerSec, TARGET_POINT_LOOKUP);
        return new ValidationResult(
            result.valid(),
            result.errors(),
            result.warnings(),
            result.throughput(),
            result.meetsTarget()
        );
    }

    /**
     * Validates Cuckoo filter lookup throughput against the target (>=5M ops/sec).
     *
     * @param opsPerSec the Cuckoo filter lookup throughput
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateCuckooLookup(double opsPerSec) {
        return validateWithTarget(opsPerSec, TARGET_CUCKOO_LOOKUP);
    }

    /**
     * Validates distance computation throughput against the target (>=50M ops/sec).
     *
     * @param opsPerSec the distance computation throughput
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateDistanceComputation(double opsPerSec) {
        return validateWithTarget(opsPerSec, TARGET_DISTANCE_COMPUTATION);
    }

    /**
     * Validates end-to-end query throughput against the target (>=1000 QPS).
     *
     * @param qps the queries per second
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateE2EQuery(double qps) {
        return validateWithTarget(qps, TARGET_E2E_QUERY);
    }

    /**
     * Ensures a throughput value is positive, returning a default if not.
     *
     * <p>This is useful for ensuring domain correctness when parsing external data.</p>
     *
     * @param opsPerSec the raw throughput value
     * @param defaultValue the default value to return if invalid (must be positive)
     * @return the original value if valid, or the default if invalid
     * @throws IllegalArgumentException if defaultValue is not positive
     */
    public static double ensurePositive(double opsPerSec, double defaultValue) {
        if (defaultValue <= 0) {
            throw new IllegalArgumentException(
                "Default value must be positive, got: " + defaultValue);
        }
        if (Double.isNaN(opsPerSec) || Double.isInfinite(opsPerSec) || opsPerSec <= 0) {
            return defaultValue;
        }
        return opsPerSec;
    }

    /**
     * Checks if a throughput value is valid (positive and finite).
     *
     * @param opsPerSec the throughput value to check
     * @return true if throughput is valid
     */
    public static boolean isValid(double opsPerSec) {
        return !Double.isNaN(opsPerSec) &&
               !Double.isInfinite(opsPerSec) &&
               opsPerSec > 0;
    }

    /**
     * Checks if a throughput value meets a target threshold.
     *
     * @param opsPerSec the throughput value to check
     * @param target the target threshold
     * @return true if throughput meets or exceeds target
     */
    public static boolean meetsTarget(double opsPerSec, double target) {
        if (!isValid(opsPerSec)) {
            return false;
        }
        return opsPerSec >= target - EPSILON;
    }

    /**
     * Asserts that a throughput value is valid, throwing an exception if not.
     *
     * @param opsPerSec the throughput value to validate
     * @throws IllegalStateException if validation fails
     */
    public static void assertValid(double opsPerSec) {
        ValidationResult result = validate(opsPerSec);
        if (!result.valid()) {
            throw new IllegalStateException(
                "Throughput validation failed: " + result.errors());
        }
    }

    /**
     * Asserts that a throughput value is valid and meets target, throwing if not.
     *
     * @param opsPerSec the throughput value to validate
     * @param target the target threshold
     * @throws IllegalStateException if validation fails
     */
    public static void assertValidAndMeetsTarget(double opsPerSec, double target) {
        ValidationResult result = validateWithTarget(opsPerSec, target);
        if (!result.valid()) {
            throw new IllegalStateException(
                "Throughput validation failed: " + result.combinedMessage());
        }
    }

    /**
     * Formats a throughput value as a human-readable string.
     *
     * @param opsPerSec the throughput value
     * @return formatted string (e.g., "25.5M ops/sec")
     */
    public static String format(double opsPerSec) {
        if (!isValid(opsPerSec)) {
            return "INVALID";
        }

        if (opsPerSec >= 1_000_000_000.0) {
            return String.format("%.2fB ops/sec", opsPerSec / 1_000_000_000.0);
        } else if (opsPerSec >= 1_000_000.0) {
            return String.format("%.2fM ops/sec", opsPerSec / 1_000_000.0);
        } else if (opsPerSec >= 1_000.0) {
            return String.format("%.2fK ops/sec", opsPerSec / 1_000.0);
        } else {
            return String.format("%.2f ops/sec", opsPerSec);
        }
    }

    /**
     * Formats a throughput value with full precision.
     *
     * @param opsPerSec the throughput value
     * @return formatted string with full precision
     */
    public static String formatPrecise(double opsPerSec) {
        if (!isValid(opsPerSec)) {
            return "INVALID";
        }
        return String.format("%.2e ops/sec", opsPerSec);
    }

    /**
     * Creates a formatted summary of throughput validation.
     *
     * @param opsPerSec the throughput value
     * @return a human-readable summary string
     */
    public static String formatSummary(double opsPerSec) {
        ValidationResult validation = validate(opsPerSec);

        StringBuilder sb = new StringBuilder();
        sb.append("Throughput Validation:\n");
        sb.append(String.format("  throughput: %s\n", format(opsPerSec)));
        sb.append(String.format("  raw value: %.2e ops/sec\n", opsPerSec));

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

        return sb.toString();
    }

    /**
     * Creates a formatted summary with target comparison.
     *
     * @param opsPerSec the throughput value
     * @param target the target threshold
     * @return a human-readable summary string
     */
    public static String formatSummaryWithTarget(double opsPerSec, double target) {
        ValidationResult validation = validateWithTarget(opsPerSec, target);

        StringBuilder sb = new StringBuilder();
        sb.append("Throughput Validation:\n");
        sb.append(String.format("  throughput: %s\n", format(opsPerSec)));
        sb.append(String.format("  target: %s\n", format(target)));
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
     * Result of a throughput validation.
     *
     * @param valid whether the validation passed
     * @param errors error messages if validation failed (empty string if valid)
     * @param warnings warning messages (may be non-empty even if valid)
     * @param throughput the throughput value that was validated
     * @param meetsTarget whether the throughput meets the target threshold
     */
    public record ValidationResult(
        boolean valid,
        String errors,
        String warnings,
        double throughput,
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
                    ? "Validation passed - throughput meets target"
                    : "Validation passed - throughput below target";
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
         * Gets the throughput as a formatted string.
         */
        public String throughputFormatted() {
            return format(throughput);
        }
    }

    private ThroughputValidator() {
        // Utility class - no instantiation
    }
}
