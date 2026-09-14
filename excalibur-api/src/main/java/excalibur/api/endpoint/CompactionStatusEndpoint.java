package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;
import excalibur.shard.CompactionReport;

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
 *   "graphs_failed": 0
 * }
 * }</pre>
 *
 * <p>{@code state} is {@code idle} before the first run, {@code running} while one is
 * happening, {@code completed} once it finished and {@code failed} when it raised an error, in
 * which case {@code error} carries the reason and there are no counts. A completed run with a
 * non-zero {@code segments_failed} is neither: it reclaimed what it reports and left the rest,
 * and the reasons are in the node's log.</p>
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
        return json.append('}').toString();
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
