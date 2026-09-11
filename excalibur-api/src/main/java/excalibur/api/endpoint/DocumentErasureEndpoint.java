package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.RequestBody;
import excalibur.api.protocol.ResponseBody;
import excalibur.ingest.erasure.DocumentErasureService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code POST /v1/erasure} - removes named documents from the corpus.
 *
 * <h3>Request</h3>
 * <pre>
 * {"source_id": "docs", "document_id": "handbook.md"}
 * {"source_id": "docs", "document_ids": ["a.md", "b.md"]}
 * {"source_id": "docs", "scope": "source"}
 * </pre>
 *
 * <p>Optionally {@code "compact": true} to rewrite the lexical index immediately rather than
 * leaving it to the policy - for a caller under a deadline who has to be able to say the data is
 * gone rather than that its removal is scheduled. It costs a rewrite of every segment holding a
 * tombstone, which is why it is not the default.</p>
 *
 * <h3>Erasing a whole source</h3>
 * <p>{@code "scope": "source"} erases what the node holds of a source without the caller having to
 * name its documents, which is the usual shape of an obligation about a person rather than a file.
 * It is a separate word rather than an omission on purpose: a request that meant "everything"
 * simply by leaving out an identifier is one a malformed client sends by accident.</p>
 *
 * <p>It also takes the source out of the crawl, and says so. Erasing every document a source
 * contributed while leaving it scheduled for re-crawl is not an erasure, it is a pause - the
 * scheduler restores the corpus within the hour. Removing a document from a source that keeps
 * serving it has the same problem, which nothing here can fix: that half of the obligation belongs
 * to whoever owns the source.</p>
 *
 * <p>Bounded per request, with {@code documents_remaining} saying how much is left, because the
 * work is a storage delete and an index probe per chunk. A large source is a loop of requests, and
 * the count is what tells a caller when the loop is done.</p>
 *
 * <h3>Why this is separate from reindexing</h3>
 * <p>A document also disappears when its source stops listing it and {@code POST /v1/reindex} runs.
 * That path removes the same things, and it is the right one for content that changed. It is the
 * wrong one for content someone asked to have removed: the document may still be in the source, and
 * "it will go when the next crawl notices" is not an answer such a caller can act on.</p>
 *
 * <h3>What the response reports</h3>
 * <p>Removal from storage and removal from the index are counted separately, because they are
 * different removals and either can fall short. {@code documents_not_found} is reported rather than
 * folded into success: a caller under an obligation needs to know this node never held the document
 * instead of assuming it was removed.</p>
 *
 * <p>What the counts do not cover is the vector indexes, which are reclaimed per segment rather
 * than per entry. Until the segment holding an erased chunk is compacted a vector query can still
 * rank it - without content, because that is resolved from storage, but under its identifier. The
 * reply does not claim otherwise.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the service it calls is safe for concurrent use.</p>
 */
