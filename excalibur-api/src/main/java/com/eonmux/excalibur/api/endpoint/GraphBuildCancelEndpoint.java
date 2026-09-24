package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.audit.AuditLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.tenancy.NamespaceId;

import java.io.IOException;
import java.util.Objects;

/**
 * {@code DELETE /v1/graph/build} - stops the graph build in progress.
 *
 * <p>The counterpart to {@code POST /v1/graph/build}, and the same relationship
 * {@code DELETE /v1/compact} has to the compaction that started one. A build replays every
 * staged fact, indexes the prose behind them and embeds every entity; on a large corpus that is
 * minutes of saturated I/O and a bill at an embedding endpoint, and one started in the wrong
 * window has to be stoppable without stopping the node - which is the more dangerous of the two
 * acts, since shutdown closes the mappings the graph is served out of.</p>
 *
 * <h2>Response</h2>
 * <pre>{@code
 * DELETE /v1/graph/build HTTP/1.1
 * X-Excalibur-Namespace: acme
 *
 * HTTP/1.1 200 OK
 * Content-Type: application/json
 *
 * {"cancelled": true, "message": "The graph build was asked to stop; poll
 *  /v1/graph/build/status for when it has"}
 * }</pre>
 *
 * <p>200 whether or not a build was running. An operator clearing a build wants to know whether
 * there was one, and "nothing was running" is an answer rather than an error: the state they
 * were asking for is the state they now have.</p>
 *
 * <h2>What a stopped build leaves behind</h2>
 * <p>The graph that was already being served, untouched. A build assembles into a scratch
 * directory beside the served one and becomes the served graph in a single rename, so a build
 * that stops before that rename publishes nothing - there is no half-published graph to
 * recover from, and no partial result beyond the honest one, which is that the namespace is
 * still answering from the graph it had.</p>
 *
 * <p>Stopping is a request. The build ends at the next staged fact, the next snippet or the
 * next embedded entity, so the readback is {@code GET /v1/graph/build/status}, which reports
 * {@code cancelled} once it has.</p>
 *
 * <h2>Scoped to the namespace</h2>
 * <p>Only the build of the namespace in the {@value GraphTenants#NAMESPACE_HEADER} header is
 * stopped. A caller holding one tenant's header must not be able to end another tenant's build
 * by sending a DELETE at the right moment, so a request that names a namespace with nothing
 * running is answered {@code "cancelled": false} even while the node is building something
 * else.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; the runner it delegates to owns the state.</p>
 *
 * @see GraphBuildEndpoint
 * @see GraphBuildStatusEndpoint
 * @author ExcaliburDB
 */
public final class GraphBuildCancelEndpoint implements HttpHandler {

    private final GraphBuildRunner runner;

    /**
     * Creates the endpoint over a runner.
     *
     * @param runner The runner holding the build in progress
     * @throws NullPointerException if runner is null
     */
    public GraphBuildCancelEndpoint(GraphBuildRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405,
                "{\"error\": \"Method not allowed; use DELETE to stop a graph build\"}");
            return;
        }

        NamespaceId namespace;
        try {
            namespace = GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
            AuditLog.target(exchange, "namespace", namespace.value());
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
            return;
        }

        boolean cancelled = runner.cancel(namespace);
        ResponseBody.sendJson(exchange, 200,
            "{\"cancelled\": " + cancelled + ", \"message\": \""
                + (cancelled
                    ? "The graph build was asked to stop; poll /v1/graph/build/status for when "
                        + "it has. Nothing it had assembled is published."
                    : "No graph build is running for namespace '"
                        + JsonText.escape(namespace.value()) + "'")
                + "\"}");
    }
}
