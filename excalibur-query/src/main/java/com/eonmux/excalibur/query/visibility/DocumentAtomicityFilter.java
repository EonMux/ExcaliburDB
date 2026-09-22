package com.eonmux.excalibur.query.visibility;

import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.query.QueryResultItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Filters query results to exclude partial (non-atomically visible) documents.
 *
 * <p>This class is the final enforcement point for the document-atomic visibility
 * guarantee. It ensures that query results never contain chunks from documents
 * that are not fully visible at the query's captured epoch.</p>
 *
 * <h2>Document Atomicity Guarantee</h2>
 * <p>A document is "atomically visible" at epoch E when:</p>
 * <ul>
 *   <li>All chunks for the document have been written to the write log</li>
 *   <li>All chunks have been registered in the visibility tracker</li>
 *   <li>The document's commit epoch is &lt;= E</li>
 * </ul>
 *
 * <h2>Query Pipeline Integration</h2>
 * <p>This filter is used in Stage 4 of the query pipeline (full vector rerank):</p>
 * <pre>{@code
 * // After aggregating results from all segments
 * List<QueryResultItem> merged =
 *     aggregator.mergeAndRankWithNormalization(segmentResults, distanceFunction);
 *
 * // Filter out partial documents
 * DocumentAtomicityFilter filter = new DocumentAtomicityFilter(visibilityChecker);
 * List<QueryResultItem> atomic = filter.filterPartialDocuments(merged, queryEpoch);
 * }</pre>
 *
 * <h2>Scope of the Filter</h2>
 * <p>The filter only removes results it can positively identify as belonging to a
 * document that is <em>not</em> yet fully visible. Two kinds of result are passed through
 * instead, for the same reason: one carrying no document identity at all, and one whose
 * document the visibility checker has never seen. In both cases the atomicity model has no
 * opinion, and discarding the result would silently drop a legitimate match rather than
 * uphold a guarantee.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Results are filtered based on document-level visibility, not chunk-level</li>
 *   <li>All chunks from a visible document are included; none from partial documents</li>
 *   <li>Results with no document ID are passed through, never dropped</li>
 *   <li>Epoch is always non-negative</li>
 *   <li>Empty input produces empty output (no null values)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for read operations. The underlying
 * {@link DocumentVisibilityChecker} handles concurrent visibility state changes.</p>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public final class DocumentAtomicityFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentAtomicityFilter.class);

    private final DocumentVisibilityChecker visibilityChecker;
    private final DocumentVisibilityTracker legacyTracker;

    /**
     * Creates a new DocumentAtomicityFilter with a visibility checker.
     *
     * <p>This is the recommended constructor for production use.</p>
     *
     * @param visibilityChecker The visibility checker for checking document visibility
     * @throws NullPointerException if visibilityChecker is null
     */
    public DocumentAtomicityFilter(DocumentVisibilityChecker visibilityChecker) {
        this.visibilityChecker = Objects.requireNonNull(visibilityChecker, "visibilityChecker must not be null");
        this.legacyTracker = null;
    }

    /**
     * Creates a new DocumentAtomicityFilter with a legacy tracker.
     *
     * <p>This constructor is provided for backward compatibility.</p>
     *
     * @param tracker The visibility tracker for checking document visibility
     * @throws NullPointerException if tracker is null
     */
    public DocumentAtomicityFilter(DocumentVisibilityTracker tracker) {
        this.legacyTracker = Objects.requireNonNull(tracker, "tracker must not be null");
        this.visibilityChecker = new TrackerAdapter(tracker);
    }

    /**
     * Filters query results to exclude partial documents.
     *
     * <p>This method ensures that only results from fully-visible documents
     * are returned. If a document is partially ingested (not all chunks visible),
     * all results from that document are excluded.</p>
     *
     * <p><strong>Atomicity guarantee:</strong> Either all chunks from a document
     * are included, or none are. No partial documents in results.</p>
     *
     * @param results The query results to filter (must not be null)
     * @param queryEpoch The epoch at which the query was captured (must be &gt;= 0)
     * @return Filtered list containing only results from fully visible documents
     * @throws NullPointerException if results is null
     * @throws IllegalArgumentException if queryEpoch is negative
     */
    public List<QueryResultItem> filterPartialDocuments(List<QueryResultItem> results, long queryEpoch) {
        Objects.requireNonNull(results, "results must not be null");
        
        if (queryEpoch < 0) {
            throw new IllegalArgumentException("queryEpoch must be non-negative: " + queryEpoch);
        }

        if (results.isEmpty()) {
            return Collections.emptyList();
        }

        List<QueryResultItem> filtered = new ArrayList<>(results.size());

        for (QueryResultItem result : results) {
            if (isResultVisible(result, queryEpoch)) {
                filtered.add(result);
            }
        }

        if (filtered.size() < results.size()) {
            LOGGER.debug("Filtered {} partial document results from {} total (epoch={})",
                results.size() - filtered.size(), results.size(), queryEpoch);
        }

        return Collections.unmodifiableList(filtered);
    }

    /**
     * Filters chunk IDs to exclude chunks from partial documents.
     *
     * <p>This is a convenience method for filtering at the chunk level
     * before results are materialized.</p>
     *
     * @param chunkIds The chunk IDs to filter (must not be null)
     * @param queryEpoch The epoch at which the query was captured (must be &gt;= 0)
     * @return Filtered list containing only chunks from fully visible documents
     * @throws NullPointerException if chunkIds is null
     * @throws IllegalArgumentException if queryEpoch is negative
     */
    public List<String> filterChunkIds(List<String> chunkIds, long queryEpoch) {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        
        if (queryEpoch < 0) {
            throw new IllegalArgumentException("queryEpoch must be non-negative: " + queryEpoch);
        }

        if (chunkIds.isEmpty()) {
            return Collections.emptyList();
        }

        return visibilityChecker.filterPartialDocuments(chunkIds, queryEpoch);
    }

    /**
     * Checks if a single query result is from a fully visible document.
     *
     * @param result The query result to check (must not be null)
     * @param queryEpoch The epoch at which the query was captured (must be &gt;= 0)
     * @return true if the result's document is fully visible at the query epoch
     * @throws NullPointerException if result is null
     * @throws IllegalArgumentException if queryEpoch is negative
     */
    public boolean isResultVisible(QueryResultItem result, long queryEpoch) {
        Objects.requireNonNull(result, "result must not be null");
        
        if (queryEpoch < 0) {
            throw new IllegalArgumentException("queryEpoch must be non-negative: " + queryEpoch);
        }

        String documentId = extractDocumentId(result);
        if (documentId == null) {
            // The result carries no document identity, so the atomicity model has nothing
            // to say about it. Filtering here would be a false negative, not a guarantee:
            // it would drop the result on the basis of an identity we never had.
            LOGGER.trace("Result has no document ID (segment={}, node={}); atomicity filter passes it through",
                result.segmentId(), result.nodeId());
            return true;
        }

        if (!visibilityChecker.isDocumentTracked(documentId)) {
            // Same reasoning, one step further in: the checker has never seen this document,
            // which is not the same as knowing it to be incomplete. These checkers are built
            // in memory as documents are ingested, so after a restart everything written by a
            // previous process is unknown -- and treating unknown as invisible would drop all
            // of it from every result the moment one new document arrives.
            LOGGER.trace("Document {} is not tracked; atomicity filter passes it through",
                documentId);
            return true;
        }

        return visibilityChecker.isDocumentVisible(documentId, queryEpoch);
    }

    /**
     * Verifies that all results in a list are from atomically visible documents.
     *
     * <p>This method is useful for assertions and debugging to verify
     * that the atomicity guarantee is being maintained.</p>
     *
     * @param results The results to verify (must not be null)
     * @param queryEpoch The epoch at which the query was captured (must be &gt;= 0)
     * @return AtomicityVerification with validity status and partial count
     * @throws NullPointerException if results is null
     * @throws IllegalArgumentException if queryEpoch is negative
     */
    public AtomicityVerification verifyAtomicity(List<QueryResultItem> results, long queryEpoch) {
        Objects.requireNonNull(results, "results must not be null");
        
        if (queryEpoch < 0) {
            throw new IllegalArgumentException("queryEpoch must be non-negative: " + queryEpoch);
        }

        int partialCount = 0;

        for (QueryResultItem result : results) {
            if (!isResultVisible(result, queryEpoch)) {
                partialCount++;
            }
        }

        boolean valid = partialCount == 0;

        return new AtomicityVerification(valid, results.size(), partialCount, queryEpoch);
    }

    /**
     * Computes filter statistics for observability.
     *
     * @param resultsBefore Number of results before filtering (must be &gt;= 0)
     * @param resultsAfter Number of results after filtering (must be &gt;= 0)
     * @return FilterStats with computed metrics
     * @throws IllegalArgumentException if either parameter is negative or if after &gt; before
     */
    public FilterStats computeStats(int resultsBefore, int resultsAfter) {
        if (resultsBefore < 0) {
            throw new IllegalArgumentException("resultsBefore must be non-negative: " + resultsBefore);
        }
        if (resultsAfter < 0) {
            throw new IllegalArgumentException("resultsAfter must be non-negative: " + resultsAfter);
        }
        if (resultsAfter > resultsBefore) {
            throw new IllegalArgumentException(
                "resultsAfter cannot be greater than resultsBefore: " + resultsAfter + " > " + resultsBefore);
        }

        int filteredCount = resultsBefore - resultsAfter;
        double filterRate = resultsBefore > 0 ? (double) filteredCount / resultsBefore : 0.0;
        boolean hadFiltering = filteredCount > 0;

        return new FilterStats(resultsBefore, resultsAfter, filteredCount, filterRate, hadFiltering);
    }

    /**
     * Extracts the document ID from a query result.
     *
     * <p>The document ID is the result's {@code documentIdStr}: the actual string
     * document ID (e.g. {@code "doc1"}, {@code "source1:file1"}) from the ingestion
     * pipeline, populated from the HNSW node-to-document mapping during search or
     * decoded from the write-log entry key during a tail scan.</p>
     *
     * <p>There is deliberately no fallback to the numeric ID. That value is the HNSW
     * node ID, which lives in a different namespace from document IDs and can never
     * match a registered document, so synthesising a document ID from it turns "we do
     * not know which document this is" into "this document is not visible" - a
     * guaranteed false negative rather than a safety net.</p>
     *
     * @param result The query result
     * @return The document ID string, or null if the result carries no document identity
     */
    private String extractDocumentId(QueryResultItem result) {
        return result.documentIdStr();
    }

    /**
     * Returns the underlying visibility checker.
     *
     * @return The DocumentVisibilityChecker
     */
    public DocumentVisibilityChecker getVisibilityChecker() {
        return visibilityChecker;
    }

    /**
     * Returns the underlying visibility tracker (for legacy compatibility).
     *
     * @return The DocumentVisibilityTracker, or null if using checker interface
     */
    public DocumentVisibilityTracker getTracker() {
        return legacyTracker;
    }

    /**
     * Adapter to make DocumentVisibilityTracker implement DocumentVisibilityChecker.
     */
    private static class TrackerAdapter implements DocumentVisibilityChecker {
        private final DocumentVisibilityTracker tracker;

        TrackerAdapter(DocumentVisibilityTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public boolean isDocumentVisible(String documentId, long queryEpoch) {
            return tracker.isDocumentFullyVisible(documentId, queryEpoch);
        }

        @Override
        public boolean isChunkVisible(String chunkId, long queryEpoch) {
            return tracker.isChunkVisible(chunkId, queryEpoch);
        }

        @Override
        public List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch) {
            return tracker.filterPartialDocuments(chunkIds, queryEpoch);
        }

        @Override
        public java.util.Set<String> getVisibleDocuments(long queryEpoch) {
            return tracker.getVisibleDocuments(queryEpoch);
        }

        @Override
        public long currentEpoch() {
            return tracker.currentEpoch();
        }

        @Override
        public long getCommitEpoch(String documentId) {
            return tracker.getCommitEpoch(documentId);
        }

        @Override
        public int getChunkCount(String documentId) {
            return tracker.getChunkCount(documentId);
        }

        @Override
        public int getDocumentCount() {
            return tracker.getDocumentCount();
        }
    }

    /**
     * Verification result for atomicity checks.
     *
     * @param valid True if all results are from atomically visible documents
     * @param totalCount Total number of results checked
     * @param partialCount Number of results from partial documents
     * @param queryEpoch The epoch at which the check was performed
     */
    public record AtomicityVerification(
        boolean valid,
        int totalCount,
        int partialCount,
        long queryEpoch
    ) {
        public AtomicityVerification {
            if (totalCount < 0) {
                throw new IllegalArgumentException("totalCount must be non-negative: " + totalCount);
            }
            if (partialCount < 0) {
                throw new IllegalArgumentException("partialCount must be non-negative: " + partialCount);
            }
            if (queryEpoch < 0) {
                throw new IllegalArgumentException("queryEpoch must be non-negative: " + queryEpoch);
            }
            if (partialCount > totalCount) {
                throw new IllegalArgumentException(
                    "partialCount cannot exceed totalCount: " + partialCount + " > " + totalCount);
            }
        }

        /**
         * Returns the number of valid (atomic) results.
         */
        public int atomicCount() {
            return totalCount - partialCount;
        }

        /**
         * Returns the ratio of atomic results (0.0 to 1.0).
         */
        public double atomicRatio() {
            return totalCount > 0 ? (double) atomicCount() / totalCount : 1.0;
        }

        @Override
        public String toString() {
            return String.format("AtomicityVerification{valid=%s, total=%d, partial=%d, epoch=%d}",
                valid, totalCount, partialCount, queryEpoch);
        }
    }

    /**
     * Statistics about filter operation.
     *
     * @param resultsBefore Number of results before filtering
     * @param resultsAfter Number of results after filtering
     * @param filteredCount Number of results that were filtered out
     * @param filterRate Ratio of filtered results (0.0 to 1.0)
     * @param hadFiltering True if any results were filtered
     */
    public record FilterStats(
        int resultsBefore,
        int resultsAfter,
        int filteredCount,
        double filterRate,
        boolean hadFiltering
    ) {
        public FilterStats {
            if (resultsBefore < 0) {
                throw new IllegalArgumentException("resultsBefore must be non-negative: " + resultsBefore);
            }
            if (resultsAfter < 0) {
                throw new IllegalArgumentException("resultsAfter must be non-negative: " + resultsAfter);
            }
            if (filteredCount < 0) {
                throw new IllegalArgumentException("filteredCount must be non-negative: " + filteredCount);
            }
        }

        /**
         * Returns the retention rate (ratio of results kept).
         */
        public double retentionRate() {
            return 1.0 - filterRate;
        }

        @Override
        public String toString() {
            return String.format("FilterStats{before=%d, after=%d, filtered=%d, rate=%.2f%%}",
                resultsBefore, resultsAfter, filteredCount, filterRate * 100);
        }
    }
}
