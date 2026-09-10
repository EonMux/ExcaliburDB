package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.RequestBody;
import excalibur.api.protocol.ResponseBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * {@code DELETE /v1/ingest} - stops crawling a source, without touching what it already indexed.
 *
 * <h3>Request</h3>
 * <pre>
 * {"source_id": "project-docs"}
 * </pre>
 *
 * <h3>Why this exists separately from erasure</h3>
 * <p>{@code POST /v1/erasure} with {@code "scope": "source"} also deregisters, because erasing a
 * source's documents while it stays scheduled is a pause rather than an erasure. But the reverse is
 * a different operation: stopping a crawl is routine - a source moved, a feed went stale, a job is
 * finished - and it must not require destroying what the source contributed. Without this route the
 * only way to stop crawling would be the one that also deletes, which is a bad thing to make an
 * operator reach for under time pressure.</p>
 *
 * <h3>What it stops</h3>
 * <p>Everything registering the source started: an ingestion run in flight, its
 * {@code /v1/ingest/status} entry, and its place in the re-crawl schedule. The documents it already
 * indexed stay, and stay queryable. A run is stopped at its next document boundary, so a document
 * already being written is finished rather than abandoned half-way.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the registry it calls is thread-safe.</p>
 */
public class SourceDeregisterEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceDeregisterEndpoint.class);

    private final SourceRegistry sourceRegistry;

    /**
     * Creates the endpoint.
     *
     * @param sourceRegistry The registered ingestion sources
     * @throws NullPointerException if the registry is null
     */
    public SourceDeregisterEndpoint(SourceRegistry sourceRegistry) {
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry,
            "sourceRegistry cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405, "{\"error\": \"Method not allowed; use DELETE\"}");
            return;
        }

        String body;
        try {
            body = RequestBody.read(exchange);
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 413, "{\"error\": \"Request body too large\"}");
            return;
        }

        String sourceId = JsonText.string(body, "source_id");
        if (sourceId == null || sourceId.isBlank()) {
            ResponseBody.sendJson(exchange, 400, "{\"error\": \"'source_id' is required\"}");
            return;
        }
        String violation = SourceIds.violation(sourceId, "source_id");
        if (violation != null) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(violation) + "\"}");
            return;
        }

        boolean removed;
        try {
            removed = sourceRegistry.remove(sourceId);
        } catch (SourceCatalogException e) {
            // The source is still registered and still being crawled. Reporting success here
            // would leave the caller believing a crawl had stopped that restarts itself at the
            // next boot; the detail is for the log, and the response names no path.
            LOGGER.error("Source deregistration could not be recorded: {}", e.getMessage(), e);
            ResponseBody.sendJson(exchange, SourceCatalogException.STATUS_CODE,
                "{\"error\": \"" + SourceCatalogException.CLIENT_MESSAGE + "\"}");
            return;
        }
        if (!removed) {
            // 404, not a quiet success: a caller stopping a crawl needs to know whether they named
            // a source this node was actually crawling, or misspelled one that still is.
            ResponseBody.sendJson(exchange, 404,
                "{\"error\": \"Source not registered: " + JsonText.escape(sourceId) + "\"}");
            return;
        }

        LOGGER.info("Deregistered source {} on request; its indexed documents were kept", sourceId);
        ResponseBody.sendJson(exchange, 200,
            "{"
                + "\"source_id\": \"" + JsonText.escape(sourceId) + "\", "
                + "\"deregistered\": true, "
                + "\"documents_kept\": true, "
                + "\"detail\": \"Crawling stopped. Documents already indexed are unchanged and "
                + "still queryable; POST /v1/erasure removes them.\""
                + "}");
    }
}
