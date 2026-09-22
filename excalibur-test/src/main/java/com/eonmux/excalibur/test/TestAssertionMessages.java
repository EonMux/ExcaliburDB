/*
 * excalibur-test - Test Assertion Messages
 *
 * Package: com.eonmux.excalibur.test
 *
 * Thread Safety:
 *   - TestAssertionMessages: Thread-safe, all methods are static and stateless
 */

package com.eonmux.excalibur.test;

import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Utility class for creating specific, domain-aware assertion messages that clearly
 * identify what failed when tests fail.
 *
 * <h2>Purpose</h2>
 * <p>This class provides helper methods for generating assertion messages that:</p>
 * <ul>
 *   <li>Clearly state what was expected vs what was found</li>
 *   <li>Include domain context (coverage values, segment states, etc.)</li>
 *   <li>Identify the domain correctness criterion that was violated</li>
 *   <li>Provide actionable information for debugging</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Instead of:
 * assertThat(coverage.estimatedCorpusCoverage()).isEqualTo(0.0);
 *
 * // Use:
 * assertThat(coverage.estimatedCorpusCoverage())
 *     .as(TestAssertionMessages.coverageMustBeZero("empty index"))
 *     .isEqualTo(0.0);
 * }</pre>
 *
 * <h2>Message Categories</h2>
 * <ul>
 *   <li><strong>Coverage assertions:</strong> Messages for coverage value checks</li>
 *   <li><strong>Result assertions:</strong> Messages for query result checks</li>
 *   <li><strong>Epoch assertions:</strong> Messages for epoch isolation checks</li>
 *   <li><strong>Deletion assertions:</strong> Messages for deletion correctness checks</li>
 *   <li><strong>LLM assertions:</strong> Messages for LLM fallback behavior checks</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
public final class TestAssertionMessages {

    // Private constructor - utility class with only static methods
    private TestAssertionMessages() {}

    // ==================== Coverage Assertions ====================

    /**
     * Creates a message asserting coverage must be exactly 0.0.
     *
     * @param context The context (e.g., "empty index", "no segments registered")
     * @return A specific assertion message
     */
    public static String coverageMustBeZero(String context) {
        return String.format(
            "Coverage must be exactly 0.0 for %s. " +
            "DOMAIN VIOLATION: Empty index must report coverage=0.0 and degraded=true. " +
            "If coverage is non-zero, check that IndexCoverage correctly calculates " +
            "estimatedCorpusCoverage when no segments are visible.",
            context
        );
    }

    /**
     * Creates a message asserting coverage must be in valid range [0.0, 1.0].
     *
     * @param actualValue The actual coverage value found
     * @return A specific assertion message
     */
    public static String coverageMustBeInRange(double actualValue) {
        return String.format(
            "Coverage must be in range [0.0, 1.0], but was %.4f. " +
            "DOMAIN VIOLATION: Coverage represents a ratio and must be normalized. " +
            "Values outside [0,1] indicate a bug in IndexCoverage calculation. " +
            "Check: totalSegments count, activeSegments count, or division logic.",
            actualValue
        );
    }

    /**
     * Creates a message asserting degraded flag must be true.
     *
     * @param context The context (e.g., "empty index", "coverage below threshold")
     * @param actualCoverage The actual coverage value
     * @return A specific assertion message
     */
    public static String degradedMustBeTrue(String context, double actualCoverage) {
        return String.format(
            "Degraded flag must be true for %s (coverage=%.4f). " +
            "DOMAIN VIOLATION: When coverage < readiness threshold (default 0.5), " +
            "degraded must be true. Check: IndexCoverage.degraded calculation " +
            "and excalibur.health.readiness-threshold config value.",
            context, actualCoverage
        );
    }

