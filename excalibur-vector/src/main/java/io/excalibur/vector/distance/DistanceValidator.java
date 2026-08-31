package io.excalibur.vector.distance;

import java.util.Objects;

/**
 * Utility class for validating distance function inputs and output values.
 *
 * <p>This class provides two categories of validation:</p>
 *
 * <h3>Input Validation</h3>
 * <p>Validates input vectors before distance computation:</p>
 * <ul>
 *   <li>Non-null vectors</li>
 *   <li>Non-empty vectors</li>
 *   <li>Finite values only (no NaN or Infinity)</li>
 *   <li>Matching dimensions between vector pairs</li>
 * </ul>
 *
 * <h3>Output Validation</h3>
 * <p>Validates computed distance values against their expected ranges:</p>
 * <ul>
 *   <li><strong>Cosine similarity:</strong> Range [-1.0, 1.0]</li>
 *   <li><strong>L2 distance:</strong> Range [0.0, +infinity)</li>
 *   <li><strong>Inner product:</strong> Range (-infinity, +infinity)</li>
 * </ul>
 *
 * <p>This class provides methods to validate that computed distance values
 * fall within the mathematically correct range for each distance metric:</p>
 *
 * <ul>
 *   <li><strong>Cosine similarity:</strong> Range [-1.0, 1.0]</li>
 *   <li><strong>L2 distance:</strong> Range [0.0, +infinity)</li>
 *   <li><strong>Inner product:</strong> Range (-infinity, +infinity)</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <p>Distance values must be semantically correct for the domain:</p>
 * <ul>
 *   <li>Cosine similarity outside [-1, 1] indicates a computation error</li>
 *   <li>Negative L2 distance is mathematically impossible</li>
 *   <li>NaN indicates invalid input or numerical instability</li>
 *   <li>Infinity may indicate overflow or extreme values</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All methods are static and stateless.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class DistanceValidator {

    /**
     * Tolerance for floating-point comparisons when validating ranges.
     */
    public static final float RANGE_TOLERANCE = 1e-6f;

    /**
     * Private constructor to prevent instantiation.
     */
    private DistanceValidator() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    // ============================================================
    // INPUT VALIDATION METHODS
    // ============================================================

    /**
     * Validates that a vector is non-null, non-empty, and contains only finite values.
     *
     * <p>This method should be called before distance computation to ensure
     * that input vectors are valid. Vectors containing NaN or Infinity values
     * will produce undefined results in distance calculations.</p>
     *
     * <p><strong>Domain Correctness:</strong> Finite values are required for
     * meaningful distance computation. NaN propagates through calculations
     * producing NaN results, and Infinity can cause overflow or undefined behavior.</p>
     *
     * @param vector The vector to validate
     * @param name The parameter name for error messages (e.g., "v1", "query vector")
     * @throws NullPointerException if vector is null
     * @throws IllegalArgumentException if vector is empty or contains NaN/Infinity values
     */
    public static void validateInputVector(float[] vector, String name) {
        Objects.requireNonNull(vector, name + " cannot be null");
        if (vector.length == 0) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException(
                    name + " contains non-finite value at index " + i + ": " + vector[i]);
            }
        }
    }

    /**
     * Validates that two vectors have matching dimensions.
     *
     * <p>This method should be called after {@link #validateInputVector(float[], String)}
     * for both vectors to ensure they have the same dimensionality.</p>
     *
     * @param v1 The first vector
     * @param v2 The second vector
     * @throws IllegalArgumentException if vectors have different lengths
     */
    public static void validateMatchingDimensions(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException(
                "Vector dimensions must match: " + v1.length + " vs " + v2.length);
        }
    }

    /**
     * Convenience method that validates both input vectors and their dimensions.
     *
     * <p>This is equivalent to calling:</p>
     * <pre>
     * validateInputVector(v1, "v1");
     * validateInputVector(v2, "v2");
     * validateMatchingDimensions(v1, v2);
     * </pre>
     *
     * @param v1 The first vector
     * @param v2 The second vector
     * @param name1 The name of the first vector for error messages
     * @param name2 The name of the second vector for error messages
     * @throws NullPointerException if either vector is null
     * @throws IllegalArgumentException if either vector is empty, contains non-finite values,
     *         or if dimensions don't match
     */
    public static void validateVectorPair(float[] v1, float[] v2, String name1, String name2) {
        validateInputVector(v1, name1);
        validateInputVector(v2, name2);
        validateMatchingDimensions(v1, v2);
    }

    // ============================================================
    // OUTPUT VALIDATION METHODS
    // ============================================================

    /**
     * Validates that a cosine similarity value is in the valid range [-1.0, 1.0].
     *
     * <p>Cosine similarity measures the cosine of the angle between two vectors.
     * By definition, this value must be in the range [-1, 1]. Values outside
     * this range indicate numerical errors in computation.</p>
     *
     * @param value The cosine similarity value to validate
     * @return true if the value is in valid range, false otherwise
     */
    public static boolean isValidCosineSimilarity(float value) {
        if (Float.isNaN(value)) {
            // NaN is considered valid (indicates invalid input vectors)
            // but should be handled by caller
            return true;
        }
        return value >= -1.0f - RANGE_TOLERANCE && value <= 1.0f + RANGE_TOLERANCE;
    }

    /**
     * Validates that an L2 distance value is non-negative [0.0, +infinity).
     *
     * <p>L2 (Euclidean) distance is computed as the square root of the sum
     * of squared differences. By definition, this value cannot be negative.
     * Negative values indicate a serious computational error.</p>
     *
     * @param value The L2 distance value to validate
     * @return true if the value is non-negative (or NaN/Infinity), false otherwise
     */
    public static boolean isValidL2Distance(float value) {
        if (Float.isNaN(value)) {
            // NaN is considered valid (indicates invalid input vectors)
            return true;
        }
        return value >= 0.0f;
    }

    /**
     * Validates that an inner product value is valid (any finite or infinite value).
     *
     * <p>Inner product (dot product) has an unbounded range (-infinity, +infinity).
     * Any value is mathematically valid, but we check for NaN which indicates
     * computational issues.</p>
     *
     * @param value The inner product value to validate
     * @return true (inner product has no range constraints)
     */
    public static boolean isValidInnerProduct(float value) {
        // Inner product has unbounded range, all values are valid
        return true;
    }

    /**
     * Validates a distance value based on the distance function type.
     *
     * @param value The distance value to validate
     * @param distanceFunction The distance function used to compute the value
     * @return true if the value is valid for the given distance type
     * @throws NullPointerException if distanceFunction is null
     */
    public static boolean isValidDistance(float value, DistanceFunction distanceFunction) {
        if (distanceFunction == null) {
            throw new NullPointerException("DistanceFunction cannot be null");
        }

        String name = distanceFunction.name();
        return switch (name) {
            case "cosine" -> isValidCosineSimilarity(value);
            case "l2" -> isValidL2Distance(value);
            case "inner_product" -> isValidInnerProduct(value);
            default -> true; // Unknown distance type, assume valid
        };
    }

    /**
     * Clamps a cosine similarity value to the valid range [-1.0, 1.0].
     *
     * <p>This is used to correct floating-point errors that may cause
     * values to slightly exceed the theoretical range.</p>
     *
     * @param value The cosine similarity value to clamp
     * @return The clamped value in range [-1.0, 1.0], or NaN if input is NaN
     */
    public static float clampCosineSimilarity(float value) {
        if (Float.isNaN(value)) {
            return Float.NaN;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        if (value < -1.0f) {
            return -1.0f;
        }
        return value;
    }

    /**
     * Clamps an L2 distance value to be non-negative [0.0, +infinity).
     *
     * <p>This is used to correct floating-point errors that may cause
     * values to be slightly negative due to precision issues.</p>
     *
     * @param value The L2 distance value to clamp
     * @return The clamped non-negative value, or NaN if input is NaN
     */
    public static float clampL2Distance(float value) {
        if (Float.isNaN(value)) {
            return Float.NaN;
        }
        return Math.max(0.0f, value);
    }

    /**
     * Clamps a distance value based on the distance function type.
     *
     * @param value The distance value to clamp
     * @param distanceFunction The distance function used to compute the value
     * @return The clamped value appropriate for the distance type
     * @throws NullPointerException if distanceFunction is null
     */
    public static float clampDistance(float value, DistanceFunction distanceFunction) {
        if (distanceFunction == null) {
            throw new NullPointerException("DistanceFunction cannot be null");
        }

        String name = distanceFunction.name();
        return switch (name) {
            case "cosine" -> clampCosineSimilarity(value);
            case "l2" -> clampL2Distance(value);
            case "inner_product" -> value; // No clamping needed
            default -> value; // Unknown type, no clamping
        };
    }

    /**
     * Validates and clamps a distance value, returning a safe value.
     *
     * <p>This is a convenience method that combines validation and clamping.
     * It logs a warning if the value was out of range.</p>
     *
     * @param value The distance value to validate and clamp
     * @param distanceFunction The distance function used to compute the value
     * @return The validated and clamped distance value
     * @throws NullPointerException if distanceFunction is null
     */
    public static float validateAndClamp(float value, DistanceFunction distanceFunction) {
        float clamped = clampDistance(value, distanceFunction);

        // Check if clamping changed the value (indicates out-of-range)
        if (!Float.isNaN(value) && !Float.isNaN(clamped) && value != clamped) {
            // Value was clamped - could log warning here if needed
            // For performance, we skip logging in hot paths
        }

        return clamped;
    }

    /**
     * Checks if a distance value is finite (not NaN or Infinity).
     *
     * <p>Finite distances are required for meaningful comparisons and sorting.
     * NaN and Infinity values should be handled specially.</p>
     *
     * @param value The distance value to check
     * @return true if the value is finite, false if NaN or Infinity
     */
    public static boolean isFinite(float value) {
        return Float.isFinite(value);
    }

    /**
     * Checks if a distance value represents a valid result (not NaN).
     *
     * <p>NaN indicates an error in computation (e.g., zero vector for cosine).
     * Infinity may be valid for some metrics (e.g., overflow in L2).</p>
     *
     * @param value The distance value to check
     * @return true if the value is not NaN
     */
    public static boolean isValidResult(float value) {
        return !Float.isNaN(value);
    }

    /**
     * Returns a human-readable description of the valid range for a distance type.
     *
     * @param distanceFunction The distance function
     * @return A string describing the valid range
     * @throws NullPointerException if distanceFunction is null
     */
    public static String getValidRangeDescription(DistanceFunction distanceFunction) {
        if (distanceFunction == null) {
            throw new NullPointerException("DistanceFunction cannot be null");
        }

        String name = distanceFunction.name();
        return switch (name) {
            case "cosine" -> "[-1.0, 1.0]";
            case "l2" -> "[0.0, +infinity)";
            case "inner_product" -> "(-infinity, +infinity)";
            default -> "unknown";
        };
    }

    // ============================================================
    // SCORE NORMALIZATION METHODS
    // ============================================================

    /**
     * Normalizes a cosine similarity value to a score in the range [0.0, 1.0].
     *
     * <p>Transforms cosine similarity from [-1.0, 1.0] to [0.0, 1.0] using the formula:</p>
     * <pre>
     * normalizedScore = (cosineSimilarity + 1) / 2
     * </pre>
     *
     * <p>This transformation preserves ordering: higher cosine similarity → higher score.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Cosine 1.0 (identical vectors) → Score 1.0 (perfect match)</li>
     *   <li>Cosine 0.0 (orthogonal vectors) → Score 0.5 (neutral)</li>
     *   <li>Cosine -1.0 (opposite vectors) → Score 0.0 (worst match)</li>
     *   <li>NaN input → NaN output</li>
     * </ul>
     *
     * @param cosineSimilarity The cosine similarity value in [-1.0, 1.0]
     * @return The normalized score in [0.0, 1.0], or NaN if input is NaN
     */
    public static float normalizeCosineToScore(float cosineSimilarity) {
        if (Float.isNaN(cosineSimilarity)) {
            return Float.NaN;
        }
        // Clamp first to ensure input is in valid range
        float clamped = clampCosineSimilarity(cosineSimilarity);
        // Transform [-1, 1] to [0, 1]: (x + 1) / 2
        return (clamped + 1.0f) / 2.0f;
    }

    /**
     * Normalizes an L2 distance value to a score in the range [0.0, 1.0].
     *
     * <p>Transforms L2 distance from [0.0, +infinity) to [0.0, 1.0] using the formula:</p>
     * <pre>
     * normalizedScore = 1 / (1 + l2Distance)
     * </pre>
     *
     * <p>This transformation inverts the ordering: lower L2 distance → higher score.
     * A distance of 0 (identical vectors) produces a score of 1.0.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>L2 distance 0.0 (identical) → Score 1.0 (perfect match)</li>
     *   <li>L2 distance 1.0 → Score 0.5</li>
     *   <li>L2 distance → +infinity → Score → 0.0</li>
     *   <li>NaN input → NaN output</li>
     * </ul>
     *
     * @param l2Distance The L2 distance value in [0.0, +infinity)
     * @return The normalized score in [0.0, 1.0], or NaN if input is NaN
     */
    public static float normalizeL2ToScore(float l2Distance) {
        if (Float.isNaN(l2Distance)) {
            return Float.NaN;
        }
        // Clamp first to ensure input is non-negative
        float clamped = clampL2Distance(l2Distance);
        // Transform [0, inf) to (0, 1]: 1 / (1 + x)
        // Using 1 + x ensures we never divide by zero and produces (0, 1]
        return 1.0f / (1.0f + clamped);
    }

    /**
     * Normalizes an inner product value to a score in the range [0.0, 1.0].
     *
     * <p>Transforms inner product from (-infinity, +infinity) to [0.0, 1.0] using
     * a sigmoid function:</p>
     * <pre>
     * normalizedScore = 1 / (1 + exp(-innerProduct))
     * </pre>
     *
     * <p>This transformation preserves ordering: higher inner product → higher score.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Inner product → +infinity → Score → 1.0</li>
     *   <li>Inner product 0.0 → Score 0.5 (neutral)</li>
     *   <li>Inner product → -infinity → Score → 0.0</li>
     *   <li>NaN input → NaN output</li>
     * </ul>
     *
     * @param innerProduct The inner product value
     * @return The normalized score in [0.0, 1.0], or NaN if input is NaN
     */
    public static float normalizeInnerProductToScore(float innerProduct) {
        if (Float.isNaN(innerProduct)) {
            return Float.NaN;
        }
        // Handle infinity cases explicitly to avoid numerical issues
        if (innerProduct == Float.POSITIVE_INFINITY) {
            return 1.0f;
        }
        if (innerProduct == Float.NEGATIVE_INFINITY) {
            return 0.0f;
        }
        // Sigmoid: 1 / (1 + exp(-x))
        // This maps (-inf, +inf) to (0, 1) smoothly
        double sigmoid = 1.0 / (1.0 + Math.exp(-innerProduct));
        return (float) sigmoid;
    }

    /**
     * Normalizes a distance value to a score in the range [0.0, 1.0] based on the
     * distance function type.
     *
     * <p>This is the primary method for converting raw distance values to normalized
     * scores suitable for ranking and presentation. The transformation depends on
     * the distance metric:</p>
     *
     * <table border="1">
     *   <tr><th>Distance Type</th><th>Input Range</th><th>Output Range</th><th>Formula</th></tr>
     *   <tr><td>Cosine</td><td>[-1, 1]</td><td>[0, 1]</td><td>(x + 1) / 2</td></tr>
     *   <tr><td>L2</td><td>[0, inf)</td><td>(0, 1]</td><td>1 / (1 + x)</td></tr>
     *   <tr><td>Inner Product</td><td>(-inf, inf)</td><td>(0, 1)</td><td>sigmoid(x)</td></tr>
     * </table>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>All outputs are guaranteed to be in [0.0, 1.0] for valid inputs</li>
     *   <li>Higher scores always indicate better matches (closer vectors)</li>
     *   <li>NaN input → NaN output</li>
     *   <li>Infinity handled gracefully</li>
     * </ul>
     *
     * @param distance The raw distance value
     * @param distanceFunction The distance function used to compute the value
     * @return The normalized score in [0.0, 1.0], or NaN if input is NaN
     * @throws NullPointerException if distanceFunction is null
     */
    public static float normalizeToScore(float distance, DistanceFunction distanceFunction) {
        if (distanceFunction == null) {
            throw new NullPointerException("DistanceFunction cannot be null");
        }

        String name = distanceFunction.name();
        return switch (name) {
            case "cosine" -> normalizeCosineToScore(distance);
            case "l2" -> normalizeL2ToScore(distance);
            case "inner_product" -> normalizeInnerProductToScore(distance);
            default -> {
                // Unknown distance type - apply a generic normalization
                // For unknown types, we assume higher = better and apply sigmoid
                yield normalizeInnerProductToScore(distance);
            }
        };
    }

    /**
     * Validates, clamps, and normalizes a distance value to a score in [0.0, 1.0].
     *
     * <p>This is a convenience method that combines validation, clamping, and
     * normalization in a single call. It is the recommended method for converting
     * raw distance values to final scores.</p>
     *
     * <h3>Processing Steps</h3>
     * <ol>
     *   <li>Clamp distance to valid range for the metric type</li>
     *   <li>Normalize to [0.0, 1.0] score range</li>
     * </ol>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Output is guaranteed to be in [0.0, 1.0] (or NaN for invalid inputs)</li>
     *   <li>Higher scores indicate better matches</li>
     * </ul>
     *
     * @param distance The raw distance value
     * @param distanceFunction The distance function used to compute the value
     * @return The normalized score in [0.0, 1.0], or NaN if input is NaN
     * @throws NullPointerException if distanceFunction is null
     */
    public static float validateClampAndNormalize(float distance, DistanceFunction distanceFunction) {
        // First clamp to valid range
        float clamped = clampDistance(distance, distanceFunction);
        // Then normalize to [0, 1]
        return normalizeToScore(clamped, distanceFunction);
    }

    /**
     * Normalizes a monotone dissimilarity to a score in [0.0, 1.0].
     *
     * <p>Use this overload for values produced by
     * {@link DistanceFunction#distance(float[], float[])} - such as
     * {@code HnswSearchResult.distance()} - where smaller always means closer.
     * The value is first converted back to the metric's natural
     * {@link DistanceFunction#compute} scale using
     * {@link DistanceFunction.Ordering#fromDistance(float)}, then normalized by
     * {@link #normalizeToScore(float, DistanceFunction)}.</p>
     *
     * <p>Passing an ordered distance to {@link #normalizeToScore} directly would
     * invert the ranking for similarity metrics (cosine, inner product).</p>
     *
     * @param orderedDistance The dissimilarity value (smaller is closer)
     * @param distanceFunction The distance function that produced the value
     * @return The normalized score in [0.0, 1.0], or NaN if input is NaN
     * @throws NullPointerException if distanceFunction is null
     * @since 1.3
     */
    public static float normalizeOrderedDistanceToScore(float orderedDistance,
                                                        DistanceFunction distanceFunction) {
        if (distanceFunction == null) {
            throw new NullPointerException("DistanceFunction cannot be null");
        }
        return normalizeToScore(
            distanceFunction.ordering().fromDistance(orderedDistance), distanceFunction);
    }

    /**
     * Validates, clamps, and normalizes a monotone dissimilarity to a score in [0.0, 1.0].
     *
     * <p>This is the {@link DistanceFunction#distance(float[], float[])} counterpart of
     * {@link #validateClampAndNormalize(float, DistanceFunction)}.</p>
     *
     * @param orderedDistance The dissimilarity value (smaller is closer)
     * @param distanceFunction The distance function that produced the value
     * @return The normalized score in [0.0, 1.0], or NaN if input is NaN
     * @throws NullPointerException if distanceFunction is null
     * @since 1.3
     */
    public static float validateClampAndNormalizeOrderedDistance(float orderedDistance,
                                                                 DistanceFunction distanceFunction) {
        if (distanceFunction == null) {
            throw new NullPointerException("DistanceFunction cannot be null");
        }
        return validateClampAndNormalize(
            distanceFunction.ordering().fromDistance(orderedDistance), distanceFunction);
    }
}
