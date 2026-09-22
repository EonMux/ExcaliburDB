package com.eonmux.excalibur.vector.distance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Cosine similarity distance function with optional Vector API acceleration.
 *
 * <p>Cosine similarity measures the cosine of the angle between two vectors,
 * producing a value in the range [-1.0, 1.0]. A value of 1.0 indicates
 * identical direction, 0.0 indicates orthogonality, and -1.0 indicates
 * opposite direction.</p>
 *
 * <h2>Formula</h2>
 * <pre>
 * similarity = (v1 · v2) / (||v1|| * ||v2||)
 *            = sum(v1[i] * v2[i]) / (sqrt(sum(v1[i]²)) * sqrt(sum(v2[i]²)))
 * </pre>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Output range: [-1.0, 1.0]</li>
 *   <li>Zero vectors: Returns 0.0 (undefined mathematically, but practical)</li>
 *   <li>NaN handling: Any NaN input produces NaN output</li>
 *   <li>Infinity handling: Computed via standard IEEE 754 arithmetic</li>
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
public final class CosineSimilarity implements DistanceFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(CosineSimilarity.class);

    /**
     * Singleton instance of CosineSimilarity.
     */
    private static final CosineSimilarity INSTANCE = new CosineSimilarity();

    /**
     * Vector API support - determines availability at runtime.
     */
    private final VectorApiSupport vectorSupport;

    /**
     * Threshold for switching between vectorized and scalar computation.
     * Vectors smaller than this use scalar path to avoid vector setup overhead.
     */
    private static final int VECTORIZATION_THRESHOLD = 64;

    /**
     * Private constructor for singleton pattern.
     */
    private CosineSimilarity() {
        this.vectorSupport = VectorApiSupport.getInstance();
        if (vectorSupport.isAvailable()) {
            LOGGER.debug("CosineSimilarity initialized with Vector API support (species length: {})",
                vectorSupport.getPreferredSpeciesLength());
        } else {
            LOGGER.debug("CosineSimilarity initialized with scalar fallback (Vector API unavailable)");
        }
    }

    /**
     * Returns the singleton instance of CosineSimilarity.
     *
     * @return The CosineSimilarity instance
     */
    public static CosineSimilarity getInstance() {
        return INSTANCE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cosine similarity is a similarity: 1.0 means identical direction and
     * -1.0 means opposite direction, so larger values are closer.</p>
     */
    @Override
    public Ordering ordering() {
        return Ordering.LARGER_IS_CLOSER;
    }

    @Override
    public String name() {
        return "cosine";
    }

    /**
     * Computes the cosine similarity between two vectors.
     *
     * <p>The result is always in the range [-1.0, 1.0], with the following
     * edge case handling:</p>
     * <ul>
     *   <li>If either vector has zero magnitude, returns 0.0</li>
     *   <li>If any input is NaN, returns NaN</li>
     *   <li>Result is clamped to [-1.0, 1.0] to handle floating-point errors</li>
     * </ul>
     *
     * @param v1 The first vector (must not be null)
     * @param v2 The second vector (must not be null)
     * @return Cosine similarity in range [-1.0, 1.0], or 0.0 for zero vectors
     * @throws IllegalArgumentException if vectors have different dimensions or are null
     */
    @Override
    public float compute(float[] v1, float[] v2) {
        // Validate inputs using DistanceValidator for consistent error handling
        DistanceValidator.validateVectorPair(v1, v2, "v1", "v2");

        final int length = v1.length;

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
     * Scalar implementation of cosine similarity.
     *
     * <p>This is the fallback path used when:</p>
     * <ul>
     *   <li>Vector API is not available</li>
     *   <li>Vector is too small for vectorization benefit</li>
     * </ul>
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @param length Vector length
     * @return Cosine similarity in [-1.0, 1.0]
     */
    private float computeScalar(float[] v1, float[] v2, int length) {
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        // Use Kahan summation for improved numerical stability with large vectors
        double dotCompensation = 0.0;
        double norm1Compensation = 0.0;
        double norm2Compensation = 0.0;

        for (int i = 0; i < length; i++) {
            final float a = v1[i];
            final float b = v2[i];

            // Dot product with Kahan summation
            final double product = (double) a * (double) b;
            final double dotY = product - dotCompensation;
            final double dotT = dotProduct + dotY;
            dotCompensation = (dotT - dotProduct) - dotY;
            dotProduct = dotT;

            // Norm1 with Kahan summation
            final double sq1 = (double) a * (double) a;
            final double norm1Y = sq1 - norm1Compensation;
            final double norm1T = norm1 + norm1Y;
            norm1Compensation = (norm1T - norm1) - norm1Y;
            norm1 = norm1T;

            // Norm2 with Kahan summation
            final double sq2 = (double) b * (double) b;
            final double norm2Y = sq2 - norm2Compensation;
            final double norm2T = norm2 + norm2Y;
            norm2Compensation = (norm2T - norm2) - norm2Y;
            norm2 = norm2T;
        }

        // Handle zero vectors
        final double normProduct = norm1 * norm2;
        if (normProduct == 0.0) {
            return 0.0f;
        }

        // Check for NaN propagation
        if (Double.isNaN(dotProduct) || Double.isNaN(normProduct)) {
            return Float.NaN;
        }

        // Compute final similarity with clamping to handle floating-point errors
        final double similarity = dotProduct / Math.sqrt(normProduct);
        return clampToValidRange(similarity);
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
     * @return Cosine similarity in [-1.0, 1.0]
     */
    private float computeVectorized(float[] v1, float[] v2, int length) {
        final VectorApiSupport.VectorOperations ops = vectorSupport.getOperations();
        if (ops == null) {
            // Fallback to scalar if operations unavailable
            return computeScalar(v1, v2, length);
        }

        final int laneCount = ops.speciesLength();
        final int upperBound = vectorSupport.loopBound(length);

        // Accumulate using double for precision
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        try {
            // Process full vector chunks using Vector API
            dotProduct += ops.dotProduct(v1, v2, 0, upperBound);
            norm1 += ops.squaredNorm(v1, 0, upperBound);
            norm2 += ops.squaredNorm(v2, 0, upperBound);
        } catch (Exception e) {
            // If vectorized path fails, fall back to scalar for the whole computation
            LOGGER.debug("Vector API computation failed, falling back to scalar: {}", e.getMessage());
            return computeScalar(v1, v2, length);
        }

        // Process tail elements (scalar)
        for (int i = upperBound; i < length; i++) {
            final float a = v1[i];
            final float b = v2[i];
            dotProduct += (double) a * (double) b;
            norm1 += (double) a * (double) a;
            norm2 += (double) b * (double) b;
        }

        // Handle zero vectors
        final double normProduct = norm1 * norm2;
        if (normProduct == 0.0) {
            return 0.0f;
        }

        // Check for NaN propagation
        if (Double.isNaN(dotProduct) || Double.isNaN(normProduct)) {
            return Float.NaN;
        }

        // Compute final similarity with clamping
        final double similarity = dotProduct / Math.sqrt(normProduct);
        return clampToValidRange(similarity);
    }

    /**
     * Clamps a similarity value to the valid range [-1.0, 1.0].
     *
     * <p>This is necessary because floating-point arithmetic can produce
     * values slightly outside the theoretical range due to rounding errors.
     * For example, with normalized vectors, the result should be exactly 1.0,
     * but might be 1.0000001.</p>
     *
     * @param value The raw similarity value
     * @return The clamped value in [-1.0, 1.0]
     */
    private static float clampToValidRange(double value) {
        if (value > 1.0) {
            return 1.0f;
        }
        if (value < -1.0) {
            return -1.0f;
        }
        return (float) value;
    }

    /**
     * Computes cosine similarity with pre-computed norms.
     *
     * <p>This optimization is useful when computing multiple similarities
     * against the same query vector. The caller pre-computes the norms
     * and passes them to avoid redundant computation.</p>
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @param norm1Sq Pre-computed squared norm of v1
     * @param norm2Sq Pre-computed squared norm of v2
     * @return Cosine similarity in [-1.0, 1.0]
     * @throws IllegalArgumentException if norms are negative or vectors don't match
     */
    public float computeWithNorms(float[] v1, float[] v2, double norm1Sq, double norm2Sq) {
        Objects.requireNonNull(v1, "First vector must not be null");
        Objects.requireNonNull(v2, "Second vector must not be null");

        if (v1.length != v2.length) {
            throw new IllegalArgumentException(
                "Vector dimensions must match: " + v1.length + " vs " + v2.length);
        }

        if (norm1Sq < 0.0 || norm2Sq < 0.0) {
            throw new IllegalArgumentException(
                "Squared norms must be non-negative: norm1Sq=" + norm1Sq + ", norm2Sq=" + norm2Sq);
        }

        final int length = v1.length;

        if (length == 0 || norm1Sq == 0.0 || norm2Sq == 0.0) {
            return 0.0f;
        }

        // Compute dot product
        double dotProduct = computeDotProduct(v1, v2, length);

        if (Double.isNaN(dotProduct)) {
            return Float.NaN;
        }

        final double normProduct = norm1Sq * norm2Sq;
        final double similarity = dotProduct / Math.sqrt(normProduct);
        return clampToValidRange(similarity);
    }

    /**
     * Computes dot product with Vector API fallback.
     */
    private double computeDotProduct(float[] v1, float[] v2, int length) {
        if (vectorSupport.isAvailable() && length >= VECTORIZATION_THRESHOLD) {
            final VectorApiSupport.VectorOperations ops = vectorSupport.getOperations();
            if (ops != null) {
                try {
                    final int upperBound = vectorSupport.loopBound(length);
                    double dotProduct = ops.dotProduct(v1, v2, 0, upperBound);

                    // Tail
                    for (int i = upperBound; i < length; i++) {
                        dotProduct += (double) v1[i] * (double) v2[i];
                    }
                    return dotProduct;
                } catch (Exception e) {
                    // Fall through to scalar
                }
            }
        }

        // Scalar path
        double dotProduct = 0.0;
        for (int i = 0; i < length; i++) {
            dotProduct += (double) v1[i] * (double) v2[i];
        }
        return dotProduct;
    }

    /**
     * Computes the squared L2 norm of a vector.
     *
     * <p>Useful for pre-computing norms when doing multiple similarity
     * comparisons against the same vector.</p>
     *
     * @param v The vector
     * @return The squared norm (sum of squared elements)
     * @throws IllegalArgumentException if vector is null
     */
    public double computeSquaredNorm(float[] v) {
        Objects.requireNonNull(v, "Vector must not be null");

        final int length = v.length;
        if (length == 0) {
            return 0.0;
        }

        // Use Vector API if available
        if (vectorSupport.isAvailable() && length >= VECTORIZATION_THRESHOLD) {
            final VectorApiSupport.VectorOperations ops = vectorSupport.getOperations();
            if (ops != null) {
                try {
                    final int upperBound = vectorSupport.loopBound(length);
                    double normSq = ops.squaredNorm(v, 0, upperBound);

                    // Tail
                    for (int i = upperBound; i < length; i++) {
                        normSq += (double) v[i] * (double) v[i];
                    }
                    return normSq;
                } catch (Exception e) {
                    // Fall through to scalar
                }
            }
        }

        // Scalar path
        double normSq = 0.0;
        for (int i = 0; i < length; i++) {
            normSq += (double) v[i] * (double) v[i];
        }
        return normSq;
    }

    /**
     * Normalizes a vector to unit length in-place.
     *
     * <p>After normalization, the vector has L2 norm of 1.0.</p>
     *
     * @param v The vector to normalize (modified in place)
     * @throws IllegalArgumentException if vector is null or zero-length
     */
    public void normalizeInPlace(float[] v) {
        Objects.requireNonNull(v, "Vector must not be null");

        if (v.length == 0) {
            throw new IllegalArgumentException("Cannot normalize zero-length vector");
        }

        final double normSq = computeSquaredNorm(v);
        if (normSq == 0.0) {
            throw new IllegalArgumentException("Cannot normalize zero vector");
        }

        final float invNorm = (float) (1.0 / Math.sqrt(normSq));
        for (int i = 0; i < v.length; i++) {
            v[i] *= invNorm;
        }
    }

    /**
     * Creates a normalized copy of a vector.
     *
     * @param v The vector to normalize
     * @return A new normalized vector (does not modify input)
     * @throws IllegalArgumentException if vector is null or zero-length
     */
    public float[] normalize(float[] v) {
        Objects.requireNonNull(v, "Vector must not be null");

        if (v.length == 0) {
            throw new IllegalArgumentException("Cannot normalize zero-length vector");
        }

        final double normSq = computeSquaredNorm(v);
        if (normSq == 0.0) {
            throw new IllegalArgumentException("Cannot normalize zero vector");
        }

        final float[] result = new float[v.length];
        final float invNorm = (float) (1.0 / Math.sqrt(normSq));
        for (int i = 0; i < v.length; i++) {
            result[i] = v[i] * invNorm;
        }

        return result;
    }

    /**
     * Checks if a vector is normalized (has unit length).
     *
     * @param v The vector to check
     * @param tolerance The tolerance for floating-point comparison
     * @return true if the vector has unit length within tolerance
     */
    public boolean isNormalized(float[] v, float tolerance) {
        Objects.requireNonNull(v, "Vector must not be null");

        final double normSq = computeSquaredNorm(v);
        return Math.abs(normSq - 1.0) <= tolerance;
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
        return "CosineSimilarity" + (vectorSupport.isAvailable() ? "(vectorized)" : "(scalar)");
    }
}
