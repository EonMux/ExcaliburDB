package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.endpoint.IngestStatusEndpoint;
import com.eonmux.excalibur.ingest.lsh.LshIngestionService;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.processor.TermFrequencyTracker;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Attaching an ingestion pipeline, and everything that follows from having one.
 *
 * <p>Three overloads on the public API offer a node three ways to say the same thing, differing
 * only in where the embedding client and the visibility tracker come from. What each of them
 * then has to do is identical and is not small: build an orchestrator over the storage engine,
 * the index orchestrator and the status registry; decide whether the atomicity filter goes onto
 * the query pipeline; read the document timeout out of configuration. Written three times that
 * drifts, and it drifted here before - which is why the knob, the timeout and the orchestrator
 * are each read in exactly one place below.</p>
 *
 * <p>Separate from {@link ServerWiring} because it is the one attachment that changes something
 * the node already had. Every other {@code with...} call stores a reference; this one also
 * replaces the query pipeline, because the atomicity filter is a wrapper rather than a setting.
 * Returning an {@link Attachment} instead of reaching back into the caller's fields is what
 * keeps that replacement visible: the caller can see it is being handed a new pipeline, rather
 * than discovering later that its own has been swapped underneath it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every field is final, and an {@link Attachment} is a value - nothing here is
 * mutated by attaching, so the only shared state involved is what the caller does with the
 * result. The two {@code setVectorPrefilter} calls are the exception and are deliberate: the
 * index orchestrator and the storage engine own the pre-filter from that point on.</p>
 *
 * @author ExcaliburDB
 */
