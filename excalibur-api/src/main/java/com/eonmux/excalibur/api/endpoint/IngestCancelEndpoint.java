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
 * {@code DELETE /v1/ingest/run} - stops the crawl of a source and keeps the source.
 *
 * <h3>Request</h3>
 * <pre>
 * {"source_id": "project-docs"}
 * </pre>
 *
 * <h3>Why this exists separately from deregistration</h3>
 * <p>{@code DELETE /v1/ingest} also stops a crawl, but it is inseparable from cancelling the
 * registration: the catalog record goes, the {@code /v1/ingest/status} entry goes, the
 * {@code /v1/reindex/status} entry goes, and the source comes out of the re-crawl schedule.
 * That is the right operation for "this source should no longer be read" and the wrong one for
 * "this run is doing damage right now" - a crawl hammering a site, or walking a root that turned
 * out to hold far more than whoever registered it expected. Reaching for deregistration under
 * that kind of time pressure means the way back is re-registering, which starts the very crawl
 * that was just stopped, from the beginning.</p>
 *
 * <p>So this route stops the run and changes nothing else. The source stays registered, keeps
 * its status entry and keeps its place in the schedule, and is crawled again at its next
 * interval - by which time whatever made this run a problem can have been fixed, with
 * {@code DELETE /v1/ingest} still there for the case where it cannot.</p>
 *
 * <h3>What "stopped" means</h3>
 * <p>The same thing it means on {@code DELETE /v1/reindex}: the run is asked to stop and stops
 * at its next document boundary, so a document already being written is finished rather than
 * abandoned half-way. {@code GET /v1/ingest/status} is what says when it has.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the registry it calls is thread-safe.</p>
 */
public class IngestCancelEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestCancelEndpoint.class);

    private final SourceRegistry sourceRegistry;

    /**
     * Creates the endpoint.
     *
     * @param sourceRegistry The registered ingestion sources
     * @throws NullPointerException if the registry is null
     */
    public IngestCancelEndpoint(SourceRegistry sourceRegistry) {
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry,
            "sourceRegistry cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405,
                "{\"error\": \"Method not allowed; use DELETE to stop an ingestion run\"}");
            return;
        }

        String sourceId = sourceIdOf(exchange);
        if (sourceId == null) {
            return;
        }

        if (!sourceRegistry.isRegistered(sourceId)) {
            // 404 rather than a quiet success, as deregistration does: a caller stopping a crawl
            // has to know whether it named a source this node is crawling, or misspelled one
            // that is still running.
            ResponseBody.sendJson(exchange, 404,
                "{\"error\": \"Source not registered: " + JsonText.escape(sourceId) + "\"}");
            return;
        }

        boolean stopped = sourceRegistry.stopIngestion(sourceId);
        if (stopped) {
            LOGGER.info("Ingestion of source {} was asked to stop on request; the source stays "
                + "registered", sourceId);
        }
        // 200 either way, with the fact in the body, exactly as DELETE /v1/reindex answers.
        // "nothing was running" is an answer and not an error: the state the caller asked for
        // is the state it now has.
        ResponseBody.sendJson(exchange, 200,
            "{"
                + "\"source_id\": \"" + JsonText.escape(sourceId) + "\", "
                + "\"stopped\": " + stopped + ", "
                + "\"still_registered\": true, "
                + "\"detail\": \"" + (stopped
                    ? "Ingestion was asked to stop; poll /v1/ingest/status for when it has. "
                        + "The source stays registered and is crawled again at its next "
                        + "scheduled interval."
                    : "No ingestion run of this source is in flight. The source stays "
                        + "registered.")
                + "\"}");
    }

    /**
     * Reads and validates the source id, answering the caller itself when it cannot.
     *
     * @param exchange The request
     * @return The id, or null when the caller has already been answered
     * @throws IOException if the response cannot be written
     */
    private static String sourceIdOf(HttpExchange exchange) throws IOException {
        String body;
        try {
            body = RequestBody.read(exchange);
        } catch (RequestBody.TooLargeException e) {
            ResponseBody.sendJson(exchange, 413, "{\"error\": \"Request body too large\"}");
            return null;
        }

        String sourceId = JsonText.string(body, "source_id");
        if (sourceId == null || sourceId.isBlank()) {
            ResponseBody.sendJson(exchange, 400, "{\"error\": \"'source_id' is required\"}");
            return null;
        }
        String violation = SourceIds.violation(sourceId, "source_id");
        if (violation != null) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(violation) + "\"}");
            return null;
        }
        return sourceId;
    }
}
