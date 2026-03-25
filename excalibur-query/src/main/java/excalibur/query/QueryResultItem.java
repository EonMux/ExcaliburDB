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
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Score must be finite (not NaN or Infinity)</li>
 *   <li>Segment ID and Node ID should be non-negative or -1 for unknown</li>
 *   <li>Payload is defensively copied on input and output</li>
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
    private final String documentIdStr;
    private final float score;
    private final byte[] payload;
    private final long segmentId;
    private final long nodeId;

    /**
     * Creates a new QueryResultItem.
     *
     * @param documentId The document identifier (numeric, for backward compatibility)
     * @param score The similarity score or distance (must be finite)
     * @param payload Optional payload data (may be null)
     * @param segmentId The segment that produced this result
     * @param nodeId The node ID within the segment's HNSW graph
     * @throws IllegalArgumentException if score is NaN or Infinity
     */
    public QueryResultItem(long documentId, float score, byte[] payload, long segmentId, long nodeId) {
        this.documentId = documentId;
        this.documentIdStr = null;
        this.score = validateScore(score);
        this.payload = payload != null ? payload.clone() : null;
        this.segmentId = segmentId;
        this.nodeId = nodeId;
    }

    /**
     * Creates a new QueryResultItem with string document ID.
     *
     * <p>This constructor is used when the actual document ID is a string
     * (e.g., "doc1", "source1:file1") from the ingestion pipeline.</p>
     *
     * @param documentIdStr The string document identifier (may be null)
     * @param score The similarity score or distance (must be finite)
     * @param payload Optional payload data (may be null)
     * @param segmentId The segment that produced this result
     * @param nodeId The node ID within the segment's HNSW graph
     * @throws IllegalArgumentException if score is NaN or Infinity
     */
    public QueryResultItem(String documentIdStr, float score, byte[] payload, long segmentId, long nodeId) {
        this.documentId = nodeId; // Use nodeId as fallback for backward compatibility
        this.documentIdStr = documentIdStr;
        this.score = validateScore(score);
        this.payload = payload != null ? payload.clone() : null;
        this.segmentId = segmentId;
        this.nodeId = nodeId;
    }

    /**
     * Creates a new QueryResultItem with both numeric and string document IDs.
     *
     * @param documentId The numeric document identifier (for backward compatibility)
     * @param documentIdStr The string document identifier (actual document ID)
     * @param score The similarity score or distance (must be finite)
     * @param payload Optional payload data (may be null)
     * @param segmentId The segment that produced this result
     * @param nodeId The node ID within the segment's HNSW graph
     * @throws IllegalArgumentException if score is NaN or Infinity
     */
    public QueryResultItem(long documentId, String documentIdStr, float score, byte[] payload, long segmentId, long nodeId) {
        this.documentId = documentId;
        this.documentIdStr = documentIdStr;
        this.score = validateScore(score);
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
     * Returns the string document identifier.
     *
     * <p>This is the actual document ID from the source (e.g., "doc1", "source1:file1"),
     * used by the {@code DocumentAtomicityFilter} to correctly identify which document
     * each query result belongs to.</p>
     *
     * @return The string document ID, or null if not available
     */
    public String documentIdStr() {
        return documentIdStr;
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
     * @param newScore The new score value (must be finite)
     * @return A new QueryResultItem with the updated score
     * @throws IllegalArgumentException if newScore is NaN or Infinity
     */
    public QueryResultItem withScore(float newScore) {
        return new QueryResultItem(documentId, documentIdStr, validateScore(newScore), payload, segmentId, nodeId);
    }

    /**
     * Validates that a score value is finite (not NaN or Infinity).
     *
     * <p><strong>Domain Correctness:</strong> Scores must be finite for meaningful
     * comparison and ranking. NaN and Infinity values indicate computation errors
     * or invalid input vectors.</p>
     *
     * @param score The score to validate
     * @return The validated score
     * @throws IllegalArgumentException if score is not finite
     */
    private static float validateScore(float score) {
        if (!Float.isFinite(score)) {
            throw new IllegalArgumentException(
                "Score must be finite (not NaN or Infinity): " + score);
        }
        return score;
    }

    /**
     * Creates a new QueryResultItem with a payload.
     *
     * @param newPayload The new payload data
     * @return A new QueryResultItem with the payload
     */
    public QueryResultItem withPayload(byte[] newPayload) {
        return new QueryResultItem(documentId, documentIdStr, score, newPayload, segmentId, nodeId);
    }

    /**
     * Creates a new QueryResultItem with a string document ID.
     *
     * @param newDocumentIdStr The new string document ID
     * @return A new QueryResultItem with the string document ID
     */
    public QueryResultItem withDocumentIdStr(String newDocumentIdStr) {
        return new QueryResultItem(documentId, newDocumentIdStr, score, payload, segmentId, nodeId);
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
            Objects.equals(documentIdStr, that.documentIdStr) &&
            Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(documentId);
        result = 31 * result + (documentIdStr != null ? documentIdStr.hashCode() : 0);
        result = 31 * result + Float.hashCode(score);
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + Long.hashCode(segmentId);
        result = 31 * result + Long.hashCode(nodeId);
        return result;
    }

    @Override
    public String toString() {
        return String.format(
            "QueryResultItem{docId=%d, docIdStr=%s, score=%.6f, segmentId=%d, nodeId=%d, hasPayload=%s}",
            documentId, documentIdStr != null ? "\"" + documentIdStr + "\"" : "null",
            score, segmentId, nodeId, hasPayload());
    }
}
