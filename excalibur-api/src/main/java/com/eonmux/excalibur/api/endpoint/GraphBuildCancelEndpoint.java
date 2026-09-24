package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.audit.AuditLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.tenancy.NamespaceId;

import java.io.IOException;
import java.util.Objects;

/**
 * {@code DELETE /v1/graph/build} - stops the namespace's graph build in progress, whichever
 * started it.
 *
 * <p>The counterpart to {@code POST /v1/graph/build}, and the same relationship
 * {@code DELETE /v1/compact} has to the compaction that started one. A build replays every
 * staged fact, indexes the prose behind them and embeds every entity; on a large corpus that is
 * minutes of saturated I/O and a bill at an embedding endpoint, and one started in the wrong
 * window has to be stoppable without stopping the node - which is the more dangerous of the two
 * acts, since shutdown closes the mappings the graph is served out of.</p>
 *
 * <h2>Explicit and automatic builds alike</h2>
 * <p>Most builds on a node that ingests are not asked for: the {@link GraphAutoBuilder} starts
 * one whenever a namespace's graph falls behind its staging. The cost an operator wants to stop
 * is the same whichever started it, so this stops both - the build {@code POST} started, and an
 * automatic one - and says which it found. An automatic build so stopped is not restarted on the
 * next pass: the namespace is deferred by {@code excalibur.graph.auto-build-max-delay-seconds}
 * and then built, since what it would have published is still staged and still owed.</p>
 *
 * <h2>Response</h2>
 * <pre>{@code
 * DELETE /v1/graph/build HTTP/1.1
 * X-Excalibur-Namespace: acme
 *
 * HTTP/1.1 200 OK
 * Content-Type: application/json
 *
 * {"cancelled": true, "explicit": false, "automatic": true,
 *  "message": "The graph build was asked to stop; poll /v1/graph/build/status for when it
 *  has. Nothing it had assembled is published. The staged changes stay staged and are built
 *  automatically no sooner than 900s after it stops, or at once by POST /v1/graph/build."}
 * }</pre>
 *
 * <p>{@code cancelled} is whether any build of the namespace was asked to stop,
 * {@code explicit} whether one started by {@code POST /v1/graph/build} was, and
 * {@code automatic} whether an automatic one was. 200 whether or not a build was running. An
 * operator clearing a build wants to know whether there was one, and "nothing was running" is
 * an answer rather than an error: the state they were asking for is the state they now
 * have.</p>
 *
 * <h2>What a stopped build leaves behind</h2>
 * <p>The graph that was already being served, untouched. A build assembles into a scratch
 * directory beside the served one and becomes the served graph in a single rename, so a build
 * that stops before that rename publishes nothing - there is no half-published graph to
 * recover from, and no partial result beyond the honest one, which is that the namespace is
 * still answering from the graph it had.</p>
 *
 * <p>Stopping is a request. The build ends at the next staged fact, the next snippet or the
 * next embedded entity, so the readback is {@code GET /v1/graph/build/status}: its top-level
 * {@code state} reports {@code cancelled} once an explicit build has stopped, and its
 * {@code automatic} block a {@code last_result} of {@code cancelled}, with {@code state}
 * {@code deferred} until {@code next_attempt_at_ms}, once an automatic one has.</p>
 *
 * <h2>Scoped to the namespace</h2>
 * <p>Only the builds of the namespace in the {@value GraphTenants#NAMESPACE_HEADER} header are
 * stopped. A caller holding one tenant's header must not be able to end another tenant's build
 * by sending a DELETE at the right moment, so a request that names a namespace with nothing
 * running is answered {@code "cancelled": false} even while the node is building something
 * else.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; the runner and the automatic build it delegates to own the state.</p>
 *
 * @see GraphBuildEndpoint
 * @see GraphBuildStatusEndpoint
 * @author ExcaliburDB
 */
public final class GraphBuildCancelEndpoint implements HttpHandler {

    private final GraphBuildRunner runner;
    private final GraphAutoBuilder autoBuilder;

    /**
     * Creates the endpoint over the runner explicit builds go through and the automatic build.
     *
     * @param runner      The runner holding the explicit build in progress
     * @param autoBuilder The automatic build, holding the automatic builds in progress
     * @throws NullPointerException if either is null
     */
    public GraphBuildCancelEndpoint(GraphBuildRunner runner, GraphAutoBuilder autoBuilder) {
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
        this.autoBuilder = Objects.requireNonNull(autoBuilder, "autoBuilder cannot be null");
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
            ResponseBody.sendError(exchange, 400, ErrorCode.INVALID_NAMESPACE, e.getMessage());
            return;
        }

        // Both asked, not the first that answers: an automatic build can be waiting on the
        // namespace's replacement lock behind an explicit one, and stopping only the explicit
        // build would hand the lock straight to the build the caller also wanted gone.
        boolean explicit = runner.cancel(namespace);
        boolean automatic = autoBuilder.cancel(namespace);
        ResponseBody.sendJson(exchange, 200, "{\"cancelled\": " + (explicit || automatic)
            + ", \"explicit\": " + explicit + ", \"automatic\": " + automatic
            + ", \"message\": \"" + message(namespace, explicit, automatic) + "\"}");
    }

    /**
     * What the caller is told, already escaped for a JSON string.
     *
     * @param namespace Whose builds were asked about
     * @param explicit  Whether an explicit build was asked to stop
     * @param automatic Whether an automatic build was asked to stop
     * @return The message
     */
    private String message(NamespaceId namespace, boolean explicit, boolean automatic) {
        if (!explicit && !automatic) {
            return "No graph build is running for namespace '"
                + JsonText.escape(namespace.value()) + "'";
        }
        String stopped = "The graph build was asked to stop; poll /v1/graph/build/status for "
            + "when it has. Nothing it had assembled is published.";
        if (!automatic) {
            return stopped;
        }
        return stopped + " The staged changes stay staged and are built automatically no "
            + "sooner than " + autoBuilder.deferral().toSeconds() + "s after it stops, or at "
            + "once by POST /v1/graph/build.";
    }
}
