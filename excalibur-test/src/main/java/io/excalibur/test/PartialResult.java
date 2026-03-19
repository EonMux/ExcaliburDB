/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: io.excalibur.test
 * 
 * Thread Safety: 
 *   - PartialResult: Thread-safe, immutable
 */

package io.excalibur.test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable representation of partial query results.
 * 
 * Partial results are used to track additional items that were found
 * but not included in the top K results, typically due to pagination
 * or result truncation.
 */
public class PartialResult {
    
    private final long totalCount;
    private final Set<String> partialIds;
    
    /**
     * Create a new PartialResult.
     * 
     * @param totalCount  Total number of matching documents
     * @param partialIds   Set of document IDs that were found but not in top K
     */
    public PartialResult(long totalCount, Set<String> partialIds) {
        this.totalCount = totalCount;
        this.partialIds = (partialIds == null) ? Collections.emptySet() : new HashSet<>(partialIds);
    }
    
    /**
     * Create an empty PartialResult.
     */
    public static PartialResult empty() {
        return new PartialResult(0, Collections.emptySet());
    }
    
    /**
     * Create a PartialResult with no partial IDs.
     */
    public static PartialResult onlyTopK(long totalCount) {
        return new PartialResult(totalCount, Collections.emptySet());
    }
    
    /**
     * Create a PartialResult with specific partial IDs.
     */
    public static PartialResult withPartialIds(long totalCount, Set<String> partialIds) {
        return new PartialResult(totalCount, partialIds);
    }
    
    /**
     * Get the total count of matching documents.
     * 
     * @return The total count
     */
    public long getTotalCount() {
        return totalCount;
    }
    
    /**
     * Get the set of partial document IDs.
     * 
     * @return The set of partial IDs
     */
    public Set<String> getPartialIds() {
        return Collections.unmodifiableSet(partialIds);
    }
    
    /**
     * Check if this partial result is empty.
     * 
     * @return true if both count and partial IDs are empty
     */
    public boolean isEmpty() {
        return totalCount == 0 && partialIds.isEmpty();
    }
    
    @Override
    public String toString() {
        return "PartialResult{totalCount=" + totalCount + 
               ", partialIds=" + partialIds + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PartialResult)) return false;
        PartialResult that = (PartialResult) o;
        return totalCount == that.totalCount &&
               Objects.equals(partialIds, that.partialIds);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(totalCount, partialIds);
    }
}
