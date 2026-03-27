package excalibur.bench.metric;

/**
 * Validates latency percentile metrics for domain correctness.
 *
 * <p>Ensures that latency percentiles follow the mathematical properties
 * required for valid benchmark results:</p>
 * <ul>
 *   <li>p50 &lt; p99 (strict ordering for non-constant distributions)</li>
 *   <li>Both values are non-negative</li>
 *   <li>Both values are in milliseconds (not nanoseconds or other units)</li>
 *   <li>Values are within reasonable ranges for query latency</li>
 * </ul>
 *
 * <h2>Domain Correctness Rules</h2>
 * <ul>
 *   <li>Latency values must be non-negative (time cannot be negative)</li>
 *   <li>p50 must be strictly less than p99 (percentile ordering)</li>
 *   <li>Values in milliseconds should typically be &lt; 60000ms (1 minute)</li>
 *   <li>Values &gt; 1000ms may indicate incorrect unit (nanoseconds not converted)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and all methods are thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since Phase 10
 */
public final class LatencyPercentileValidator {

    /**
     * Maximum reasonable latency in milliseconds (1 minute).
     * Values above this may indicate unit conversion issues.
     */
    public static final double MAX_REASONABLE_LATENCY_MS = 60_000.0;

    /**
     * Threshold for detecting likely nanosecond values passed as milliseconds.
     * If a latency value exceeds this, it's likely the value wasn't converted from ns to ms.
     */
    public static final double LIKELY_NANOSECONDS_THRESHOLD_MS = 1_000.0;

    /**
     * Minimum number of samples required for reliable percentile calculation.
     */
    public static final int MIN_SAMPLES_FOR_PERCENTILES = 10;