    /**
     * Creates a message asserting coverage should reflect actual state.
     *
     * @param expectedMin Minimum expected coverage
     * @param actual Actual coverage found
     * @param activeSegments Number of active segments
     * @param totalSegments Total segments
     * @return A specific assertion message
     */
    public static String coverageShouldReflectState(
            double expectedMin, double actual, int activeSegments, int totalSegments) {
        return String.format(
            "Coverage should reflect actual index state. " +
            "Expected: >= %.2f, Actual: %.4f, Active segments: %d/%d. " +
            "If coverage is too low, check: (1) segments registered in VisibilityTracker, " +
            "(2) segments in queryable states (INDEXING/WARMING/ACTIVE/COMPACTING), " +
            "(3) IndexCoverage calculation correctly counts active vs total segments.",
            expectedMin, actual, activeSegments, totalSegments
        );
    }

    /**
     * Creates a message for coverage monotonic increase assertion.
     *
     * @param stage Current stage
     * @param previousCoverage Coverage at previous stage
     * @param currentCoverage Coverage at current stage
     * @return A specific assertion message
     */
    public static String coverageMustIncreaseMonotonically(
            int stage, double previousCoverage, double currentCoverage) {
        return String.format(
            "Coverage at stage %d (%.4f) must be >= stage %d (%.4f). " +
            "DOMAIN VIOLATION: Coverage must increase (or stay same) as more data is ingested. " +
            "Decreasing coverage suggests: (1) segments being tombstoned incorrectly, " +
            "(2) VisibilityTracker epoch regression, (3) IndexCoverage calculation bug.",
            stage, currentCoverage, stage - 1, previousCoverage
        );
    }

    // ==================== Result Assertions ====================

    /**
     * Creates a message asserting results should not be empty.
     *
     * @param context The context (e.g., "after ingesting 100 documents")
     * @param topKRequested The top_k value requested
     * @return A specific assertion message
     */
    public static String resultsShouldNotBeEmpty(String context, int topKRequested) {
        return String.format(
            "Query results should not be empty %s. " +
            "Requested top_k=%d. " +
            "EMPTY RESULTS INDICATE: (1) No segments visible to query, " +
            "(2) All segments tombstoned, (3) HNSW graph has no vectors, " +
            "(4) VisibilityTracker not registering segments, " +
            "(5) Query vector dimension mismatch with index.",
            context, topKRequested
        );
    }

    /**
     * Creates a message asserting results should be sorted by score.
     *
     * @param resultIndex The index of the out-of-order result
     * @param currentScore Score at current index
     * @param previousScore Score at previous index
     * @return A specific assertion message
     */
    public static String resultsMustBeSortedByScore(int resultIndex, float currentScore, float previousScore) {
        return String.format(
            "Results must be sorted by score (highest similarity first). " +
            "Result[%d].score=%.4f should be <= Result[%d].score=%.4f. " +
            "DOMAIN VIOLATION: Query results must be ordered by relevance. " +
            "Check: QueryPipeline merge and sort logic in final result assembly.",
            resultIndex, currentScore, resultIndex - 1, previousScore
        );
    }

    /**
     * Creates a message asserting no duplicate document IDs in results.
     *
     * @param duplicateId The duplicate document ID found
     * @param firstIndex Index where duplicate first appeared
     * @param secondIndex Index where duplicate appeared again
     * @return A specific assertion message
     */
    public static String noDuplicateResults(long duplicateId, int firstIndex, int secondIndex) {
        return String.format(
            "No duplicate document IDs allowed in results. " +
            "Document ID %d appears at both index %d and %d. " +
            "DOMAIN VIOLATION: Each document should appear at most once in results. " +
            "Check: (1) HNSW graph for duplicate node entries, " +
            "(2) Segment storage for duplicate keys, " +
            "(3) Result deduplication in QueryPipeline.",
            duplicateId, firstIndex, secondIndex
        );
    }

