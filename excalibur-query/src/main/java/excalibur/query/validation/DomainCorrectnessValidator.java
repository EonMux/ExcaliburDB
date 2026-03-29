package excalibur.query.validation;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Centralized validator for domain correctness constraints across the query pipeline.
 *
 * <p>This class provides validation methods to ensure that all output values
 * are semantically correct for the domain:</p>
 *
 * <ul>
 *   <li>Coverage values must be in [0.0, 1.0]</li>
 *   <li>Scores must be in [0.0, 1.0]</li>
 *   <li>Latency must be non-negative</li>
 *   <li>Segment counts must be non-negative</li>
 *   <li>Results must be sorted by score (descending)</li>
 *   <li>No duplicate document IDs in results</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Validate a single result item
 * DomainCorrectnessValidator.validateResultItem(item);
 *
 * // Validate a full response
 * DomainCorrectnessValidator.validateResponse(response);
 *
 * // Validate and fix (clamp) invalid values
 * float clamped = DomainCorrectnessValidator.clampScore(score);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is stateless and thread-safe. All methods are static.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class DomainCorrectnessValidator {

    /**
     * Minimum valid score value.
     */
    public static final float MIN_SCORE = 0.0f;

    /**
     * Maximum valid score value.
     */
    public static final float MAX_SCORE = 1.0f;

    /**
     * Minimum valid coverage value.
     */
    public static final double MIN_COVERAGE = 0.0;

    /**
     * Maximum valid coverage value.
     */
    public static final double MAX_COVERAGE = 1.0;

    /**
     * Minimum valid latency (milliseconds).
     */
    public static final long MIN_LATENCY_MS = 0L;

    /**
     * Score tolerance for floating-point comparisons.
     */
    public static final float SCORE_TOLERANCE = 1e-6f;

    /**
     * Coverage tolerance for floating-point comparisons.
     */
    public static final double COVERAGE_TOLERANCE = 1e-9;

    // Private constructor - utility class
    private DomainCorrectnessValidator() {}

    // ==================== Score Validation ====================

    /**
     * Validates that a score is within the valid range [0.0, 1.0].
     *
     * @param score The score to validate
     * @throws IllegalArgumentException if score is NaN, infinite, or outside [0.0, 1.0]
     */
    public static void validateScore(float score) {
        if (Float.isNaN(score)) {
            throw new IllegalArgumentException("Score cannot be NaN");
        }
        if (Float.isInfinite(score)) {
            throw new IllegalArgumentException("Score cannot be infinite: " + score);
        }
        if (score < MIN_SCORE || score > MAX_SCORE) {
            throw new IllegalArgumentException(
                String.format("Score must be in range [%.1f, %.1f]: %.6f", MIN_SCORE, MAX_SCORE, score));
        }
    }

    /**
     * Clamps a score to the valid range [0.0, 1.0].
     *
     * <p>This method handles edge cases gracefully:</p>
     * <ul>
     *   <li>NaN → 0.0 (conservative default)</li>
     *   <li>Infinity → 1.0 (positive) or 0.0 (negative)</li>
     *   <li>Out of range → clamped to nearest boundary</li>
     * </ul>
     *
     * @param score The score to clamp
     * @return The clamped score in [0.0, 1.0]
     */
    public static float clampScore(float score) {
        if (Float.isNaN(score)) {
            return MIN_SCORE;
        }
        if (Float.isInfinite(score)) {
            return score > 0 ? MAX_SCORE : MIN_SCORE;
        }
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    /**
     * Validates and clamps a score, returning the clamped value.
     *
     * <p>Unlike {@link #validateScore(float)}, this method never throws.
     * It logs a warning if clamping was required.</p>
     *
     * @param score The score to validate and clamp
     * @param context Description of the context (for logging)
     * @return The validated and clamped score
     */
    public static float validateAndClampScore(float score, String context) {
        float clamped = clampScore(score);
        if (Math.abs(clamped - score) > SCORE_TOLERANCE) {
            // Score was clamped - caller may want to log this
        }
        return clamped;
    }

    /**
     * Checks if a score is valid without throwing.
     *
     * @param score The score to check
     * @return true if score is valid (finite and in [0.0, 1.0])
     */
    public static boolean isValidScore(float score) {
        return !Float.isNaN(score) && !Float.isInfinite(score) &&
               score >= MIN_SCORE && score <= MAX_SCORE;
    }

    // ==================== Coverage Validation ====================

    /**
     * Validates that a coverage value is within the valid range [0.0, 1.0].
     *
     * @param coverage The coverage to validate
     * @throws IllegalArgumentException if coverage is NaN, infinite, or outside [0.0, 1.0]
     */
    public static void validateCoverage(double coverage) {
        if (Double.isNaN(coverage)) {
            throw new IllegalArgumentException("Coverage cannot be NaN");
        }
        if (Double.isInfinite(coverage)) {
            throw new IllegalArgumentException("Coverage cannot be infinite: " + coverage);
        }
        if (coverage < MIN_COVERAGE || coverage > MAX_COVERAGE) {
            throw new IllegalArgumentException(
                String.format("Coverage must be in range [%.1f, %.1f]: %.6f", MIN_COVERAGE, MAX_COVERAGE, coverage));
        }
    }

    /**
     * Clamps a coverage value to the valid range [0.0, 1.0].
     *
     * <p>This method handles edge cases gracefully:</p>
     * <ul>
     *   <li>NaN → 0.0 (conservative default)</li>
     *   <li>Infinity → 1.0 (positive) or 0.0 (negative)</li>
     *   <li>Out of range → clamped to nearest boundary</li>
     * </ul>
     *
     * @param coverage The coverage to clamp
     * @return The clamped coverage in [0.0, 1.0]
     */
    public static double clampCoverage(double coverage) {
        if (Double.isNaN(coverage)) {
            return MIN_COVERAGE;
        }
        if (Double.isInfinite(coverage)) {
            return coverage > 0 ? MAX_COVERAGE : MIN_COVERAGE;
        }
        return Math.max(MIN_COVERAGE, Math.min(MAX_COVERAGE, coverage));
    }

    /**
     * Checks if a coverage value is valid without throwing.
     *
     * @param coverage The coverage to check
     * @return true if coverage is valid (finite and in [0.0, 1.0])
     */
    public static boolean isValidCoverage(double coverage) {
        return !Double.isNaN(coverage) && !Double.isInfinite(coverage) &&
               coverage >= MIN_COVERAGE && coverage <= MAX_COVERAGE;
    }

    /**
     * Validates an IndexCoverage instance.
     *
     * @param coverage The coverage to validate
     * @throws NullPointerException if coverage is null
     * @throws IllegalArgumentException if any field is invalid
     */
    public static void validateIndexCoverage(IndexCoverage coverage) {
        Objects.requireNonNull(coverage, "Coverage cannot be null");

        // Validate coverage value
        validateCoverage(coverage.estimatedCorpusCoverage());

        // Validate segment counts are non-negative (should be enforced by builder, but double-check)
        if (coverage.totalSegments() < 0) {
            throw new IllegalArgumentException("totalSegments cannot be negative");
        }
        if (coverage.tailScanEntries() < 0) {
            throw new IllegalArgumentException("tailScanEntries cannot be negative");
        }
    }

    // ==================== Latency Validation ====================

    /**
     * Validates that a latency value is non-negative.
     *
     * @param latencyMs The latency in milliseconds
     * @throws IllegalArgumentException if latency is negative
     */
    public static void validateLatency(long latencyMs) {
        if (latencyMs < MIN_LATENCY_MS) {
            throw new IllegalArgumentException(
                String.format("Latency must be non-negative: %d ms", latencyMs));
        }
    }

    /**
     * Checks if a latency value is valid (non-negative).
     *
     * @param latencyMs The latency in milliseconds
     * @return true if latency is valid
     */
    public static boolean isValidLatency(long latencyMs) {
        return latencyMs >= MIN_LATENCY_MS;
    }

    // ==================== Result Item Validation ====================

    /**
     * Validates a single query result item.
     *
     * @param item The result item to validate
     * @throws NullPointerException if item is null
     * @throws IllegalArgumentException if any field is invalid
     */
    public static void validateResultItem(QueryResultItem item) {
        Objects.requireNonNull(item, "Result item cannot be null");
        validateScore(item.score());
    }

    /**
     * Validates a list of query result items.
     *
     * @param results The result items to validate
     * @throws NullPointerException if results is null
     * @throws IllegalArgumentException if any item is invalid
     */
    public static void validateResultItems(List<QueryResultItem> results) {
        Objects.requireNonNull(results, "Results cannot be null");

        for (int i = 0; i < results.size(); i++) {
            QueryResultItem item = results.get(i);
            if (item == null) {
                throw new IllegalArgumentException("Result item at index " + i + " is null");
            }
            try {
                validateScore(item.score());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid result at index " + i + ": " + e.getMessage(), e);
            }
        }
    }

    // ==================== Result Ordering Validation ====================

    /**
     * Validates that results are sorted by score in descending order.
     *
     * <p>Results should be sorted with highest scores first (descending order).
     * This is the expected order for query results where higher scores mean
     * better matches.</p>
     *
     * @param results The results to validate
     * @return true if results are properly sorted
     */
    public static boolean isSortedDescending(List<QueryResultItem> results) {
        Objects.requireNonNull(results, "Results cannot be null");

        for (int i = 1; i < results.size(); i++) {
            if (results.get(i).score() > results.get(i - 1).score()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates that results are sorted by score in descending order.
     *
     * @param results The results to validate
     * @throws IllegalArgumentException if results are not sorted
     */
    public static void validateSortedDescending(List<QueryResultItem> results) {
        if (!isSortedDescending(results)) {
            throw new IllegalArgumentException("Results are not sorted in descending order by score");
        }
    }

    // ==================== Duplicate Detection ====================

    /**
     * Checks for duplicate document IDs in results.
     *
     * @param results The results to check
     * @return Set of duplicate document IDs, or empty set if none found
     */
    public static Set<Long> findDuplicateDocumentIds(List<QueryResultItem> results) {
        Objects.requireNonNull(results, "Results cannot be null");

        Set<Long> seen = new HashSet<>();
        Set<Long> duplicates = new HashSet<>();

        for (QueryResultItem item : results) {
            long docId = item.documentId();
            if (!seen.add(docId)) {
                duplicates.add(docId);
            }
        }

        return duplicates;
    }

    /**
     * Validates that results contain no duplicate document IDs.
     *
     * @param results The results to validate
     * @throws IllegalArgumentException if duplicates are found
     */
    public static void validateNoDuplicates(List<QueryResultItem> results) {
        Set<Long> duplicates = findDuplicateDocumentIds(results);
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                "Results contain duplicate document IDs: " + duplicates);
        }
    }

    // ==================== Full Response Validation ====================

    /**
     * Validates a complete query response for domain correctness.
     *
     * <p>This method performs comprehensive validation:</p>
     * <ul>
     *   <li>Coverage values are in [0.0, 1.0]</li>
     *   <li>Latency is non-negative</li>
     *   <li>All result scores are in [0.0, 1.0]</li>
     *   <li>Results are sorted by score (descending)</li>
     *   <li>No duplicate document IDs in results</li>
     * </ul>
     *
     * @param response The response to validate
     * @throws NullPointerException if response is null
     * @throws IllegalArgumentException if any validation fails
     */
    public static void validateResponse(QueryResponse response) {
        Objects.requireNonNull(response, "Response cannot be null");

        // Validate latency
        validateLatency(response.queryTimeMs());

        // Validate coverage
        validateIndexCoverage(response.coverage());

        // Validate results
        List<QueryResultItem> results = response.results();
        validateResultItems(results);

        // Validate ordering
        validateSortedDescending(results);

        // Validate no duplicates
        validateNoDuplicates(results);
    }

    /**
     * Validates a query response and returns a validation result.
     *
     * <p>Unlike {@link #validateResponse(QueryResponse)}, this method never throws.
     * It returns a result object indicating success or listing all failures.</p>
     *
     * @param response The response to validate
     * @return A ValidationResult indicating success or listing failures
     */
    public static ValidationResult validateResponseWithResult(QueryResponse response) {
        ValidationResult.Builder builder = ValidationResult.builder();

        if (response == null) {
            return builder.addError("Response is null").build();
        }

        // Validate latency
        if (!isValidLatency(response.queryTimeMs())) {
            builder.addError("Latency is negative: " + response.queryTimeMs() + " ms");
        }

        // Validate coverage
        IndexCoverage coverage = response.coverage();
        if (coverage == null) {
            builder.addError("Coverage is null");
        } else {
            if (!isValidCoverage(coverage.estimatedCorpusCoverage())) {
                builder.addError("Coverage out of range [0.0, 1.0]: " +
                    coverage.estimatedCorpusCoverage());
            }
        }

        // Validate results
        List<QueryResultItem> results = response.results();
        if (results == null) {
            builder.addError("Results list is null");
        } else {
            // Check each result
            for (int i = 0; i < results.size(); i++) {
                QueryResultItem item = results.get(i);
                if (item == null) {
                    builder.addError("Result at index " + i + " is null");
                } else if (!isValidScore(item.score())) {
                    builder.addError("Result at index " + i + " has invalid score: " +
                        item.score());
                }
            }

            // Check ordering
            if (!isSortedDescending(results)) {
                builder.addError("Results are not sorted in descending order by score");
            }

            // Check duplicates
            Set<Long> duplicates = findDuplicateDocumentIds(results);
            if (!duplicates.isEmpty()) {
                builder.addError("Results contain duplicate document IDs: " + duplicates);
            }
        }

        return builder.build();
    }

    /**
     * Result of a domain correctness validation.
     */
    public static final class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        private ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }

        /**
         * Returns whether validation passed.
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Returns the list of validation errors.
         */
        public List<String> errors() {
            return errors;
        }

        /**
         * Returns a summary of validation results.
         */
        public String summary() {
            if (valid) {
                return "Validation passed";
            }
            return "Validation failed with " + errors.size() + " error(s): " +
                String.join("; ", errors);
        }

        /**
         * Creates a new builder for ValidationResult.
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder for ValidationResult.
         */
        public static final class Builder {
            private final java.util.ArrayList<String> errors = new java.util.ArrayList<>();

            /**
             * Adds an error to the validation result.
             */
            public Builder addError(String error) {
                errors.add(error);
                return this;
            }

            /**
             * Builds the ValidationResult.
             */
            public ValidationResult build() {
                return new ValidationResult(errors.isEmpty(), java.util.Collections.unmodifiableList(errors));
            }
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Normalizes a raw distance value to a score in [0.0, 1.0].
     *
     * <p>This is a convenience method that delegates to DistanceValidator.
     * The normalization formula depends on the distance type:</p>
     * <ul>
     *   <li>Cosine similarity: [-1, 1] → [0, 1] via (x+1)/2</li>
     *   <li>L2 distance: [0, ∞) → (0, 1] via 1/(1+x)</li>
     *   <li>Inner product: (-∞, ∞) → (0, 1) via sigmoid</li>
     * </ul>
     *
     * @param rawDistance The raw distance value
     * @param isDistanceMetric true if smaller is better (L2), false if larger is better (cosine, IP)
     * @return Normalized score in [0.0, 1.0]
     */
    public static float normalizeDistanceToScore(float rawDistance, boolean isDistanceMetric) {
        float score;
        if (isDistanceMetric) {
            // L2-style: 0 is perfect match, larger is worse
            // Use 1/(1+x) to map [0, ∞) → (0, 1]
            score = 1.0f / (1.0f + Math.max(0, rawDistance));
        } else {
            // Similarity-style (cosine/IP): larger is better
            // For cosine: [-1, 1] → [0, 1] via (x+1)/2
            // For inner product: use sigmoid
            if (rawDistance >= -1.0f && rawDistance <= 1.0f) {
                // Looks like cosine similarity
                score = (rawDistance + 1.0f) / 2.0f;
            } else {
                // Treat as inner product, use sigmoid
                score = (float) (1.0 / (1.0 + Math.exp(-rawDistance)));
            }
        }
        return clampScore(score);
    }

    /**
     * Validates that a count/size value is non-negative.
     *
     * @param count The count to validate
     * @param name The name of the count field (for error messages)
     * @throws IllegalArgumentException if count is negative
     */
    public static void validateNonNegativeCount(long count, String name) {
        if (count < 0) {
            throw new IllegalArgumentException(
                String.format("%s cannot be negative: %d", name, count));
        }
    }

    /**
     * Validates that an index is within bounds.
     *
     * @param index The index to validate
     * @param size The size of the collection
     * @param name The name of the index (for error messages)
     * @throws IndexOutOfBoundsException if index is out of bounds
     */
    public static void validateIndex(int index, int size, String name) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                String.format("%s index %d out of bounds [0, %d)", name, index, size));
        }
    }
}
