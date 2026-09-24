package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphBuildService;
import com.eonmux.excalibur.api.bootstrap.GraphExtraction;
import com.eonmux.excalibur.api.bootstrap.GraphFreshness;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.tenancy.NamespaceId;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

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
 *   "staged_facts": 12840,
 *   "withheld_facts": 0,
 *   "retired": false,
 *   "freshness": {
 *     "behind_staging": true,
 *     "staged_changes_since_ms": 1736899500000,
 *     "last_staged_change_ms": 1736899530000,
 *     "withheld": false
 *   },
 *   "automatic": {
 *     "enabled": true,
 *     "state": "pending",
 *     "debounce_seconds": 60,
 *     "max_delay_seconds": 900,
 *     "consecutive_failures": 0
 *   }
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
 * it with an empty one would turn every question into a refusal. Such a build carries a
 * {@code message} saying why nothing was staged, read from {@code extraction} - the same field
 * {@code POST /v1/graph/build} answers with, present on every status: {@code active},
 * {@code disabled}, or - each with a {@code warning} - {@code missing_api_key} when extraction was
 * asked for and the chat endpoint is the hosted default with no key, {@code chat_disabled} when
 * {@code excalibur.llm.chat-enabled} is false, and {@code unavailable} when a chat model is
 * configured but the ingestion pipeline could not be set up.</p>
 *
 * <p>A build that finds every staged fact withdrawn - each document the graph was built from
 * replaced by nothing or removed - reports {@code "retired": true}: the namespace stops serving
 * the graph those facts built, since serving it would go on answering with documents that are
 * gone.</p>
 *
 * <p>{@code withheld_facts} counts staged facts the build left out because an outstanding
 * erasure obligation covers them - a retracted fact, an erased entity's, a withdrawn document's -
 * which staging still holds until a compaction applies the obligation. A build never publishes
 * those, and a build that would have published nothing else retires the graph that held them.
 * An obligation recorded while a build runs fails that build, with an {@code error} saying so,
 * rather than letting it publish what it read before the obligation existed; the graph it would
 * have replaced goes on being served and stays behind, so the next build publishes without it.</p>
 *
 * <h2>Whether the graph is current</h2>
 * <p>{@code freshness} says whether the served graph is behind what the namespace has staged:
 * {@code behind_staging} is true from the first change ingestion stages after a build - a new
 * document's facts, or a replaced or removed one's withdrawal - until a build publishes it.
 * {@code staged_changes_since_ms} is when the oldest unpublished change was made and
 * {@code last_staged_change_ms} the newest, each present when known. {@code withheld} is true
 * while the graph is withheld until an interrupted erasure is applied. When freshness cannot be
 * read, the block carries an {@code error} instead.</p>
 *
 * <p>{@code automatic} is what the node does about it by itself. {@code state} is
 * {@code disabled} when {@code excalibur.graph.auto-build-enabled} is off; {@code paused}, with
 * {@code paused_reason}, while extraction is {@code missing_api_key} or {@code unavailable};
 * {@code current}; {@code pending} while the graph is behind and waits for staging to be quiet
 * for {@code debounce_seconds} or for its oldest change to reach {@code max_delay_seconds};
 * {@code running}; {@code backing_off} after a failed automatic build, until
 * {@code next_attempt_at_ms}; and {@code withheld}. {@code last_started_at_ms},
 * {@code last_finished_at_ms}, {@code last_result} ({@code completed}, {@code unchanged},
 * {@code failed} or {@code cancelled}) and {@code last_error} describe the last automatic build
 * of the namespace, and {@code consecutive_failures} how many in a row have failed. The
 * top-level fields describe the last build asked for through {@code POST /v1/graph/build}.</p>
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
    private final GraphAutoBuilder autoBuilder;

    /**
     * Creates the endpoint over a runner and the automatic build beside it.
     *
     * @param runner      The runner holding the current and last build of each namespace
     * @param autoBuilder The automatic build, which says whether each graph is behind its
     *                    staging and what it last did about it
     * @throws NullPointerException if either is null
     */
    public GraphBuildStatusEndpoint(GraphBuildRunner runner, GraphAutoBuilder autoBuilder) {
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
        this.autoBuilder = Objects.requireNonNull(autoBuilder, "autoBuilder cannot be null");
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

        StringBuilder json = new StringBuilder(720);
        appendBuild(json, runner.snapshot(namespace), runner.extraction());
        appendAutomatic(json, autoBuilder.status(namespace));
        ResponseBody.sendJson(exchange, 200, json.append('}').toString());
    }

    /**
     * Appends whether the namespace's graph is behind its staging and what the automatic build
     * is doing about it.
     *
     * @param json   The body being built; left without its closing brace
     * @param status What the automatic build has to say about the namespace
     */
    private static void appendAutomatic(StringBuilder json, GraphAutoBuilder.Status status) {
        json.append(", \"freshness\": {");
        GraphFreshness freshness = status.freshness();
        if (freshness == null) {
            json.append("\"error\": \"").append(JsonText.escape(status.freshnessError()))
                .append('"');
        } else {
            json.append("\"behind_staging\": ").append(freshness.behind());
            freshness.pendingSince().ifPresent(since ->
                json.append(", \"staged_changes_since_ms\": ").append(since.toEpochMilli()));
            freshness.lastChangedAt().ifPresent(last ->
                json.append(", \"last_staged_change_ms\": ").append(last.toEpochMilli()));
            json.append(", \"withheld\": ").append(freshness.withheld());
        }
        json.append('}');

        GraphAutoBuilder.Memory memory = status.memory();
        json.append(", \"automatic\": {\"enabled\": ").append(status.enabled())
            .append(", \"state\": \"").append(status.state()).append('"');
        status.pausedBy().ifPresent(paused -> json.append(", \"paused_reason\": \"")
            .append(paused.wireName()).append('"'));
        json.append(", \"debounce_seconds\": ")
            .append(status.settings().debounce().toSeconds())
            .append(", \"max_delay_seconds\": ")
            .append(status.settings().maxDelay().toSeconds());
        if (memory.lastStartedAtMs() > 0) {
            json.append(", \"last_started_at_ms\": ").append(memory.lastStartedAtMs());
        }
        if (memory.lastFinishedAtMs() > 0) {
            json.append(", \"last_finished_at_ms\": ").append(memory.lastFinishedAtMs());
        }
        if (memory.lastResult() != null) {
            json.append(", \"last_result\": \"").append(memory.lastResult()).append('"');
        }
        if (memory.lastError() != null) {
            json.append(", \"last_error\": \"").append(JsonText.escape(memory.lastError()))
                .append('"');
        }
        json.append(", \"consecutive_failures\": ").append(memory.consecutiveFailures());
        if (memory.retryAtMs() > 0) {
            json.append(", \"next_attempt_at_ms\": ").append(memory.retryAtMs());
        }
        json.append('}');
    }


    /**
     * Opens the response body with what the last explicit build did.
     *
     * @param json       The body being built; left without its closing brace
     * @param snapshot   What the runner has to say about the namespace
     * @param extraction Whether ingestion on this node extracts facts, if it says
     */
    private static void appendBuild(StringBuilder json, GraphBuildRunner.Snapshot snapshot,
                                    Optional<GraphExtraction> extraction) {
        json.append("{\"state\": \"").append(JsonText.escape(snapshot.state()))
            .append("\", \"namespace\": \"")
            .append(JsonText.escape(snapshot.namespace().value())).append('"');
        GraphBuildEndpoint.appendExtraction(json, extraction);
        if (GraphBuildRunner.STATE_IDLE.equals(snapshot.state())) {
            return;
        }
        GraphBuildService.BuildOutcome outcome = snapshot.outcome();
        if (outcome != null && outcome.retired()) {
            json.append(", \"message\": \"Every staged fact has been withdrawn or is owed to "
                + "an erasure, so this namespace no longer serves the graph they built\"");
        } else if (outcome != null && !outcome.published() && outcome.withheld() > 0) {
            json.append(", \"message\": \"Every staged fact is owed to an outstanding erasure, "
                + "so nothing was published\"");
        } else if (outcome != null && !outcome.published() && outcome.staged() == 0) {
            // The outcome a caller most needs explained: a completed build that changed nothing.
            json.append(", \"message\": \"").append(JsonText.escape(nothingStaged(extraction)))
                .append('"');
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
    }

    /**
     * Why a build found nothing staged.
     *
     * @param extraction Whether ingestion on this node extracts facts, if it says
     * @return A sentence an operator can act on
     */
    private static String nothingStaged(Optional<GraphExtraction> extraction) {
        return "Nothing was staged, so nothing was published and this namespace is still "
            + "serving the graph it had. " + extraction.map(GraphExtraction::whyNothingStaged)
                .orElse("Ingestion stages facts only when relation extraction is enabled and an "
                    + "LLM API key is configured.");
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
            .append(", \"staged_facts\": ").append(outcome.staged())
            .append(", \"withheld_facts\": ").append(outcome.withheld())
            .append(", \"retired\": ").append(outcome.retired());
    }
}
