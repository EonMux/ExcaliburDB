/*
 * excalibur-test - Query Result Verification Service
 *
 * Package: com.eonmux.excalibur.test
 *
 * Thread Safety:
 *   - QueryResult: Thread-safe, immutable
 */

package com.eonmux.excalibur.test;

import java.util.*;
import java.util.stream.Collectors;

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
     * Create a QueryResult from an com.eonmux.excalibur.query.QueryResponse.
     *
     * <p>This factory method converts the query pipeline's response format
     * into the test framework's verification format, enabling users to
     * verify actual query results without manual field-by-field conversion.</p>
     *
     * <h3>Field Mapping</h3>
     * <ul>
     *   <li>{@code documentId}: Prefers the string ID when present and non-blank, otherwise
     *       the numeric ID rendered as a string</li>
     *   <li>{@code score}: Copied directly</li>
     *   <li>{@code metadata}: Includes segmentId and nodeId from query result</li>
     *   <li>{@code content/source/chunkIndex}: Not available from QueryResponse, set to defaults</li>
     *   <li>{@code IndexCoverage}: Converted from long to int segment counts</li>
     * </ul>
     *
     * @param response The query response from the query pipeline (must not be null)
     * @param queryId  Unique identifier for this query (must not be null)
     * @return A QueryResult suitable for verification in test frameworks
     * @throws NullPointerException if response or queryId is null
     */
    public static QueryResult fromQueryResponse(com.eonmux.excalibur.query.QueryResponse response, String queryId) {
        Objects.requireNonNull(response, "QueryResponse cannot be null");
        Objects.requireNonNull(queryId, "QueryId cannot be null");

        // Convert each QueryResultItem from query pipeline to test framework format
        List<QueryResultItem> items = response.results().stream()
            .map(r -> {
                // Prefer string document ID if available, otherwise convert numeric ID.
                // A blank string is not a usable identity, so it falls back too.
                String docId = (r.documentIdStr() != null && !r.documentIdStr().isBlank())
                    ? r.documentIdStr()
                    : String.valueOf(r.documentId());

                // Build metadata with available information from query result
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("segmentId", r.segmentId());
                metadata.put("nodeId", r.nodeId());
                metadata.put("hasPayload", r.hasPayload());

                return new QueryResultItem(
                    docId,
                    r.score(),
                    metadata,
                    null,  // content - not available from QueryResponse (payload would need decoding)
                    null,  // source - not available from QueryResponse
                    -1     // chunkIndex - not available from QueryResponse
                );
            })
            .collect(Collectors.toList());

        // Convert IndexCoverage from query pipeline to test framework format
        com.eonmux.excalibur.query.IndexCoverage queryCoverage = response.coverage();
        IndexCoverage coverage = new IndexCoverage(
            safeLongToInt(queryCoverage.totalSegments()),
            safeLongToInt(queryCoverage.activeSegments()),
            safeLongToInt(queryCoverage.indexingSegments()),
            safeLongToInt(queryCoverage.warmingSegments()),
            queryCoverage.estimatedCorpusCoverage(),
            queryCoverage.degraded()
        );

        // Build metadata with query response information
        Map<String, Object> responseMetadata = new HashMap<>();
        responseMetadata.put("queryTimeMs", response.queryTimeMs());
        responseMetadata.put("fromCache", response.fromCache());
        responseMetadata.put("timedOut", response.timedOut());
        responseMetadata.put("isDegraded", response.isDegraded());
        responseMetadata.put("resultCount", response.resultCount());
        // Include additional coverage information not mapped to test IndexCoverage
        responseMetadata.put("compactingSegments", queryCoverage.compactingSegments());
        responseMetadata.put("tombstonedSegments", queryCoverage.tombstonedSegments());
        responseMetadata.put("tailScanEntries", queryCoverage.tailScanEntries());
        responseMetadata.put("segmentsSearched", queryCoverage.segmentsSearched());
        responseMetadata.put("segmentsWithErrors", queryCoverage.segmentsWithErrors());
        responseMetadata.put("segmentsTimedOut", queryCoverage.segmentsTimedOut());

        return new QueryResult(queryId, items, PartialResult.empty(), responseMetadata, coverage);
    }

    /**
     * Safely convert a long to int, clamping to int range if necessary.
     *
     * <p>Domain correctness: segment counts should realistically fit in an int
     * (max ~2B segments), but we clamp to avoid overflow exceptions.</p>
     *
     * @param value The long value to convert
     * @return The int value, clamped to Integer.MAX_VALUE if too large
     */
    private static int safeLongToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
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
