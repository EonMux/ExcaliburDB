package com.eonmux.excalibur.query;

import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;

import java.util.Arrays;
import java.util.Objects;

/**
 * A single result item from an ANN query.
 *
 * <p>QueryResultItem represents one matching result from a top-k ANN search: one chunk of a
 * document, named by its chunk id ({@link #documentIdStr()}), with its similarity
 * score/distance and optionally the chunk's stored text. Several chunks of one document can
 * each be a result.</p>
 *
 * <h2>Score vs Distance</h2>
 * <p>The interpretation of the score depends on the distance function:</p>
 * <ul>
 *   <li><strong>L2 distance:</strong> Lower scores are better (closer to 0)</li>
 *   <li><strong>Cosine similarity:</strong> Higher scores are better (closer to 1)</li>
 *   <li><strong>Inner product:</strong> Higher scores are better</li>
 * </ul>
 *
 * <h2>Graph Generation</h2>
 * <p>{@link #nodeId()} is only meaningful within the HNSW graph generation that
 * produced it, because compaction renumbers nodes. {@link #generation()} records
 * that generation so a later re-read can pass it to
 * {@link com.eonmux.excalibur.vector.hnsw.HnswGraph#readNode(long, long)} and have a stale
 * ID rejected rather than silently resolving to a different vector. Items that did
 * not come from a graph - write-log tail-scan hits in particular - carry
 * {@link HnswSearchResult#GENERATION_UNKNOWN}.</p>
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
    private final long generation;

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
        this(documentId, null, score, payload, segmentId, nodeId, HnswSearchResult.GENERATION_UNKNOWN);
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
        // nodeId doubles as the numeric ID for backward compatibility
        this(nodeId, documentIdStr, score, payload, segmentId, nodeId, HnswSearchResult.GENERATION_UNKNOWN);
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
        this(documentId, documentIdStr, score, payload, segmentId, nodeId, HnswSearchResult.GENERATION_UNKNOWN);
    }

    /**
     * Creates a new QueryResultItem bound to the graph generation its node ID belongs to.
     *
     * @param documentId The numeric document identifier (for backward compatibility)
     * @param documentIdStr The string document identifier (actual document ID)
     * @param score The similarity score or distance (must be finite)
     * @param payload Optional payload data (may be null)
     * @param segmentId The segment that produced this result
     * @param nodeId The node ID within the segment's HNSW graph
     * @param generation The graph generation {@code nodeId} belongs to, or
     *                   {@link HnswSearchResult#GENERATION_UNKNOWN} when the item did
     *                   not come from a graph
     * @throws IllegalArgumentException if score is NaN or Infinity
     */
    public QueryResultItem(long documentId, String documentIdStr, float score, byte[] payload,
                           long segmentId, long nodeId, long generation) {
        this.documentId = documentId;
        this.documentIdStr = documentIdStr;
        this.score = validateScore(score);
        this.payload = payload != null ? payload.clone() : null;
        this.segmentId = segmentId;
        this.nodeId = nodeId;
        this.generation = generation;
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
     * Returns the name the ingestion pipeline gave what this result found.
     *
     * <p>For a vector result that is the chunk the vector embeds,
     * {@code sourceId:documentId:chunk:N} - the storage key its text is read back by, and the
     * name the lexical index gives the same passage, which is what lets hybrid fusion recognise
     * one passage found by both. The owning document is derived from it with
     * {@code ChunkIdentifiers.documentKeyOfChunk}, as the {@code DocumentAtomicityFilter} does
     * to decide the result's visibility. A graph an older build attributed by document names
     * {@code sourceId:documentId} instead, until the node re-keys it on its first integration
     * pass.</p>
     *
     * @return The chunk id, or null if not available
     */
    public String documentIdStr() {
        return documentIdStr;
    }

    /**
     * Returns how this result is named back to a caller outside this process.
     *
     * <p>The string identifier the ingestion pipeline assigned, when there is one. That is the
     * identifier a caller can actually look something up by, and the one both retrieval paths
     * report, which is what lets a result found by each of them be recognised as one result.</p>
     *
     * <p><strong>Not a unique key.</strong> A result the pipeline could not name - a graph node
     * its node-to-chunk mapping has no entry for, a write-log entry with an empty key - falls
     * back to the numeric identifier, and for those results the numeric identifier is the node
     * ID. Node IDs are unique only within their segment and are renumbered by compaction, so two
     * such results can share this string and be different documents, and none of them names
     * anything outside this process. Anything keying on identity rather than display must pair
     * it with {@link #segmentId()}, as {@code ResultAggregator} and {@code HybridFusion} both
     * do.</p>
     *
     * <p>The fallback is for embedded callers, which can use the node id together with
     * {@link #segmentId()} and {@link #generation()}. A caller that hands identifiers to someone
     * outside the process must never see it, and asks the pipeline to drop unnamed results
     * instead - see {@link QueryRequest#requireNamedResults()}. The HTTP query route does, so
     * a node id never reaches a client as a {@code document_id}.</p>
     *
     * @return The display identifier, never null
     */
    public String externalId() {
        return documentIdStr != null && !documentIdStr.isEmpty()
            ? documentIdStr
            : Long.toString(documentId);
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
     * Returns the HNSW graph generation this item's {@link #nodeId()} belongs to.
     *
     * <p>Pass this to {@link com.eonmux.excalibur.vector.hnsw.HnswGraph#readNode(long, long)}
     * when re-reading the node so a node ID invalidated by a concurrent compaction is
     * rejected instead of resolving to a different vector.</p>
     *
     * @return The graph generation, or {@link HnswSearchResult#GENERATION_UNKNOWN}
     *         if this item did not come from a graph
     */
    public long generation() {
        return generation;
    }

    /**
     * Creates a new QueryResultItem with a different score.
     *
     * @param newScore The new score value (must be finite)
     * @return A new QueryResultItem with the updated score
     * @throws IllegalArgumentException if newScore is NaN or Infinity
     */
    public QueryResultItem withScore(float newScore) {
        return new QueryResultItem(documentId, documentIdStr, validateScore(newScore), payload,
            segmentId, nodeId, generation);
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
        return new QueryResultItem(documentId, documentIdStr, score, newPayload, segmentId, nodeId, generation);
    }

    /**
     * Creates a new QueryResultItem with a string document ID.
     *
     * @param newDocumentIdStr The new string document ID
     * @return A new QueryResultItem with the string document ID
     */
    public QueryResultItem withDocumentIdStr(String newDocumentIdStr) {
        return new QueryResultItem(documentId, newDocumentIdStr, score, payload, segmentId, nodeId, generation);
    }

    /**
     * Creates a new QueryResultItem bound to the given graph generation.
     *
     * @param newGeneration The graph generation this item's node ID belongs to
     * @return A new QueryResultItem with the updated generation
     */
    public QueryResultItem withGeneration(long newGeneration) {
        return new QueryResultItem(documentId, documentIdStr, score, payload, segmentId, nodeId, newGeneration);
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
            generation == that.generation &&
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
        result = 31 * result + Long.hashCode(generation);
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
