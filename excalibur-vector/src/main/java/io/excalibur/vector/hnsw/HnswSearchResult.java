package io.excalibur.vector.hnsw;

/**
 * Result of an HNSW nearest neighbor search.
 *
 * <p>Contains the node ID and distance to the query vector for a single
 * search result. Results are typically returned sorted by distance
 * (closest first).</p>
 *
 * <h2>Distance Interpretation</h2>
 * <p>The distance value depends on the distance function used:</p>
 * <ul>
 *   <li><strong>Cosine similarity:</strong> Range [-1, 1], where 1 is most similar</li>
 *   <li><strong>L2 distance:</strong> Range [0, ∞), where 0 is most similar</li>
 *   <li><strong>Inner product:</strong> Range (-∞, ∞), higher is more similar</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class HnswSearchResult {

    private final long nodeId;
    private final float distance;

    /**
     * Creates a new search result.
     *
     * @param nodeId The ID of the node found
     * @param distance The distance/similarity to the query vector
     */
    public HnswSearchResult(long nodeId, float distance) {
        this.nodeId = nodeId;
        this.distance = distance;
    }

    /**
     * Returns the node ID of this result.
     *
     * @return The node ID
     */
    public long nodeId() {
        return nodeId;
    }

    /**
     * Returns the distance to the query vector.
     *
     * <p>The interpretation of this value depends on the distance function:</p>
     * <ul>
     *   <li>For L2 distance: smaller values are more similar</li>
     *   <li>For cosine similarity: larger values (closer to 1) are more similar</li>
     *   <li>For inner product: larger values are more similar</li>
     * </ul>
     *
     * @return The distance/similarity value
     */
    public float distance() {
        return distance;
    }

    /**
     * Returns a new result with a different distance.
     *
     * <p>This is useful for re-ranking or adjusting distances.</p>
     *
     * @param newDistance The new distance value
     * @return A new HnswSearchResult with the updated distance
     */
    public HnswSearchResult withDistance(float newDistance) {
        return new HnswSearchResult(nodeId, newDistance);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HnswSearchResult that = (HnswSearchResult) o;
        return nodeId == that.nodeId &&
            Float.compare(that.distance, distance) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(nodeId) + Float.hashCode(distance);
    }

    @Override
    public String toString() {
        return String.format("HnswSearchResult{nodeId=%d, distance=%.6f}", nodeId, distance);
    }
}
