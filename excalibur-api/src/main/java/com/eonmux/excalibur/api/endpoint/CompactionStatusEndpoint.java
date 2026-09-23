package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.shard.CompactionReport;

import java.io.IOException;
import java.util.Objects;

/**
 * HTTP endpoint reading back what the last compaction did.
 *
 * <p>{@code POST /v1/compact} answers 202 and works in the background, so without this route
 * the run's verdict would reach the node's log and nothing else.</p>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * GET /v1/compact/status HTTP/1.1
 *
 * HTTP/1.1 200 OK
 * Content-Type: application/json
 *
 * {
 *   "state": "completed",
 *   "force": false,
 *   "started_at_ms": 1736899200000,
 *   "finished_at_ms": 1736899214000,
 *   "duration_ms": 13820,
 *   "segments_considered": 64,
 *   "segments_compacted": 9,
 *   "segments_failed": 0,
 *   "segments_skipped": 55,
 *   "graphs_considered": 64,
 *   "graphs_compacted": 4,
 *   "graphs_skipped": 2,
 *   "graphs_failed": 0,
 *   "lexical": {"performed": true, "segments_rewritten": 5, "segments_produced": 1,
 *               "chunks_removed": 1840, "groups_abandoned": 0,
 *               "reason": "due: 1840 of 7200 document(s) tombstoned across 12 segment(s)"}
 * }
 * }</pre>
 *
 * <p>{@code state} is {@code idle} before the first run, {@code running} while one is
 * happening, {@code completed} once it finished and {@code failed} when the segment store's half
 * raised an error, in which case {@code error} carries the reason and there are no segment or
 * graph counts. A completed run with a non-zero {@code segments_failed} is neither: it reclaimed
 * what it reports and left the rest, and the reasons are in the node's log.</p>
 *
 * <p>{@code lexical} is the lexical chunk index's half of the run and is present only on a node
 * that keeps one. It is reported whatever the store's half came to, because the two are
 * compacted independently: {@code performed} false with a {@code reason} is a policy that found
 * nothing due or a run stopped before reaching the index, and {@code performed} false with an
 * {@code error} is a compaction of the index that failed. {@code groups_abandoned} counts groups
 * of segments the rewrite left exactly as they were because storage could not read a surviving
 * chunk's text back: with {@code performed} true the rewrite was partial, and with it false
 * nothing was published at all - a forced rewrite that reports that has not succeeded, and any
 * erasure in those segments is still owed.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; it reads an immutable snapshot.</p>
 *
 * @see CompactionEndpoint
 */
public final class CompactionStatusEndpoint implements HttpHandler {

    private final CompactionRunner runner;

    /**
     * Creates the endpoint over a runner.
     *
     * @param runner The runner holding the current and last run
     * @throws NullPointerException if runner is null
     */
    public CompactionStatusEndpoint(CompactionRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendError(exchange, 405,
                "Method Not Allowed - Use GET to read compaction status");
            return;
        }
        ResponseBody.sendJson(exchange, 200, render(runner.snapshot()));
    }

    /**
     * Renders a snapshot as the response body.
     *
     * @param snapshot What the runner has to say
     * @return The JSON body
     */
    private static String render(CompactionRunner.Snapshot snapshot) {
        StringBuilder json = new StringBuilder(320);
        json.append("{\"state\": \"").append(JsonText.escape(snapshot.state())).append('"');
        if (CompactionRunner.STATE_IDLE.equals(snapshot.state())) {
            return json.append('}').toString();
        }
        json.append(", \"force\": ").append(snapshot.force())
            .append(", \"started_at_ms\": ").append(snapshot.startedAtMs());
        if (snapshot.finishedAtMs() > 0) {
            json.append(", \"finished_at_ms\": ").append(snapshot.finishedAtMs());
        }
        if (snapshot.error() != null) {
            json.append(", \"error\": \"").append(JsonText.escape(snapshot.error())).append('"');
        }
        appendCounts(json, snapshot.report());
        appendLexical(json, snapshot.lexical());
        return json.append('}').toString();
    }

    /**
     * Appends the chunk index's half of a finished run, if this node keeps one.
     *
     * <p>A failure carries only its reason: counts beside it would read as work done. Anything
     * else - a rewrite, a policy that found nothing due, a run stopped before it got there - is
     * reported with its counts and the reason it gave.</p>
     *
     * @param json    The body being built
     * @param lexical The chunk index's outcome, or null
     */
    private static void appendLexical(StringBuilder json, CompactionRunner.LexicalOutcome lexical) {
        if (lexical == null) {
            return;
        }
        json.append(", \"lexical\": {\"performed\": ").append(lexical.performed());
        if (lexical.error() != null) {
            json.append(", \"error\": \"").append(JsonText.escape(lexical.error())).append('"');
        } else {
            json.append(", \"segments_rewritten\": ").append(lexical.segmentsRewritten())
                .append(", \"segments_produced\": ").append(lexical.segmentsProduced())
                .append(", \"chunks_removed\": ").append(lexical.chunksRemoved())
                .append(", \"groups_abandoned\": ").append(lexical.groupsAbandoned())
                .append(", \"reason\": \"").append(JsonText.escape(lexical.reason()))
                .append('"');
        }
        json.append('}');
    }

    /**
     * Appends the counts of a finished run, if there are any.
     *
     * @param json   The body being built
     * @param report The run's counts, or null while running or after a failure
     */
    private static void appendCounts(StringBuilder json, CompactionReport report) {
        if (report == null) {
            return;
        }
        json.append(", \"duration_ms\": ").append(report.duration().toMillis())
            .append(", \"segments_considered\": ").append(report.segmentsConsidered())
            .append(", \"segments_compacted\": ").append(report.segmentsCompacted())
            .append(", \"segments_failed\": ").append(report.segmentsFailed())
            .append(", \"segments_skipped\": ").append(report.segmentsSkipped())
            .append(", \"graphs_considered\": ").append(report.graphsConsidered())
            .append(", \"graphs_compacted\": ").append(report.graphsCompacted())
            .append(", \"graphs_skipped\": ").append(report.graphsSkipped())
            .append(", \"graphs_failed\": ").append(report.graphsFailed());
    }
}
