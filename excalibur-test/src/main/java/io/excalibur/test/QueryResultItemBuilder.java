/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: io.excalibur.test
 * 
 * Thread Safety: 
 *   - QueryResultItemBuilder: Not thread-safe - use one instance per result
 */

package io.excalibur.test;

import java.util.*;
import java.util.function.Supplier;

/**
 * Fluent builder for creating QueryResultItem instances.
 * 
 * Use this builder to construct expected result items with minimal boilerplate:
 * <pre>
 *   QueryResultItem item = QueryResultItem.builder()
 *       .documentId("doc-1")
 *       .score(0.95f)
 *       .metadata(Map.of("title", "Result 1", "author", "Author A"))
 *       .content("This is the content snippet...")
 *       .build();
 * </pre>
 */
public class QueryResultItemBuilder {
    
    private String documentId;
    private float score;
    private Map<String, Object> metadata;
    private String content;
    private String source;
    private int chunkIndex;
    
    private QueryResultItemBuilder() {
        this.documentId = null;
        this.score = 0f;
        this.metadata = Collections.emptyMap();
        this.content = null;
        this.source = null;
        this.chunkIndex = -1;
    }
    
    /**
     * Create a new builder.
     * 
     * @return A new builder instance
     */
    public static QueryResultItemBuilder builder() {
        return new QueryResultItemBuilder();
    }
    
    /**
     * Create a builder with a default document ID.
     * 
     * @param documentId  The default document ID
     * @return A new builder instance
     */
    public static QueryResultItemBuilder builder(String documentId) {
        QueryResultItemBuilder builder = new QueryResultItemBuilder();
        builder.documentId = documentId;
        return builder;
    }
    
    /**
     * Set the document identifier.
     * 
     * @param documentId The document ID
     * @return This builder
     */
    public QueryResultItemBuilder documentId(String documentId) {
        this.documentId = documentId;
        return this;
    }
    
    /**
     * Set the relevance score.
     * 
     * @param score The score (0.0 to 1.0)
     * @return This builder
     */
    public QueryResultItemBuilder score(float score) {
        this.score = score;
        return this;
    }
    
    /**
     * Set document metadata.
     * 
     * @param metadata The metadata map
     * @return This builder
     */
    public QueryResultItemBuilder metadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }
    
    /**
     * Add a metadata entry.
     * 
     * @param key   The metadata key
     * @param value The metadata value
     * @return This builder
     */
    public QueryResultItemBuilder metadata(String key, Object value) {
        Map<String, Object> newMetadata = new HashMap<>(metadata);
        newMetadata.put(key, value);
        this.metadata = newMetadata;
        return this;
    }
    
    /**
     * Set the content snippet.
     * 
     * @param content The content
     * @return This builder
     */
    public QueryResultItemBuilder content(String content) {
        this.content = content;
        return this;
    }
    
    /**
     * Set the source identifier.
     * 
     * @param source The source
     * @return This builder
     */
    public QueryResultItemBuilder source(String source) {
        this.source = source;
        return this;
    }
    
    /**
     * Set the chunk index.
     * 
     * @param chunkIndex The chunk index
     * @return This builder
     */
    public QueryResultItemBuilder chunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
        return this;
    }
    
    /**
     * Build the QueryResultItem.
     * 
     * @return The built QueryResultItem
     */
    public QueryResultItem build() {
        return new QueryResultItem(documentId, score, metadata, content, source, chunkIndex);
    }
}
