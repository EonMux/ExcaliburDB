package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphErasureService;
import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.RequestBody;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

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
 * <h3>The graph half</h3>
 * <p>A document that was ingested into the knowledge graph left two things there that removing
 * its chunks does not touch: the facts an extractor read out of it, and up to two kilobytes of
 * its text against every entity it mentioned. Both are keyed by a provenance id derived from the
 * document's key, so withdrawing them is a source-scoped graph obligation per document - which
 * is submitted here, for exactly the documents that were actually erased.
 *
 * <p>That half is a queued obligation rather than an immediate removal, so this reply can no
 * longer be a flat 200. When the graph still owes something the status is 202 and
 * {@code graph_erasure_complete} is false: the content is out of the corpus, an obligation for
 * the rest is recorded durably, and a caller under an obligation of their own must be able to
 * see the difference. {@code "compact": true} discharges both halves before replying, which is
 * what turns it back into a 200.
 *
 * <p>A node serving no graph reports nothing submitted and {@code graph_erasure_complete} true,
 * because nothing was ever extracted into one.
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
 * <h3>When the node could not finish</h3>
 * <p>A document whose chunks would not all come out of storage or out of the index is counted in
 * {@code documents_incomplete}, and the reply is a 500 rather than a 200.
 * {@code chunks_not_removed} says how many are still in storage and
 * {@code chunks_still_searchable} how many the lexical index still matches; they are separate
 * numbers rather than one total because a chunk can be in both states, and adding them would
 * report twice as much content left behind as there is. It is a failure and not a partial success: the caller
 * asked for content to be gone, some of it is not, and the remedy is to repeat the request - which
 * is safe, because the registry entry is kept for exactly the documents that fell short, so the
 * repeat can still name their chunks. A caller looping a source erasure until
 * {@code documents_remaining} reaches zero has to watch this too, or it will loop forever over
 * documents the node keeps failing to erase.</p>
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
    private final InlineSourceIds suppliedSources;

    /**
     * The namespaces whose graphs hold what was extracted from these documents, or null.
     *
     * <p>The shared one is the only one that can: ingestion is not namespace-aware and hands
     * every published document to one sink, which stages into the shared tenant. Reading the
     * namespace header here would submit the obligation against a graph that never held the
     * facts, and report it discharged.
     */
    private final GraphTenants graphTenants;

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
        this(erasureService, sourceRegistry, null);
    }

    /**
     * Creates the endpoint, able to release an erased source's id for reuse.
     *
     * @param erasureService  The service that performs the removal
     * @param sourceRegistry  The registered ingestion sources, or null on a node that keeps none
     * @param suppliedSources The ids documents have been supplied directly under, so that a
     *                        source erased down to nothing stops refusing a crawl under its id,
     *                        or null on a node that takes no supplied documents
     * @throws NullPointerException if the service is null
     */
    public DocumentErasureEndpoint(DocumentErasureService erasureService,
                                   SourceRegistry sourceRegistry,
                                   InlineSourceIds suppliedSources) {
        this(erasureService, sourceRegistry, suppliedSources, null);
    }

    /**
     * Creates the endpoint, able to withdraw the erased documents from the graph as well.
     *
     * @param erasureService  The service that performs the removal
     * @param sourceRegistry  The registered ingestion sources, or null on a node that keeps none
     * @param suppliedSources The ids documents have been supplied directly under, or null
     * @param graphTenants    The namespaces whose graphs hold what was extracted from these
     *                        documents, or null on a node that serves no graph. Without it an
     *                        erasure removes the document's text and leaves every fact read out
     *                        of it, and up to two kilobytes of that text per entity mentioned,
     *                        in the graph and in the staging log the next build replays
     * @throws NullPointerException if the service is null
     */
    public DocumentErasureEndpoint(DocumentErasureService erasureService,
                                   SourceRegistry sourceRegistry,
                                   InlineSourceIds suppliedSources,
                                   GraphTenants graphTenants) {
        this.erasureService = Objects.requireNonNull(erasureService,
            "erasureService cannot be null");
        this.sourceRegistry = sourceRegistry;
        this.suppliedSources = suppliedSources;
        this.graphTenants = graphTenants;
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
                // After the erasure, not before it: until the documents are gone, a crawl
                // registered under this id would still delete them. Released afterwards so the
                // id can be reused - the supplied half of that guard has no other way to be
                // told that what it was protecting is gone.
                if (suppliedSources != null) {
                    suppliedSources.forget(sourceId);
                }
            } else {
                outcome = erasureService.erase(sourceId, documentIds, compactNow);
            }

            sendOutcome(exchange, sourceId, scope, deregistered, outcome,
                eraseFromGraph(sourceId, outcome.erasedDocumentIds(), compactNow));
        } catch (SourceCatalogException e) {
            // Nothing has been erased: the source comes off the crawl before its documents go,
            // and that is the step that failed. A 500 would be true and would not say that the
            // request can simply be repeated once the catalog is writable again.
            LOGGER.error("Erasure of source {} could not deregister it: {}", sourceId, e.getMessage(), e);
            ResponseBody.sendJson(exchange, SourceCatalogException.STATUS_CODE,
                "{\"error\": \"" + SourceCatalogException.CLIENT_MESSAGE + "\"}");
        } catch (IOException | RuntimeException e) {
            // IOException as well as RuntimeException: recording the graph obligation is a
            // durable write, and one that fails must be answered rather than escaping the
            // handler with no response sent at all.
            //
            // Logged with detail, answered without it: the message names the path the node
            // failed on, which a client has no business seeing.
            LOGGER.error("Erasure of {} document(s) from source {} failed",
                documentIds.size(), sourceId, e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    /**
     * Withdraws from the graph what was extracted from the documents that were actually erased.
     *
     * <p>Only the ones that came out whole. Submitting an obligation for a document whose
     * chunks are still in storage would withdraw its facts while its text is still being served,
     * which is the wrong half to erase first and would leave the reply claiming a graph erasure
     * for content the corpus still holds.
     *
     * @param sourceId    The source the documents belong to
     * @param documentIds The documents this node no longer holds any part of
     * @param compactNow  Rewrite the served graph now rather than leaving it to the policy
     * @return What the graph half did
     * @throws IOException if an obligation cannot be recorded or the compaction fails
     */
    private GraphErasureService.DocumentGraphOutcome eraseFromGraph(
            String sourceId, List<String> documentIds, boolean compactNow) throws IOException {
        if (graphTenants == null) {
            return GraphErasureService.DocumentGraphOutcome.NO_GRAPH;
        }
        List<String> keys = new ArrayList<>(documentIds.size());
        for (String documentId : documentIds) {
            keys.add(ChunkIdentifiers.documentKey(sourceId, documentId));
        }
        try (NamespaceRegistry.Lease<GraphTenant> lease =
                 graphTenants.borrow(NamespaceId.SHARED)) {
            GraphErasureService.DocumentGraphOutcome outcome =
                lease.resource().erasureService().eraseDocuments(keys, compactNow);
            if (outcome.compacted()) {
                // The compaction reopened the rewritten store into the tenant's holder already.
                // What is left is the accounting: the store it now maps is not the one the
                // mapping budget was told about.
                graphTenants.recost(NamespaceId.SHARED);
            }
            return outcome;
        }
    }

    /**
     * Answers with what the erasure actually did.
     *
     * <p>200 and not 202: unlike the graph's erasure this is not a queued obligation. The content
     * is out of storage and out of the index by the time this replies; only the index's own
     * reclamation may still be pending, and the response says which. The exception is a document
     * the node could not finish, which is a 500 carrying the same counts - see the class
     * documentation for why that is a failure rather than a partial success.</p>
     *
     * @param exchange     The request to answer
     * @param sourceId     The source the request named
     * @param scope        What it asked to erase
     * @param deregistered Whether the source was taken out of the crawl
     * @param outcome      What the service reports it did
     * @param graph        What the graph half of the erasure managed
     * @throws IOException if the response cannot be written
     */
    private static void sendOutcome(HttpExchange exchange, String sourceId, String scope,
                                    boolean deregistered,
                                    DocumentErasureService.Outcome outcome,
                                    GraphErasureService.DocumentGraphOutcome graph)
            throws IOException {
        boolean incomplete = outcome.documentsIncomplete() > 0;
        if (incomplete) {
            LOGGER.error("Erasure of source {} left {} document(s) unfinished: {} chunk(s) "
                    + "still in storage, {} still searchable: {}", sourceId,
                outcome.documentsIncomplete(), outcome.chunksNotRemoved(),
                outcome.chunksStillSearchable(), outcome.detail());
        }
        // 500 first, then 202, then 200. A document the corpus could not release is a failure;
        // a graph obligation recorded and not yet carried out is an acceptance; only both
        // halves being done is a completion. Collapsing the middle case into 200 is what would
        // let a partial erasure read as a complete one.
        ResponseBody.sendJson(exchange, incomplete ? 500 : graph.complete() ? 200 : 202,
            "{"
                + (incomplete ? "\"error\": \"Some documents could not be fully erased; "
                    + "repeat the request\", " : "")
                + "\"source_id\": \"" + JsonText.escape(sourceId) + "\", "
                + "\"scope\": \"" + scope + "\", "
                + "\"documents_erased\": " + outcome.documentsErased() + ", "
                + "\"documents_incomplete\": " + outcome.documentsIncomplete() + ", "
                + "\"documents_not_found\": " + outcome.documentsNotFound() + ", "
                + "\"documents_remaining\": " + outcome.documentsRemaining() + ", "
                + "\"source_deregistered\": " + deregistered + ", "
                + "\"chunks_erased\": " + outcome.chunksTombstoned() + ", "
                + "\"chunks_unindexed\": " + outcome.chunksUnindexed() + ", "
                + "\"chunks_not_removed\": " + outcome.chunksNotRemoved() + ", "
                + "\"chunks_still_searchable\": " + outcome.chunksStillSearchable() + ", "
                + "\"vectors_hidden\": " + outcome.vectorsHidden() + ", "
                + "\"compacted\": " + outcome.compacted() + ", "
                + "\"chunks_reclaimed\": " + outcome.chunksReclaimed() + ", "
                + "\"outstanding\": " + outcome.outstanding() + ", "
                // The graph half, reported separately because it is a separate removal that can
                // fall short on its own. A caller reading only the corpus counts would think a
                // document was gone while every fact read out of it was still being served.
                + "\"graph_requests_submitted\": " + graph.submitted() + ", "
                + "\"graph_requests_satisfied\": " + graph.satisfied() + ", "
                + "\"graph_requests_withheld\": " + graph.withheld() + ", "
                + "\"graph_outstanding\": " + graph.outstanding() + ", "
                + "\"graph_compacted\": " + graph.compacted() + ", "
                + "\"graph_erasure_complete\": " + graph.complete() + ", "
                + "\"graph_detail\": \"" + JsonText.escape(graph.detail()) + "\", "
                + "\"detail\": \"" + JsonText.escape(outcome.detail()) + "\""
                + "}");
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
