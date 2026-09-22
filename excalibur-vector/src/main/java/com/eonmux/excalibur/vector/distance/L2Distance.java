package com.eonmux.excalibur.vector.distance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * L2 (Euclidean) distance function with optional Vector API acceleration.
 *
 * <p>L2 distance computes the straight-line distance between two points in
 * Euclidean space. It is the most common distance metric for vector similarity.</p>
 *
 * <h2>Formula</h2>
 * <pre>
 * L2(v1, v2) = sqrt(sum((v1[i] - v2[i])^2))
 * </pre>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Output range: [0.0, +infinity)</li>
 *   <li>Identical vectors produce exactly 0.0</li>
 *   <li>NaN handling: Any NaN input produces NaN output</li>
 *   <li>Overflow prevention: Uses Kahan summation for numerical stability</li>
 * </ul>
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
public final class L2Distance implements DistanceFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(L2Distance.class);

    /**
     * Singleton instance of L2Distance.
     */
    private static final L2Distance INSTANCE = new L2Distance();

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
     * Private constructor for singleton pattern.
     */
    private L2Distance() {
        this.vectorSupport = VectorApiSupport.getInstance();
        if (vectorSupport.isAvailable()) {
            LOGGER.debug("L2Distance initialized with Vector API support (species length: {})",
                vectorSupport.getPreferredSpeciesLength());
        } else {
            LOGGER.debug("L2Distance initialized with scalar fallback (Vector API unavailable)");
        }
    }

    /**
     * Returns the singleton instance of L2Distance.
     *
     * @return The L2Distance instance
     */
    public static L2Distance getInstance() {
        return INSTANCE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>L2 is a true distance: 0.0 means identical, so smaller values are closer.</p>
     */
    @Override
    public Ordering ordering() {
        return Ordering.SMALLER_IS_CLOSER;
    }

    @Override
    public String name() {
        return "l2";
    }

    /**
     * Computes the L2 (Euclidean) distance between two vectors.
     *
     * <p>The result is always non-negative, with the following
     * edge case handling:</p>
     * <ul>
     *   <li>Identical vectors produce exactly 0.0</li>
     *   <li>Empty vectors produce 0.0</li>
     *   <li>Any NaN input produces NaN output</li>
     * </ul>
     *
     * @param v1 The first vector (must not be null)
     * @param v2 The second vector (must not be null)
     * @return L2 distance (always &gt;= 0.0), or NaN if inputs contain NaN
     * @throws IllegalArgumentException if vectors have different dimensions
     */
    @Override
    public float compute(float[] v1, float[] v2) {
        // Validate inputs using DistanceValidator for consistent error handling
        DistanceValidator.validateVectorPair(v1, v2, "v1", "v2");

        final int length = v1.length;

        // Handle empty vectors - distance is 0.0
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
     * Scalar implementation of L2 distance.
     *
     * <p>Uses Kahan summation for improved numerical accuracy with large vectors.</p>
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @param length Vector length
     * @return L2 distance
     */
    private float computeScalar(float[] v1, float[] v2, int length) {
        double sum = 0.0;
        double compensation = 0.0; // Kahan compensation term

        for (int i = 0; i < length; i++) {
            final double diff = (double) v1[i] - (double) v2[i];
            final double squared = diff * diff;

            // Kahan summation to reduce floating-point error accumulation
            final double y = squared - compensation;
            final double t = sum + y;
            compensation = (t - sum) - y;
            sum = t;
        }

        // Ensure non-negative result (numerical stability)
        // Math.max handles potential tiny negative values from floating-point errors
        return (float) Math.sqrt(Math.max(0.0, sum));
    }

    /**
     * Vectorized implementation using Vector API via reflection.
     *
     * <p>Processes vectors in chunks of the preferred SIMD width, then
     * handles the tail with scalar operations.</p>
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @param length Vector length
     * @return L2 distance
     */
    private float computeVectorized(float[] v1, float[] v2, int length) {
        final VectorApiSupport.VectorOperations ops = vectorSupport.getOperations();
        if (ops == null) {
            // Fallback to scalar if operations unavailable
            return computeScalar(v1, v2, length);
        }

        final int upperBound = vectorSupport.loopBound(length);

        // Accumulate squared differences using double for precision
        double sum = 0.0;

        try {
            // Process full vector chunks using Vector API
            sum += ops.squaredL2(v1, v2, 0, upperBound);
        } catch (Exception e) {
            // If vectorized path fails, fall back to scalar for the whole computation
            LOGGER.debug("Vector API computation failed, falling back to scalar: {}", e.getMessage());
            return computeScalar(v1, v2, length);
        }

        // Process tail elements (scalar)
        for (int i = upperBound; i < length; i++) {
            final double diff = (double) v1[i] - (double) v2[i];
            sum += diff * diff;
        }

        // Ensure non-negative result
        return (float) Math.sqrt(Math.max(0.0, sum));
    }

    /**
     * Computes squared L2 distance (without square root).
     *
     * <p>More efficient when only relative distances are needed,
     * as it avoids the expensive square root operation.
     * The square root is monotonic, so ordering is preserved.</p>
     *
     * @param v1 First vector (must not be null)
     * @param v2 Second vector (must not be null)
     * @return Squared L2 distance (always &gt;= 0.0)
     * @throws IllegalArgumentException if vectors have different dimensions
     */
    public float computeSquared(float[] v1, float[] v2) {
        Objects.requireNonNull(v1, "First vector must not be null");
        Objects.requireNonNull(v2, "Second vector must not be null");

        if (v1.length != v2.length) {
            throw new IllegalArgumentException(
                "Vector dimensions must match: " + v1.length + " vs " + v2.length);
        }

        final int length = v1.length;
        if (length == 0) {
            return 0.0f;
        }

        if (vectorSupport.isAvailable() && length >= VECTORIZATION_THRESHOLD) {
            return computeSquaredVectorized(v1, v2, length);
        }

        return computeSquaredScalar(v1, v2, length);
    }

    /**
     * Scalar implementation of squared L2 distance.
     */
    private float computeSquaredScalar(float[] v1, float[] v2, int length) {
        double sum = 0.0;
        double compensation = 0.0;

        for (int i = 0; i < length; i++) {
            final double diff = (double) v1[i] - (double) v2[i];
            final double squared = diff * diff;

            final double y = squared - compensation;
            final double t = sum + y;
            compensation = (t - sum) - y;
            sum = t;
        }

        return (float) Math.max(0.0, sum);
    }

    /**
     * Vectorized implementation of squared L2 distance.
     */
    private float computeSquaredVectorized(float[] v1, float[] v2, int length) {
        final VectorApiSupport.VectorOperations ops = vectorSupport.getOperations();
        if (ops == null) {
            return computeSquaredScalar(v1, v2, length);
        }

        final int upperBound = vectorSupport.loopBound(length);

        double sum = 0.0;

        try {
            sum += ops.squaredL2(v1, v2, 0, upperBound);
        } catch (Exception e) {
            LOGGER.debug("Vector API computation failed, falling back to scalar: {}", e.getMessage());
            return computeSquaredScalar(v1, v2, length);
        }

        // Process tail elements
        for (int i = upperBound; i < length; i++) {
            final double diff = (double) v1[i] - (double) v2[i];
            sum += diff * diff;
        }

        return (float) Math.max(0.0, sum);
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
        return "L2Distance" + (vectorSupport.isAvailable() ? "(vectorized)" : "(scalar)");
    }
}
