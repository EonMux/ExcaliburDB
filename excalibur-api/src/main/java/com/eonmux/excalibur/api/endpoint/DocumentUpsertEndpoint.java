package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.audit.AuditLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.RequestBody;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.processor.TextContent;
import com.eonmux.excalibur.ingest.source.InlineSource;
import com.eonmux.excalibur.ingest.source.Source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code POST /v1/documents} - indexes documents a client supplies directly.
 *
 * <h3>Request</h3>
 * <pre>
 * {"source_id": "notes",
 *  "documents": [{"document_id": "a.md", "text": "...", "content_type": "text/plain"}]}
 * </pre>
 *
 * <h3>Why this exists alongside {@code /v1/ingest}</h3>
 * <p>{@code /v1/ingest} registers a target to crawl, and a crawl target has to be allow-listed -
 * a filesystem root the operator named, or a host the operator named. That is the right policy
 * for a node that goes and reads things by itself, and it leaves no way in at all for a client
 * that already holds the documents and has nowhere the node is permitted to read them from. A
 * default node is therefore deny-by-default to every corpus that is not already on its disk.
 * This route is the other direction: the caller brings the content, already authenticated, and
 * no target is read on its behalf, so there is nothing to allow-list.</p>
 *
 * <h3>Why it answers when the work is done</h3>
 * <p>A crawl is of unknown size and is answered with 202 and a status to poll. A batch in the
 * caller's hand is not: it is ingested inside this request, and the reply says what happened to
 * it. A caller that has to poll to learn whether the documents it just sent were accepted has
 * been given a worse answer than the one that was available.</p>
 *
 * <h3>Re-posting a document</h3>
 * <p>Posting a document identifier again replaces what the node holds under it, because that is
 * what ingesting a document that is already indexed does. There is no separate update call.</p>
 *
 * <h3>Why a crawled source's id is refused</h3>
 * <p>Documents recorded under a crawl's source id are removed the next time that crawl is
 * reindexed, because the crawl does not list them and reindexing removes what a source has
 * stopped listing. The data loss would happen later, silently, for a reason the caller could not
 * have seen at the time, so the id is refused up front instead.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the orchestrator it calls refuses a second concurrent run over one source.</p>
 */
