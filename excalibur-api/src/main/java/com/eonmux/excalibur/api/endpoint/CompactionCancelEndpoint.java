package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.ResponseBody;

import java.io.IOException;
import java.util.Objects;

/**
 * HTTP endpoint for stopping the compaction in progress.
 *
 * <p>The counterpart to {@code POST /v1/compact}, and the same relationship {@code DELETE
 * /v1/reindex} has to the reindex that started one. A forced compaction rewrites every live
 * segment and rebuilds every graph; on a large corpus that is minutes of saturated I/O, and
 * one started in the wrong window has to be stoppable without stopping the node - which is the
 * more dangerous of the two acts, because shutdown closes the arena the segments are mapped
 * out of while the rewrite is still copying into it.</p>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * HTTP/1.1 200 OK
 * Content-Type: application/json
 *
 * {"cancelled": true, "message": "Compaction was asked to stop; poll /v1/compact/status ..."}
 * }</pre>
 *
 * <p>200 whether or not a run was in progress. An operator clearing a compaction wants to know
 * whether there was one, and "nothing was running" is an answer rather than an error: the state
 * they were asking for is the state they now have.</p>
 *
 * <p>Stopping is a request. The run ends at its next segment boundary, so the readback is
 * {@code GET /v1/compact/status}, which reports {@code cancelled} once it has - with the counts
 * of what it did reclaim before it stopped, because an interrupted rewrite leaves the segment it
 * was working on untouched and keeps every segment it had already replaced.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; the runner it delegates to owns the state.</p>
 *
 * @see CompactionEndpoint
 * @see CompactionStatusEndpoint
 */
public final class CompactionCancelEndpoint implements HttpHandler {

    private final CompactionRunner runner;

    /**
     * Creates the endpoint over a runner.
     *
     * @param runner The runner holding the compaction in progress
     * @throws NullPointerException if runner is null
     */
    public CompactionCancelEndpoint(CompactionRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendError(exchange, 405,
                "Method Not Allowed - Use DELETE to stop a compaction");
            return;
        }

        boolean cancelled = runner.cancel();
        ResponseBody.sendJson(exchange, 200,
            "{\"cancelled\": " + cancelled + ", \"message\": \""
                + (cancelled
                    ? "Compaction was asked to stop; poll /v1/compact/status for when it has"
                    : "No compaction is running")
                + "\"}");
    }
}
