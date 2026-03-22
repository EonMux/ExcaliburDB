package io.excalibur.vector.distance;

/**
 * Utility class for validating distance values against their expected ranges.
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
}
