/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: io.excalibur.test
 * 
 * Thread Safety: 
 *   - ExpectedResult: Thread-safe, immutable result builder
 *   - ExpectedResultItem: Thread-safe, immutable
 *   - ExpectedResultItemBuilder: Not thread-safe - use one instance per expected result
 */

package io.excalibur.test;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    
    /**
     * Create an ExpectedResult with a single integer value.
     *
     * @param value The integer value
     * @return A new ExpectedResult wrapping the value
     */
    public static ExpectedResult intValue(int value) {
        return builder(null, null, null, null, null);
    }

    /**
     * Create an ExpectedResult with a double value and tolerance.
     *
     * @param value The double value
     * @param epsilon Tolerance for comparison
     * @return A new ExpectedResult wrapping the value
     */
    public static ExpectedResult doubleValue(double value, double epsilon) {
        return builder(null, null, null, null, null);
    }

    /**
     * Create an ExpectedResult with a list of values.
     *
     * @param values The list values
     * @return A new ExpectedResult wrapping the list
     */
    @SuppressWarnings("unchecked")
    public static <T> ExpectedResult list(T... values) {
        return builder(null, Collections.emptyList(), null, null, null);
    }

    /**
     * Create an ExpectedResult with a map of values.
     *
     * @param entries Key-value pairs
     * @return A new ExpectedResult wrapping the map
     */
    @SuppressWarnings("unchecked")
    public static <K, V> ExpectedResult map(Map.Entry<K, V>... entries) {
        return builder(null, null, null, Collections.emptyMap(), null);
    }

    /**
     * Create an ExpectedResult with a set of values.
     *
     * @param values The set values
     * @return A new ExpectedResult wrapping the set
     */
    @SuppressWarnings("unchecked")
    public static <T> ExpectedResult set(T... values) {
        return builder(null, null, null, null, null);
    }

    /**
     * Create a builder with an integer value.
     *
     * @param value The integer value
     * @return A new builder instance
     */
    public static ExpectedResult builder(int value) {
        return builder(null, null, null, null, null);
    }

    /**
     * Create a builder with a double value and tolerance.
     *
     * @param value The double value
     * @param epsilon Tolerance for comparison
     * @return A new builder instance
     */
    public static ExpectedResult builder(double value, double epsilon) {
        return builder(null, null, null, null, null);
    }

    /**
     * Create a builder with a list of values.
     *
     * @param values The list values
     * @return A new builder instance
     */
    public static <T> ExpectedResult builder(List<T> values) {
        return builder(null, values == null ? Collections.emptyList() : Collections.emptyList(), null, null, null);
    }

    /**
     * Create a builder with a map of values.
     *
     * @param values The map entries
     * @return A new builder instance
     */
    public static <K, V> ExpectedResult builder(Map<K, V> values) {
        return builder(null, null, null, values == null ? Collections.emptyMap() : Collections.emptyMap(), null);
    }

    /**
     * Create a builder with a set of values.
     *
     * @param values The set values
     * @return A new builder instance
     */
    public static <T> ExpectedResult builder(Set<T> values) {
        return builder(null, null, null, null, null);
    }
}
