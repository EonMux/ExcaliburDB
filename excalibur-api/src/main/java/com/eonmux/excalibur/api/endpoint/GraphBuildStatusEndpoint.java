package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphBuildService;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.tenancy.NamespaceId;

import java.io.IOException;
import java.util.Objects;

/**
 * {@code GET /v1/graph/build/status} - what a namespace's current or last graph build did.
 *
 * <p>{@code POST /v1/graph/build} answers 202 and works behind the response, so without this
 * route a build's outcome would reach the node's log and nothing else - including the one
 * outcome a caller most needs to see, which is that a build published nothing.</p>
 *
 * <h2>Scoped by the namespace header, like every other graph route</h2>
 * <p>A namespace is told about its own last build. One that has never had a build on this node
 * is {@code idle}, even while another namespace is building: answering with somebody else's
 * build would be the one place the header stops meaning what it means everywhere else.</p>
 *
 * <h2>Response</h2>
 * <pre>{@code
 * GET /v1/graph/build/status HTTP/1.1
 * X-Excalibur-Namespace: acme
 *
 * HTTP/1.1 200 OK
 * Content-Type: application/json
 *
 * {
 *   "state": "completed",
 *   "namespace": "acme",
 *   "started_at_ms": 1736899200000,
 *   "finished_at_ms": 1736899261000,
 *   "published": true,
 *   "edges": 12840,
 *   "entities": 3221,
 *   "relations": 47,
 *   "entities_with_text": 3180,
 *   "entities_with_vectors": 3180,
 *   "staged_facts": 12840
 * }
 * }</pre>
 *
 * <p>{@code state} is {@code idle} before the first build, {@code running} while one is
 * happening, {@code completed} once it finished, {@code cancelled} when it was asked to stop
 * and did, and {@code failed} when it raised an error - in which case {@code error} says why
 * and there are no counts.</p>
 *
 * <p>A completed build with {@code "published": false} is not a failure and not an empty graph:
 * nothing was staged, so the namespace goes on serving whatever graph it already had. Replacing
 * it with an empty one would turn every question into a refusal.</p>
 *
 * <p>A cancelled build reports {@code "published": false} for a different reason: it stopped
 * before the rename that would have published it. There is no partial graph to describe,
 * because a build becomes the served graph in one move or not at all - the honest partial
 * result is the graph from before, which is the one still being served.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; it reads an immutable snapshot.</p>
 *
 * @see GraphBuildEndpoint
 * @author ExcaliburDB
 */
public final class GraphBuildStatusEndpoint implements HttpHandler {

    private final GraphBuildRunner runner;

    /**
     * Creates the endpoint over a runner.
     *
     * @param runner The runner holding the current and last build of each namespace
     * @throws NullPointerException if runner is null
     */
    public GraphBuildStatusEndpoint(GraphBuildRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405,
                "{\"error\": \"Method not allowed; use GET to read graph build status\"}");
            return;
        }

        NamespaceId namespace;
        try {
            namespace = GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
            return;
        }

        ResponseBody.sendJson(exchange, 200, render(runner.snapshot(namespace)));
    }

    /**
     * Renders a snapshot as the response body.
     *
     * @param snapshot What the runner has to say about the namespace
     * @return The JSON body
     */
    private static String render(GraphBuildRunner.Snapshot snapshot) {
        StringBuilder json = new StringBuilder(320);
        json.append("{\"state\": \"").append(JsonText.escape(snapshot.state()))
            .append("\", \"namespace\": \"")
            .append(JsonText.escape(snapshot.namespace().value())).append('"');
        if (GraphBuildRunner.STATE_IDLE.equals(snapshot.state())) {
            return json.append('}').toString();
        }
        json.append(", \"started_at_ms\": ").append(snapshot.startedAtMs());
        if (snapshot.finishedAtMs() > 0) {
            json.append(", \"finished_at_ms\": ").append(snapshot.finishedAtMs());
        }
        if (GraphBuildRunner.STATE_CANCELLED.equals(snapshot.state())) {
            json.append(", \"published\": false, \"message\": \"The build was stopped before "
                + "it published; this namespace is still serving the graph it had\"");
        }
        if (snapshot.error() != null) {
            json.append(", \"error\": \"").append(JsonText.escape(snapshot.error())).append('"');
        }
        appendCounts(json, snapshot.outcome());
        return json.append('}').toString();
    }

    /**
     * Appends the counts of a finished build, if there are any.
     *
     * @param json    The body being built
     * @param outcome What the build published, or null while running, stopped or failed
     */
    private static void appendCounts(StringBuilder json,
                                     GraphBuildService.BuildOutcome outcome) {
        if (outcome == null) {
            return;
        }
        json.append(", \"published\": ").append(outcome.published())
            .append(", \"edges\": ").append(outcome.edges())
            .append(", \"entities\": ").append(outcome.entities())
            .append(", \"relations\": ").append(outcome.relations())
            .append(", \"entities_with_text\": ").append(outcome.indexed())
            .append(", \"entities_with_vectors\": ").append(outcome.embedded())
            .append(", \"staged_facts\": ").append(outcome.staged());
    }
}
