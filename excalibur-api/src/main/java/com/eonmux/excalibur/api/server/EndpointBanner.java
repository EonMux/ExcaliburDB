package com.eonmux.excalibur.api.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The routes this node serves, as an operator reads them at startup.
 *
 * <p>Derived from the same conditions {@link EndpointRegistrar} branches on, and
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
     * Which of the conditional routes this node was wired for.
     *
     * @param erasure        Whether {@code /v1/erasure} is registered
     * @param graphTenanted  Whether the full tenant-backed graph surface is registered
     * @param graphQueryOnly Whether only {@code /v1/graph/query} is registered, from a pipeline
     *                       wired without tenants
     * @param ingestion      Whether {@code POST /v1/documents} has a pipeline behind it; the
     *                       route is registered either way and answers 503 without one, and a
     *                       banner naming it on a node that cannot serve it would be a lie
     * @param documentListing Whether {@code GET /v1/documents/list} is registered, which it is
     *                       only on a node keeping a document registry
     */
    record Routes(boolean erasure, boolean graphTenanted, boolean graphQueryOnly,
                  boolean ingestion, boolean documentListing) {
    }

    /**
     * The banner for a node wired the given way.
     *
     * @param routes Which conditional routes are registered
     * @return One line per route, aligned on the description
     * @throws NullPointerException if routes is null
     */
    static List<String> lines(Routes routes) {
        Objects.requireNonNull(routes, "routes cannot be null");
        List<String> lines = new ArrayList<>(23);
        lines.add(route("GET", "/health", "Liveness (unauthenticated)"));
        // The route a probe is actually pointed at: unauthenticated like /health, and the
        // only one that answers 503 rather than describing the problem in a 200.
        lines.add(route("GET", "/health/ready", "Readiness, 200 or 503 (unauthenticated)"));
        lines.add(route("GET", "/metrics", "Prometheus text exposition"));
        lines.add(route("GET", "/v1/stats", "System statistics"));
        lines.add(route("GET", "/v1/version", "Which build this node is running"));
        lines.add(route("POST", "/v1/query", "Query execution"));
        lines.add(route("DELETE", "/v1/query/{query_id}", "Cancel an in-progress query"));
        lines.add(route("GET", "/v1/documents", "Read one chunk's text"));
        lines.add(route("POST", "/v1/documents/fetch", "Read the text of a page of chunks"));
        if (routes.documentListing()) {
            lines.add(route("GET", "/v1/documents/list",
                "Which documents this node holds, or one document's chunks"));
        }
        if (routes.ingestion()) {
            lines.add(route("POST", "/v1/documents", "Index documents sent in the request"));
        }
        lines.add(route("POST", "/v1/ingest", "Register an ingestion source"));
        lines.add(route("PUT", "/v1/ingest", "Replace a registered source's configuration"));
        lines.add(route("GET", "/v1/ingest", "Read back what each source was registered as"));
        lines.add(route("DELETE", "/v1/ingest", "Stop crawling a source"));
        lines.add(route("DELETE", "/v1/ingest/run", "Stop the ingestion run, keep the source"));
        lines.add(route("GET", "/v1/ingest/status", "Ingestion status per source"));
        lines.add(route("POST", "/v1/reindex", "Force a reindex"));
        lines.add(route("DELETE", "/v1/reindex", "Ask a running reindex to stop"));
        lines.add(route("GET", "/v1/reindex/status", "Reindex status per source"));
        lines.add(route("GET", "/v1/query/status", "Queries running right now"));
        lines.add(route("POST", "/v1/drain", "Report unready, to empty the load balancer"));
        lines.add(route("DELETE", "/v1/drain", "Report ready again"));
        lines.add(route("POST", "/v1/compact", "Reclaim tombstoned space now"));
        lines.add(route("DELETE", "/v1/compact", "Stop the compaction in progress"));
        lines.add(route("GET", "/v1/compact/status", "What the last compaction did"));
        if (routes.erasure()) {
            lines.add(route("POST", "/v1/erasure", "Remove named documents from the corpus"));
            lines.add(route("GET", "/v1/erasure/{request_id}",
                "Where one document erasure stands"));
        }
        if (routes.graphTenanted() || routes.graphQueryOnly()) {
            lines.add(route("POST", "/v1/graph/query", "Answer a question from the graph"));
        }
        if (routes.graphTenanted()) {
            lines.add(route("POST", "/v1/graph/build", "Publish the staged facts as the graph"));
            lines.add(route("DELETE", "/v1/graph/build", "Stop the graph build in progress"));
            lines.add(route("GET", "/v1/graph/build/status", "What the last graph build did"));
            lines.add(route("POST", "/v1/graph/erasure", "Record an obligation to erase"));
            lines.add(route("GET", "/v1/graph/erasure/{request_id}",
                "Where one erasure obligation stands"));
            lines.add(route("GET", "/v1/graph/entities", "Entities in the served graph"));
            lines.add(route("GET", "/v1/graph/entities/{id}",
                "One entity, its edges counted"));
            lines.add(route("GET", "/v1/graph/entities/{id}/edges",
                "One entity's edges, with provenance"));
            lines.add(route("POST", "/v1/graph/facts", "Stage facts a caller states"));
            lines.add(route("DELETE", "/v1/graph/facts/{fact_id}", "Retract an asserted fact"));
            lines.add(route("GET", "/v1/graph/namespaces", "Every namespace on this node"));
            lines.add(route("PUT", "/v1/graph/namespaces/{namespace}", "Create a namespace"));
            lines.add(route("DELETE", "/v1/graph/namespaces/{namespace}",
                "Drop a namespace entirely"));
        }
        return List.copyOf(lines);
    }

    /** Widths chosen for the longest method and the longest path above. */
    private static String route(String method, String path, String description) {
        return String.format("  %-6s %-32s - %s", method, path, description);
    }
}
