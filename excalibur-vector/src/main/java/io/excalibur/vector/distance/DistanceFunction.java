package io.excalibur.vector.distance;

/**
 * Interface for vector distance/similarity functions.
 *
 * <p>Distance functions compute a scalar value representing the similarity
 * or distance between two vectors. Implementations must be thread-safe
 * and support vectors of arbitrary dimension.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Both vectors must have the same dimension</li>
 *   <li>Implementations must handle edge cases (zero vectors, NaN, etc.)</li>
 *   <li>Results must be within the documented range for each function type</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All implementations must be thread-safe and support concurrent invocation.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public interface DistanceFunction {

    /**
     * Computes the distance or similarity between two vectors.
     *
     * @param v1 The first vector (must not be null)
     * @param v2 The second vector (must not be null)
     * @return The computed distance/similarity value
     * @throws IllegalArgumentException if vectors have different dimensions
     *                                  or are null
     */
    float compute(float[] v1, float[] v2);

    /**
     * Returns the name of this distance function.
     *
     * @return The function name (e.g., "cosine", "l2", "inner_product")
     */
    String name();
}
