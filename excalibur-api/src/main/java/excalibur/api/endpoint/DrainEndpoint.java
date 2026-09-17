package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.QueryParams;
import excalibur.api.protocol.ResponseBody;

import java.io.IOException;
import java.util.Objects;

/**
 * HTTP endpoint for {@code /v1/drain}: POST takes the node out of rotation, DELETE puts it
 * back.
 *
 * <p>The companion to {@link ReadinessEndpoint}. Readiness reports what the node can do; this
 * is how an operator overrides it before doing something to the node - a restart, a manual
 * compaction, a disk replacement. Without it the only way to stop traffic reaching a node was
 * to stop the node, which drops whatever was in flight at that moment.</p>
 *
 * <p>Two verbs on one noun, the same shape {@code /v1/ingest} and {@code /v1/reindex} use, and
 * for the same reason: starting a drain and ending one are one resource, not two.
 * {@code GET} is deliberately absent - {@code /health/ready} already reports the drain state,
 * and it reports it to the probe that acts on it.</p>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * POST /v1/drain?reason=rolling%20restart
 *
 * HTTP/1.1 200 OK
 * {"draining": true, "sinceMs": 1736899200000, "reason": "rolling restart"}
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; the state it changes is one atomic reference.</p>
 *
 * @author ExcaliburDB
 */
public final class DrainEndpoint implements HttpHandler {

    /**
     * Longest reason accepted.
     *
     * <p>The reason is echoed in a log line and in every readiness response until the drain
     * ends, so an unbounded one is a caller-controlled string repeated on every probe.</p>
     */
    private static final int MAX_REASON_LENGTH = 256;

    private final ServiceReadiness readiness;

    /**
     * Creates the endpoint over the flag it sets.
     *
     * @param readiness The drain flag readiness is read from
     * @throws NullPointerException if readiness is null
     */
    public DrainEndpoint(ServiceReadiness readiness) {
        this.readiness = Objects.requireNonNull(readiness, "readiness cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("POST".equalsIgnoreCase(method)) {
            beginDrain(exchange);
            return;
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            readiness.endDrain();
            ResponseBody.sendJson(exchange, 200, "{\"draining\": false}");
            return;
        }
        exchange.getResponseHeaders().set("Allow", "POST, DELETE");
        ResponseBody.sendError(exchange, 405,
            "Method Not Allowed - Use POST to drain or DELETE to undrain");
    }

    /**
     * Takes the node out of rotation, recording why.
     *
     * <p>Answers 200 whether or not this call was the one that began the drain: a retry after
     * a timed-out request must not read as a failure, and "the node is drained" is true either
     * way. The state the caller asked for is what the response describes.</p>
     *
     * @param exchange The request
     * @throws IOException if the response cannot be written
     */
    private void beginDrain(HttpExchange exchange) throws IOException {
        String reason = QueryParams.value(exchange.getRequestURI().getRawQuery(), "reason");
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            ResponseBody.sendError(exchange, 400,
                "Bad Request - reason must be at most " + MAX_REASON_LENGTH + " characters");
            return;
        }
        readiness.beginDrain(reason == null || reason.isBlank() ? null : reason);

        StringBuilder json = new StringBuilder(128);
        json.append("{\"draining\": true, \"sinceMs\": ").append(readiness.drainingSinceMs());
        String inForce = readiness.reason();
        if (inForce != null) {
            json.append(", \"reason\": \"").append(JsonText.escape(inForce)).append('"');
        }
        ResponseBody.sendJson(exchange, 200, json.append('}').toString());
    }
}
