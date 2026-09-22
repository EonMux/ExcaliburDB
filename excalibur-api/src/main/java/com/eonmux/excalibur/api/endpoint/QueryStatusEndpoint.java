package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.query.executor.QueryRegistry;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * HTTP endpoint for {@code GET /v1/query/status}: what this node is running now.
 *
 * <h2>Why it has to exist</h2>
 * <p>{@code DELETE /v1/query/{id}} has been able to cancel a query since the registry was
 * written, and there was no way to learn an id you had not issued yourself. So the one
 * operation for a query that will not finish was reachable only by the caller already waiting
 * on it - which, for a query that has hung, is the caller that has already given up and
 * disconnected. Ingestion and reindex both had status routes; queries did not, while the
 * registry had been tracking every figure this route serves and had no callers at all.</p>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "active": 2,
 *   "registered": 1841,
 *   "cancelled": 3,
 *   "queries": [
 *     {
 *       "query_id": "1f0c...",
 *       "correlation_id": "checkout-42",
 *       "started_at_ms": 1736899200000,
 *       "duration_ms": 91400,
 *       "cancelled": false
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Longest-running first. An operator reading this is looking for the query that will not
 * finish, and it is the first line rather than somewhere in a hash order.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; it renders a snapshot the registry builds atomically.</p>
 *
 * @author ExcaliburDB
 */
public final class QueryStatusEndpoint implements HttpHandler {

    /**
     * Most queries named individually in one response.
     *
     * <p>The counts above the list are always exact. A node running thousands of concurrent
     * queries would otherwise produce a response whose size is the problem being diagnosed,
     * and the ones past this bound are the short ones nobody is looking for.</p>
     */
    private static final int MAX_LISTED_QUERIES = 100;

    private final QueryRegistry queryRegistry;

    /**
     * Creates the endpoint over the registry every query is registered in.
     *
     * @param queryRegistry Where in-flight queries are tracked
     * @throws NullPointerException if queryRegistry is null
     */
    public QueryStatusEndpoint(QueryRegistry queryRegistry) {
        this.queryRegistry = Objects.requireNonNull(queryRegistry, "queryRegistry cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            ResponseBody.sendError(exchange, 405, "Method Not Allowed - Use GET");
            return;
        }
        ResponseBody.sendJson(exchange, 200, render());
    }

    /**
     * Renders the registry's current state.
     *
     * @return The response body
     */
    private String render() {
        List<QueryRegistry.ActiveQueryInfo> running = queryRegistry.getActiveQueries().stream()
            .sorted(Comparator.comparingLong(QueryRegistry.ActiveQueryInfo::durationMs).reversed())
            .toList();

        StringBuilder json = new StringBuilder(256 + running.size() * 128);
        json.append("{\"active\": ").append(queryRegistry.getActiveQueryCount())
            .append(", \"registered\": ").append(queryRegistry.getTotalQueriesRegistered())
            .append(", \"cancelled\": ").append(queryRegistry.getTotalQueriesCancelled())
            .append(", \"queries\": [");
        int written = 0;
        for (QueryRegistry.ActiveQueryInfo query : running) {
            if (written == MAX_LISTED_QUERIES) {
                break;
            }
            append(json, query, written == 0);
            written++;
        }
        json.append(']');
        if (running.size() > written) {
            json.append(", \"truncated\": ").append(running.size() - written);
        }
        return json.append('}').toString();
    }

    /**
     * Appends one query.
     *
     * @param json  The body being built
     * @param query The query
     * @param first Whether this is the first entry, which takes no separator
     */
    private static void append(StringBuilder json, QueryRegistry.ActiveQueryInfo query,
                               boolean first) {
        json.append(first ? "" : ", ")
            .append("{\"query_id\": \"").append(JsonText.escape(query.queryId()))
            .append("\", \"correlation_id\": \"").append(JsonText.escape(query.correlationId()))
            .append("\", \"started_at_ms\": ").append(query.startTimeMs())
            .append(", \"duration_ms\": ").append(query.durationMs())
            .append(", \"cancelled\": ").append(query.cancelled())
            .append('}');
    }
}
