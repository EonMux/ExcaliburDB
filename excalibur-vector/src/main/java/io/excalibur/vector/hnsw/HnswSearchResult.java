package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.DistanceValidator;

import java.util.Objects;

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
 * <h2>Domain Correctness</h2>
 * <p>Distance values are validated at construction time:</p>
 * <ul>
 *   <li>NaN values are preserved (indicate computation issues)</li>
 *   <li>Infinite values are preserved (indicate overflow)</li>
 *   <li>Finite values are assumed to be within valid range for their metric</li>
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
    private final String documentId;

    /**
     * Creates a new search result.
     *
     * @param nodeId The ID of the node found
     * @param distance The distance/similarity to the query vector
     */
    public HnswSearchResult(long nodeId, float distance) {
        this(nodeId, distance, null);
    }

    /**
     * Creates a new search result with document ID.
     *
     * @param nodeId The ID of the node found
     * @param distance The distance/similarity to the query vector
     * @param documentId The document ID this node belongs to (may be null)
     */
    public HnswSearchResult(long nodeId, float distance, String documentId) {
        this.nodeId = nodeId;
        // Store the distance as-is; validation/clamping is the responsibility
        // of the distance function implementations
        // NaN is valid (indicates invalid input), Infinity is valid (overflow)
        this.distance = distance;
        this.documentId = documentId;
    }

    /**
     * Creates a new search result with distance validation for a specific metric.
     *
     * <p>This factory method validates the distance is within the expected range
     * for the given metric type. If the distance is out of range, it is clamped.</p>
     *
     * @param nodeId The ID of the node found
     * @param distance The distance/similarity to the query vector
     * @param isCosine true if using cosine similarity (range [-1, 1])
     * @return A new HnswSearchResult with validated distance
     */
    public static HnswSearchResult createWithValidation(long nodeId, float distance, boolean isCosine) {
        return createWithValidation(nodeId, distance, isCosine, null);
    }

    /**
     * Creates a new search result with distance validation and document ID.
     *
     * <p>This factory method validates the distance is within the expected range
     * for the given metric type. If the distance is out of range, it is clamped.</p>
     *
     * @param nodeId The ID of the node found
     * @param distance The distance/similarity to the query vector
     * @param isCosine true if using cosine similarity (range [-1, 1])
     * @param documentId The document ID this node belongs to (may be null)
     * @return A new HnswSearchResult with validated distance
     */
    public static HnswSearchResult createWithValidation(long nodeId, float distance, boolean isCosine, String documentId) {
        float validatedDistance = distance;
        if (isCosine && Float.isFinite(distance)) {
            validatedDistance = DistanceValidator.clampCosineSimilarity(distance);
        } else if (!isCosine && Float.isFinite(distance)) {
            // For L2 and other distance metrics, ensure non-negative
            validatedDistance = DistanceValidator.clampL2Distance(distance);
        }
        return new HnswSearchResult(nodeId, validatedDistance, documentId);
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
     * Returns the document ID this node belongs to.
     *
     * <p>The document ID is the actual identifier from the source document,
     * such as "doc1" or "source1:file1". This is used by the
     * {@code DocumentAtomicityFilter} to correctly identify which document
     * each query result belongs to.</p>
     *
     * @return The document ID, or null if not available
     */
    public String documentId() {
        return documentId;
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
        return new HnswSearchResult(nodeId, newDistance, documentId);
    }

    /**
     * Returns a new result with a different document ID.
     *
     * @param newDocumentId The new document ID
     * @return A new HnswSearchResult with the updated document ID
     */
    public HnswSearchResult withDocumentId(String newDocumentId) {
        return new HnswSearchResult(nodeId, distance, newDocumentId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HnswSearchResult that = (HnswSearchResult) o;
        return nodeId == that.nodeId &&
            Float.compare(that.distance, distance) == 0 &&
            Objects.equals(documentId, that.documentId);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(nodeId);
        result = 31 * result + Float.hashCode(distance);
        result = 31 * result + (documentId != null ? documentId.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return String.format("HnswSearchResult{nodeId=%d, distance=%.6f, documentId=%s}",
            nodeId, distance, documentId != null ? "\"" + documentId + "\"" : "null");
    }
}
