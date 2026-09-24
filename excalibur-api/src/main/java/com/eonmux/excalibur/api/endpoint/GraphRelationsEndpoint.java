package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphRagHolder;
import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.QueryParams;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.graph.browse.CatalogPage;
import com.eonmux.excalibur.graph.browse.CatalogRelation;
import com.eonmux.excalibur.graph.browse.GraphCatalog;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code GET /v1/graph/relations} - the served graph's relation vocabulary.
 *
 * <p>Several things are keyed by a relation's label: {@code excalibur.graph.single-valued-relations},
 * the {@code relation} filter of {@code GET /v1/graph/entities/{id}/edges}, and the relation a
 * question names, which narrows a graph query's walk. Each matches the label the graph stores,
 * which is whatever the extractor chose, cleaned. This route says what those labels are, so a
 * setting or a filter can be written against the graph rather than guessed at.</p>
 *
 * <h3>Request</h3>
 * <pre>
 * GET /v1/graph/relations?limit=100&amp;cursor=...
 * </pre>
 * <p>The namespace comes from the {@value GraphTenants#NAMESPACE_HEADER} header. {@code limit}
 * is {@value #DEFAULT_LIMIT} by default and at most {@value GraphCatalog#MAX_LIMIT};
 * {@code cursor} continues after the {@code next_cursor} of the previous page, which is null on
 * the last one. A cursor names the last relation returned, so one carried across a rebuild
 * resumes after it.</p>
 *
 * <h3>Response</h3>
 * <pre>
 * {"namespace":"acme",
 *  "relations":[{"relation":"works_at","relation_id":3,"edges":120}],
 *  "next_cursor":null}
 * </pre>
 * <p>Every relation at least one served edge uses, in label order, with how many edges use it
 * over all of valid time. A relation whose every fact was erased is not listed. Counted once per
 * served graph, the first time it is asked, and kept until the graph is replaced.</p>
 *
 * <h3>No graph</h3>
 * <p>Answered 503 exactly where {@code /v1/graph/query} and {@code /v1/graph/entities} are, and
 * for their reason: a reply that told a namespace with no graph apart from one that does not
 * exist would tell one tenant which namespaces the others have.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; each request holds its own lease and reading.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphRelationsEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphRelationsEndpoint.class);

    /** The route this endpoint serves. */
    public static final String PATH = "/v1/graph/relations";

    /** Relations per page when the request names no limit. */
    static final int DEFAULT_LIMIT = 100;

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces whose served graphs are read
     */
    public GraphRelationsEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed; use GET");
            return;
        }
        String query = exchange.getRequestURI().getRawQuery();
        int limit;
        try {
            limit = limit(QueryParams.value(query, "limit"));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        }
        String cursor = QueryParams.value(query, "cursor");
        String after = cursor == null || cursor.isEmpty() ? null : cursor;
        NamespaceId namespace;
        try {
            namespace = GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            ResponseBody.sendError(exchange, 400, ErrorCode.INVALID_NAMESPACE, e.getMessage());
            return;
        }

        Optional<NamespaceRegistry.Lease<GraphTenant>> borrowed;
        try {
            borrowed = tenants.borrowIfPresent(namespace);
        } catch (RuntimeException e) {
            LOGGER.error("graph relation read failed for namespace {}", namespace, e);
            sendError(exchange, 500, "Internal Server Error");
            return;
        }
        if (borrowed.isEmpty()) {
            sendNoGraph(exchange);
            return;
        }

        String body;
        try (NamespaceRegistry.Lease<GraphTenant> lease = borrowed.get()) {
            Optional<GraphRagHolder.Reading> reading = lease.resource().holder().acquire();
            if (reading.isEmpty()) {
                sendNoGraph(exchange);
                return;
            }
            // Released before the response is written, as the query releases its reading: a
            // slow client must not keep a replaced graph mapped.
            try (GraphRagHolder.Reading held = reading.get()) {
                body = body(namespace, held.catalog().relations(limit, after));
            }
        } catch (IllegalArgumentException e) {
            // The cursor, which only the catalog can tell is not one it issued.
            sendError(exchange, 400, e.getMessage());
            return;
        } catch (RuntimeException e) {
            LOGGER.error("graph relation read failed for namespace {}", namespace, e);
            sendError(exchange, 500, "Internal Server Error");
            return;
        }
        ResponseBody.sendJson(exchange, 200, body);
    }

    /**
     * Reads the page size.
     *
     * @param raw What the request carried, or null
     * @return The limit to use
     * @throws IllegalArgumentException if it is present and not a whole number in range
     */
    static int limit(String raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(raw.trim());
            if (limit >= 1 && limit <= GraphCatalog.MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException notANumber) {
            // Answered below, with the range.
        }
        throw new IllegalArgumentException("'limit' must be a whole number from 1 to "
            + GraphCatalog.MAX_LIMIT + ", got \"" + raw + "\"");
    }

    private static String body(NamespaceId namespace, CatalogPage<CatalogRelation> page) {
        List<CatalogRelation> items = page.items();
        StringBuilder json = new StringBuilder(64 + items.size() * 64)
            .append("{\"namespace\":").append(GraphCatalogJson.quoted(namespace.value()))
            .append(",\"relations\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(GraphCatalogJson.relation(items.get(i)));
        }
        return json.append("],\"next_cursor\":")
            .append(page.nextCursor() == null ? "null" : GraphCatalogJson.quoted(page.nextCursor()))
            .append('}').toString();
    }

    private static void sendError(HttpExchange exchange, int status, String message)
            throws IOException {
        ResponseBody.sendJson(exchange, status, "{\"error\":" + GraphCatalogJson.quoted(message)
            + "}");
    }

    private static void sendNoGraph(HttpExchange exchange) throws IOException {
        ResponseBody.sendError(exchange, 503, ErrorCode.GRAPH_NOT_SERVED,
            GraphQueryEndpoint.NO_GRAPH);
    }
}
