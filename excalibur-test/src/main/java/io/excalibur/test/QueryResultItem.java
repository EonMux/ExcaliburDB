/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: io.excalibur.test
 * 
 * Thread Safety: 
 *   - QueryResultItem: Thread-safe, immutable
 *   - QueryResultItemBuilder: Not thread-safe - use one instance per result
 */

package io.excalibur.test;

import java.util.*;
import java.util.function.Supplier;

/**
 * Immutable representation of a single query result item.
 * 
 * Each item contains a document identifier, relevance score, and optional
 * content metadata for verification purposes.
 */
public class QueryResultItem {
    
    private final String documentId;
    private final float score;
    private final Map<String, Object> metadata;
    private final String content;
    private final String source;
    private final int chunkIndex;
    
    /**
     * Create a new QueryResultItem.
     * 
     * @param documentId  Unique document identifier
     * @param score        Relevance score (0.0 to 1.0)
     * @param metadata     Optional document metadata
     * @param content      Optional content snippet
     * @param source       Optional source identifier
     * @param chunkIndex   Optional chunk index within document
     */
    public QueryResultItem(String documentId, float score, 
                           Map<String, Object> metadata, 
                           String content, String source, int chunkIndex) {
        this.documentId = documentId;
        this.score = score;
        this.metadata = (metadata == null) ? Collections.emptyMap() : new HashMap<>(metadata);
        this.content = (content == null) ? null : content;
        this.source = (source == null) ? null : source;
        this.chunkIndex = (chunkIndex == -1) ? -1 : chunkIndex;
    }
    
    /**
     * Create a QueryResultItem with default metadata.
     * 
     * @param documentId  Unique document identifier
     * @param score        Relevance score (0.0 to 1.0)
     */
    public QueryResultItem(String documentId, float score) {
        this(documentId, score, null, null, null, -1);
    }
    
    /**
     * Create a QueryResultItem with minimal metadata.
     * 
     * @param documentId  Unique document identifier
     * @param score        Relevance score (0.0 to 1.0)
     * @param metadata     Document metadata map
     */
    public QueryResultItem(String documentId, float score, Map<String, Object> metadata) {
        this(documentId, score, metadata, null, null, -1);
    }
    
    /**
     * Get the document identifier.
     * 
     * @return The document ID
     */
    public String getDocumentId() {
        return documentId;
    }
    
    /**
     * Get the relevance score.
     * 
     * @return The score (0.0 to 1.0)
     */
    public float getScore() {
        return score;
    }
    
    /**
     * Get document metadata.
     * 
     * @return The metadata map
     */
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
    
    /**
     * Get content snippet.
     * 
     * @return The content, or null if not available
     */
    public String getContent() {
        return content;
    }
    
    /**
     * Get source identifier.
     * 
     * @return The source, or null if not available
     */
    public String getSource() {
        return source;
    }
    
    /**
     * Get chunk index within the document.
     * 
     * @return The chunk index, or -1 if not applicable
     */
    public int getChunkIndex() {
        return chunkIndex;
    }
    
    /**
     * Check if this item matches another item.
     *
     * @param other  The other item to compare against (must not be null)
     * @param tolerance  Tolerance for score comparison
     * @return true if items match within tolerance
     */
    public boolean matches(QueryResultItem other, double tolerance) {
        if (other == null) {
            return false;
        }
        return Objects.equals(documentId, other.getDocumentId()) &&
               Math.abs(score - other.getScore()) <= tolerance &&
               Objects.equals(source, other.getSource()) &&
               chunkIndex == other.getChunkIndex() &&
               Objects.equals(content, other.getContent());
    }
    
    @Override
    public String toString() {
        return "QueryResultItem{documentId=" + documentId + 
               ", score=" + score + 
               ", metadata=" + metadata + 
               ", content=" + content + 
               ", source=" + source + 
               ", chunkIndex=" + chunkIndex + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QueryResultItem)) return false;
        QueryResultItem that = (QueryResultItem) o;
        return Math.abs(score - that.score) < 1e-6 &&
               Objects.equals(documentId, that.documentId) &&
               Objects.equals(metadata, that.metadata) &&
               Objects.equals(content, that.content) &&
               Objects.equals(source, that.source) &&
               chunkIndex == that.chunkIndex;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(documentId, score, metadata, content, source, chunkIndex);
    }
}
