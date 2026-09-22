/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: com.eonmux.excalibur.test
 * 
 * Thread Safety:
 *   - ExpectedResult: Thread-safe, immutable
 */

package com.eonmux.excalibur.test;

import java.util.*;

/**
 * Immutable builder for expected query results in golden tests.
 * 
 * This builder constructs the expected result structure that will be
 * compared against actual query results. Each builder creates a new
 * immutable ExpectedResult instance.
 */
public class ExpectedResult {
    
    private final String queryId;
    private final List<QueryResultItem> topK;
    private final PartialResult partial;
    private final Map<String, Object> metadata;
    private final IndexCoverage indexCoverage;
    private final double epsilon;
    
    /**
     * Create a new ExpectedResult.
     * 
     * @param queryId  Unique query identifier
     * @param topK     Top K result items
     * @param partial  Partial results (if any)
     * @param metadata Additional metadata
     * @param indexCoverage Index coverage information
     */
    public ExpectedResult(String queryId, List<QueryResultItem> topK, PartialResult partial, 
                          Map<String, Object> metadata, IndexCoverage indexCoverage) {
        this(queryId, topK, partial, metadata, indexCoverage, 1e-6);
    }
    
    /**
     * Create a new ExpectedResult with custom epsilon.
     * 
     * @param queryId  Unique query identifier
     * @param topK     Top K result items
     * @param partial  Partial results (if any)
     * @param metadata Additional metadata
     * @param indexCoverage Index coverage information
     * @param epsilon  Tolerance for numeric comparisons
     */
    public ExpectedResult(String queryId, List<QueryResultItem> topK, PartialResult partial, 
                          Map<String, Object> metadata, IndexCoverage indexCoverage, double epsilon) {
        this.queryId = queryId;
        this.topK = (topK == null) ? Collections.emptyList() : new ArrayList<>(topK);
        this.partial = (partial == null) ? PartialResult.empty() : partial;
        this.metadata = (metadata == null) ? Collections.emptyMap() : new HashMap<>(metadata);
        this.indexCoverage = (indexCoverage == null) ? IndexCoverage.empty() : indexCoverage;
        this.epsilon = epsilon;
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
    
    /**
     * Get the epsilon tolerance for numeric comparisons.
     * 
     * @return The epsilon value
     */
    public double getEpsilon() {
        return epsilon;
    }
    
    @Override
    public String toString() {
        return "ExpectedResult{queryId=" + queryId + 
               ", topK=" + topK.size() + 
               ", partial=" + partial + 
               ", coverage=" + indexCoverage + 
               ", epsilon=" + epsilon + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExpectedResult)) return false;
        ExpectedResult that = (ExpectedResult) o;
        return Objects.equals(queryId, that.queryId) &&
               Objects.equals(topK, that.topK) &&
               Objects.equals(partial, that.partial) &&
               Objects.equals(metadata, that.metadata) &&
               Objects.equals(indexCoverage, that.indexCoverage) &&
               Math.abs(epsilon - that.epsilon) < 1e-6;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(queryId, topK, partial, metadata, indexCoverage, epsilon);
    }
    
    /**
     * Create a new ExpectedResult.
     * 
     * @param queryId  Unique query identifier
     * @param topK     Top K result items
     * @param partial  Partial results (if any)
     * @param metadata Additional metadata
     * @param indexCoverage Index coverage information
     * @return A new ExpectedResult instance
     */
    public static ExpectedResult builder(String queryId, List<QueryResultItem> topK, PartialResult partial,
                                          Map<String, Object> metadata, IndexCoverage indexCoverage) {
        return new ExpectedResult(queryId, topK, partial, metadata, indexCoverage);
    }
    
    /**
     * Create a builder with default values.
     * 
     * @return A new builder instance
     */
    public static ExpectedResult builder() {
        return ExpectedResult.builder(null, Collections.emptyList(), PartialResult.empty(),
                                        Collections.emptyMap(), IndexCoverage.empty());
    }
}
