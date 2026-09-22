package com.eonmux.excalibur.bench.metric;

import java.util.Locale;

/**
 * Validates latency percentile metrics for domain correctness.
 *
 * <p>Ensures that latency percentiles follow the mathematical properties
 * required for valid benchmark results:</p>
 * <ul>
 *   <li>p99 &gt; p50 &gt; p0 (strict ordering for non-constant distributions)</li>
 *   <li>All values are POSITIVE (&gt; 0) - operations take non-zero time</li>
 *   <li>All values are in milliseconds (not nanoseconds or other units)</li>
 *   <li>Values are within reasonable ranges for query latency</li>
 * </ul>
 *
 * <h2>Domain Correctness Rules</h2>
 * <ul>
 *   <li>Latency values must be POSITIVE (&gt; 0) - real operations take non-zero time</li>
 *   <li>Zero latency indicates measurement error (sub-ms operations need ns precision)</li>
 *   <li>Negative latency is physically impossible</li>
 *   <li>p99 &gt; p50 &gt; p0 (strict percentile ordering)</li>
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
     * Validates full percentile ordering: p99 &gt; p50 &gt; p0, all positive values.
     *
     * <p>This is the primary validation method ensuring complete domain correctness
     * for latency percentiles.</p>
     *
     * @param p0Ms the p0 (minimum) latency in milliseconds
     * @param p50Ms the p50 (median) latency in milliseconds
     * @param p99Ms the p99 (99th percentile) latency in milliseconds
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateFull(double p0Ms, double p50Ms, double p99Ms) {
        return validateFull(p0Ms, p50Ms, p99Ms, 0);
    }

    /**
     * Validates full percentile ordering with sample count: p99 &gt; p50 &gt; p0, all positive values.
     *
     * @param p0Ms the p0 (minimum) latency in milliseconds
     * @param p50Ms the p50 (median) latency in milliseconds
     * @param p99Ms the p99 (99th percentile) latency in milliseconds
     * @param sampleCount the number of samples used to calculate percentiles
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateFull(double p0Ms, double p50Ms, double p99Ms, int sampleCount) {
        StringBuilder warnings = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        // Check for NaN or infinite values
        if (Double.isNaN(p0Ms) || Double.isInfinite(p0Ms)) {
            errors.append("p0 is not a finite number: ").append(p0Ms).append("; ");
        }
        if (Double.isNaN(p50Ms) || Double.isInfinite(p50Ms)) {
            errors.append("p50 is not a finite number: ").append(p50Ms).append("; ");
        }
        if (Double.isNaN(p99Ms) || Double.isInfinite(p99Ms)) {
            errors.append("p99 is not a finite number: ").append(p99Ms).append("; ");
        }

        if (errors.length() > 0) {
            return new ValidationResult(false, errors.toString(), warnings.toString());
        }

        // Check POSITIVE values - latency must be > 0 (operations take non-zero time)
        // Domain correctness: Zero latency indicates measurement error (sub-ms operations need ns precision)
        if (p0Ms <= 0) {
            errors.append("p0 must be POSITIVE (> 0 ms), got: ").append(p0Ms).append("ms; ");
        }
        if (p50Ms <= 0) {
            errors.append("p50 must be POSITIVE (> 0 ms), got: ").append(p50Ms).append("ms; ");
        }
        if (p99Ms <= 0) {
            errors.append("p99 must be POSITIVE (> 0 ms), got: ").append(p99Ms).append("ms; ");
        }

        // Check strict ordering: p0 < p50 < p99
        // Allow equality only when all samples are identical (constant distribution)
        if (p0Ms > p50Ms) {
            errors.append(String.format(
                "INVALID: p0 (%.3fms) > p50 (%.3fms) - percentiles must be ordered (p99 > p50 > p0); ",
                p0Ms, p50Ms));
        }
        if (p50Ms > p99Ms) {
            errors.append(String.format(
                "INVALID: p50 (%.3fms) > p99 (%.3fms) - percentiles must be ordered (p99 > p50 > p0); ",
                p50Ms, p99Ms));
        }
        if (p0Ms > p99Ms) {
            errors.append(String.format(
                "INVALID: p0 (%.3fms) > p99 (%.3fms) - percentiles must be ordered (p99 > p50 > p0); ",
                p0Ms, p99Ms));
        }

        // Check for likely unit conversion issues (values that look like nanoseconds)
        if (p0Ms > LIKELY_NANOSECONDS_THRESHOLD_MS) {
            warnings.append(String.format(
                "WARNING: p0 (%.3fms) > 1000ms - may be nanoseconds not converted to ms; ",
                p0Ms));
        }
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
        if (p0Ms > MAX_REASONABLE_LATENCY_MS) {
            warnings.append(String.format(
                "WARNING: p0 (%.3fms) exceeds maximum reasonable latency (%.0fms); ",
                p0Ms, MAX_REASONABLE_LATENCY_MS));
        }
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

        // Check samples count
        if (sampleCount > 0 && sampleCount < MIN_SAMPLES_FOR_PERCENTILES) {
            warnings.append(String.format(
                "WARNING: Only %d samples - percentile estimates may be unreliable (min: %d); ",
                sampleCount, MIN_SAMPLES_FOR_PERCENTILES));
        }

        // Check for constant distribution (all values equal)
        if (p0Ms == p50Ms && p50Ms == p99Ms && p0Ms > 0) {
            warnings.append(String.format(
                "INFO: p0 == p50 == p99 (%.3fms) - indicates constant distribution (all samples identical); ",
                p0Ms));
        } else if (p0Ms == p50Ms && p0Ms > 0) {
            warnings.append(String.format(
                "INFO: p0 == p50 (%.3fms) - indicates highly consistent samples; ",
                p0Ms));
        } else if (p50Ms == p99Ms && p50Ms > 0) {
            warnings.append(String.format(
                "INFO: p50 == p99 (%.3fms) - indicates tight upper bound distribution; ",
                p50Ms));
        }

        boolean valid = errors.length() == 0;
        return new ValidationResult(valid, errors.toString(), warnings.toString());
    }

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

        // Check POSITIVE values - latency must be > 0 (operations take non-zero time)
        // Domain correctness: Zero latency indicates measurement error (sub-ms operations need ns precision)
        if (p50Ms <= 0) {
            errors.append("p50 must be POSITIVE (> 0 ms), got: ").append(p50Ms).append("ms; ");
        }
        if (p99Ms <= 0) {
            errors.append("p99 must be POSITIVE (> 0 ms), got: ").append(p99Ms).append("ms; ");
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
            return ValidationResult.notApplicable(
                "No p50/p99 metrics present - latency percentiles were never captured, "
                    + "so this benchmark's latency was not validated; ");
        }

        if (p50 == null) {
            return new ValidationResult(false, "p50 metric missing", "");
        }
        if (p99 == null) {
            return new ValidationResult(false, "p99 metric missing", "");
        }

        // Verify units are milliseconds
        String p50Unit = p50.unit().toLowerCase(Locale.ROOT);
        String p99Unit = p99.unit().toLowerCase(Locale.ROOT);

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
     * Validates full latency percentile metrics from a BenchmarkResult.
     *
     * <p>Validates p99 &gt; p50 &gt; p0, all positive values.</p>
     *
     * @param result the benchmark result containing p0, p50 and p99 metrics
     * @return a validation result indicating success or failure with details
     */
    public static ValidationResult validateFull(BenchmarkResult result) {
        BenchmarkMetric p0 = result.getP0();
        BenchmarkMetric p50 = result.getP50();
        BenchmarkMetric p99 = result.getP99();

        if (p0 == null && p50 == null && p99 == null) {
            return ValidationResult.notApplicable(
                "No p0/p50/p99 metrics present - latency percentiles were never captured, "
                    + "so this benchmark's latency was not validated; ");
        }

        StringBuilder errors = new StringBuilder();

        if (p0 == null) {
            errors.append("p0 metric missing; ");
        }
        if (p50 == null) {
            errors.append("p50 metric missing; ");
        }
        if (p99 == null) {
            errors.append("p99 metric missing; ");
        }

        if (errors.length() > 0) {
            return new ValidationResult(false, errors.toString(), "");
        }

        // Verify units are milliseconds
        String p0Unit = p0.unit().toLowerCase(Locale.ROOT);
        String p50Unit = p50.unit().toLowerCase(Locale.ROOT);
        String p99Unit = p99.unit().toLowerCase(Locale.ROOT);

        if (!p0Unit.equals("ms") && !p0Unit.equals("millis") && !p0Unit.equals("milliseconds")) {
            if (p0Unit.contains("ns") || p0Unit.contains("nano")) {
                errors.append("p0 unit is nanoseconds - expected milliseconds; ");
            } else if (p0Unit.contains("us") || p0Unit.contains("micro")) {
                errors.append("p0 unit is microseconds - expected milliseconds; ");
            } else if (p0Unit.contains("s") && !p0Unit.contains("ms")) {
                errors.append("p0 unit is seconds - expected milliseconds; ");
            }
        }

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

        return validateFull(p0.value(), p50.value(), p99.value(), (int) p50.sampleCount());
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
     * Asserts that full latency percentiles are valid, throwing an exception if not.
     *
     * @param p0Ms the p0 latency in milliseconds
     * @param p50Ms the p50 latency in milliseconds
     * @param p99Ms the p99 latency in milliseconds
     * @throws IllegalStateException if validation fails
     */
    public static void assertValidFull(double p0Ms, double p50Ms, double p99Ms) {
        ValidationResult result = validateFull(p0Ms, p50Ms, p99Ms);
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
     * Creates a formatted summary of full latency percentiles (p0, p50, p99).
     *
     * @param p0Ms the p0 latency in milliseconds
     * @param p50Ms the p50 latency in milliseconds
     * @param p99Ms the p99 latency in milliseconds
     * @return a human-readable summary string
     */
    public static String formatSummaryFull(double p0Ms, double p50Ms, double p99Ms) {
        ValidationResult validation = validateFull(p0Ms, p50Ms, p99Ms);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Latency Percentiles:%n"));
        sb.append(String.format("  p0:  %.3f ms%n", p0Ms));
        sb.append(String.format("  p50: %.3f ms%n", p50Ms));
        sb.append(String.format("  p99: %.3f ms%n", p99Ms));

        if (p0Ms > 0) {
            sb.append(String.format("  ratio (p99/p0):  %.2fx%n", p99Ms / p0Ms));
            sb.append(String.format("  ratio (p50/p0):  %.2fx%n", p50Ms / p0Ms));
        }
        if (p50Ms > 0) {
            sb.append(String.format("  ratio (p99/p50): %.2fx%n", p99Ms / p50Ms));
        }

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
    public record ValidationResult(boolean valid, String errors, String warnings, boolean applicable) {

        /**
         * Creates an applicable validation result.
         *
         * @param valid whether the percentiles satisfy every rule
         * @param errors accumulated error text, empty when valid
         * @param warnings accumulated warning text
         */
        public ValidationResult(boolean valid, String errors, String warnings) {
            this(valid, errors, warnings, true);
        }

        /**
         * Creates a result for a benchmark that carries no percentile metrics at all.
         *
         * <p>This is not a pass: nothing was measured, so nothing was validated.</p>
         *
         * @param reason why the validation could not run
         * @return an inapplicable, invalid result
         */
        public static ValidationResult notApplicable(String reason) {
            return new ValidationResult(false, reason, "", false);
        }


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