    /**
     * Validates a pair of p50 and p99 latency values.
     *
     * @param p50Ms the p50 (median) latency in milliseconds
     * @param p99Ms the p99 (99th percentile) latency in milliseconds
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validate(double p50Ms, double p99Ms) {
        return validate(p50Ms, p99Ms, 0);
    }

    /**
     * Validates a pair of p50 and p99 latency values with sample count.
     *
     * @param p50Ms the p50 (median) latency in milliseconds
     * @param p99Ms the p99 (99th percentile) latency in milliseconds
     * @param sampleCount the number of samples used to calculate percentiles
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validate(double p50Ms, double p99Ms, int sampleCount) {
        StringBuilder warnings = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        // Check for NaN or infinite values
        if (Double.isNaN(p50Ms) || Double.isInfinite(p50Ms)) {
            errors.append("p50 is not a finite number: ").append(p50Ms).append("; ");
        }
        if (Double.isNaN(p99Ms) || Double.isInfinite(p99Ms)) {
            errors.append("p99 is not a finite number: ").append(p99Ms).append("; ");
        }

        if (errors.length() > 0) {
            return new ValidationResult(false, errors.toString(), warnings.toString());
        }

        // Check non-negative
        if (p50Ms < 0) {
            errors.append("p50 must be non-negative, got: ").append(p50Ms).append("ms; ");
        }
        if (p99Ms < 0) {
            errors.append("p99 must be non-negative, got: ").append(p99Ms).append("ms; ");
        }

        // Check strict ordering: p50 < p99
        // Allow equality only when all samples are identical (constant distribution)
        if (p50Ms > p99Ms) {
            errors.append(String.format(
                "INVALID: p50 (%.3fms) > p99 (%.3fms) - percentiles must be ordered",
                p50Ms, p99Ms));
        }

        // Check for likely unit conversion issues (values that look like nanoseconds)
        if (p50Ms > LIKELY_NANOSECONDS_THRESHOLD_MS) {
            warnings.append(String.format(
                "WARNING: p50 (%.3fms) > 1000ms - may be nanoseconds not converted to ms; ",
                p50Ms));
        }
        if (p99Ms > LIKELY_NANOSECONDS_THRESHOLD_MS) {
            warnings.append(String.format(
                "WARNING: p99 (%.3fms) > 1000ms - may be nanoseconds not converted to ms; ",
                p99Ms));
        }

        // Check for unreasonably high latency
        if (p50Ms > MAX_REASONABLE_LATENCY_MS) {
            warnings.append(String.format(
                "WARNING: p50 (%.3fms) exceeds maximum reasonable latency (%.0fms); ",
                p50Ms, MAX_REASONABLE_LATENCY_MS));
        }
        if (p99Ms > MAX_REASONABLE_LATENCY_MS) {
            warnings.append(String.format(
                "WARNING: p99 (%.3fms) exceeds maximum reasonable latency (%.0fms); ",
                p99Ms, MAX_REASONABLE_LATENCY_MS));
        }

        // Check sample count
        if (sampleCount > 0 && sampleCount < MIN_SAMPLES_FOR_PERCENTILES) {
            warnings.append(String.format(
                "WARNING: Only %d samples - percentile estimates may be unreliable (min: %d); ",
                sampleCount, MIN_SAMPLES_FOR_PERCENTILES));
        }

        // Check for constant distribution (p50 == p99 exactly)
        if (p50Ms == p99Ms && p50Ms > 0) {
            warnings.append(String.format(
                "INFO: p50 == p99 (%.3fms) - indicates constant distribution (all samples identical); ",
                p50Ms));
        }

        boolean valid = errors.length() == 0;
        return new ValidationResult(valid, errors.toString(), warnings.toString());
    }

    /**
     * Validates latency percentile metrics from a BenchmarkResult.
     *
     * @param result the benchmark result containing p50 and p99 metrics
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validate(BenchmarkResult result) {
        BenchmarkMetric p50 = result.getP50();
        BenchmarkMetric p99 = result.getP99();

        if (p50 == null && p99 == null) {
            return new ValidationResult(true, "", "No p50/p99 metrics present");
        }

        if (p50 == null) {
            return new ValidationResult(false, "p50 metric missing", "");
        }
        if (p99 == null) {
            return new ValidationResult(false, "p99 metric missing", "");
        }

        // Verify units are milliseconds
        String p50Unit = p50.unit().toLowerCase();
        String p99Unit = p99.unit().toLowerCase();

        StringBuilder errors = new StringBuilder();

        if (!p50Unit.equals("ms") && !p50Unit.equals("millis") && !p50Unit.equals("milliseconds")) {
            if (p50Unit.contains("ns") || p50Unit.contains("nano")) {
                errors.append("p50 unit is nanoseconds - expected milliseconds; ");
            } else if (p50Unit.contains("us") || p50Unit.contains("micro")) {
                errors.append("p50 unit is microseconds - expected milliseconds; ");
            } else if (p50Unit.contains("s") && !p50Unit.contains("ms")) {
                errors.append("p50 unit is seconds - expected milliseconds; ");
            }
        }

        if (!p99Unit.equals("ms") && !p99Unit.equals("millis") && !p99Unit.equals("milliseconds")) {
            if (p99Unit.contains("ns") || p99Unit.contains("nano")) {
                errors.append("p99 unit is nanoseconds - expected milliseconds; ");
            } else if (p99Unit.contains("us") || p99Unit.contains("micro")) {
                errors.append("p99 unit is microseconds - expected milliseconds; ");
            } else if (p99Unit.contains("s") && !p99Unit.contains("ms")) {
                errors.append("p99 unit is seconds - expected milliseconds; ");
            }
        }

        if (errors.length() > 0) {
            return new ValidationResult(false, errors.toString(), "");
        }

        return validate(p50.value(), p99.value(), (int) p50.sampleCount());
    }

    /**
     * Asserts that latency percentiles are valid, throwing an exception if not.
     *
     * @param p50Ms the p50 latency in milliseconds
     * @param p99Ms the p99 latency in milliseconds
     * @throws IllegalStateException if validation fails
     */
    public static void assertValid(double p50Ms, double p99Ms) {
        ValidationResult result = validate(p50Ms, p99Ms);
        if (!result.valid()) {
            throw new IllegalStateException("Latency percentile validation failed: " + result.errors());
        }
    }

    /**
     * Creates a formatted summary of latency percentiles.
     *
     * @param p50Ms the p50 latency in milliseconds
     * @param p99Ms the p99 latency in milliseconds
     * @return a human-readable summary string
     */
    public static String formatSummary(double p50Ms, double p99Ms) {
        ValidationResult validation = validate(p50Ms, p99Ms);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Latency Percentiles:%n"));
        sb.append(String.format("  p50: %.3f ms%n", p50Ms));
        sb.append(String.format("  p99: %.3f ms%n", p99Ms));
        sb.append(String.format("  ratio (p99/p50): %.2fx%n", p50Ms > 0 ? p99Ms / p50Ms : 0.0));

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
     * Result of a latency percentile validation.
     *
     * @param valid whether the validation passed
     * @param errors error messages if validation failed (empty string if valid)
     * @param warnings warning messages (may be non-empty even if valid)
     */
    public record ValidationResult(boolean valid, String errors, String warnings) {

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
                return "Validation passed";
            }
            if (errors.isEmpty()) {
                return "Warnings: " + warnings.trim();
            }
            if (warnings.isEmpty()) {
                return "Errors: " + errors.trim();
            }
            return "Errors: " + errors.trim() + "; Warnings: " + warnings.trim();
        }
    }

    private LatencyPercentileValidator() {
        // Utility class - no instantiation
    }
}
