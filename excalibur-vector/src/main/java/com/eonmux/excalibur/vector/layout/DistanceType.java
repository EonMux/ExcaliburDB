package com.eonmux.excalibur.vector.layout;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.InnerProduct;
import com.eonmux.excalibur.vector.distance.L2Distance;

/**
 * Enumeration of supported distance/similarity metrics for batch vector operations.
 *
 * <p>Each distance type defines how similarity between vectors is computed:</p>
 *
 * <table border="1">
 *   <tr><th>Type</th><th>Range</th><th>Interpretation</th></tr>
 *   <tr><td>COSINE</td><td>[-1.0, 1.0]</td><td>Larger = more similar</td></tr>
 *   <tr><td>L2</td><td>[0.0, +inf)</td><td>Smaller = more similar</td></tr>
 *   <tr><td>INNER_PRODUCT</td><td>(-inf, +inf)</td><td>Larger = more similar</td></tr>
 * </table>
 *
 * <h2>Thread Safety</h2>
 * <p>As an enum, this class is inherently thread-safe. Enum instances are
 * immutable and may be safely accessed from multiple threads concurrently.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public enum DistanceType {

    /**
     * Cosine similarity measures the cosine of the angle between two vectors.
     *
     * <p>Formula: cos(θ) = (v1 · v2) / (||v1|| * ||v2||)</p>
     *
     * <h3>Properties</h3>
     * <ul>
     *   <li>Range: [-1.0, 1.0]</li>
     *   <li>1.0 = identical direction</li>
     *   <li>0.0 = orthogonal</li>
     *   <li>-1.0 = opposite direction</li>
     *   <li>Scale-invariant (normalized vectors produce same result)</li>
     * </ul>
     *
     * <h3>When to Use</h3>
     * <p>Ideal for text embeddings, semantic search, and any case where
     * vector magnitude doesn't matter, only direction.</p>
     */
    COSINE,

    /**
     * L2 (Euclidean) distance measures the straight-line distance between vectors.
     *
     * <p>Formula: L2(v1, v2) = sqrt(sum((v1[i] - v2[i])^2))</p>
     *
     * <h3>Properties</h3>
     * <ul>
     *   <li>Range: [0.0, +infinity)</li>
     *   <li>0.0 = identical vectors</li>
     *   <li>Sensitive to magnitude differences</li>
     *   <li>Triangle inequality holds</li>
     * </ul>
     *
     * <h3>When to Use</h3>
     * <p>Ideal for image features, physical measurements, and any case where
     * absolute position matters.</p>
     */
    L2,

    /**
     * Inner product (dot product) measures the sum of element-wise products.
     *
     * <p>Formula: IP(v1, v2) = sum(v1[i] * v2[i])</p>
     *
     * <h3>Properties</h3>
     * <ul>
     *   <li>Range: (-infinity, +infinity)</li>
     *   <li>Positive = vectors point in similar directions</li>
     *   <li>Negative = vectors point in opposite directions</li>
     *   <li>Zero = orthogonal vectors</li>
     * </ul>
     *
     * <h3>When to Use</h3>
     * <p>Ideal for normalized vectors (where it equals cosine similarity),
     * or when you want magnitude to influence the score.</p>
     *
     * <h3>Note</h3>
     * <p>For similarity search where smaller = more similar, use the negated
     * value: distance = -inner_product</p>
     */
    INNER_PRODUCT;

    /**
     * Returns the {@link DistanceFunction} that computes this metric.
     *
     * <p>The batch operations in this package name their metric with this enum while
     * the index and the query path name it with a {@code DistanceFunction}. Both
     * describe the same three metrics, so this method is the single place the two
     * vocabularies are tied together: a metric added to one has to be given a mapping
     * here before it can be used from the other.</p>
     *
     * @return The shared singleton implementing this metric, never null
     */
    public DistanceFunction toDistanceFunction() {
        return switch (this) {
            case COSINE -> CosineSimilarity.getInstance();
            case L2 -> L2Distance.getInstance();
            case INNER_PRODUCT -> InnerProduct.getInstance();
        };
    }
}
