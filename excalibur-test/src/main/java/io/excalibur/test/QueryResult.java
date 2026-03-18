/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: io.excalibur.test
 * 
 * Thread Safety: 
 *   - QueryResult: Thread-safe, immutable
 */

package io.excalibur.test;

import java.util.*;

/**
 * Immutable representation of actual query results.
 * 
 * Each result contains a query ID, top K results, partial results, metadata,
 * and index coverage information for verification purposes.
 */
public class QueryResult {
    
    private final String queryId;
    private final List<QueryResultItem> topK;
    private final PartialResult partial;
    private final Map<String, Object> metadata;
    private final IndexCoverage indexCoverage;
    
    /**
     * Create a new QueryResult.
     * 
     * @param queryId  Unique query identifier
     * @param topK     Top K result items
     * @param partial  Partial results (if any)
     * @param metadata Additional metadata
     * @param indexCoverage Index coverage information
     */
    public QueryResult(String queryId, List<QueryResultItem> topK, PartialResult partial,
                       Map<String, Object> metadata, IndexCoverage indexCoverage) {
        this.queryId = queryId;
        this.topK = (topK == null) ? Collections.emptyList() : new ArrayList<>(topK);
        this.partial = (partial == null) ? PartialResult.empty() : partial;
        this.metadata = (metadata == null) ? Collections.emptyMap() : new HashMap<>(metadata);
        this.indexCoverage = (indexCoverage == null) ? IndexCoverage.empty() : indexCoverage;
    }
    
    /**
     * Get the query identifier.
     * 
     * @return The query ID
     */
    public String getQueryId() {
        return queryId;
    }
    
    /**
     * Get the top K results.
     * 
     * @return The top K results
     */
    public List<QueryResultItem> getTopK() {
        return Collections.unmodifiableList(topK);
    }
    
    /**
     * Get partial results.
     * 
     * @return The partial results, or empty if none
     */
    public PartialResult getPartial() {
        return partial;
    }
    
    /**
     * Get additional metadata.
     * 
     * @return The metadata map
     */
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
    
    /**
     * Get index coverage information.
     * 
     * @return The index coverage
     */
    public IndexCoverage getIndexCoverage() {
        return indexCoverage;
    }
    
    @Override
    public String toString() {
        return "QueryResult{queryId=\"" + queryId + "\", topK=" + topK.size() +
               ", partial=" + partial + ", coverage=" + indexCoverage + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QueryResult)) return false;
        QueryResult that = (QueryResult) o;
        return Objects.equals(queryId, that.queryId) &&
               Objects.equals(topK, that.topK) &&
               Objects.equals(partial, that.partial) &&
               Objects.equals(metadata, that.metadata) &&
               Objects.equals(indexCoverage, that.indexCoverage);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(queryId, topK, partial, metadata, indexCoverage);
    }
}