    /**
     * Creates a message asserting score must be in valid range.
     *
     * @param actualScore The actual score value
     * @param documentId The document ID for context
     * @return A specific assertion message
     */
    public static String scoreMustBeNormalized(float actualScore, long documentId) {
        return String.format(
            "Score must be normalized to [0.0, 1.0] range. " +
            "Document %d has score=%.4f. " +
            "DOMAIN VIOLATION: All similarity scores must be normalized for consistent ranking. " +
            "Check: (1) DistanceFunction normalization, (2) LLM relevancy scoring output, " +
            "(3) Score transformation in QueryPipeline.",
            documentId, actualScore
        );
    }

    /**
     * Creates a message asserting expected document should be in results.
     *
     * @param expectedDocumentId The expected document ID
     * @param resultCount Number of results returned
     * @return A specific assertion message
     */
    public static String expectedDocumentInResults(String expectedDocumentId, int resultCount) {
        return String.format(
            "Expected document '%s' should be in query results. " +
            "Query returned %d results but none matched. " +
            "FAILURE INDICATES: (1) Document not ingested correctly, " +
            "(2) Vector not inserted into HNSW graph, " +
            "(3) Document ID not registered with graph.registerDocumentId(), " +
            "(4) Query vector too different from document vector.",
            expectedDocumentId, resultCount
        );
    }

    // ==================== Epoch Isolation Assertions ====================

    /**
     * Creates a message asserting epoch isolation (no torn reads).
     *
     * @param queryEpoch The epoch captured by the query
     * @param segmentVisibleAtEpoch Whether segment was visible at query epoch
     * @return A specific assertion message
     */
    public static String epochIsolationNoTornReads(long queryEpoch, boolean segmentVisibleAtEpoch) {
        return String.format(
            "Query must only see data visible at its captured epoch. " +
            "Query epoch=%d, segment visible=%b. " +
            "DOMAIN VIOLATION: Epoch isolation ensures consistent snapshots. " +
            "Torn reads indicate: (1) VisibilityTracker epoch not captured before data access, " +
            "(2) Segment state changed during query execution, " +
            "(3) Missing synchronization between epoch capture and segment visibility check.",
            queryEpoch, segmentVisibleAtEpoch
        );
    }

    /**
     * Creates a message asserting epoch must increase monotonically.
     *
     * @param previousEpoch The previous epoch value
     * @param currentEpoch The current epoch value
     * @return A specific assertion message
     */
    public static String epochMustIncreaseMonotonically(long previousEpoch, long currentEpoch) {
        return String.format(
            "Epoch must monotonically increase. " +
            "Previous=%d, Current=%d. " +
            "DOMAIN VIOLATION: Epoch values must never decrease. " +
            "Regression indicates: (1) VisibilityTracker epoch counter overflow, " +
            "(2) Race condition in advanceEpoch(), (3) Incorrect epoch initialization.",
            previousEpoch, currentEpoch
        );
    }

    /**
     * Creates a message asserting document atomicity.
     *
     * @param documentId The document ID
     * @param expectedChunks Expected number of chunks
     * @param foundChunks Found number of chunks
     * @return A specific assertion message
     */
    public static String documentAtomicity(String documentId, int expectedChunks, int foundChunks) {
        return String.format(
            "All chunks of document must be visible atomically. " +
            "Document '%s': expected %d chunks, found %d. " +
            "DOMAIN VIOLATION: Document atomicity requires all chunks visible at same epoch. " +
            "Partial visibility indicates: (1) DocumentBatchPublisher not publishing atomically, " +
            "(2) Chunks written across multiple epochs, (3) Write log fsync not synchronized with epoch advance.",
            documentId, expectedChunks, foundChunks
        );
    }

    // ==================== Deletion Correctness Assertions ====================

    /**
     * Creates a message asserting deleted key must not be found.
     *
     * @param key The deleted key
     * @param filterType The filter type (e.g., "Cuckoo", "HierarchicalCuckoo")
     * @return A specific assertion message
     */
    public static String deletedKeyMustNotBeFound(String key, String filterType) {
        return String.format(
            "Deleted key '%s' must NOT be found by %s filter. " +
            "DOMAIN VIOLATION: After delete(key), contains(key) MUST return false. " +
            "False positive for deleted key indicates: (1) delete() not removing fingerprint, " +
            "(2) Fingerprint collision with another key (rare at normal load factors), " +
            "(3) Hierarchical deletion not propagating to all levels.",
            key, filterType
        );
    }

