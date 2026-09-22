package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.core.logging.StructuredLoggingHelper;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.QueryParams;
import com.eonmux.excalibur.api.protocol.ResponseBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code GET /v1/reindex/status} - what a reindex did, or is doing.
 *
 * <h2>Why this exists</h2>
 * <p>{@code POST /v1/reindex} answers 202 and does the work on a background thread. The run
 * records its progress and its verdict, and without this route that record reached the node's
 * own log and nothing else - so a caller could start a reindex and had no way to learn whether
 * it finished, how far it got, or why it stopped. Ingestion has had {@code /v1/ingest/status}
 * for exactly this reason. The two describe the same kind of work and are reported in the same
 * shape, down to the envelope, so a client does not have to learn two.</p>
 *
 * <h2>Response</h2>
 * <p>With {@code ?source_id=...}, one object. Without it, {@code {"sources": [...]}} holding
 * every source this node has reindexed since it started. A source that has never been
 * reindexed is a 404 rather than an invented idle run: "nothing has been asked of it" and
 * "it ran and did nothing" are different answers.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. The registry is concurrent and each status object's fields are volatile.</p>
 */
public class ReindexStatusEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexStatusEndpoint.class);

    private final ReindexEndpoint.ReindexStatusRegistry statusRegistry;

    /**
     * Creates the endpoint.
     *
     * @param statusRegistry Where reindex runs record what they are doing
     * @throws NullPointerException if statusRegistry is null
     */
    public ReindexStatusEndpoint(ReindexEndpoint.ReindexStatusRegistry statusRegistry) {
        this.statusRegistry = Objects.requireNonNull(statusRegistry,
            "statusRegistry cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String correlationId = UUID.randomUUID().toString().substring(0, 8);

        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withCorrelationId(correlationId)
                .build()) {

            try {
                // The raw query, so a source id carrying an encoded delimiter is not split on
                // its own decoded text - see QueryParams.
                String sourceId = QueryParams.value(
                    exchange.getRequestURI().getRawQuery(), "source_id");

                if (sourceId == null) {
                    sendAll(exchange, statusRegistry.getAllStatuses());
                    return;
                }

                String violation = SourceIds.violation(sourceId, "source_id");
                if (violation != null) {
                    sendError(exchange, 400, violation);
                    return;
                }

                ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus(sourceId);
                if (status == null) {
                    sendError(exchange, 404, "No reindex has been run for source: " + sourceId);
                    return;
                }
                send(exchange, 200, toJson(status));

            } catch (Exception e) {
                LOGGER.error("Error processing reindex status request: {}", e.getMessage(), e);
                sendError(exchange, 500, "Internal Server Error");
            }
        }
    }

    /**
     * Renders every source that has been reindexed, in the envelope
     * {@code /v1/ingest/status} uses.
     */
    private void sendAll(HttpExchange exchange,
                         Map<String, ReindexEndpoint.ReindexStatus> statuses) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"sources\": [");
        boolean first = true;
        for (ReindexEndpoint.ReindexStatus status : statuses.values()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(toJson(status));
        }
        sb.append("]}");
        send(exchange, 200, sb.toString());
    }

    /**
     * Converts one status to JSON.
     *
     * <p>{@code documents_}, not {@code entries_}: a reindex counts documents, and the ingest
     * status's {@code entries_} names chunks. Giving the two the same name for different units
     * would be worse than giving them different names.</p>
     *
     * <p>{@code cancelled} is its own field rather than something to be read out of
     * {@code state}, for the same reason {@code /v1/query} carries one: a caller that has just
     * issued {@code DELETE /v1/reindex} is polling for exactly this fact, and making it
     * string-match a state name - or worse, an error message - to find it is how a deliberate
     * stop ends up filed as an outage. {@code finished_at_ms} is 0 while the run is going, and
     * with {@code started_at_ms} it is how long the run took.</p>
     */
    private String toJson(ReindexEndpoint.ReindexStatus status) {
        // Locale.ROOT, because the default locale picks the decimal separator: under de_DE a
        // progress of 0.73 is formatted "0,7300", and the comma ends the JSON value.
        return String.format(
            Locale.ROOT,
            "{"
                + "\"source_id\": \"%s\", "
                + "\"state\": \"%s\", "
                + "\"cancelled\": %b, "
                + "\"progress\": %.4f, "
                + "\"documents_processed\": %d, "
                + "\"documents_total\": %d, "
                + "\"started_at_ms\": %d, "
                + "\"finished_at_ms\": %d, "
                + "\"last_error\": %s"
                + "}",
            JsonText.escape(status.sourceId()),
            JsonText.escape(status.state()),
            status.cancelled(),
            status.progress(),
            status.entriesProcessed(),
            status.entriesTotal(),
            status.startTimeMs(),
            status.finishedAtMs(),
            status.lastError() != null
                ? "\"" + JsonText.escape(status.lastError()) + "\""
                : "null"
        );
    }

    private void send(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
    }

    private void sendError(HttpExchange exchange, int statusCode, String message)
            throws IOException {
        send(exchange, statusCode, String.format("{\"error\": \"%s\"}", JsonText.escape(message)));
    }
}
