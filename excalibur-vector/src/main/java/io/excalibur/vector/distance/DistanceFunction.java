package io.excalibur.vector.distance;

import java.util.Comparator;

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
 *   <li>Implementations must declare how their {@link #compute} output is ordered
 *       via {@link #ordering()}</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 * <p>{@link #compute} returns the metric's <em>natural</em> value, which is a
 * similarity for some metrics (cosine, inner product) and a distance for others
 * (L2). Because those two families are ordered in opposite directions, ranking
 * code must never compare {@code compute()} results directly. Use
 * {@link #distance(float[], float[])} instead: it always returns a monotone
 * dissimilarity where <strong>smaller means closer</strong>, regardless of the
 * underlying metric.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>All implementations must be thread-safe and support concurrent invocation.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public interface DistanceFunction {

    /**
     * Direction in which the values returned by {@link #compute} are ordered.
     *
     * <p>This is the contract that lets generic ranking code (HNSW traversal,
     * neighbour pruning, result sorting) work with any metric without hard-coding
     * whether the metric is a similarity or a distance.</p>
     *
     * @since 1.3
     */
    enum Ordering {

        /**
         * {@code compute()} returns a distance: smaller values are more similar
         * (for example L2).
         */
        SMALLER_IS_CLOSER,

        /**
         * {@code compute()} returns a similarity: larger values are more similar
         * (for example cosine similarity or inner product).
         */
        LARGER_IS_CLOSER;

        /**
         * Converts a raw {@link DistanceFunction#compute} value into a monotone
         * dissimilarity where smaller means closer.
         *
         * <p>Negation is used for {@link #LARGER_IS_CLOSER} metrics so the value
         * stays inside the metric's documented range (cosine stays in [-1, 1]) and
         * so the transform is its own inverse.</p>
         *
         * @param computeValue The raw value returned by {@code compute()}
         * @return The equivalent dissimilarity (smaller is closer)
         */
        public float toDistance(float computeValue) {
            return this == SMALLER_IS_CLOSER ? computeValue : -computeValue;
        }

        /**
         * Inverse of {@link #toDistance(float)}: converts a monotone dissimilarity
         * back into the metric's natural {@code compute()} value.
         *
         * @param distance The dissimilarity value (smaller is closer)
         * @return The equivalent raw {@code compute()} value
         */
        public float fromDistance(float distance) {
            return this == SMALLER_IS_CLOSER ? distance : -distance;
        }
    }

    /**
     * Computes the distance or similarity between two vectors.
     *
     * <p>The direction of the returned value is metric-specific; see
     * {@link #ordering()}. Callers that need to rank results must use
     * {@link #distance(float[], float[])} rather than comparing these values
     * directly.</p>
     *
     * @param v1 The first vector (must not be null)
     * @param v2 The second vector (must not be null)
     * @return The computed distance/similarity value
     * @throws IllegalArgumentException if vectors have different dimensions
     *                                  or are null
     */
    float compute(float[] v1, float[] v2);

    /**
     * Declares how the values returned by {@link #compute} are ordered.
     *
     * <p>This method is intentionally abstract: every implementation must make an
     * explicit statement about its direction so that a new metric cannot silently
     * be ranked backwards.</p>
     *
     * @return The ordering of this function's {@code compute()} output
     * @since 1.3
     */
    Ordering ordering();

    /**
     * Computes a monotone dissimilarity between two vectors where
     * <strong>smaller is closer</strong>, whatever the underlying metric.
     *
     * <p>This is the value all ranking code (graph traversal, neighbour pruning,
     * result ordering) must use.</p>
     *
     * @param v1 The first vector (must not be null)
     * @param v2 The second vector (must not be null)
     * @return The dissimilarity between the vectors (smaller is closer)
     * @throws IllegalArgumentException if vectors have different dimensions
     *                                  or are null
     * @since 1.3
     */
    default float distance(float[] v1, float[] v2) {
        return ordering().toDistance(compute(v1, v2));
    }

    /**
     * Returns a comparator that orders raw {@link #compute} values best-first
     * (most similar first) according to this function's {@link #ordering()}.
     *
     * @return Comparator ordering raw compute values from most to least similar
     * @since 1.3
     */
    default Comparator<Float> bestFirstComparator() {
        final Ordering ordering = ordering();
        return Comparator.comparingDouble(value -> ordering.toDistance(value));
    }

    /**
     * Returns the name of this distance function.
     *
     * @return The function name (e.g., "cosine", "l2", "inner_product")
     */
    String name();
}