    /**
     * Creates a message asserting false positive rate must be within target.
     *
     * @param actualRate The actual false positive rate
     * @param targetRate The target false positive rate
     * @param testCount Number of tests performed
     * @param falsePositiveCount Number of false positives found
     * @return A specific assertion message
     */
    public static String falsePositiveRateWithinTarget(
            double actualRate, double targetRate, int testCount, int falsePositiveCount) {
        return String.format(
            "False positive rate must be within target. " +
            "Actual: %.4f%% (%d/%d), Target: %.4f%%. " +
            "EXCESSIVE FP RATE INDICATES: (1) Fingerprint size too small, " +
            "(2) Load factor too high, (3) Hash function not distributing well, " +
            "(4) Bucket size too small. Consider: increase fingerprint bits, reduce load factor.",
            actualRate * 100, falsePositiveCount, testCount, targetRate * 100
        );
    }

    // ==================== LLM Fallback Assertions ====================

    /**
     * Creates a message asserting LLM fallback must return results.
     *
     * @param llmAvailable Whether LLM was available
     * @param resultCount Number of results returned
     * @return A specific assertion message
     */
    public static String llmFallbackMustReturnResults(boolean llmAvailable, int resultCount) {
        return String.format(
            "Query must return results even when LLM unavailable (vector-only fallback). " +
            "LLM available=%b, Results=%d. " +
            "DOMAIN VIOLATION: LLM unavailability must not prevent query results. " +
            "No results with LLM unavailable indicates: (1) QueryPipeline not catching LLMException, " +
            "(2) Circuit breaker blocking entire query instead of just LLM scoring, " +
            "(3) Vector search not executing when LLM scoring requested but unavailable.",
            llmAvailable, resultCount
        );
    }

    /**
     * Creates a message asserting circuit breaker must open after failures.
     *
     * @param failureCount Number of consecutive failures
     * @param failureThreshold The failure threshold
     * @param currentState The current circuit breaker state
     * @return A specific assertion message
     */
    public static String circuitBreakerMustOpen(int failureCount, int failureThreshold, String currentState) {
        return String.format(
            "Circuit breaker must open after %d consecutive failures (threshold=%d). " +
            "Current state: %s. " +
            "DOMAIN VIOLATION: Circuit breaker must transition CLOSED -> OPEN after threshold failures. " +
            "State not OPEN indicates: (1) Failure count not incrementing, " +
            "(2) Failure threshold misconfigured, (3) Success incorrectly resetting count.",
            failureCount, failureThreshold, currentState
        );
    }

    /**
     * Creates a message asserting response must indicate degraded state.
     *
     * @param reason The reason for degraded state
     * @param actualDegraded The actual degraded flag value
     * @param coverage The coverage value
     * @return A specific assertion message
     */
    public static String responseMustIndicateDegraded(String reason, boolean actualDegraded, double coverage) {
        return String.format(
            "Response must indicate degraded state when %s. " +
            "degraded=%b, coverage=%.4f. " +
            "DOMAIN VIOLATION: Degraded conditions must be reflected in response metadata. " +
            "Check: IndexCoverage.degraded calculation and QueryResponse assembly.",
            reason, actualDegraded, coverage
        );
    }

    // ==================== Query Response Summary ====================

