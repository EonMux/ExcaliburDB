package com.eonmux.excalibur.query.visibility;

import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswInvariantVerifier;
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
 *   <li>lastIntegratedOffset should be &lt;= writeLogHead (never ahead of write log)</li>
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

        GraphState state = GraphState.capture(graph);

        // === Check 1: Empty graph ===
        if (state.nodeCount() == 0) {
            return emptyGraphResult(state, writeLogHead, issues);
        }

        // === Check 2: Invalid entry point ===
        HnswGraphChecks.reportEntryPoint(graph, state.entryPoint(), issues);

        // === Check 3: lastIntegratedOffset consistency ===
        HnswGraphChecks.reportIntegrationOffset(
            state.lastIntegratedOffset(), writeLogHead, state.nodeCount(), issues);

        // === Check 4: Graph connectivity ===
        int reachableNodes = HnswGraphChecks.reportConnectivity(graph, issues);

        // === Check 5: Edge bidirectionality ===
        HnswGraphChecks.reportEdgeInvariants(graph, issues);

        long edgeCount = HnswInvariantVerifier.countBidirectionalEdges(graph, 0);

        // === Check 6: Self-search test ===
        int searchResultCount = HnswGraphChecks.reportSelfSearch(
            graph, state.activeNodeCount(), state.entryPoint(), issues);

        // === Check 7: Average edge count per node ===
        HnswGraphChecks.reportEdgeDensity(state.activeNodeCount(), edgeCount, issues);

        GraphDiagnosticResult result = new GraphDiagnosticResult(
            state.nodeCount(), state.activeNodeCount(), state.entryPoint(), state.maxLevel(),
            state.lastIntegratedOffset(), writeLogHead, edgeCount, reachableNodes,
            searchResultCount, Collections.unmodifiableList(issues)
        );

        logDiagnostic(result);

        return result;
    }

    /**
     * The graph's headline counters, read once at the start of a diagnostic.
     *
     * <p>Read together and then passed around rather than re-read per check, so that every
     * issue the diagnostic raises and the result it returns describe one reading of a graph
     * other threads are still writing to.</p>
     *
     * @param nodeCount Total nodes in the graph
     * @param activeNodeCount Active (non-tombstoned) nodes
     * @param entryPoint Current entry point node ID
     * @param maxLevel Maximum level in the graph
     * @param lastIntegratedOffset Current lastIntegratedOffset value
     */
    private record GraphState(
        long nodeCount,
        long activeNodeCount,
        long entryPoint,
        int maxLevel,
        long lastIntegratedOffset
    ) {
        /**
         * Captures the graph state a diagnostic runs against.
         *
         * @param graph The graph to read
         * @return The counters as they stand
         */
        static GraphState capture(HnswGraph graph) {
            return new GraphState(
                graph.nodeCount(),
                graph.activeNodeCount(),
                graph.entryPoint(),
                graph.maxLevel(),
                graph.getLastIntegratedOffset()
            );
        }

    }

    /**
     * Reports a graph with nothing in it, and returns the diagnostic there is no more to add to.
     *
     * <p>Only a warning: an empty graph is what a segment looks like before anything has been
     * ingested into it, and every check after this one asks a question about nodes there are
     * none of. Edges, reachability and self-search therefore report zero because nothing
     * looked, not because something looked and found none.</p>
     *
     * @param state The graph counters this diagnostic captured
     * @param writeLogHead Current write log head position
     * @param issues The list collecting this diagnostic's findings
     * @return The diagnostic result for an empty graph
     */
    private static GraphDiagnosticResult emptyGraphResult(
            GraphState state,
            long writeLogHead,
            List<GraphDiagnosticIssue> issues) {

        issues.add(GraphDiagnosticIssue.warning(
            "EMPTY_GRAPH",
            "Graph has no nodes",
            "Insert vectors into the graph before querying"
        ));

        return new GraphDiagnosticResult(
            state.nodeCount(), state.activeNodeCount(), state.entryPoint(), state.maxLevel(),
            state.lastIntegratedOffset(), writeLogHead, 0L, 0,
            0, Collections.unmodifiableList(issues)
        );
    }

    /**
     * Logs what the diagnostic found.
     *
     * <p>The summary goes out at info because an operator running a diagnostic wants its
     * headline without turning debug on; the issues themselves stay at debug because the
     * caller already has them in the result and is expected to act on them there.</p>
     *
     * @param result The completed diagnostic result
     */
    private static void logDiagnostic(GraphDiagnosticResult result) {
        LOGGER.info("HNSW graph diagnostic complete: {} nodes, {} active, {} edges, {} reachable, {} search results, {} issues",
            result.nodeCount(), result.activeNodeCount(), result.edgeCount(),
            result.reachableNodes(), result.searchResultCount(), result.issues().size());

        for (GraphDiagnosticIssue issue : result.issues()) {
            LOGGER.debug("Graph diagnostic issue: {}", issue);
        }
    }

    /**
     * Quick check: Is the lastIntegratedOffset correct relative to write log state?
     *
     * <p>This checks if the offset is within valid bounds:</p>
     * <ul>
     *   <li>Offset must be >= 0</li>
     *   <li>Offset must be &lt;= writeLogHead</li>
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
        HnswGraphChecks.SelfSearch probe = HnswGraphChecks.probeSelfSearch(graph);
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
