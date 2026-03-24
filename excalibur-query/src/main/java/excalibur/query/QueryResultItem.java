package excalibur.query;

import java.util.Arrays;
import java.util.Objects;

/**
 * A single result item from an ANN query.
 *
 * <p>QueryResultItem represents one matching result from a top-k ANN search.
 * It includes the document ID, similarity score/distance, and optionally
 * the stored payload data.</p>
 *
 * <h2>Score vs Distance</h2>
 * <p>The interpretation of the score depends on the distance function:</p>
 * <ul>
 *   <li><strong>L2 distance:</strong> Lower scores are better (closer to 0)</li>
 *   <li><strong>Cosine similarity:</strong> Higher scores are better (closer to 1)</li>
 *   <li><strong>Inner product:</strong> Higher scores are better</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class QueryResultItem {

    private final long documentId;
    private final float score;
    private final byte[] payload;
    private final long segmentId;
    private final long nodeId;

    /**
     * Creates a new QueryResultItem.
     *
     * @param documentId The document identifier
     * @param score The similarity score or distance
     * @param payload Optional payload data (may be null)
     * @param segmentId The segment that produced this result
     * @param nodeId The node ID within the segment's HNSW graph
     */
    public QueryResultItem(long documentId, float score, byte[] payload, long segmentId, long nodeId) {
        this.documentId = documentId;
        this.score = score;
        this.payload = payload != null ? payload.clone() : null;
        this.segmentId = segmentId;
        this.nodeId = nodeId;
    }

    /**
     * Creates a QueryResultItem with minimal information.
     *
     * @param documentId The document identifier
     * @param score The similarity score or distance
     */
    public QueryResultItem(long documentId, float score) {
        this(documentId, score, null, -1, -1);
    }

    /**
     * Returns the document identifier.
     *
     * <p>The document ID is a unique identifier for the source document
     * that was indexed into the vector database.</p>
     *
     * @return The document ID
     */
    public long documentId() {
        return documentId;
    }

    /**
     * Returns the similarity score or distance.
     *
     * <p>The interpretation depends on the distance function used:</p>
     * <ul>
     *   <li>L2 distance: Lower is better</li>
     *   <li>Cosine similarity: Higher is better</li>
     *   <li>Inner product: Higher is better</li>
     * </ul>
     *
     * @return The score value
     */
    public float score() {
        return score;
    }

    /**
     * Returns the payload data, if available.
     *
     * <p>The payload contains the original stored data associated with
     * this document. Returns a defensive copy.</p>
     *
     * @return The payload bytes, or null if not available
     */
    public byte[] payload() {
        return payload != null ? payload.clone() : null;
    }

    /**
     * Returns whether this result has payload data.
     *
     * @return true if payload is available
     */
    public boolean hasPayload() {
        return payload != null && payload.length > 0;
    }

    /**
     * Returns the segment ID that produced this result.
     *
     * @return The segment ID, or -1 if unknown
     */
    public long segmentId() {
        return segmentId;
    }

    /**
     * Returns the node ID within the HNSW graph.
     *
     * @return The node ID, or -1 if unknown
     */
    public long nodeId() {
        return nodeId;
    }

    /**
     * Creates a new QueryResultItem with a different score.
     *
     * @param newScore The new score value
     * @return A new QueryResultItem with the updated score
     */
    public QueryResultItem withScore(float newScore) {
        return new QueryResultItem(documentId, newScore, payload, segmentId, nodeId);
    }

    /**
     * Creates a new QueryResultItem with a payload.
     *
     * @param newPayload The new payload data
     * @return A new QueryResultItem with the payload
     */
    public QueryResultItem withPayload(byte[] newPayload) {
        return new QueryResultItem(documentId, score, newPayload, segmentId, nodeId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryResultItem that = (QueryResultItem) o;
        return documentId == that.documentId &&
            Float.compare(that.score, score) == 0 &&
            segmentId == that.segmentId &&
            nodeId == that.nodeId &&
            Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(documentId);
        result = 31 * result + Float.hashCode(score);
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + Long.hashCode(segmentId);
        result = 31 * result + Long.hashCode(nodeId);
        return result;
    }

    @Override
    public String toString() {
        return String.format(
            "QueryResultItem{docId=%d, score=%.6f, segmentId=%d, nodeId=%d, hasPayload=%s}",
            documentId, score, segmentId, nodeId, hasPayload());
    }
}
