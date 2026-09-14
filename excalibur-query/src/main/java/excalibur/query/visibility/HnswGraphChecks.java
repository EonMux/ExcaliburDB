package excalibur.query.visibility;

import excalibur.query.visibility.HnswGraphDiagnostic.GraphDiagnosticIssue;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswInvariantVerifier;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The individual health checks {@link HnswGraphDiagnostic#runFullDiagnostic} runs over a graph.
 *
 * <p>Each check appends what it found to the caller's issue list and, where it also measures
 * something the result reports, returns that measurement. They are kept in the order the
 * diagnostic runs them: an issue list read top to bottom follows the graph from its entry point
 * out through its edges to whether it can answer a query at all.</p>
 */
final class HnswGraphChecks {

    /**
     * Named for the diagnostic rather than for this class, so that moving the checks out of it
     * does not move the log lines out from under the category operators filter on.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(HnswGraphDiagnostic.class);

    private HnswGraphChecks() {
        // Utility class - no instances
    }

    /**
     * Reports whether the graph has a usable node to start a search from.
     *
     * <p>A graph with nodes and no way in cannot answer anything, so all three findings are
     * critical: there is no degraded mode where a search starts somewhere else.</p>
     *
     * @param graph The graph being diagnosed
     * @param entryPoint The graph's current entry point node ID
     * @param issues The list collecting this diagnostic's findings
     */
    static void reportEntryPoint(HnswGraph graph, long entryPoint, List<GraphDiagnosticIssue> issues) {
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
    }

    /**
     * Reports how far the graph's integration watermark has drifted from the write log.
     *
     * <p>The two directions mean opposite things. An offset ahead of the log is corruption,
     * because it claims entries were integrated that were never written. An offset behind the
     * node count is routine: it means a tail scan is needed to cover the entries the graph has
     * not absorbed yet, which the query path already does.</p>
     *
     * @param lastIntegratedOffset The graph's integration watermark
     * @param writeLogHead Current write log head position
     * @param nodeCount Total nodes in the graph
     * @param issues The list collecting this diagnostic's findings
     */
    static void reportIntegrationOffset(
            long lastIntegratedOffset,
            long writeLogHead,
            long nodeCount,
            List<GraphDiagnosticIssue> issues) {

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
    }

    /**
     * Reports whether every active node can still be walked to from the entry point.
     *
     * <p>A graph that fails this reports no reachable count at all rather than a partial one.
     * The verifier stops describing the graph once it finds a violation, and a count taken
     * from an abandoned traversal would read as a connectivity ratio the graph never had.</p>
     *
     * @param graph The graph being diagnosed
     * @param issues The list collecting this diagnostic's findings
     * @return The number of nodes reachable from the entry point, or 0 when connectivity is
     *         broken
     */
    static int reportConnectivity(HnswGraph graph, List<GraphDiagnosticIssue> issues) {
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
            return 0;
        }

        return connectivityResult.edgesChecked(); // edgesChecked is actually reachable count
    }

    /**
     * Reports the graph invariants the verifier found broken.
     *
     * @param graph The graph being diagnosed
     * @param issues The list collecting this diagnostic's findings
     */
    static void reportEdgeInvariants(HnswGraph graph, List<GraphDiagnosticIssue> issues) {
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
    }

    /**
     * Reports whether the graph can find its own vectors.
     *
     * <p>Skipped entirely for a graph with nothing active to search for or no way in, because
     * the earlier checks have already said so and a probe here would only restate it.</p>
     *
     * @param graph The graph being diagnosed
     * @param activeNodeCount Active (non-tombstoned) nodes, which sets how many hits a healthy
     *                        graph should return
     * @param entryPoint The graph's current entry point node ID
     * @param issues The list collecting this diagnostic's findings
     * @return How many results the probe returned, 0 when it did not run
     */
    static int reportSelfSearch(
            HnswGraph graph,
            long activeNodeCount,
            long entryPoint,
            List<GraphDiagnosticIssue> issues) {

        // Try to search the graph using one of its own vectors
        if (activeNodeCount <= 0 || entryPoint < 0) {
            return 0;
        }

        SelfSearch selfSearch = probeSelfSearch(graph);
        int searchResultCount = selfSearch.results();
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

        return searchResultCount;
    }

    /**
     * Reports a graph whose nodes are too sparsely linked to search well.
     *
     * <p>Only a warning, and only about recall: a thinly connected graph still answers, it
     * just misses neighbours a denser one would have reached.</p>
     *
     * @param activeNodeCount Active (non-tombstoned) nodes
     * @param edgeCount Bidirectional edges at level 0
     * @param issues The list collecting this diagnostic's findings
     */
    static void reportEdgeDensity(long activeNodeCount, long edgeCount, List<GraphDiagnosticIssue> issues) {
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
    record SelfSearch(boolean ran, int results, String reason) {

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
    static SelfSearch probeSelfSearch(HnswGraph graph) {
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
}
