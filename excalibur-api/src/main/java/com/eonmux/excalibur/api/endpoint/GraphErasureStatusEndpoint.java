package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code GET /v1/graph/erasure/{request_id}} - where one graph erasure request stands.
 *
 * <p>{@code POST /v1/graph/erasure} answers 202 with a {@code request_id}: the obligation is
 * durable, and the edges it selects leave the served graph at a later compaction. This is how the
 * caller learns that the later compaction happened, and when - the question a deadline under a
 * data-protection obligation actually asks.</p>
 *
 * <h3>Request</h3>
 * <p>The namespace comes from the {@value GraphTenants#NAMESPACE_HEADER} header, as it did on the
 * POST: request ids are handed out per namespace, so the same number names different requests in
 * different namespaces.</p>
 *
 * <h3>Response</h3>
 * <pre>
 * {"request_id": 7, "namespace": "acme", "state": "discharged", "scope": "entity",
 *  "entity_id": 42, "requested_at_ms": 1736899200000, "discharged_at_ms": 1736899260000,
 *  "detail_retained": true, "remaining": null}
 * </pre>
 * <ul>
 *   <li>{@code pending} - accepted and durable; {@code remaining} says what is still owed.</li>
 *   <li>{@code discharged} - the edges the scope selects are gone from the served graph and from
 *       staging, and the log records it.</li>
 * </ul>
 *
 * <h3>Retention</h3>
 * <p>The answer is read from the namespace's erasure log, so it survives a restart. The log keeps
 * the scope and timestamps of the {@value ErasureLog#RETAINED_COMPLETIONS} most recently
 * discharged requests of each namespace; an older discharged request is still reported as
 * {@code discharged}, because ids are handed out in order and it is no longer outstanding, but
 * with {@code "detail_retained": false} and its scope and timestamps null. A pending request always
 * carries its detail.</p>
 *
 * <h3>What it never says</h3>
 * <p>A request that named an entity by {@code entity_name} was resolved to an id before it was
 * recorded, and the log holds the id alone. So a status carries no name, and asking about a
 * request cannot recover the erased person's.</p>
 *
 * <h3>Errors</h3>
 * <ul>
 *   <li>{@code 400} - the path segment is not a positive integer, or the header does not name a
 *       usable namespace.</li>
 *   <li>{@code 404} - the namespace never handed that id out. A namespace that does not exist is
 *       answered the same way, so this route cannot be used to learn which namespaces exist, and
 *       asking never creates or opens one.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the tenants it reads are thread-safe.</p>
 */
public class GraphErasureStatusEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphErasureStatusEndpoint.class);

    /** The collection whose members this serves. */
    static final String COLLECTION_PATH = "/v1/graph/erasure";

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces whose erasure logs are read
     */
    public GraphErasureStatusEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405, "{\"error\": \"Method Not Allowed - Use GET "
                + COLLECTION_PATH + "/{request_id}\"}");
            return;
        }

        long requestId;
        NamespaceId namespace;
        try {
            requestId = requestIdOf(exchange.getRequestURI().getPath(), COLLECTION_PATH);
            namespace = GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
            return;
        }

        Optional<ErasureLog.RequestStatus> status;
        try {
            status = tenants.erasureStatus(namespace, requestId);
        } catch (IOException | RuntimeException e) {
            // Logged with detail, answered without: the message names the log's path.
            LOGGER.error("could not read the status of erasure request {} in namespace {}",
                requestId, namespace.value(), e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
            return;
        }
        if (status.isEmpty()) {
            ResponseBody.sendJson(exchange, 404, "{\"error\": \"No erasure request "
                + requestId + " in this namespace\"}");
            return;
        }

        StringBuilder json = new StringBuilder("{");
        ErasureStatusJson.appendFields(json, status.get());
        json.append(", \"namespace\": \"").append(JsonText.escape(namespace.value()))
            .append("\", \"remaining\": ");
        if (status.get().state() == ErasureLog.State.PENDING) {
            json.append("\"the edges this request selects are still in the served graph or in "
                + "staging; the next erasure compaction removes them, and POST "
                + COLLECTION_PATH + " with \\\"compact\\\": true runs one now\"");
        } else {
            json.append("null");
        }
        ResponseBody.sendJson(exchange, 200, json.append('}').toString());
    }

    /**
     * The request id a member path names.
     *
     * @param path       The decoded request path
     * @param collection The collection path the id follows
     * @return The id
     * @throws IllegalArgumentException if the segment is missing, nested, or not a positive
     *                                  integer
     */
    static long requestIdOf(String path, String collection) {
        String segment = path == null || !path.startsWith(collection + "/")
            ? ""
            : path.substring(collection.length() + 1);
        if (segment.endsWith("/")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        if (segment.isEmpty() || segment.length() > 19 || !segment.chars().allMatch(
                c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException(
                "the path must end in a request id, a positive integer: GET " + collection
                    + "/{request_id}");
        }
        long id;
        try {
            id = Long.parseLong(segment);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("request id " + segment + " is out of range", e);
        }
        if (id <= 0) {
            throw new IllegalArgumentException("a request id is a positive integer");
        }
        return id;
    }
}
