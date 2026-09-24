package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.api.audit.AuditLog;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRefusedException;
import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;

import com.eonmux.excalibur.tenancy.NamespaceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * {@code PUT /v1/graph/namespaces/{namespace}} - creates a namespace on purpose.
 *
 * <p>Naming a namespace in the {@value GraphTenants#NAMESPACE_HEADER} header creates it, which
 * is convenient and is also how a typo becomes a tenant: the documents registered under
 * {@code acmee} are staged, built and served there, and nothing tells anyone they are not in
 * {@code acme}. With {@code excalibur.graph.require-explicit-namespaces} on, this route is the
 * only way a namespace comes into being, and every other route refuses one nobody created. With
 * it off, this route still lets a provisioning step create a tenant ahead of its first document,
 * so a cap reached is found out then rather than at the first ingest.</p>
 *
 * <h3>Response</h3>
 * <pre>{@code
 * {"namespace": "acme", "created": true}
 * }</pre>
 *
 * <h3>Status codes</h3>
 * <ul>
 *   <li>{@code 201} - created. It holds nothing yet, and costs no open graph until a request
 *       borrows it</li>
 *   <li>{@code 200} - it already existed, with {@code "created": false}. PUT is idempotent, so a
 *       provisioning step that runs twice is not an error; {@code shared} always exists</li>
 *   <li>{@code 400 invalid_namespace} - the path segment is not a namespace identifier, by the
 *       same allowlist every other route applies, since the segment becomes a directory name</li>
 *   <li>{@code 429 namespace_limit_reached} - the node already holds
 *       {@code excalibur.graph.max-namespaces} tenant namespaces</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the registry counts and creates under one lock.</p>
 *
 * @author ExcaliburDB
 */
public class GraphNamespaceCreateEndpoint implements HttpHandler {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(GraphNamespaceCreateEndpoint.class);

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces a new one is created among
     */
    public GraphNamespaceCreateEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendError(exchange, 405, "Method Not Allowed - Use PUT "
                + "/v1/graph/namespaces/{namespace} to create one namespace");
            return;
        }

        String requested = GraphNamespaceDeleteEndpoint.namespaceSegment(
            exchange.getRequestURI().getPath());
        if (requested == null) {
            ResponseBody.sendError(exchange, 405, "Method Not Allowed - name the namespace to "
                + "create: PUT /v1/graph/namespaces/{namespace}");
            return;
        }
        if (requested.isEmpty()) {
            ResponseBody.sendError(exchange, 400, ErrorCode.INVALID_NAMESPACE,
                "A namespace must be named in the path");
            return;
        }
        // NamespaceId.of refuses the shared name so no tenant can claim it, and it is not a
        // malformed request: it names the one namespace that always exists.
        if (NamespaceId.SHARED.value().equals(requested)) {
            AuditLog.target(exchange, "namespace", NamespaceId.SHARED.value());
            ResponseBody.sendJson(exchange, 200, body(NamespaceId.SHARED, false));
            return;
        }

        NamespaceId id;
        try {
            id = NamespaceId.of(requested);
            AuditLog.target(exchange, "namespace", id.value());
        } catch (IllegalArgumentException e) {
            ResponseBody.sendError(exchange, 400, ErrorCode.INVALID_NAMESPACE, e.getMessage());
            return;
        }

        try {
            boolean created = tenants.create(id);
            ResponseBody.sendJson(exchange, created ? 201 : 200, body(id, created));
        } catch (NamespaceRefusedException e) {
            ResponseBody.sendError(exchange, e.reason().statusCode(), e.reason().code(),
                e.getMessage());
        } catch (IOException | RuntimeException e) {
            // Logged with the path the node failed on, answered without it.
            LOGGER.error("Could not create namespace {}", id, e);
            ResponseBody.sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Renders the answer.
     *
     * @param id      The namespace
     * @param created Whether this request created it
     * @return The response body
     */
    private static String body(NamespaceId id, boolean created) {
        return "{\"namespace\": \"" + JsonText.escape(id.value()) + "\", \"created\": " + created
            + "}";
    }
}
