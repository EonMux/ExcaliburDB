package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.audit.AuditLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;

import com.eonmux.excalibur.tenancy.NamespaceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code DELETE /v1/graph/namespaces/{namespace}} - removes a tenant's graph from this node.
 *
 * <p>The counterpart to {@link GraphNamespacesEndpoint}: an inventory nobody can act on is a
 * report, and a namespace that can be created by a header and never removed is a retention
 * failure in a system that otherwise takes erasure obligations seriously. This deletes the
 * namespace's graph, the facts it had staged and the log of what it still owed.</p>
 *
 * <h3>Response</h3>
 * <pre>{@code
 * {
 *   "namespace": "acme", "dropped": true, "was_open": true, "had_graph": true,
 *   "staged_facts": 1204, "discarded_erasures": 2,
 *   "files_deleted": 57, "bytes_deleted": 8431104
 * }
 * }</pre>
 *
 * <p>Every number is measured before the delete, because afterwards there is nothing left to
 * ask. This operation is irreversible - there is no tombstone and nothing to restore from - so
 * the one thing the response owes its caller is an exact account of what stopped existing.
 * {@code discarded_erasures} is part of that: obligations the node had accepted and not yet
 * applied are satisfied by the deletion rather than by a compaction, and an operator has to be
 * able to see that the node stopped owing something by destroying it.</p>
 *
 * <h3>Status codes</h3>
 * <ul>
 *   <li>{@code 200} - dropped, with the account above</li>
 *   <li>{@code 400} - the path segment is not a namespace identifier. It becomes a directory
 *       name, so this is not a formatting preference: {@code ..}, a separator or an absolute
 *       path would resolve outside the graph root, and the allowlist in {@link NamespaceId#of}
 *       is what makes that impossible rather than merely unlikely</li>
 *   <li>{@code 404} - no such namespace exists on this node</li>
 *   <li>{@code 405} - the method is not DELETE, or no namespace was named. A DELETE on the
 *       collection would mean "drop every tenant on this node", which is not an operation this
 *       API offers by omission</li>
 *   <li>{@code 409} - the namespace is {@code shared}. Not 403, which would say a credential
 *       could make it allowed, and not 404, which would say it is not there: it is plainly
 *       there, it is where every request without a {@value GraphTenants#NAMESPACE_HEADER}
 *       header lands, and the registry holds a lease on it for the life of the process. The
 *       request conflicts with the state of the resource, which is what 409 says</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the registry it calls serialises the eviction and the delete.</p>
 *
 * @author ExcaliburDB
 */
public class GraphNamespaceDeleteEndpoint implements HttpHandler {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(GraphNamespaceDeleteEndpoint.class);

    /** The collection this route's members hang off. */
    static final String COLLECTION_PATH = "/v1/graph/namespaces";

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces that can be dropped
     */
    public GraphNamespaceDeleteEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405, "{\"error\": \"Method Not Allowed - Use DELETE "
                + "/v1/graph/namespaces/{namespace} to drop one namespace\"}");
            return;
        }

        String requested = namespaceSegment(exchange.getRequestURI().getPath());
        if (requested == null) {
            ResponseBody.sendJson(exchange, 405, "{\"error\": \"Method Not Allowed - name the "
                + "namespace to drop: DELETE /v1/graph/namespaces/{namespace}\"}");
            return;
        }
        if (requested.isEmpty()) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"A namespace must be named in the path\"}");
            return;
        }
        // Answered before the identifier is parsed, because NamespaceId.of refuses the shared
        // name on purpose - a tenant may not claim it - and reporting that as a malformed
        // request would be wrong about a namespace that plainly exists.
        if (NamespaceId.SHARED.value().equals(requested)) {
            ResponseBody.sendJson(exchange, 409, "{\"error\": \"The shared namespace cannot be "
                + "dropped: it is where every request without an "
                + GraphTenants.NAMESPACE_HEADER + " header lands, and this node holds it open "
                + "for the life of the process\", \"code\": \""
                + ErrorCode.NAMESPACE_PROTECTED.wireName() + "\", \"namespace\": \"shared\"}");
            return;
        }

        NamespaceId id;
        try {
            id = NamespaceId.of(requested);
            AuditLog.target(exchange, "namespace", id.value());
        } catch (IllegalArgumentException e) {
            // The segment becomes a directory name. Refused here rather than sanitised: there
            // is no correct repair for a name that tried to leave the graph root.
            LOGGER.warn("refused a namespace deletion for an unusable identifier: {}",
                e.getMessage());
            ResponseBody.sendError(exchange, 400, ErrorCode.INVALID_NAMESPACE, e.getMessage());
            return;
        }

        drop(exchange, id);
    }

    /**
     * Drops the namespace and answers with the account of what went.
     *
     * @param exchange The request
     * @param id       The namespace, already validated
     * @throws IOException if the reply cannot be written
     */
    private void drop(HttpExchange exchange, NamespaceId id) throws IOException {
        try {
            Optional<GraphTenants.Dropped> dropped = tenants.drop(id);
            if (dropped.isEmpty()) {
                ResponseBody.sendError(exchange, 404, ErrorCode.NAMESPACE_NOT_FOUND,
                    "No namespace named \"" + id.value() + "\" exists on this node");
                return;
            }
            LOGGER.warn("namespace {} dropped by request: {}", id, dropped.get());
            ResponseBody.sendJson(exchange, 200, body(dropped.get()));
        } catch (IllegalArgumentException e) {
            // Unreachable while the shared namespace is answered above, and kept so that the
            // registry's own refusal can never escape as a 500.
            ResponseBody.sendError(exchange, 409, ErrorCode.NAMESPACE_PROTECTED, e.getMessage());

        } catch (IOException | RuntimeException e) {
            // A partially deleted namespace is possible here - the walk removes files as it
            // goes - so this says the node failed rather than that nothing happened, and the
            // detail goes to the log where the paths belong.
            LOGGER.error("Could not drop namespace {}", id, e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    /**
     * Renders the account of what was destroyed.
     *
     * @param dropped What the registry removed
     * @return The response body
     */
    private static String body(GraphTenants.Dropped dropped) {
        return "{"
            + "\"namespace\": \"" + JsonText.escape(dropped.id().value()) + "\", "
            + "\"dropped\": true, "
            + "\"was_open\": " + dropped.wasOpen() + ", "
            + "\"had_graph\": " + dropped.hadGraph() + ", "
            + "\"staged_facts\": " + dropped.stagedFacts() + ", "
            + "\"discarded_erasures\": " + unknownAsNull(dropped.discardedErasures()) + ", "
            + "\"files_deleted\": " + dropped.filesDeleted() + ", "
            + "\"bytes_deleted\": " + dropped.bytesDeleted()
            + "}";
    }

    /**
     * Renders a count the node could not read as {@code null} rather than as a number.
     *
     * @param value The count, or empty
     * @return The JSON value
     */
    private static String unknownAsNull(OptionalInt value) {
        return value.isPresent() ? Integer.toString(value.getAsInt()) : "null";
    }

    /**
     * The namespace a request path names, before any validation of it.
     *
     * <p>Taken from {@code URI.getPath()}, which is already percent-decoded - so a segment that
     * spelled its separators as {@code %2f} or its dots as {@code %2e} arrives here in the form
     * it was trying to hide, and is handed to {@link NamespaceId#of} exactly like a plain one.
     * Nothing here strips, normalises or repairs: the only thing this decides is where the
     * segment starts.</p>
     *
     * @param path The decoded request path, or null
     * @return The segment, empty when one was present but blank, or null when the path is the
     *         collection itself and names no namespace at all
     */
    static String namespaceSegment(String path) {
        if (path == null || !path.startsWith(COLLECTION_PATH + "/")) {
            return null;
        }
        String segment = path.substring(COLLECTION_PATH.length() + 1);
        // One trailing slash, because that is what a browser and most clients produce for a
        // bare resource path. A second one is part of the segment and fails validation.
        if (segment.endsWith("/")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        return segment;
    }
}
