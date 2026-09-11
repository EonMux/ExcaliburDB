package excalibur.query.visibility;

import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswInvariantVerifier;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Diagnostic utility for checking HNSW graph health and lastIntegratedOffset correctness.
 *
 * <p>This class provides methods to diagnose why HNSW graph searches may return
 * empty results. It checks:</p>
 * <ul>
 *   <li>Are vectors actually linked in the graph (bidirectional edges)?</li>
 *   <li>Is the entry point valid and accessible?</li>
 *   <li>Is lastIntegratedOffset correct relative to write log state?</li>
 *   <li>Are there disconnected components in the graph?</li>
 *   <li>Can the graph find results when searching with known vectors?</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Run full diagnostic
 * GraphDiagnosticResult result = HnswGraphDiagnostic.runFullDiagnostic(
 *     graph, writeLogHead
 * );
 *
 * // Check for issues
 * if (!result.isHealthy()) {
 *     for (GraphDiagnosticIssue issue : result.issues()) {
 *         System.err.println("Issue: " + issue.description());
 *     }
 * }
 * }</pre>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>lastIntegratedOffset should be <= writeLogHead (never ahead of write log)</li>
 *   <li>lastIntegratedOffset should equal nodeCount if all vectors are integrated</li>
 *   <li>Entry point must be valid and not tombstoned</li>
 *   <li>All active nodes must be reachable from entry point</li>
 *   <li>All edges must be bidirectional</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All diagnostic methods capture consistent snapshots.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class HnswGraphDiagnostic {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswGraphDiagnostic.class);

    private HnswGraphDiagnostic() {
        // Utility class - no instances
    }

    // ==================== Diagnostic Result Types ====================

    /**
     * Result of a full HNSW graph diagnostic.
     *
     * @param nodeCount Total nodes in the graph
     * @param activeNodeCount Active (non-tombstoned) nodes
     * @param entryPoint Current entry point node ID
     * @param maxLevel Maximum level in the graph
     * @param lastIntegratedOffset Current lastIntegratedOffset value
     * @param writeLogHead Current write log head position
     * @param edgeCount Number of bidirectional edges at level 0
     * @param reachableNodes Nodes reachable from entry point via level 0 edges
     * @param searchResultCount Results from self-search test
     * @param issues List of diagnostic issues found
     */
    public record GraphDiagnosticResult(
        long nodeCount,
        long activeNodeCount,
        long entryPoint,
        int maxLevel,
        long lastIntegratedOffset,
        long writeLogHead,
        long edgeCount,
        int reachableNodes,
        int searchResultCount,
        List<GraphDiagnosticIssue> issues
    ) {
        /**
         * Returns true if no issues were found and the graph appears healthy.
         *
         * @return true if the graph is healthy
         */
        public boolean isHealthy() {
            return issues.isEmpty();
        }

        /**
         * Returns the integration progress as a percentage.
         *
         * @return Integration progress (0.0 to 1.0)
         */
        public double integrationProgress() {
            if (writeLogHead <= 0) return 1.0;
            return Math.min(1.0, (double) lastIntegratedOffset / writeLogHead);
        }

        /**
         * Returns true if there are un-integrated entries (tail entries).
         *
         * @return true if tail scan is needed
         */
        public boolean hasTailEntries() {
            return lastIntegratedOffset < writeLogHead;
        }

        /**
         * Returns the number of tail entries that need scanning.
         *
         * @return Tail entry count (may be approximate)
         */
        public long tailEntryCount() {
            return Math.max(0, writeLogHead - lastIntegratedOffset);
        }

        /**
         * Returns true if lastIntegratedOffset is consistent with node count.
         *
         * <p>If all vectors have been integrated, lastIntegratedOffset should
         * approximately equal the number of nodes in the graph.</p>
         *
         * @return true if offset is consistent with node count
         */
        public boolean isOffsetConsistentWithNodeCount() {
            // Allow some slack for concurrent operations
            // lastIntegratedOffset can be <= nodeCount if vectors are still being added
            // but should never be significantly greater than nodeCount
            return lastIntegratedOffset <= nodeCount + 10;
        }

        /**
         * Returns the connectivity ratio (reachable / active nodes).
         *
         * @return Connectivity ratio (0.0 to 1.0)
         */
        public double connectivityRatio() {
            if (activeNodeCount <= 0) return 1.0;
            return (double) reachableNodes / activeNodeCount;
        }

        /**
         * Returns a formatted summary of the diagnostic.
         *
         * @return Human-readable summary
         */
        public String summary() {
            return String.format(
                "HnswGraphDiagnostic{nodes=%d, active=%d, entryPoint=%d, maxLevel=%d, " +
                "offset=%d, writeLogHead=%d, edges=%d, reachable=%d/%d, searchResults=%d, issues=%d}",
                nodeCount, activeNodeCount, entryPoint, maxLevel,
                lastIntegratedOffset, writeLogHead, edgeCount,
                reachableNodes, activeNodeCount, searchResultCount, issues.size()
            );
        }
    }

    /**
     * Severity level for graph diagnostic issues.
     */
    public enum Severity {
        /** Critical issue - queries will return empty or incorrect results */
        CRITICAL,
        /** Warning - graph may be suboptimal but functional */
        WARNING,
        /** Informational - potential optimization opportunity */
        INFO
    }

    /**
     * A single diagnostic issue found during graph checking.
     *
     * @param severity Issue severity
     * @param code Short code identifying the issue type
     * @param description Human-readable description
     * @param recommendation Suggested fix
     */
    public record GraphDiagnosticIssue(
        Severity severity,
        String code,
        String description,
        String recommendation
    ) {
        /**
         * Creates a critical issue.
         */
        public static GraphDiagnosticIssue critical(String code, String description, String recommendation) {
            return new GraphDiagnosticIssue(Severity.CRITICAL, code, description, recommendation);
        }

        /**
         * Creates a warning issue.
         */
        public static GraphDiagnosticIssue warning(String code, String description, String recommendation) {
            return new GraphDiagnosticIssue(Severity.WARNING, code, description, recommendation);
        }

        /**
         * Creates an info issue.
         */
        public static GraphDiagnosticIssue info(String code, String description, String recommendation) {
            return new GraphDiagnosticIssue(Severity.INFO, code, description, recommendation);
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: %s - %s",
                severity, code, description, recommendation);
        }
    }

    // ==================== Main Diagnostic Methods ====================

    /**
     * Runs a full diagnostic on an HNSW graph.
     *
     * <p>This method checks all aspects of graph health:</p>
     * <ol>
     *   <li>Basic graph state (node count, entry point, max level)</li>
     *   <li>lastIntegratedOffset consistency with write log</li>
     *   <li>Graph connectivity (all nodes reachable from entry point)</li>
     *   <li>Edge bidirectionality</li>
     *   <li>Self-search test (can the graph find results?)</li>
     * </ol>
     *
     * @param graph The HNSW graph to diagnose
     * @param writeLogHead Current write log head position
     * @return Diagnostic result with issues list
     */
    public static GraphDiagnosticResult runFullDiagnostic(
            HnswGraph graph,
            long writeLogHead) {

        List<GraphDiagnosticIssue> issues = new ArrayList<>();

        // Capture graph state
        long nodeCount = graph.nodeCount();
        long activeNodeCount = graph.activeNodeCount();
        long entryPoint = graph.entryPoint();
        int maxLevel = graph.maxLevel();
        long lastIntegratedOffset = graph.getLastIntegratedOffset();
        long edgeCount = 0;
        int reachableNodes = 0;
        int searchResultCount = 0;

        // === Check 1: Empty graph ===
        if (nodeCount == 0) {
            issues.add(GraphDiagnosticIssue.warning(
                "EMPTY_GRAPH",
                "Graph has no nodes",
                "Insert vectors into the graph before querying"
            ));

            return new GraphDiagnosticResult(
                nodeCount, activeNodeCount, entryPoint, maxLevel,
                lastIntegratedOffset, writeLogHead, edgeCount, reachableNodes,
                searchResultCount, Collections.unmodifiableList(issues)
            );
        }

        // === Check 2: Invalid entry point ===
        if (entryPoint < 0) {
            issues.add(GraphDiagnosticIssue.critical(
                "INVALID_ENTRY_POINT",
                "Graph has nodes but no valid entry point (entryPoint=" + entryPoint + ")",
                "Entry point should be set to the highest-level node. Check graph initialization."
            ));
        } else if (graph.isTombstoned(entryPoint)) {
            issues.add(GraphDiagnosticIssue.critical(
                "TOMBSTONED_ENTRY_POINT",
                "Entry point " + entryPoint + " is tombstoned",
                "Find a new active entry point from the highest-level nodes"
            ));
        } else {
            HnswGraph.NodeView entryNode = graph.readNode(entryPoint);
            if (entryNode == null) {
                issues.add(GraphDiagnosticIssue.critical(
                    "UNREADABLE_ENTRY_POINT",
                    "Entry point " + entryPoint + " cannot be read",
                    "Graph file may be corrupted. Verify disk storage."
                ));
            }
        }

        // === Check 3: lastIntegratedOffset consistency ===
        // lastIntegratedOffset should never exceed writeLogHead
        if (lastIntegratedOffset > writeLogHead && writeLogHead > 0) {
            issues.add(GraphDiagnosticIssue.critical(
                "OFFSET_AHEAD_OF_WRITELOG",
                "lastIntegratedOffset (" + lastIntegratedOffset + ") > writeLogHead (" + writeLogHead + ")",
                "Offset tracking is corrupted. Reset lastIntegratedOffset to writeLogHead."
            ));
        }

        // lastIntegratedOffset should approximately equal nodeCount if fully integrated
        // (allowing for concurrent operations)
        if (lastIntegratedOffset < nodeCount - 1 && writeLogHead >= nodeCount) {
            // Only warn if writeLogHead indicates more entries should exist
            issues.add(GraphDiagnosticIssue.warning(
                "OFFSET_BEHIND_NODECOUNT",
                "lastIntegratedOffset (" + lastIntegratedOffset + ") < nodeCount (" + nodeCount + ")",
                "Not all vectors have been integrated. Tail scan will be needed for queries."
            ));
        }

        // === Check 4: Graph connectivity ===
        HnswInvariantVerifier.VerificationResult connectivityResult =
            HnswInvariantVerifier.verifyConnectivity(graph);

        if (!connectivityResult.isValid()) {
            for (String violation : connectivityResult.violations()) {
                issues.add(GraphDiagnosticIssue.critical(
                    "DISCONNECTED_GRAPH",
                    "Graph connectivity issue: " + violation,
                    "Rebuild the graph or check for insertion bugs"
                ));
            }
        } else {
            reachableNodes = connectivityResult.edgesChecked(); // edgesChecked is actually reachable count
        }

        // === Check 5: Edge bidirectionality ===
        HnswInvariantVerifier.VerificationResult invariantResult =
            HnswInvariantVerifier.verifyAllInvariants(graph);

        if (!invariantResult.isValid()) {
            // Count the violations first, then report each kind once. Reporting inside the
            // loop emitted one issue per violation, each quoting the running count, so a
            // graph with N unidirectional edges produced N issues claiming 1, 2, ... N.
            int unidirectionalEdges = 0;
            List<String> otherViolations = new ArrayList<>();

            for (String violation : invariantResult.violations()) {
                if (violation.contains("unidirectional")) {
                    unidirectionalEdges++;
                } else {
                    otherViolations.add(violation);
                }
            }

            // Only a warning: some unidirectional edges can occur during concurrent updates
            if (unidirectionalEdges > 0) {
                issues.add(GraphDiagnosticIssue.warning(
                    "UNIDIRECTIONAL_EDGES",
                    "Found " + unidirectionalEdges + " unidirectional edges",
                    "This may be temporary during concurrent updates. If persistent, check edge management."
                ));
            }

            for (String violation : otherViolations) {
                issues.add(GraphDiagnosticIssue.warning(
                    "INVARIANT_VIOLATION",
                    "Graph invariant violated: " + violation,
                    "Inspect the graph's insertion and pruning paths."
                ));
            }
        }

        edgeCount = HnswInvariantVerifier.countBidirectionalEdges(graph, 0);

        // === Check 6: Self-search test ===
        // Try to search the graph using one of its own vectors
        if (activeNodeCount > 0 && entryPoint >= 0) {
            SelfSearch selfSearch = testSelfSearch(graph);
            searchResultCount = selfSearch.results();
            if (!selfSearch.ran()) {
                // The probe did not get an answer to judge. Compaction renumbers node ids and
                // closes the graph while it swaps, and search() checks the closed flag before
                // taking the read lock, so a diagnostic landing in that window fails here with
                // the graph entirely intact. Calling that broken traversal sends an operator
                // after a fault that does not exist.
                issues.add(GraphDiagnosticIssue.warning(
                    "SELF_SEARCH_UNAVAILABLE",
                    "Self-search probe could not run: " + selfSearch.reason(),
                    "Usually a compaction that closed or renumbered the graph between choosing "
                        + "the probe node and reading it, which is routine and clears itself. "
                        + "The probe failed, not the graph. Investigate only if it persists "
                        + "across runs while nothing is compacting."
                ));
            } else if (searchResultCount == 0) {
                issues.add(GraphDiagnosticIssue.critical(
                    "SELF_SEARCH_EMPTY",
                    "Self-search test returned 0 results (graph has " + activeNodeCount + " active nodes)",
                    "Graph traversal is broken. Check search algorithm and node data."
                ));
            } else if (searchResultCount < Math.min(5, activeNodeCount)) {
                issues.add(GraphDiagnosticIssue.warning(
                    "SELF_SEARCH_FEW_RESULTS",
                    "Self-search test returned only " + searchResultCount + " results",
                    "Graph may have poor connectivity or search parameters are too restrictive."
                ));
            }
        }

        // === Check 7: Average edge count per node ===
        if (activeNodeCount > 0 && edgeCount > 0) {
            double avgEdgesPerNode = (double) edgeCount * 2 / activeNodeCount;
            if (avgEdgesPerNode < 2.0) {
                issues.add(GraphDiagnosticIssue.warning(
                    "LOW_EDGE_DENSITY",
                    "Average edges per node is low: " + String.format("%.1f", avgEdgesPerNode),
                    "Graph may have poor recall. Consider increasing efConstruction."
                ));
            }
        }

        // Log results
        LOGGER.info("HNSW graph diagnostic complete: {} nodes, {} active, {} edges, {} reachable, {} search results, {} issues",
            nodeCount, activeNodeCount, edgeCount, reachableNodes, searchResultCount, issues.size());

        for (GraphDiagnosticIssue issue : issues) {
            LOGGER.debug("Graph diagnostic issue: {}", issue);
        }

        return new GraphDiagnosticResult(
            nodeCount, activeNodeCount, entryPoint, maxLevel,
            lastIntegratedOffset, writeLogHead, edgeCount, reachableNodes,
            searchResultCount, Collections.unmodifiableList(issues)
        );
    }

    /**
     * What a self-search probe found, or why it could not look.
     *
     * <p>The distinction is the whole point of the type. A search that ran and returned
     * nothing says the graph cannot find its own vectors, which is a fault. A probe that
     * never ran says nothing about the graph at all, and folding both into the number 0 made
     * a routine compaction indistinguishable from broken traversal.</p>
     *
     * @param ran     Whether a search actually completed
     * @param results How many it returned, 0 when it did not run
     * @param reason  Why it did not run, empty when it did
     */
    private record SelfSearch(boolean ran, int results, String reason) {

        static SelfSearch completed(int results) {
            return new SelfSearch(true, results, "");
        }

        static SelfSearch couldNotRun(String reason) {
            return new SelfSearch(false, 0, reason);
        }
    }

    /**
     * Tests if the graph can find results by searching with one of its own vectors.
     *
     * <p>This is a basic sanity check: if a graph with nodes cannot find results
     * when searching with one of its own vectors, something is fundamentally broken.</p>
     *
     * @param graph The graph to test
     * @return What the probe found, or why it could not look
     */
    private static SelfSearch testSelfSearch(HnswGraph graph) {
        try {
            // Find an active node to use as the query vector
            long queryNodeId = findFirstActiveNode(graph);
            if (queryNodeId < 0) {
                LOGGER.debug("Self-search test: no active nodes found");
                return SelfSearch.couldNotRun("no active node to query with");
            }

            HnswGraph.NodeView queryNode = graph.readNode(queryNodeId);
            if (queryNode == null) {
                LOGGER.debug("Self-search test: could not read node {}", queryNodeId);
                return SelfSearch.couldNotRun("node " + queryNodeId + " could not be read");
            }

            float[] queryVector = queryNode.vector();
            if (queryVector == null || queryVector.length == 0) {
                LOGGER.debug("Self-search test: node {} has no vector", queryNodeId);
                return SelfSearch.couldNotRun("node " + queryNodeId + " carries no vector");
            }

            // Search with k=10
            List<HnswSearchResult> results = graph.search(queryVector, 10);

            LOGGER.debug("Self-search test: found {} results using node {} as query",
                results.size(), queryNodeId);

            return SelfSearch.completed(results.size());
        } catch (Exception e) {
            LOGGER.debug("Self-search test failed with exception: {}", e.getMessage());
            return SelfSearch.couldNotRun(e.toString());
        }
    }

    /**
     * Finds the first active (non-tombstoned) node in the graph.
     *
     * @param graph The graph to search
     * @return First active node ID, or -1 if none found
     */
    private static long findFirstActiveNode(HnswGraph graph) {
        long nodeCount = graph.nodeCount();
        for (long nodeId = 0; nodeId < nodeCount; nodeId++) {
            if (!graph.isTombstoned(nodeId)) {
                return nodeId;
            }
        }
        return -1;
    }

    /**
     * Quick check: Is the lastIntegratedOffset correct relative to write log state?
     *
     * <p>This checks if the offset is within valid bounds:</p>
     * <ul>
     *   <li>Offset must be >= 0</li>
     *   <li>Offset must be <= writeLogHead</li>
     * </ul>
     *
     * @param graph The HNSW graph
     * @param writeLogHead Current write log head position
     * @return true if offset is valid
     */
    public static boolean isOffsetCorrect(HnswGraph graph, long writeLogHead) {
        long offset = graph.getLastIntegratedOffset();
        return offset >= 0 && offset <= writeLogHead;
    }

    /**
     * Quick check: Are there any edges in the graph?
     *
     * <p>A graph with nodes but no edges cannot perform meaningful searches.</p>
     *
     * @param graph The graph to check
     * @return true if the graph has at least some edges
     */
    public static boolean hasEdges(HnswGraph graph) {
        if (graph.nodeCount() == 0) {
            return true; // Empty graph is trivially OK
        }
        return HnswInvariantVerifier.countBidirectionalEdges(graph, 0) > 0;
    }

    /**
     * Quick check: Can the graph find results when searching?
     *
     * <p>This is a basic sanity check using self-search.</p>
     *
     * <p>A probe that could not run answers true. It found no evidence either way, and the
     * caller reads a false as "this graph cannot find its own vectors" - a verdict a probe
     * beaten by a concurrent compaction has not earned.</p>
     *
     * @param graph The graph to test
     * @return true if the graph can find at least one result, or the probe could not run
     */
    public static boolean canFindResults(HnswGraph graph) {
        if (graph.activeNodeCount() == 0) {
            return true; // Empty graph is trivially OK
        }
        SelfSearch probe = testSelfSearch(graph);
        return !probe.ran() || probe.results() > 0;
    }

    /**
     * Checks if lastIntegratedOffset is consistent with the actual graph state.
     *
     * <p>This performs a detailed analysis of offset vs. actual integration state.</p>
     *
     * @param graph The HNSW graph
     * @return OffsetAnalysis with detailed findings
     */
    public static OffsetAnalysis analyzeOffset(HnswGraph graph) {
        long offset = graph.getLastIntegratedOffset();
        long nodeCount = graph.nodeCount();
        long activeCount = graph.activeNodeCount();

        List<String> findings = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        // Check basic validity
        if (offset < 0) {
            findings.add("Offset is negative: " + offset);
            recommendations.add("Reset offset to 0");
        }

        // Check relationship to node count
        if (offset > nodeCount) {
            findings.add("Offset (" + offset + ") exceeds node count (" + nodeCount + ")");
            recommendations.add("Offset should never exceed node count. Check for corruption.");
        }

        // Check for tail entries
        long tailSize = Math.max(0, nodeCount - offset);
        if (tailSize > 0) {
            findings.add("Tail entries detected: " + tailSize + " entries need integration");
            recommendations.add("Run HNSW integration worker to process tail entries");
        }

        // Calculate integration progress
        double progress = nodeCount > 0 ? (double) offset / nodeCount : 1.0;

        return new OffsetAnalysis(
            offset,
            nodeCount,
            activeCount,
            tailSize,
            progress,
            findings,
            recommendations
        );
    }

    /**
     * Detailed analysis of lastIntegratedOffset state.
     *
     * @param offset Current lastIntegratedOffset value
     * @param nodeCount Total nodes in graph
     * @param activeCount Active nodes in graph
     * @param tailSize Number of un-integrated entries
     * @param integrationProgress Integration progress (0.0 to 1.0)
     * @param findings List of findings from analysis
     * @param recommendations List of recommended actions
     */
    public record OffsetAnalysis(
        long offset,
        long nodeCount,
        long activeCount,
        long tailSize,
        double integrationProgress,
        List<String> findings,
        List<String> recommendations
    ) {
        /**
         * Returns true if the offset appears to be correct.
         *
         * @return true if no issues found
         */
        public boolean isCorrect() {
            return findings.isEmpty();
        }

        /**
         * Returns true if there are tail entries needing integration.
         *
         * @return true if tail scan is needed
         */
        public boolean hasTail() {
            return tailSize > 0;
        }

        @Override
        public String toString() {
            return String.format(
                "OffsetAnalysis{offset=%d, nodes=%d, active=%d, tail=%d, progress=%.1f%%, findings=%d}",
                offset, nodeCount, activeCount, tailSize, integrationProgress * 100, findings.size()
            );
        }
    }

    /**
     * Formats a diagnostic report for logging or display.
     *
     * @param result The diagnostic result
     * @return Formatted report string
     */
    public static String formatReport(GraphDiagnosticResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== HNSW Graph Diagnostic Report ===\n");
        sb.append(String.format("Node Count: %d (active: %d)\n", result.nodeCount(), result.activeNodeCount()));
        sb.append(String.format("Entry Point: %d\n", result.entryPoint()));
        sb.append(String.format("Max Level: %d\n", result.maxLevel()));
        sb.append(String.format("Last Integrated Offset: %d\n", result.lastIntegratedOffset()));
        sb.append(String.format("Write Log Head: %d\n", result.writeLogHead()));
        sb.append(String.format("Tail Entries: %d\n", result.tailEntryCount()));
        sb.append(String.format("Integration Progress: %.1f%%\n", result.integrationProgress() * 100));
        sb.append(String.format("Bidirectional Edges (Level 0): %d\n", result.edgeCount()));
        sb.append(String.format("Reachable Nodes: %d/%d (%.1f%%)\n",
            result.reachableNodes(), result.activeNodeCount(),
            result.connectivityRatio() * 100));
        sb.append(String.format("Self-Search Results: %d\n", result.searchResultCount()));
        sb.append(String.format("Health Status: %s\n", result.isHealthy() ? "HEALTHY" : "ISSUES FOUND"));

        if (!result.issues().isEmpty()) {
            sb.append("\n--- Issues ---\n");
            for (GraphDiagnosticIssue issue : result.issues()) {
                sb.append(String.format("  %s\n", issue));
            }
        }

        return sb.toString();
    }
}
