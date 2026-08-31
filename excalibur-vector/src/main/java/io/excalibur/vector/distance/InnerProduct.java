package io.excalibur.vector.distance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Inner product (dot product) distance function with optional Vector API acceleration.
 *
 * <p>Inner product computes the sum of element-wise products between two vectors.
 * For normalized vectors, inner product equals cosine similarity. For general
 * vectors, larger positive values indicate more similarity.</p>
 *
 * <h2>Formula</h2>
 * <pre>
 * inner_product(v1, v2) = sum(v1[i] * v2[i])
 * </pre>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Output range: (-infinity, +infinity)</li>
 *   <li>Identical non-zero vectors produce positive values (sum of squares)</li>
 *   <li>Orthogonal vectors produce 0.0</li>
 *   <li>Opposite vectors produce negative values</li>
 *   <li>Negative values are handled correctly: product of opposite signs is negative</li>
 *   <li>NaN handling: Any NaN input produces NaN output</li>
 *   <li>Overflow prevention: Uses double accumulation with explicit overflow detection</li>
 *   <li>Large vector support: Accurate results for vectors with 1M+ dimensions</li>
 * </ul>
 *
 * <h2>Overflow Handling</h2>
 * <p>This implementation uses double-precision accumulation to prevent overflow
 * for most practical use cases. For extremely large values, the result is clamped
 * to {@link Float#MAX_VALUE} or {@code -Float.MAX_VALUE} rather than overflowing
 * to infinity. This ensures predictable behavior for similarity ranking.</p>
 *
 * <h2>Usage Note</h2>
 * <p>For similarity search, larger values indicate more similar vectors.
 * To use as a distance metric (smaller = more similar), negate the result
 * or use {@link #computeNegated(float[], float[])}.</p>
 *
 * <h2>Vector API Fallback</h2>
 * <p>Uses the JDK Vector API (jdk.incubator.vector) for SIMD acceleration
 * when available at runtime. Gracefully falls back to scalar computation when:</p>
 * <ul>
 *   <li>Vector API classes are not loaded (--add-modules=jdk.incubator.vector missing)</li>
 *   <li>Platform does not support SIMD operations</li>
 *   <li>Vector is too small for vectorization benefit</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All state is immutable.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class InnerProduct implements DistanceFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(InnerProduct.class);

    /**
     * Singleton instance of InnerProduct.
     */
    private static final InnerProduct INSTANCE = new InnerProduct();

    /**
     * Vector API support - determines availability at runtime.
     */
    private final VectorApiSupport vectorSupport;

    /**
     * Threshold for switching between vectorized and scalar computation.
     * Vectors smaller than this use scalar path to avoid vector setup overhead.
     */
    private static final int VECTORIZATION_THRESHOLD = 8;

    /**
     * Maximum safe value for float result before clamping.
     * Used to prevent overflow to infinity.
     */
    private static final double MAX_SAFE_FLOAT = Float.MAX_VALUE;

    /**
     * Minimum safe value for float result before clamping (negative).
     * Used to prevent underflow to negative infinity.
     */
    private static final double MIN_SAFE_FLOAT = -Float.MAX_VALUE;

    /**
     * Private constructor for singleton pattern.
     */
    private InnerProduct() {
        this.vectorSupport = VectorApiSupport.getInstance();
        if (vectorSupport.isAvailable()) {
            LOGGER.debug("InnerProduct initialized with Vector API support (species length: {})",
                vectorSupport.getPreferredSpeciesLength());
        } else {
            LOGGER.debug("InnerProduct initialized with scalar fallback (Vector API unavailable)");
        }
    }

    /**
     * Returns the singleton instance of InnerProduct.
     *
     * @return The InnerProduct instance
     */
    public static InnerProduct getInstance() {
        return INSTANCE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Inner product is a similarity: larger values indicate more similar
     * vectors, so larger values are closer.</p>
     */
    @Override
    public Ordering ordering() {
        return Ordering.LARGER_IS_CLOSER;
    }

    @Override
    public String name() {
        return "inner_product";
    }

    /**
     * Computes the inner product (dot product) between two vectors.
     *
     * <p>The result can be positive, negative, or zero:</p>
     * <ul>
     *   <li>Positive: vectors point in similar directions</li>
     *   <li>Zero: vectors are orthogonal</li>
     *   <li>Negative: vectors point in opposite directions</li>
     * </ul>
     *
     * <p><strong>Overflow handling:</strong> If the result exceeds the range
     * of float, it is clamped to {@link Float#MAX_VALUE} or
     * {@code -Float.MAX_VALUE} rather than becoming infinite.</p>
     *
     * @param v1 The first vector (must not be null)
     * @param v2 The second vector (must not be null)
     * @return Inner product value (larger = more similar), clamped to float range
     * @throws IllegalArgumentException if vectors have different dimensions
     */
    @Override
    public float compute(float[] v1, float[] v2) {
        // Validate inputs using DistanceValidator for consistent error handling
        DistanceValidator.validateVectorPair(v1, v2, "v1", "v2");

        final int length = v1.length;

        // Handle empty vectors
        if (length == 0) {
            return 0.0f;
        }

        // Use vectorized path only if Vector API is available and vector is large enough
        if (vectorSupport.isAvailable() && length >= VECTORIZATION_THRESHOLD) {
            return computeVectorized(v1, v2, length);
        }

        return computeScalar(v1, v2, length);
    }

    /**
     * Scalar implementation of inner product.
     *
     * <p>Uses Kahan summation for improved numerical accuracy with large vectors.
     * All intermediate computations use double precision to prevent overflow.</p>
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @param length Vector length
     * @return Inner product, clamped to float range
     */
    private float computeScalar(float[] v1, float[] v2, int length) {
        // Use double for all intermediate calculations to prevent overflow
        // and maintain precision for negative value handling
        double sum = 0.0;
        double compensation = 0.0; // Kahan compensation term

        for (int i = 0; i < length; i++) {
            // Cast to double BEFORE multiplication to:
            // 1. Prevent overflow in float multiplication
            // 2. Preserve sign correctly for negative * negative = positive
            // 3. Maintain full precision for large vectors
            final double a = v1[i];
            final double b = v2[i];
            final double product = a * b;

            // Kahan summation to reduce floating-point error accumulation
            // This is critical for large vectors (100K+ dimensions)
            final double y = product - compensation;
            final double t = sum + y;
            compensation = (t - sum) - y;
            sum = t;
        }

        return clampToFloatRange(sum);
    }

    /**
     * Vectorized implementation using Vector API via reflection.
     *
     * <p>Processes vectors in chunks of the preferred SIMD width, then
     * handles the tail with scalar operations. Uses double accumulation
     * to prevent overflow for large vectors.</p>
     *
     * <p><strong>Note:</strong> The vectorized path computes products in float
     * precision before reduction, which may lose precision for very large values.
     * For extreme cases, consider using the scalar path or accepting the minor
     * precision trade-off for the performance benefit.</p>
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @param length Vector length
     * @return Inner product, clamped to float range
     */
    private float computeVectorized(float[] v1, float[] v2, int length) {
        final VectorApiSupport.VectorOperations ops = vectorSupport.getOperations();
        if (ops == null) {
            // Fallback to scalar if operations unavailable
            return computeScalar(v1, v2, length);
        }

        final int upperBound = vectorSupport.loopBound(length);

        // Accumulate using double for precision and overflow prevention
        double sum = 0.0;

        try {
            // Process full vector chunks using Vector API
            // Note: FloatVector.mul computes in float precision, but we accumulate
            // in double to prevent overflow in the final sum
            sum += ops.dotProduct(v1, v2, 0, upperBound);
        } catch (Exception e) {
            // If vectorized path fails, fall back to scalar for the whole computation
            LOGGER.debug("Vector API computation failed, falling back to scalar: {}", e.getMessage());
            return computeScalar(v1, v2, length);
        }

        // Process tail elements with double precision (scalar, high precision)
        for (int i = upperBound; i < length; i++) {
            // Cast to double BEFORE multiplication for precision
            sum += (double) v1[i] * (double) v2[i];
        }

        return clampToFloatRange(sum);
    }

    /**
     * Clamps a double value to the valid float range.
     *
     * <p>This prevents overflow to infinity, ensuring predictable behavior
     * for similarity ranking. Values exceeding Float.MAX_VALUE are clamped
     * rather than becoming infinite.</p>
     *
     * @param value The double value to clamp
     * @return The value clamped to float range
     */
    private static float clampToFloatRange(double value) {
        // Handle NaN - preserve it
        if (Double.isNaN(value)) {
            return Float.NaN;
        }

        // Handle positive overflow
        if (value > MAX_SAFE_FLOAT) {
            return Float.MAX_VALUE;
        }

        // Handle negative overflow
        if (value < MIN_SAFE_FLOAT) {
            return -Float.MAX_VALUE;
        }

        // Safe to cast
        return (float) value;
    }

    /**
     * Computes the negated inner product.
     *
     * <p>This is useful for similarity search where smaller values indicate
     * more similar vectors (converting similarity to distance).</p>
     *
     * @param v1 First vector (must not be null)
     * @param v2 Second vector (must not be null)
     * @return Negated inner product (smaller = more similar)
     * @throws IllegalArgumentException if vectors have different dimensions
     */
    public float computeNegated(float[] v1, float[] v2) {
        return -compute(v1, v2);
    }

    /**
     * Computes inner product with one vector pre-normalized.
     *
     * <p>This is an optimization when one vector is already normalized
     * and you want to avoid redundant normalization.</p>
     *
     * @param v1 First vector (may not be normalized)
     * @param v2 Second vector (assumed to be normalized)
     * @param v2NormSq Pre-computed squared norm of v2 (must be positive if v2 is normalized)
     * @return Inner product
     * @throws IllegalArgumentException if vectors have different dimensions or v2NormSq is negative
     */
    public float computeWithOneNorm(float[] v1, float[] v2, double v2NormSq) {
        Objects.requireNonNull(v1, "First vector must not be null");
        Objects.requireNonNull(v2, "Second vector must not be null");

        if (v1.length != v2.length) {
            throw new IllegalArgumentException(
                "Vector dimensions must match: " + v1.length + " vs " + v2.length);
        }

        if (v2NormSq < 0.0) {
            throw new IllegalArgumentException(
                "Squared norm must be non-negative: " + v2NormSq);
        }

        return compute(v1, v2);
    }

    /**
     * Computes the squared norm (L2 norm squared) of a vector.
     *
     * <p>This is equivalent to the inner product of a vector with itself.
     * Uses double-precision accumulation with overflow protection.</p>
     *
     * @param v The vector
     * @return The squared norm (sum of squared elements), clamped to float range
     * @throws NullPointerException if vector is null
     */
    public float computeSquaredNorm(float[] v) {
        Objects.requireNonNull(v, "Vector must not be null");

        final int length = v.length;
        if (length == 0) {
            return 0.0f;
        }

        double sum = 0.0;

        // Use Vector API if available
        if (vectorSupport.isAvailable() && length >= VECTORIZATION_THRESHOLD) {
            final VectorApiSupport.VectorOperations ops = vectorSupport.getOperations();
            if (ops != null) {
                try {
                    final int upperBound = vectorSupport.loopBound(length);
                    sum += ops.squaredNorm(v, 0, upperBound);

                    // Tail - use double precision for consistency
                    for (int i = upperBound; i < length; i++) {
                        final double val = v[i];
                        sum += val * val;
                    }
                    return clampToFloatRange(sum);
                } catch (Exception e) {
                    // Fall through to scalar
                    LOGGER.debug("Vector API computation failed, falling back to scalar: {}", e.getMessage());
                }
            }
        }

        // Scalar path
        for (int i = 0; i < length; i++) {
            final double val = v[i];
            sum += val * val;
        }

        return clampToFloatRange(sum);
    }

    /**
     * Checks if two vectors are orthogonal (inner product equals zero).
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @param tolerance The tolerance for considering the product zero
     * @return true if the vectors are orthogonal within tolerance
     */
    public boolean isOrthogonal(float[] v1, float[] v2, float tolerance) {
        float product = compute(v1, v2);
        return Math.abs(product) <= tolerance;
    }

    /**
     * Returns whether Vector API acceleration is available.
     *
     * @return true if Vector API is being used for acceleration
     */
    public boolean isVectorApiAvailable() {
        return vectorSupport.isAvailable();
    }

    @Override
    public String toString() {
        return "InnerProduct" + (vectorSupport.isAvailable() ? "(vectorized)" : "(scalar)");
    }
}
