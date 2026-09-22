package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.RequestBody;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * HTTP endpoint for starting an on-demand compaction.
 *
 * <p>Compaction reclaims the space tombstoned entries still occupy. Two background workers do
 * it on a timer, which is the right default; this route is for the times a timer is the wrong
 * answer - after a bulk erasure, before a snapshot, or ahead of a maintenance window - when
 * waiting an interval for the space back is not acceptable.</p>
 *
 * <h2>Request Format</h2>
 * <pre>{@code
 * POST /v1/compact HTTP/1.1
 * Content-Type: application/json
 *
 * {"force": false}
 * }</pre>
 *
 * <p>{@code force} is optional and defaults to false, which applies exactly the thresholds the
 * background workers apply and so brings forward work that would have happened anyway. Forced,
 * every live segment is rewritten and every graph rebuilt whether or not there is dead weight
 * in them: what an operator wants after erasing a large fraction of the corpus, and otherwise
 * a great deal of I/O for nothing.</p>
 *
 * <h2>Response Format (Accepted)</h2>
 * <pre>{@code
 * HTTP/1.1 202 Accepted
 * Content-Type: application/json
 *
 * {"state": "running", "force": false, "started_at_ms": 1736899200000}
 * }</pre>
 *
 * <p>The run happens behind the response; {@code GET /v1/compact/status} is the readback.</p>
 *
 * <h2>Response Format (Already Running)</h2>
 * <pre>{@code
 * HTTP/1.1 409 Conflict
 * Content-Type: application/json
 *
 * {"error": "Compaction is already in progress"}
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; the runner it delegates to claims the run atomically.</p>
 *
 * @see CompactionStatusEndpoint
 */
public final class CompactionEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompactionEndpoint.class);

    /** Request field asking for every segment and graph to be rewritten. */
    private static final String FORCE = "force";

    private final CompactionRunner runner;

    /**
     * Creates the endpoint over a runner.
     *
     * @param runner The runner that performs and remembers compaction runs
     * @throws NullPointerException if runner is null
     */
    public CompactionEndpoint(CompactionRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendError(exchange, 405,
                "Method Not Allowed - Use POST to start a compaction");
            return;
        }

        boolean force;
        try {
            force = parseForce(RequestBody.read(exchange));
        } catch (RequestBody.TooLargeException e) {
            ResponseBody.sendError(exchange, 413, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            ResponseBody.sendError(exchange, 400, e.getMessage());
            return;
        }

        CompactionRunner.Snapshot started = runner.start(force);
        if (started == null) {
            ResponseBody.sendError(exchange, 409, "Compaction is already in progress");
            return;
        }

        // Rendered from the run that was just started, not from a re-read of the shared
        // snapshot. On a small corpus the run can finish before this line, and a 202 reporting
        // "completed" would tell a client keying on "running" not to poll for the result.
        LOGGER.info("On-demand compaction started (force={})", force);
        ResponseBody.sendJson(exchange, 202,
            "{\"state\": \"" + JsonText.escape(started.state())
            + "\", \"force\": " + started.force()
            + ", \"started_at_ms\": " + started.startedAtMs() + "}");
    }

    /**
     * Reads the optional {@code force} flag.
     *
     * <p>A value that is present but not a boolean is refused rather than read as false. A
     * caller who wrote {@code "force": "yes"} asked for every segment to be rewritten, and
     * answering 202 to a run that will rewrite almost none of them tells them the opposite of
     * what happened.</p>
     *
     * @param body The request body, possibly empty
     * @return Whether to ignore the compaction thresholds
     * @throws IllegalArgumentException if force is present with a non-boolean value,
     *                                  which {@link JsonText#booleanValue} refuses
     */
    private static boolean parseForce(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        return JsonText.booleanValue(body, FORCE, false);
    }
}
