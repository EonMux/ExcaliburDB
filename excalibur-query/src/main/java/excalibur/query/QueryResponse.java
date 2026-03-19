package excalibur.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Response from an ANN query containing results and coverage metadata.
 *
 * <p>QueryResponse is the primary return type for the query service.
 * It includes the top-k results along with metadata about index coverage
 * and query execution quality.</p>
 *
 * <h2>Result Ordering</h2>
 * <p>Results are always sorted by score. For distance-based metrics (L2),
 * results are sorted in ascending order (closest first). For similarity-based
 * metrics (cosine, inner product), results are sorted in descending order
 * (most similar first).</p>
 *
 * <h2>Degraded Queries</h2>
 * <p>A query may be marked as "degraded" when:</p>
 * <ul>
 *   <li>Coverage is below the configured threshold (default 0.5)</li>
 *   <li>Some segments timed out or had errors</li>
 *   <li>Partial results are returned due to failures</li>
 *   <li>The query exceeded its timeout</li>
 * </ul>
 *
 * <h2>Timeout Handling</h2>
 * <p>When a query times out, the response will have:</p>
 * <ul>
 *   <li>{@code timedOut = true}</li>
 *   <li>Partial results collected before timeout</li>
 *   <li>{@code degraded = true}</li>
 *   <li>Coverage indicating segments that timed out</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class QueryResponse {

    private final List<QueryResultItem> results;
    private final IndexCoverage coverage;
    private final long queryTimeMs;
    private final boolean fromCache;
    private final boolean timedOut;

    /**
     * Creates a new QueryResponse.
     *
     * @param results The query results (will be copied)
     * @param coverage The index coverage metadata
     * @param queryTimeMs The query execution time in milliseconds
     * @param fromCache Whether results came from cache
     * @param timedOut Whether the query timed out
     * @throws NullPointerException if results or coverage is null
     */
    public QueryResponse(List<QueryResultItem> results, IndexCoverage coverage,
                         long queryTimeMs, boolean fromCache, boolean timedOut) {
        this.results = Collections.unmodifiableList(new ArrayList<>(
            Objects.requireNonNull(results, "Results cannot be null")));
        this.coverage = Objects.requireNonNull(coverage, "Coverage cannot be null");
        this.queryTimeMs = queryTimeMs;
        this.fromCache = fromCache;
        this.timedOut = timedOut;
    }

    /**
     * Creates a new QueryResponse with cache flag and timedOut set to false.
     *
     * @param results The query results
     * @param coverage The index coverage metadata
     * @param queryTimeMs The query execution time in milliseconds
     */
    public QueryResponse(List<QueryResultItem> results, IndexCoverage coverage, long queryTimeMs) {
        this(results, coverage, queryTimeMs, false, false);
    }

    /**
     * Creates a new QueryResponse with specified cache flag.
     *
     * @param results The query results
     * @param coverage The index coverage metadata
     * @param queryTimeMs The query execution time in milliseconds
     * @param fromCache Whether results came from cache
     */
    public QueryResponse(List<QueryResultItem> results, IndexCoverage coverage,
                         long queryTimeMs, boolean fromCache) {
        this(results, coverage, queryTimeMs, fromCache, false);
    }

    /**
     * Creates an empty response with no results.
     *
     * @param coverage The index coverage metadata
     * @param queryTimeMs The query execution time
     * @return An empty QueryResponse
     */
    public static QueryResponse empty(IndexCoverage coverage, long queryTimeMs) {
        return new QueryResponse(Collections.emptyList(), coverage, queryTimeMs, false);
    }

    /**
     * Returns the list of query results.
     *
     * <p>Results are sorted by relevance (closest/most similar first).</p>
     *
     * @return Immutable list of results
     */
    public List<QueryResultItem> results() {
        return results;
    }

    /**
     * Returns the number of results.
     *
     * @return Result count
     */
    public int resultCount() {
        return results.size();
    }

    /**
     * Returns whether there are no results.
     *
     * @return true if results is empty
     */
    public boolean isEmpty() {
        return results.isEmpty();
    }

    /**
     * Returns the index coverage metadata.
     *
     * @return The coverage information
     */
    public IndexCoverage coverage() {
        return coverage;
    }

    /**
     * Returns whether this query is in a degraded state.
     *
     * @return true if coverage indicates degraded state or query timed out
     */
    public boolean isDegraded() {
        return coverage.degraded() || timedOut;
    }

    /**
     * Returns whether the query timed out.
     *
     * <p>When a query times out, it may still return partial results.
     * Check {@link #isDegraded()} for a combined degraded status.</p>
     *
     * @return true if the query exceeded its timeout
     */
    public boolean timedOut() {
        return timedOut;
    }

    /**
     * Returns the query execution time in milliseconds.
     *
     * @return Query time in ms
     */
    public long queryTimeMs() {
        return queryTimeMs;
    }

    /**
     * Returns whether results came from cache.
     *
     * @return true if cached results were returned
     */
    public boolean fromCache() {
        return fromCache;
    }

    /**
     * Returns the top result, or null if no results.
     *
     * @return The first result, or null
     */
    public QueryResultItem topResult() {
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Creates a new QueryResponse with results from cache.
     *
     * @return A new QueryResponse with fromCache=true
     */
    public QueryResponse withFromCache() {
        return new QueryResponse(results, coverage, queryTimeMs, true, timedOut);
    }

    /**
     * Creates a new QueryResponse with updated query time.
     *
     * @param newQueryTimeMs The new query time
     * @return A new QueryResponse with the updated time
     */
    public QueryResponse withQueryTimeMs(long newQueryTimeMs) {
        return new QueryResponse(results, coverage, newQueryTimeMs, fromCache, timedOut);
    }

    /**
     * Creates a new QueryResponse with timedOut flag.
     *
     * @param timedOut Whether the query timed out
     * @return A new QueryResponse with the timedOut flag
     */
    public QueryResponse withTimedOut(boolean timedOut) {
        return new QueryResponse(results, coverage, queryTimeMs, fromCache, timedOut);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryResponse that = (QueryResponse) o;
        return queryTimeMs == that.queryTimeMs &&
            fromCache == that.fromCache &&
            timedOut == that.timedOut &&
            results.equals(that.results) &&
            coverage.equals(that.coverage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(results, coverage, queryTimeMs, fromCache, timedOut);
    }

    @Override
    public String toString() {
        return String.format(
            "QueryResponse{results=%d, coverage=%.1f%%, degraded=%s, time=%dms, cached=%s, timedOut=%s}",
            results.size(), coverage.estimatedCorpusCoverage() * 100,
            isDegraded(), queryTimeMs, fromCache, timedOut);
    }
}
