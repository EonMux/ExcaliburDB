package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.QueryParams;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * {@code GET /v1/graph/erasure} - the erasure obligations of one namespace, a page at a time.
 *
 * <p>{@code GET /v1/graph/erasure/{request_id}} answers a caller who kept the id
 * {@code POST /v1/graph/erasure} handed back. This answers the one who did not - an operator
 * asked what a namespace still owes, or a client that lost its ids to a crash - without guessing
 * numbers.</p>
 *
 * <h3>Request</h3>
 * <pre>
 * GET /v1/graph/erasure?state=pending&amp;limit=100&amp;cursor=17
 * </pre>
 * <p>The namespace comes from the {@value GraphTenants#NAMESPACE_HEADER} header, as it does on
 * the POST. {@code state} is {@code pending} or {@code discharged}, and absent lists both.
 * {@code limit} bounds the page, {@value #DEFAULT_LIMIT} by default and at most
 * {@value #MAX_LIMIT}; a larger one is refused rather than trimmed. {@code cursor} continues
 * after the {@code next_cursor} of the previous page, which is null on the last one. Requests
 * are listed in id order, which is the order they were accepted, and a page starts strictly
 * after its cursor, so one discharged between two pages is neither skipped nor shown twice.</p>
 *
 * <h3>Response</h3>
 * <pre>
 * {"namespace": "acme",
 *  "requests": [{"request_id": 7, "state": "pending", "scope": "entity", "entity_id": 42,
 *                "requested_at_ms": 1736899200000, "discharged_at_ms": null,
 *                "detail_retained": true}],
 *  "count": 1, "total": 1, "next_cursor": null}
 * </pre>
 * <p>Each request is spelled exactly as the member route spells it. {@code count} is this
 * page's length and {@code total} how many the listing holds across every page.</p>
 *
 * <h3>What it lists</h3>
 * <p>Every outstanding request, and the {@value ErasureLog#RETAINED_COMPLETIONS} most recently
 * discharged - the ones whose scope and timestamps the log still keeps. An older discharged
 * request is not listed, since nothing of it is known but its id; the member route still answers
 * it {@code discharged}.</p>
 *
 * <h3>What it never says</h3>
 * <p>The log holds identifiers alone - an entity id, a source's type and numeric id, an extractor
 * version - never the name a request was made by, so a listing can no more recover an erased
 * name than the member route can. A namespace that does not exist lists nothing, exactly as one
 * that never owed anything does, so this route cannot be used to learn which namespaces exist,
 * and asking never creates or opens one.</p>
 *
 * <h3>Errors</h3>
 * <ul>
 *   <li>{@code 400} - {@code state}, {@code limit} or {@code cursor} is malformed, or the header
 *       does not name a usable namespace.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the tenants it reads are thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphErasureListEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphErasureListEndpoint.class);

    /** Requests a page carries when the request names no limit. */
    public static final int DEFAULT_LIMIT = 100;

    /** Most requests one page may carry. */
    public static final int MAX_LIMIT = 1000;

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces whose erasure logs are read
     */
    public GraphErasureListEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405, "{\"error\": \"Method Not Allowed - Use GET "
                + GraphErasureStatusEndpoint.COLLECTION_PATH + " to list erasure requests\"}");
            return;
        }

        String query = exchange.getRequestURI().getRawQuery();
        ErasureLog.State state;
        int limit;
        long after;
        try {
            state = state(QueryParams.value(query, "state"));
            limit = GraphNamespacesEndpoint.limit(QueryParams.value(query, "limit"));
            after = cursor(QueryParams.value(query, "cursor"));
        } catch (IllegalArgumentException e) {
            ResponseBody.sendError(exchange, 400, ErrorCode.INVALID_REQUEST, e.getMessage());
            return;
        }
        NamespaceId namespace;
        try {
            namespace = GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            ResponseBody.sendError(exchange, 400, ErrorCode.INVALID_NAMESPACE, e.getMessage());
            return;
        }

        ErasureLog.RequestPage page;
        try {
            page = tenants.erasureRequests(namespace, state, after, limit);
        } catch (IOException | RuntimeException e) {
            // Logged with detail, answered without: the message names the log's path.
            LOGGER.error("could not list the erasure requests of namespace {}",
                namespace.value(), e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
            return;
        }
        ResponseBody.sendJson(exchange, 200, body(namespace, page));
    }

    /**
     * Reads the state a listing is narrowed to.
     *
     * @param raw What the request carried, or null
     * @return The state, or null for both
     * @throws IllegalArgumentException if it names neither state
     */
    static ErasureLog.State state(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (ErasureLog.State state : ErasureLog.State.values()) {
            if (ErasureStatusJson.stateName(state).equals(raw.trim().toLowerCase(Locale.ROOT))) {
                return state;
            }
        }
        throw new IllegalArgumentException("'state' must be pending or discharged, got \"" + raw
            + "\"");
    }

    /**
     * Reads the id a page continues after.
     *
     * @param raw What the request carried, or null
     * @return The id, or 0 for the first page
     * @throws IllegalArgumentException if it is present and not a request id
     */
    static long cursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            long id = Long.parseLong(raw.trim());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException notANumber) {
            // Answered below.
        }
        throw new IllegalArgumentException("'cursor' must be the next_cursor of a previous "
            + "page, got \"" + raw + "\"");
    }

    private static String body(NamespaceId namespace, ErasureLog.RequestPage page) {
        StringJoiner requests = new StringJoiner(", ", "[", "]");
        for (ErasureLog.RequestStatus status : page.requests()) {
            requests.add(ErasureStatusJson.appendFields(new StringBuilder("{"), status)
                .append('}'));
        }
        return "{\"namespace\": \"" + JsonText.escape(namespace.value()) + "\""
            + ", \"requests\": " + requests
            + ", \"count\": " + page.requests().size()
            + ", \"total\": " + page.total()
            // Null rather than a cursor on the last page: a caller stops when it is told to.
            + ", \"next_cursor\": " + page.next().map(id -> "\"" + id + "\"").orElse("null")
            + "}";
    }
}