public final class DocumentUpsertEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentUpsertEndpoint.class);

    /** The field naming what the documents belong to. */
    private static final String SOURCE_ID = "source_id";

    /** The field carrying the documents. */
    private static final String DOCUMENTS = "documents";

    private final IngestionOrchestrator orchestrator;
    private final SourceRegistry crawlSources;
    private final InlineSourceIds suppliedSources;
    private final int maxDocuments;

    /**
     * Creates the endpoint.
     *
     * @param orchestrator    Runs the ingestion pipeline over the supplied documents
     * @param crawlSources    The registered crawl sources, whose ids this route refuses, or null
     *                        on a node that keeps none
     * @param suppliedSources Records the ids taken here, so that a crawl registered over one is
     *                        refused too, or null to record none
     * @param maxDocuments    Most documents one request may carry
     * @throws NullPointerException     if the orchestrator is null
     * @throws IllegalArgumentException if maxDocuments is not positive
     */
    public DocumentUpsertEndpoint(IngestionOrchestrator orchestrator, SourceRegistry crawlSources,
                                  InlineSourceIds suppliedSources, int maxDocuments) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
        this.crawlSources = crawlSources;
        this.suppliedSources = suppliedSources;
        if (maxDocuments <= 0) {
            throw new IllegalArgumentException("maxDocuments must be positive: " + maxDocuments);
        }
        this.maxDocuments = maxDocuments;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405,
                "{\"error\": \"Method not allowed; use POST to supply documents\"}");
            return;
        }

        String sourceId;
        InlineSource source;
        try {
            String body = RequestBody.read(exchange);
            sourceId = requireSourceId(body);
            AuditLog.target(exchange, "source_id", sourceId);
            source = InlineSource.of(sourceId, documentsFrom(body));
        } catch (RequestBody.TooLargeException e) {
            ResponseBody.sendJson(exchange, 413, "{\"error\": \"Request body too large\"}");
            return;
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
            return;
        }

        if (!claim(exchange, sourceId)) {
            return;
        }

        ingest(exchange, sourceId, source);
    }

    /**
     * Runs the pipeline over the supplied documents and reports what it did.
     *
     * @param exchange The request to answer
     * @param sourceId The id the documents were supplied under
     * @param source   The batch, as a source the pipeline can crawl
     * @throws IOException if the response cannot be written
     */
    private void ingest(HttpExchange exchange, String sourceId, InlineSource source)
            throws IOException {
try {
            IngestionOrchestrator.IngestionOutcome outcome = orchestrator.ingestNow(source);
            ResponseBody.sendJson(exchange, 200,
                "{"
                    + "\"" + SOURCE_ID + "\": \"" + JsonText.escape(sourceId) + "\", "
                    + "\"documents_ingested\": " + outcome.documentsIngested() + ", "
                    + "\"chunks_ingested\": " + outcome.chunksIngested() + ", "
                    + "\"documents_failed\": " + outcome.documentsFailed() + ", "
                    // A run cut short by shutdown has ingested a prefix of what was sent, and
                    // the counts alone read as a complete run over a smaller batch.
                    + "\"stopped\": " + outcome.stopped()
                    + "}");
        } catch (IllegalStateException e) {
            // The source is already being ingested, or the node is stopping. Both are states the
            // same request succeeds in once they pass, which is what 409 tells a caller.
            LOGGER.warn("Refused a document batch for {}: {}", sourceId, e.getMessage());
            ResponseBody.sendJson(exchange, 409,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
        } catch (IngestionOrchestrator.IngestionFailedException e) {
            // Not 200 with zero counts: a run that failed outright and a run that ingested
            // nothing are different outcomes, and the caller acts differently on each.
            //
            // The reason goes to the log and not to the caller. What fails here is an internal
            // stage - an embedding endpoint, a storage write - and those messages carry
            // endpoint URLs and filesystem paths that the caller has no business learning from
            // a document it posted. The status code is what the caller acts on either way.
            LOGGER.error("Ingestion of a supplied batch for {} failed: {}",
                sourceId, e.getMessage(), e);
            ResponseBody.sendJson(exchange, 500,
                "{\"error\": \"Ingestion failed; see the node's log for the reason\"}");
        }
    }

    /**
     * Takes the id for supplied documents, or refuses the request.
     *
     * <p>Two things can refuse it. The id may belong to a crawl, whose first pass removes every
     * document under it the crawl did not list - which is every document supplied here, a loss
     * the caller cannot see coming. Or this node may already hold supplied documents under as
     * many ids as it is configured for.</p>
     *
     * <p>The crawl is checked on both sides of the claim. Both routes publish their claim
     * before their last read - this one records the id, the crawl route registers it - so
     * whichever publishes second sees the first and the pair can never both proceed.</p>
     *
     * @param exchange The request, answered here when the id cannot be taken
     * @param sourceId The id the documents were supplied under
     * @return true when the id is this request's to write under
     * @throws IOException if a refusal cannot be written
     */
    private boolean claim(HttpExchange exchange, String sourceId) throws IOException {
        if (crawlSources != null && crawlSources.isRegistered(sourceId)) {
            ResponseBody.sendError(exchange, 409, ErrorCode.SOURCE_KIND_CONFLICT, "Source '"
                + sourceId + "' is a registered crawl source; documents supplied under its id "
                + "would be removed by the next reindex of that crawl. Use a different source_id.");
            return false;
        }

        // Recorded before the write, not after, so a crawl registration arriving while this run
        // is still going is refused as well. A run that then fails leaves the id recorded, which
        // is the safe direction: a failed run can still have written part of the batch.
        if (suppliedSources != null && !suppliedSources.remember(sourceId)) {
            LOGGER.warn("Refused a document batch for {}: the supplied-source limit is reached",
                sourceId);
            ResponseBody.sendError(exchange, 429, ErrorCode.SOURCE_LIMIT_REACHED,
                "This node already holds supplied documents under as many "
                    + "source_ids as it is configured for. Add to one of them, or raise "
                    + "excalibur.ingest.max-sources.");
            return false;
        }

        if (crawlSources != null && crawlSources.isRegistered(sourceId)) {
            ResponseBody.sendError(exchange, 409, ErrorCode.SOURCE_KIND_CONFLICT, "Source '"
                + sourceId + "' was registered as a crawl source while this request was being "
                + "handled; documents supplied under its id would be removed by the next reindex "
                + "of that crawl. Use a different source_id.");
            return false;
        }
        return true;
    }

    /**
     * Reads the source the documents belong to.
     *
     * @param body The request body
     * @return The source id
     * @throws IllegalArgumentException if it is absent or empty
     */
    private static String requireSourceId(String body) {
        String sourceId = JsonText.string(body, SOURCE_ID);
        if (sourceId == null || sourceId.isEmpty()) {
            throw new IllegalArgumentException("Field '" + SOURCE_ID + "' is required");
        }
        return sourceId;
    }

    /**
     * Reads the documents a request carries.
     *
     * @param body The request body
     * @return The documents, in the order given
     * @throws IllegalArgumentException if the field is absent, empty, too long, or an element is
     *                                  missing what a document needs
     */
    private List<InlineSource.Document> documentsFrom(String body) {
        String[] raw = JsonText.objectArray(body, DOCUMENTS);
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("Field '" + DOCUMENTS
                + "' is required and must carry at least one document");
        }
        // The body as a whole is already bounded, so this is not about bytes: each document is a
        // chunking pass, an embedding call and a set of index writes, and they all happen inside
        // one request that a caller is waiting on.
        if (raw.length > maxDocuments) {
            throw new IllegalArgumentException("Field '" + DOCUMENTS + "' must carry at most "
                + maxDocuments + " documents: " + raw.length);
        }
        List<InlineSource.Document> documents = new ArrayList<>(raw.length);
        for (String element : raw) {
            String documentId = JsonText.string(element, "document_id");
            if (documentId == null || documentId.isEmpty()) {
                throw new IllegalArgumentException(
                    "Every document needs a non-empty 'document_id'");
            }
            String text = JsonText.string(element, "text");
            // Refused rather than indexed as empty: a document with no text produces no chunks,
            // so it would be reported as ingested while being unfindable by any query.
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException(
                    "Document '" + documentId + "' needs non-empty 'text'");
            }
            // The same rule a crawl applies to the bytes it reads, applied here at the boundary
            // instead: a caller who supplies content can be told what is wrong with it, where a
            // crawl can only pass the file over and count it.
            if (!TextContent.isText(text)) {
                throw new IllegalArgumentException(
                    "Document '" + documentId + "' has 'text' that is not text");
            }
            String contentType = JsonText.string(element, "content_type");
            documents.add(new InlineSource.Document(documentId,
                text.getBytes(StandardCharsets.UTF_8),
                contentType == null || contentType.isEmpty() ? "text/plain" : contentType,
                JsonText.longValue(element, "last_modified_millis", 0L)));
        }
        return documents;
    }
}