final class IngestionPipelineWiring {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionPipelineWiring.class);

    private final Config config;
    private final StorageEngine storageEngine;
    private final IndexOrchestrator indexOrchestrator;
    private final IngestStatusEndpoint.IngestionStatusRegistry ingestionStatus;

    /**
     * What attaching a pipeline produced, for the caller to hold in place of what it had.
     *
     * @param orchestrator  The background ingestion this node now runs
     * @param embeddings    The embedding client the query side also embeds through, or null
     *                      for a lexical-only node
     * @param queryPipeline The query pipeline, wrapped in the atomicity filter where that
     *                      applies and unchanged where it does not
     */
    record Attachment(IngestionOrchestrator orchestrator,
                      EmbeddingClient embeddings,
                      QueryPipeline queryPipeline) {
    }

    /**
     * Creates the wiring for one node's ingestion.
     *
     * @param config            Endpoint and ingestion defaults, or null for the compiled-in ones
     * @param storageEngine     Where chunks are written
     * @param indexOrchestrator Owns the visibility clock and the integration worker
     * @param ingestionStatus   What {@code GET /v1/ingest/status} serves
     * @throws NullPointerException if a required collaborator is null
     */
    IngestionPipelineWiring(Config config,
                            StorageEngine storageEngine,
                            IndexOrchestrator indexOrchestrator,
                            IngestStatusEndpoint.IngestionStatusRegistry ingestionStatus) {
        this.config = config;
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.indexOrchestrator =
            Objects.requireNonNull(indexOrchestrator, "indexOrchestrator cannot be null");
        this.ingestionStatus =
            Objects.requireNonNull(ingestionStatus, "ingestionStatus cannot be null");
    }

    /**
     * Whether a document's chunks become visible to queries together.
     *
     * <p>On, the atomicity filter is installed on the query pipeline and a query never sees
     * half of a document being ingested. Off, chunks become visible as they land, which is
     * cheaper and is what {@code excalibur.ingest.document-atomicity=false} asks for.</p>
     *
     * <p>A node built without a {@link Config} has it on: atomicity is the safe reading, and
     * the absence of configuration is not a request to drop a correctness guarantee.</p>
     *
     * @return true when the filter should run
     */
    boolean isDocumentAtomicityEnabled() {
        return config == null || config.ingest().isDocumentAtomicityEnabled();
    }

    /**
     * How long one document's ingestion may wait on HNSW backpressure.
     *
     * <p>{@code excalibur.ingest.document-atomicity-timeout-ms}, or the orchestrator's own
     * default where this node was built without a {@link Config}.</p>
     *
     * @return The timeout in milliseconds
     */
    private int configuredDocumentTimeoutMs() {
        return config != null
            ? config.ingest().getDocumentTimeoutMs()
            : IngestionOrchestrator.DEFAULT_DOCUMENT_TIMEOUT_MS;
    }

    /**
     * Attaches a pipeline that embeds through the given LLM client, on a visibility tracker
     * this node creates for itself.
     *
     * @param textChunker   Splits documents into chunks
     * @param llmClient     The LLM client the embedding client is built from
     * @param queryPipeline The query pipeline the atomicity filter may wrap
     * @return What the caller should now hold
     * @throws NullPointerException if textChunker or llmClient is null
     */
    Attachment attach(TextChunker textChunker, LLMHttpClient llmClient,
                      QueryPipeline queryPipeline) {
        Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        Objects.requireNonNull(llmClient, "LLMHttpClient cannot be null");

        // Create dedicated EmbeddingClient from LLMHttpClient's config
        EmbeddingClient embeddings = new EmbeddingClient(llmClient.getConfig());

        // Document visibility on the node's own epoch clock -- the same counter the query
        // pipeline captures in stage 0. Sharing it is what makes the atomicity filter a
        // snapshot bound rather than a completeness check; a tracker with a private counter
        // would produce epochs the query has no way to compare against.
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(
            new DocumentRegistry(), indexOrchestrator.getVisibilityTracker());

        IngestionOrchestrator orchestrator = newOrchestrator(textChunker, embeddings, tracker);
        QueryPipeline wrapped = withDocumentAtomicity(queryPipeline, tracker);
        logAttached(embeddings, tracker, null);
        return new Attachment(orchestrator, embeddings, wrapped);
    }

    /**
     * Attaches a pipeline on a visibility tracker somebody else owns.
     *
     * <p>Sharing the tracker is the point: the application and this node then agree about which
     * documents are whole, rather than each keeping its own answer.</p>
     *
     * @param textChunker       Splits documents into chunks
     * @param embeddings        Embeds chunks
     * @param visibilityTracker Makes a document's chunks visible together, or null for none
     * @param queryPipeline     The query pipeline the atomicity filter may wrap
     * @return What the caller should now hold
     * @throws NullPointerException if textChunker or embeddings is null
     */
    Attachment attach(TextChunker textChunker, EmbeddingClient embeddings,
                      DocumentVisibilityTracker visibilityTracker, QueryPipeline queryPipeline) {
        Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        Objects.requireNonNull(embeddings, "EmbeddingClient cannot be null");

        IngestionOrchestrator orchestrator =
            newOrchestrator(textChunker, embeddings, visibilityTracker);
        QueryPipeline wrapped = withDocumentAtomicity(queryPipeline, visibilityTracker);
        logAttached(embeddings, visibilityTracker, null);
        return new Attachment(orchestrator, embeddings, wrapped);
    }

    /**
     * Attaches a pipeline and the vector pre-filter stage 2 of a query prunes with.
     *
     * @param textChunker        Splits documents into chunks
     * @param embeddings         Embeds chunks, or null for lexical-only ingestion
     * @param lshIngestionService The LSH ingestion service, or null to disable LSH
     * @param visibilityTracker  Makes a document's chunks visible together, or null for none
     * @param queryPipeline      The query pipeline the atomicity filter may wrap
     * @return What the caller should now hold
     * @throws NullPointerException if textChunker is null
     */
    Attachment attach(TextChunker textChunker, EmbeddingClient embeddings,
                      LshIngestionService lshIngestionService,
                      DocumentVisibilityTracker visibilityTracker, QueryPipeline queryPipeline) {
        Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        // embeddings may be null: that is lexical-only ingestion, where documents are
        // chunked, stored and term-indexed but never embedded. Vector search is then the one
        // retrieval signal this node cannot offer, rather than a reason to index nothing.

        IngestionOrchestrator orchestrator =
            newOrchestrator(textChunker, embeddings, visibilityTracker);

        // The vector pre-filter is keyed by HNSW node id, so it is written by the integration
        // worker as it assigns those ids rather than by ingestion, which holds the vector before
        // any id exists for it. Wiring it here keeps that decision in one place for every caller.
        indexOrchestrator.setVectorPrefilter(lshIngestionService);

        // The engine owns when a pre-filter exists, because that has to follow the graph it is
        // keyed against: attached to created, recovered and split segments alike, and discarded
        // from a split child before it can adopt the one describing its parent. This runs after
        // start(), so the engine back-fills the segments that already exist.
        storageEngine.setVectorPrefilter(lshIngestionService);

        QueryPipeline wrapped = withDocumentAtomicity(queryPipeline, visibilityTracker);
        logAttached(embeddings, visibilityTracker, lshIngestionService);
        return new Attachment(orchestrator, embeddings, wrapped);
    }

    /**
     * Builds the ingestion orchestrator behind every overload.
     *
     * <p>The overloads differ only in where the embedding client and the visibility tracker come
     * from. Everything else - the storage engine, the index orchestrator, the bridge onto what
     * {@code GET /v1/ingest/status} serves, a term frequency tracker and the configured document
     * timeout - is one wiring, so it is written once rather than once per overload.</p>
     *
     * @param chunker    Splits documents into chunks
     * @param embeddings Embeds chunks, or null for lexical-only ingestion
     * @param visibility Makes a document's chunks visible together, or null for none
     * @return A configured orchestrator
     */
    private IngestionOrchestrator newOrchestrator(TextChunker chunker,
                                                  EmbeddingClient embeddings,
                                                  DocumentVisibilityTracker visibility) {
        return new IngestionOrchestrator(
            chunker,
            embeddings,
            storageEngine,
            indexOrchestrator,
            new IngestionStatusBridge(ingestionStatus),
            visibility,
            // Term frequency tracker for selectivity estimation: term frequencies in the segment
            // sketches are what let the planner estimate a predicate rather than guess it.
            new TermFrequencyTracker(),
            configuredDocumentTimeoutMs()
        );
    }

    /**
     * The query pipeline with the atomicity filter on it, unless it is turned off or the
     * pipeline already carries one.
     *
     * <p>Every overload goes through this, so the knob is honoured in one place rather than in
     * three that can drift.</p>
     *
     * @param pipeline The pipeline as it stands
     * @param tracker  The tracker the filter reads, or null when this node has none
     * @return The pipeline to use from now on, which may be the one passed in
     */
    private QueryPipeline withDocumentAtomicity(QueryPipeline pipeline,
                                                DocumentVisibilityTracker tracker) {
        if (tracker == null || pipeline.getVisibilityChecker() != null) {
            return pipeline;
        }
        if (!isDocumentAtomicityEnabled()) {
            LOGGER.info("Document atomicity disabled by excalibur.ingest.document-atomicity; "
                + "a query may see a document that is still being ingested");
            return pipeline;
        }
        LOGGER.info("Query pipeline wired with document visibility checker for atomicity filtering");
        return pipeline.withVisibilityChecker(new DocumentVisibilityTrackerAdapter(tracker));
    }

    /**
     * Records what an operator can expect of the pipeline that was just attached.
     *
     * <p>One message for all three overloads. They differ only in where their parts came from,
     * and three near-identical lines drift until the log stops describing the node.</p>
     *
     * @param embeddings The embedding client, or null for lexical-only ingestion
     * @param visibility The visibility tracker, or null when a document lands chunk by chunk
     * @param prefilter  The vector pre-filter, or null when this overload wires none
     */
    private void logAttached(EmbeddingClient embeddings, DocumentVisibilityTracker visibility,
                             LshIngestionService prefilter) {
        LOGGER.info("Ingestion pipeline configured: TextChunker, TermFrequencyTracker, "
                + "embedding={}, documentTimeoutMs={}ms, documentAtomicity={}, vectorPreFilter={}",
            embeddings != null ? "enabled" : "disabled",
            configuredDocumentTimeoutMs(),
            visibility != null && isDocumentAtomicityEnabled() ? "enabled" : "disabled",
            prefilter != null ? "enabled" : "disabled");
    }
}