public class DocumentErasureEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentErasureEndpoint.class);

    /**
     * Documents one request may name.
     *
     * <p>Bounded because the work is unbounded per document: every chunk is a storage delete and an
     * index probe, and a request naming a million documents would hold the mutate budget for as
     * long as it took. A caller with more than this has a loop, not a request.</p>
     */
    static final int MAX_DOCUMENTS = 1000;

    /** The word a request uses to ask for everything a source contributed. */
    private static final String SCOPE_SOURCE = "source";

    /** The default: only the documents the request names. */
    private static final String SCOPE_DOCUMENT = "document";

    private final DocumentErasureService erasureService;
    private final SourceRegistry sourceRegistry;

    /**
     * Creates the endpoint.
     *
     * @param erasureService The service that performs the removal
     * @throws NullPointerException if the service is null
     */
    public DocumentErasureEndpoint(DocumentErasureService erasureService) {
        this(erasureService, null);
    }

    /**
     * Creates the endpoint, able to take an erased source out of the crawl.
     *
     * @param erasureService The service that performs the removal
     * @param sourceRegistry The registered ingestion sources, or null on a node that keeps none.
     *                       Without it a source-scoped erasure still removes every document, and
     *                       says that the source was left registered - so a caller can see that
     *                       the next crawl will bring the documents back
     * @throws NullPointerException if the service is null
     */
    public DocumentErasureEndpoint(DocumentErasureService erasureService,
                                   SourceRegistry sourceRegistry) {
        this.erasureService = Objects.requireNonNull(erasureService,
            "erasureService cannot be null");
        this.sourceRegistry = sourceRegistry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405, "{\"error\": \"Method not allowed; use POST\"}");
            return;
        }

        String body;
        try {
            body = RequestBody.read(exchange);
        } catch (RequestBody.TooLargeException e) {
            ResponseBody.sendJson(exchange, 413, "{\"error\": \"Request body too large\"}");
            return;
        }

        String sourceId;
        String scope;
        List<String> documentIds;
        boolean compactNow;
        try {
            sourceId = requireSourceId(body);
            scope = scopeFrom(body);
            documentIds = SCOPE_SOURCE.equals(scope) ? List.of() : documentsFrom(body);
            // Read with the rest of the request, not after it. A body this cannot make sense of
            // is a bad request whichever field it stumbles on, and answering one malformed field
            // with a 500 tells the caller the node broke when it was the caller that did.
            compactNow = JsonText.booleanValue(body, "compact", false);
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
            return;
        }

        try {
            boolean deregistered = false;
            DocumentErasureService.Outcome outcome;
            if (SCOPE_SOURCE.equals(scope)) {
                // Before erasing, not after: a crawl that ran in between would reingest the
                // documents this request is in the middle of removing. Removing the source also
                // stops a run of it that is in flight, though only at its next document
                // boundary - so one more document may land, and documents_remaining is what
                // shows it.
                deregistered = sourceRegistry != null && sourceRegistry.remove(sourceId);
                outcome = erasureService.eraseSource(sourceId, MAX_DOCUMENTS, compactNow);
            } else {
                outcome = erasureService.erase(sourceId, documentIds, compactNow);
            }

            // 200, not 202: unlike the graph's erasure this is not a queued obligation. The
            // content is out of storage and out of the index by the time this replies; only the
            // index's own reclamation may still be pending, and the response says which.
            ResponseBody.sendJson(exchange, 200,
                "{"
                    + "\"source_id\": \"" + JsonText.escape(sourceId) + "\", "
                    + "\"scope\": \"" + scope + "\", "
                    + "\"documents_erased\": " + outcome.documentsErased() + ", "
                    + "\"documents_not_found\": " + outcome.documentsNotFound() + ", "
                    + "\"documents_remaining\": " + outcome.documentsRemaining() + ", "
                    + "\"source_deregistered\": " + deregistered + ", "
                    + "\"chunks_erased\": " + outcome.chunksTombstoned() + ", "
                    + "\"chunks_unindexed\": " + outcome.chunksUnindexed() + ", "
                    + "\"vectors_hidden\": " + outcome.vectorsHidden() + ", "
                    + "\"compacted\": " + outcome.compacted() + ", "
                    + "\"chunks_reclaimed\": " + outcome.chunksReclaimed() + ", "
                    + "\"outstanding\": " + outcome.outstanding() + ", "
                    + "\"detail\": \"" + JsonText.escape(outcome.detail()) + "\""
                    + "}");
        } catch (SourceCatalogException e) {
            // Nothing has been erased: the source comes off the crawl before its documents go,
            // and that is the step that failed. A 500 would be true and would not say that the
            // request can simply be repeated once the catalog is writable again.
            LOGGER.error("Erasure of source {} could not deregister it: {}", sourceId, e.getMessage(), e);
            ResponseBody.sendJson(exchange, SourceCatalogException.STATUS_CODE,
                "{\"error\": \"" + SourceCatalogException.CLIENT_MESSAGE + "\"}");
        } catch (RuntimeException e) {
            // Logged with detail, answered without it: the message names the path the node
            // failed on, which a client has no business seeing.
            LOGGER.error("Erasure of {} document(s) from source {} failed",
                documentIds.size(), sourceId, e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    /**
     * What the request asked to erase: the documents it names, or everything the source holds.
     *
     * @throws IllegalArgumentException if the scope is unknown, or names both a source and
     *                                  particular documents
     */
    private static String scopeFrom(String body) {
        String scope = JsonText.string(body, "scope");
        if (scope == null || scope.isBlank()) {
            return SCOPE_DOCUMENT;
        }
        String value = scope.trim().toLowerCase(Locale.ROOT);
        if (SCOPE_DOCUMENT.equals(value)) {
            return SCOPE_DOCUMENT;
        }
        if (!SCOPE_SOURCE.equals(value)) {
            throw new IllegalArgumentException(
                "unknown scope '" + scope + "'; expected document or source");
        }
        if (JsonText.string(body, "document_id") != null
                || JsonText.stringArray(body, "document_ids") != null) {
            // Two different requests. Guessing which was meant risks erasing a whole source
            // because a client sent a stale field beside the new one.
            throw new IllegalArgumentException(
                "scope 'source' erases every document of the source, so it cannot be combined "
                    + "with 'document_id' or 'document_ids'");
        }
        return SCOPE_SOURCE;
    }

    /** The source the documents were ingested under. */
    private static String requireSourceId(String body) {
        String sourceId = JsonText.string(body, "source_id");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("'source_id' is required");
        }
        String violation = SourceIds.violation(sourceId, "source_id");
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
        return sourceId;
    }

    /**
     * The documents a request names, from either field.
     *
     * <p>Both spellings, because erasing one document is the common case and making a caller wrap
     * it in an array to do the obvious thing invites the mistake of erasing the wrong shape.</p>
     */
    private static List<String> documentsFrom(String body) {
        List<String> documents = new ArrayList<>();
        String single = JsonText.string(body, "document_id");
        if (single != null && !single.isBlank()) {
            documents.add(single);
        }
        String[] many = JsonText.stringArray(body, "document_ids");
        if (many != null) {
            for (String documentId : many) {
                if (documentId != null && !documentId.isBlank()) {
                    documents.add(documentId);
                }
            }
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException(
                "'document_id' or a non-empty 'document_ids' array is required");
        }
        if (documents.size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("at most " + MAX_DOCUMENTS
                + " documents per request, got " + documents.size());
        }
        return documents;
    }
}
