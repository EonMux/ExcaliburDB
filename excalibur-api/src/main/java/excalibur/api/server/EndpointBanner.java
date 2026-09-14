package excalibur.api.server;

import java.util.ArrayList;
import java.util.List;

/**
 * The routes this node serves, as an operator reads them at startup.
 *
 * <p>Derived from the same three conditions {@link EndpointRegistrar} branches on, and
 * package-private so a test can hold the two against each other. The banner is the only
 * inventory of the HTTP surface an operator is given, so one that quietly omits a registered
 * route is worse than no banner at all.</p>
 *
 * @author ExcaliburDB
 */
final class EndpointBanner {

    private EndpointBanner() {
    }

    /**
     * The banner for a node wired the given way.
     *
     * @param erasureWired    Whether {@code /v1/erasure} is registered
     * @param graphTenanted   Whether the full tenant-backed graph surface is registered
     * @param graphQueryOnly  Whether only {@code /v1/graph/query} is registered, from a pipeline
     *                        wired without tenants
     * @param ingestionWired  Whether {@code POST /v1/documents} has a pipeline behind it; the
     *                        route is registered either way and answers 503 without one, and a
     *                        banner naming it on a node that cannot serve it would be a lie
     * @return One line per route, aligned on the description
     */
    static List<String> lines(boolean erasureWired, boolean graphTenanted,
                              boolean graphQueryOnly, boolean ingestionWired) {
        List<String> lines = new ArrayList<>(17);
        lines.add(route("GET", "/health", "Liveness and readiness (unauthenticated)"));
        lines.add(route("GET", "/v1/stats", "System statistics"));
        lines.add(route("POST", "/v1/query", "Query execution"));
        lines.add(route("DELETE", "/v1/query/{query_id}", "Cancel an in-progress query"));
        lines.add(route("GET", "/v1/documents", "Read one chunk's text"));
        lines.add(route("POST", "/v1/documents/fetch", "Read the text of a page of chunks"));
        if (ingestionWired) {
            lines.add(route("POST", "/v1/documents", "Index documents sent in the request"));
        }
        lines.add(route("POST", "/v1/ingest", "Register an ingestion source"));
        lines.add(route("DELETE", "/v1/ingest", "Stop crawling a source"));
        lines.add(route("GET", "/v1/ingest/status", "Ingestion status per source"));
        lines.add(route("POST", "/v1/reindex", "Force a reindex"));
        lines.add(route("DELETE", "/v1/reindex", "Ask a running reindex to stop"));
        lines.add(route("GET", "/v1/reindex/status", "Reindex status per source"));
        lines.add(route("POST", "/v1/compact", "Reclaim tombstoned space now"));
        lines.add(route("GET", "/v1/compact/status", "What the last compaction did"));
        if (erasureWired) {
            lines.add(route("POST", "/v1/erasure", "Remove named documents from the corpus"));
        }
        if (graphTenanted || graphQueryOnly) {
            lines.add(route("POST", "/v1/graph/query", "Answer a question from the graph"));
        }
        if (graphTenanted) {
            lines.add(route("POST", "/v1/graph/build", "Publish the staged facts as the graph"));
            lines.add(route("POST", "/v1/graph/erasure", "Record an obligation to erase"));
        }
        return List.copyOf(lines);
    }

    /** Widths chosen for the longest method and the longest path above. */
    private static String route(String method, String path, String description) {
        return String.format("  %-6s %-20s - %s", method, path, description);
    }
}