    /**
     * Creates a detailed summary of a query response for debugging.
     *
     * @param response The query response
     * @return A detailed summary string
     */
    public static String queryResponseSummary(QueryResponse response) {
        if (response == null) {
            return "QueryResponse is null";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("QueryResponse summary:\n");
        sb.append("  - Results: ").append(response.results().size()).append("\n");
        sb.append("  - Timed out: ").append(response.timedOut()).append("\n");
        sb.append("  - Query time: ").append(response.queryTimeMs()).append("ms\n");

        IndexCoverage coverage = response.coverage();
        if (coverage != null) {
            sb.append("  - Coverage:\n");
            sb.append("      estimated: ").append(coverage.estimatedCorpusCoverage()).append("\n");
            sb.append("      degraded: ").append(coverage.degraded()).append("\n");
            sb.append("      totalSegments: ").append(coverage.totalSegments()).append("\n");
            sb.append("      activeSegments: ").append(coverage.activeSegments()).append("\n");
            sb.append("      indexingSegments: ").append(coverage.indexingSegments()).append("\n");
            sb.append("      warmingSegments: ").append(coverage.warmingSegments()).append("\n");
            sb.append("      tailScanEntries: ").append(coverage.tailScanEntries()).append("\n");
        } else {
            sb.append("  - Coverage: null\n");
        }

        return sb.toString();
    }

    /**
     * Creates a summary of result scores for debugging.
     *
     * @param results The query results
     * @param maxItems Maximum items to include
     * @return A summary string
     */
    public static String resultScoresSummary(List<QueryResultItem> results, int maxItems) {
        if (results == null || results.isEmpty()) {
            return "No results";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Result scores (showing first ").append(Math.min(maxItems, results.size())).append("):\n");

        for (int i = 0; i < Math.min(maxItems, results.size()); i++) {
            QueryResultItem item = results.get(i);
            sb.append(String.format("  [%d] docId=%d, score=%.4f",
                i, item.documentId(), item.score()));
            if (item.documentIdStr() != null) {
                sb.append(", docIdStr='").append(item.documentIdStr()).append("'");
            }
            sb.append("\n");
        }

        if (results.size() > maxItems) {
            sb.append("  ... and ").append(results.size() - maxItems).append(" more\n");
        }

        return sb.toString();
    }

    // ==================== Concurrent Operation Assertions ====================

    /**
     * Creates a message asserting no exceptions during concurrent operations.
     *
     * @param operationType The operation type (e.g., "ingestion/query")
     * @param exceptionCount Number of exceptions
     * @param exceptionMessages Sample exception messages
     * @return A specific assertion message
     */
    public static String noExceptionsDuringConcurrentOperations(
            String operationType, int exceptionCount, List<String> exceptionMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "No exceptions should occur during concurrent %s. " +
            "Found %d exceptions.",
            operationType, exceptionCount
        ));

        if (!exceptionMessages.isEmpty()) {
            sb.append(" Sample exceptions:\n");
            int maxSamples = Math.min(5, exceptionMessages.size());
            for (int i = 0; i < maxSamples; i++) {
                sb.append("  - ").append(exceptionMessages.get(i)).append("\n");
            }
            if (exceptionMessages.size() > maxSamples) {
                sb.append("  ... and ").append(exceptionMessages.size() - maxSamples).append(" more\n");
            }
        }

        sb.append("\nDOMAIN VIOLATION: Concurrent operations must complete without exceptions. " +
            "Check: (1) Thread-safe data structures, (2) Proper synchronization, " +
            "(3) No race conditions in shared state.");

        return sb.toString();
    }

    /**
     * Creates a message asserting results should improve progressively.
     *
     * @param stage The stage number
     * @param previousCount Result count at previous stage
     * @param currentCount Result count at current stage
     * @return A specific assertion message
     */
    public static String resultsShouldImproveProgressively(int stage, int previousCount, int currentCount) {
        return String.format(
            "Result count at stage %d (%d) should be >= stage %d (%d). " +
            "DOMAIN VIOLATION: Results should improve (or stay same) as more data is indexed. " +
            "Decreasing results indicate: (1) Segments becoming invisible, " +
            "(2) Data being tombstoned incorrectly, (3) Query returning different data each time.",
            stage, currentCount, stage - 1, previousCount
        );
    }
}
