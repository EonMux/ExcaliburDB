package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.audit.AuditLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphExtraction;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@code POST /v1/graph/build} - turns the facts ingestion has staged into the graph being served.
 *
 * <p>A build is a step rather than a consequence of ingesting, because the edge store is produced
 * whole: it is a sorted CSR, and the property that makes traversal bounded is exactly the one that
 * makes an append impossible. Rebuilding after every document would cost the whole graph per
 * document, so ingestion stages facts and this publishes them.</p>
 *
 * <h2>Why this answers before the build has happened</h2>
 * <p>Publishing replays every staged fact, indexes the prose they came from and embeds every
 * entity. On a real corpus that is minutes - longer than any load balancer will hold a
 * connection open and longer than a client will wait, so a synchronous build ended as a timeout
 * at the caller while the work carried on invisibly and the retry raced it. The build therefore
 * happens behind this response, and {@code GET /v1/graph/build/status} is where its outcome is
 * read. {@code DELETE /v1/graph/build} ends one started at the wrong moment.</p>
 *
 * <h2>Request</h2>
 * <pre>{@code
 * POST /v1/graph/build HTTP/1.1
 * X-Excalibur-Namespace: acme
 * }</pre>
 *
 * <p>No body. The namespace header names whose graph to build, and its absence means the shared
 * namespace, as it does on every other graph route.</p>
 *
 * <h2>Response (accepted)</h2>
 * <pre>{@code
 * HTTP/1.1 202 Accepted
 * Content-Type: application/json
 *
 * {"state": "running", "namespace": "acme", "started_at_ms": 1736899200000,
 *  "extraction": "active"}
 * }</pre>
 *
 * <p>{@code extraction} says whether ingestion on this node extracts the facts a build publishes:
 * {@code active}, {@code disabled} when {@code excalibur.graph.extraction-enabled} is off,
 * {@code missing_api_key} when it is on and the chat endpoint is the hosted default with no key,
 * {@code chat_disabled} when it is on and {@code excalibur.llm.chat-enabled} is false, or
 * {@code unavailable} when a chat model is configured but the ingestion pipeline could not be set
 * up. The last three also carry a {@code warning}, because nobody chose them and the build they
 * answer will publish nothing new.</p>
 *
 * <h2>Response (a build is already running)</h2>
 * <pre>{@code
 * HTTP/1.1 409 Conflict
 * Content-Type: application/json
 *
 * {"error": "A graph build is already in progress for namespace 'acme'"}
 * }</pre>
 *
 * <p>One build at a time for the whole node, and the namespace holding it is named. Two builds
 * would not corrupt each other - each assembles into its own scratch directory - but they would
 * take twice as long each over the same disk and the same embedding endpoint, with neither
 * caller told why.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the runner it delegates to claims the node's single build slot atomically.</p>
 *
 * @see GraphBuildStatusEndpoint
 * @see GraphBuildCancelEndpoint
 * @author ExcaliburDB
 */
public class GraphBuildEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphBuildEndpoint.class);

    private final GraphBuildRunner runner;

    /**
     * Creates the endpoint.
     *
     * @param runner The runner that performs and remembers graph builds
     * @throws NullPointerException if runner is null
     */
    public GraphBuildEndpoint(GraphBuildRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405,
                "{\"error\": \"Method not allowed; use POST\"}");
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

        GraphBuildRunner.Claim claim;
        try {
            claim = runner.start(namespace);
        } catch (RejectedExecutionException e) {
            // The node is shutting down. Retryable at another node or after a restart, which is
            // what 503 says and 500 does not.
            LOGGER.warn("a graph build of namespace {} was asked for during shutdown", namespace);
            ResponseBody.sendJson(exchange, 503,
                "{\"error\": \"This node is shutting down and is not accepting builds\"}");
            return;
        }

        if (!claim.accepted()) {
            ResponseBody.sendJson(exchange, 409,
                "{\"error\": \"A graph build is already in progress for namespace '"
                    + JsonText.escape(claim.blockedBy().namespace().value()) + "'\"}");
            return;
        }

        // Rendered from the build that was just started rather than from a re-read of the
        // runner's state. On an empty namespace the build can finish before this line, and a
        // 202 reporting "completed" would tell a client keying on "running" not to poll.
        StringBuilder json = new StringBuilder(256)
            .append("{\"state\": \"").append(JsonText.escape(claim.started().state()))
            .append("\", \"namespace\": \"").append(JsonText.escape(namespace.value()))
            .append("\", \"started_at_ms\": ").append(claim.started().startedAtMs());
        appendExtraction(json, runner.extraction());
        ResponseBody.sendJson(exchange, 202, json.append('}').toString());
    }

    /**
     * Says whether ingestion on this node extracts facts, and warns when it was asked to and
     * cannot.
     *
     * <p>In the answer to the request rather than only in the status a caller may never read:
     * a build on a node whose extraction is inactive publishes nothing, and the caller that
     * asked for it is the one who needs to know why before waiting on it.</p>
     *
     * @param json       The body being built
     * @param extraction The node's extraction state, if it says
     */
    static void appendExtraction(StringBuilder json, Optional<GraphExtraction> extraction) {
        extraction.ifPresent(state -> {
            json.append(", \"extraction\": \"").append(state.wireName()).append('"');
            state.warning().ifPresent(warning -> json.append(", \"warning\": \"")
                .append(JsonText.escape(warning)).append('"'));
        });
    }
}
