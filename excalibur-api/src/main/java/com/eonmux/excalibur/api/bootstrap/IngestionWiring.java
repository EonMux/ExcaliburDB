package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.ingest.erasure.SegmentVectorEraser;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.graph.RelationExtractor;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.ingest.lsh.LshIngestionService;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.retrieval.expand.LlmQueryExpander;
import com.eonmux.excalibur.retrieval.expand.CachingQueryExpander;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.tenancy.NamespaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The ingestion half of a node, attached to a server that already has the query half.
 *
 * <p>Held apart from the composition root because it is a decision tree rather than a list of
 * constructions. What a node ingests with depends on which models it was configured to reach:
 * an embedding endpoint turns on vectors and the LSH pre-filter, a chat model turns on query
 * expansion and relation extraction, and neither is a precondition for having an index at all -
 * a node with no LLM still chunks, stores and term-indexes everything it crawls. Those
 * combinations are the part worth testing, and they were not reachable from a test while they
 * lived inside a constructor that also opened the storage engine.</p>
 *
 * <p>Failure here is absorbed rather than fatal, which is a decision about what a node is for
 * rather than leniency. Registering a source is bookkeeping in a catalog and needs no pipeline,
 * so a node whose embedding endpoint is unreachable at startup still accepts registrations and
 * reports every source as unconfigured, rather than refusing to start. Whatever was opened
 * before the failure is still handed back for the same reason: the chunk index is a directory
 * this process now holds open, and the caller is the only thing that will close it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>The record is immutable and {@link #attachTo(ExcaliburDBServer)} is meant to be called once,
 * from the thread building the node, before the server starts accepting requests. The components
 * it attaches carry their own guarantees from that point on.</p>
 *
 * @param config                    The node's configuration, which decides every branch below
 * @param dataDirectory             Where the chunk index is opened, beside the segments
 * @param storageEngine             Storage the chunk text is read back from and vectors erased in
 * @param indexOrchestrator         Supplies the visibility clock the chunk index stamps with
 * @param hierarchicalFilter        The membership filter ingestion must populate for Stage 1
 *                                  to have term membership to order segments by
 * @param documentVisibilityTracker The atomicity tracker shared with the query pipeline
 * @param graphTenants              Every namespace's graph, for the staging store ingestion
 *                                  writes facts into
 * @author ExcaliburDB
 */
public record IngestionWiring(Config config,
                              Path dataDirectory,
                              StorageEngine storageEngine,
                              IndexOrchestrator indexOrchestrator,
                              HierarchicalCuckooFilter hierarchicalFilter,
                              DocumentVisibilityTracker documentVisibilityTracker,
                              GraphTenants graphTenants) {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionWiring.class);

    /**
     * Validates that every collaborator is present.
     *
     * <p>All seven are required. None of them has a sensible absent case: a missing one is a
     * composition-root bug, and left unchecked it surfaces as the pipeline quietly declining to
     * configure itself, which reads in the log exactly like an unreachable embedding endpoint.</p>
     *
     * @throws NullPointerException if any component is null
     */
    public IngestionWiring {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(dataDirectory, "dataDirectory cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(indexOrchestrator, "indexOrchestrator cannot be null");
        Objects.requireNonNull(hierarchicalFilter, "hierarchicalFilter cannot be null");
        Objects.requireNonNull(documentVisibilityTracker, "documentVisibilityTracker cannot be null");
        Objects.requireNonNull(graphTenants, "graphTenants cannot be null");
    }

    /**
     * What attaching the pipeline opened, for the caller to hold and eventually close.
     *
     * <p>Returned rather than left in the wiring object because both of these outlive the
     * attachment and their lifetime belongs to the node: closing the chunk index is what
     * publishes the tail of a run, and stopping the sweep before that index closes is what stops
     * a compaction running against an index that is being torn down underneath it. Either being
     * null is an ordinary outcome, not an error - a node whose chunk index would not open serves
     * vectors and nothing else.</p>
     *
     * @param chunkIndex          The BM25 index over this node's chunks, or null if none opened
     * @param lexicalErasureSweep The timer enforcing that index's erasure deadline, or null when
     *                            there is no index for it to compact
     */
    public record Attachment(ChunkIndex chunkIndex, LexicalErasureSweep lexicalErasureSweep) {
    }

    /**
     * Wires background document processing onto a server, enabling {@code POST /v1/ingest}.
     *
     * <p>The order is load-bearing in one place: the chunk index is opened before the pipeline is
     * attached, because the orchestrator the attachment creates is what the index is then handed
     * to. Everything else follows from what the configuration allows, and each decision is logged
     * where it is made so an operator can read back which half of retrieval this node actually
     * built.</p>
     *
     * @param server The server to attach to, already holding the query pipeline
     * @return What was opened, whether or not the attachment ran to completion
     */
    public Attachment attachTo(ExcaliburDBServer server) {
        ChunkIndex chunkIndex = null;
        LexicalErasureSweep sweep = null;
        try {
            TextChunker textChunker = createChunker();

            // Not embedding is lexical-only ingestion, not "no ingestion". Documents are chunked,
            // written and term-indexed either way; only the vector signal is missing, which is one
            // signal of several rather than the precondition for having an index at all --
            // requiring it meant a corpus-wide embedding pass before a single document could be
            // searched.
            RetrievalCapabilities capabilities = RetrievalCapabilities.from(config);

            LLMClientConfig llmClientConfig = LLMClientConfig.fromConfig(config.llm());
            EmbeddingClient embeddingClient = createEmbeddingClient(capabilities, llmClientConfig);
            int vectorDimensions = resolveVectorDimensions(llmClientConfig);
            chunkIndex = openChunkIndex();
            LshIngestionService lshIngestionService =
                createLshIngestionService(capabilities, vectorDimensions);

            server.withIngestionPipeline(textChunker, embeddingClient, lshIngestionService,
                documentVisibilityTracker);
            LOGGER.info("Ingestion pipeline configured with document atomicity");

            attachOrchestratorHooks(server);

            if (chunkIndex != null) {
                sweep = attachLexicalRetrieval(server, chunkIndex, capabilities, llmClientConfig);
            }

            logRetrievalMode(config, capabilities, chunkIndex != null);

            // Relation extraction is a chat call, so it turns on with the model rather than with
            // the embedding pass. The tenants already hold the state this node is in without
            // ingestion; the only thing reported here is the step up to active, once a sink is
            // actually in place to hand the extractor documents. Every way of not getting there -
            // no orchestrator, or an exception anywhere above - leaves the state that says so.
            boolean extractionEnabled = config.graph().isExtractionEnabled();
            if (extractionEnabled && !capabilities.chatModel()) {
                LOGGER.warn("excalibur.graph.extraction-enabled is true but excalibur.llm.api-key "
                    + "(EXCALIBUR_LLM_API_KEY) is not set - relation extraction is INACTIVE. "
                    + "Documents are ingested and searchable, but no facts are staged, so "
                    + "POST /v1/graph/build has nothing to publish. Set the key and restart to "
                    + "extract facts.");
            } else if (capabilities.chatModel()) {
                boolean wired = configureGraphIngest(server, llmClientConfig);
                graphTenants.reportExtraction(
                    GraphExtraction.of(extractionEnabled, true, wired));
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to configure ingestion pipeline: {}. Sources can be registered "
                + "and crawled, but nothing will be indexed.", e.getMessage(), e);
            // Continue without the pipeline: registration is bookkeeping and does not need one,
            // and /v1/ingest/status reports every source as unconfigured rather than as ingested.
        }
        return new Attachment(chunkIndex, sweep);
    }

    /**
     * Builds the chunker ingestion splits documents with.
     *
     * <p>Sentence-boundary-aware; the sizing is configuration because it is the trade every
     * corpus makes differently - larger chunks carry more context into one result, smaller ones
     * rank more precisely.</p>
     *
     * @return A chunker sized from {@code excalibur.ingest.chunk-*}
     */
    private TextChunker createChunker() {
        int chunkTargetTokens = config.ingest().getChunkTargetTokens();
        int chunkOverlapTokens = config.ingest().getChunkOverlapTokens();
        TextChunker textChunker = new TextChunker(chunkTargetTokens, chunkOverlapTokens);
        LOGGER.info("TextChunker created: {} token chunks overlapping by {}",
            chunkTargetTokens, chunkOverlapTokens);
        return textChunker;
    }

    /**
     * Builds the embedding client, or explains in the log why this node will not have one.
     *
     * <p>The three outcomes are genuinely different and an operator has to be able to tell them
     * apart from the log alone: embeddings on, embeddings declined by configuration, and no key
     * at all. Only the last is a warning, because it is the one nobody chose.</p>
     *
     * @param capabilities    What the configured models allow
     * @param llmClientConfig The resolved LLM endpoint and dimensions
     * @return A client, or null when this node ingests lexically
     */
    private EmbeddingClient createEmbeddingClient(RetrievalCapabilities capabilities,
                                                  LLMClientConfig llmClientConfig) {
        if (capabilities.embedCorpus()) {
            EmbeddingClient embeddingClient = new EmbeddingClient(llmClientConfig);
            LOGGER.info("EmbeddingClient created: baseUrl={}, model={}, dimensions={}",
                config.llm().getBaseUrl(), config.llm().getEmbeddingModel(),
                llmClientConfig.getEmbeddingDimension());
            return embeddingClient;
        }
        if (capabilities.chatModel()) {
            LOGGER.info("Corpus embedding declined by excalibur.llm.embeddings-enabled - "
                + "ingesting lexically. Documents are chunked, stored and term-indexed, and "
                + "POST /v1/query answers from BM25 with the chat model expanding the query.");
        } else {
            LOGGER.warn("LLM API key not configured - ingesting lexically. Documents are "
                + "chunked, stored and term-indexed, and POST /v1/query answers from BM25 "
                + "over that index. No vectors are produced, so the vector signal is "
                + "missing and every answer reports its coverage as degraded. Set "
                + "excalibur.llm.api-key, or EXCALIBUR_LLM_API_KEY, to add it.");
        }
        return null;
    }

    /**
     * Settles how wide a vector this node writes.
     *
     * <p>Configuration wins when it is set, because a corpus already built at one width has to
     * keep being written at that width. When it is not set the embedding model's own dimension is
     * the only answer that cannot disagree with the vectors the client will return.</p>
     *
     * @param llmClientConfig The resolved LLM endpoint, which knows its model's width
     * @return The vector width the LSH pre-filter is sized for
     */
    private int resolveVectorDimensions(LLMClientConfig llmClientConfig) {
        int configured = config.query().getVectorDimensions();
        return configured > 0 ? configured : llmClientConfig.getEmbeddingDimension();
    }

    /**
     * Opens the BM25 index over the chunks this node stores.
     *
     * <p>Attempted regardless of whether embeddings are configured: it is the retrieval path that
     * does not require the corpus to be permuted into vectors first. Failing to open it is not
     * fatal for the same reason - the node loses one signal rather than the ability to ingest.</p>
     *
     * @return The opened index, or null when it could not be opened
     */
    private ChunkIndex openChunkIndex() {
        try {
            ChunkIndex chunkIndex = ChunkIndexFactory.open(config, dataDirectory,
                () -> indexOrchestrator.getVisibilityTracker().currentEpoch());
            LOGGER.info("Chunk lexical index opened on the {} engine", chunkIndex.engine());
            return chunkIndex;
        } catch (IOException e) {
            LOGGER.warn("Chunk lexical index unavailable, BM25 retrieval disabled: {}",
                e.getMessage());
            return null;
        }
    }

    /**
     * Builds the vector pre-filter, which only a node that produces vectors has any use for.
     *
     * <p>It is written by HNSW integration rather than by ingestion, because it is keyed by the
     * node ids integration assigns. With no vectors to place it is dead weight on every write,
     * so a lexical-only node gets none and the log says so rather than leaving the absence to be
     * inferred.</p>
     *
     * @param capabilities     What the configured models allow
     * @param vectorDimensions The settled vector width
     * @return The pre-filter, or null on a node that does not embed its corpus
     */
    private LshIngestionService createLshIngestionService(RetrievalCapabilities capabilities,
                                                          int vectorDimensions) {
        LshIngestionService lshIngestionService = capabilities.embedCorpus()
            ? new LshIngestionService(vectorDimensions,
                config.shard().getLshHashTables(),
                config.shard().getLshHashFunctions(),
                config.shard().getLshProbes())
            : null;
        LOGGER.info("LshIngestionService: {}",
            lshIngestionService == null ? "disabled (lexical-only ingestion)"
                : "dimensions=" + vectorDimensions
                    + ", tables=" + config.shard().getLshHashTables()
                    + ", hashFunctions=" + config.shard().getLshHashFunctions()
                    + ", probes=" + config.shard().getLshProbes());
        return lshIngestionService;
    }

    /**
     * Gives the new orchestrator the two things it cannot discover for itself.
     *
     * <p>Stage 1 of the query pipeline orders segments by the membership filter, and without this
     * only the reindex path fills it - so a node that crawled its corpus once had no term to order
     * any query by. The vector eraser is the rollback counterpart: an ingestion that is rolled back
     * deletes the chunks it wrote, and without it the graph nodes built from them stay behind and
     * a vector query keeps returning a name and a distance for content that is no longer there.
     * </p>
     *
     * @param server The server holding the orchestrator the attachment just created
     */
    private void attachOrchestratorHooks(ExcaliburDBServer server) {
        IngestionOrchestrator ingestion = server.getIngestionOrchestrator();
        if (ingestion != null) {
            ingestion.setMembershipFilter(hierarchicalFilter);
            ingestion.setVectorEraser(SegmentVectorEraser.of(storageEngine));
        }
    }

    /**
     * Puts the chunk index on both sides of the node: written by ingestion, read by queries.
     *
     * <p>Both halves matter and they fail differently. Without the write side the index is opened
     * and never filled; without the read side it is filled and never consulted, and the only
     * symptom is that hybrid retrieval quietly has one signal. The erasure sweep is what makes
     * {@code excalibur.ingest.lexical-max-latency-seconds} a bound at all: compaction is otherwise
     * only checked at the end of an ingestion or reindex run, so a node that erased a chunk and
     * then went quiet kept it on disk indefinitely.</p>
     *
     * @param server          The server to attach the read side to
     * @param chunkIndex      The index that opened
     * @param capabilities    What the configured models allow
     * @param llmClientConfig The resolved LLM endpoint, for the expander's client
     * @return The sweep enforcing the index's erasure deadline
     */
    private LexicalErasureSweep attachLexicalRetrieval(ExcaliburDBServer server,
                                                       ChunkIndex chunkIndex,
                                                       RetrievalCapabilities capabilities,
                                                       LLMClientConfig llmClientConfig) {
        IngestionOrchestrator orchestrator = server.getIngestionOrchestrator();
        if (orchestrator != null) {
            orchestrator.setLexicalIndex(chunkIndex);
            orchestrator.setLexicalRefreshIntervalMs(
                config.ingest().getLexicalRefreshIntervalMs());
        }
        // Remembered, and shared with the query route: the ranker expands to search and the
        // response expands to report, and a second generation is a second bill and possibly
        // different terms from the ones that actually ran.
        QueryExpander expander = CachingQueryExpander.over(
            createQueryExpander(capabilities, llmClientConfig));
        server.withLexicalQueryService(new LexicalQueryService(
            chunkIndex, expander, List.of(),
            config.query().getRrfK(), config.query().getPerSignalOverFetch()));
        server.withQueryExpander(expander);
        server.withChunkIndex(chunkIndex);

        return new LexicalErasureSweep(
            chunkIndex,
            StoredChunkText.of(storageEngine),
            config.ingest().getLexicalMaxLatencySeconds());
    }

    /**
     * Decides whether BM25 gets the query it was given or a widened one.
     *
     * <p>Query expansion is what carries the semantics when the corpus is not embedded, so it
     * belongs to nodes that have a chat model and no vectors. Without one this is plain BM25,
     * which is a weaker search and still a working one - which is why the absence is logged only
     * on a node that has nothing else to fall back on.</p>
     *
     * @param capabilities    What the configured models allow
     * @param llmClientConfig The resolved LLM endpoint, for the expander's client
     * @return The expander the lexical query service will run every query through
     */
    private QueryExpander createQueryExpander(RetrievalCapabilities capabilities,
                                              LLMClientConfig llmClientConfig) {
        if (capabilities.expandQueries()) {
            // On the expansion budget rather than the ingestion client's. Expansion runs inside
            // a request a caller is holding open and the search cannot start until it returns,
            // so the 60s the client is configured with - longer than any query budget a caller
            // can ask for - made one slow call to the chat model an empty timed-out answer.
            LlmQueryExpander expander = new LlmQueryExpander(
                new LLMHttpClient(llmClientConfig),
                config.query().getExpansionTimeoutMs());
            LOGGER.info("Query expansion enabled with chat model {}",
                config.llm().getChatModel());
            return expander;
        }
        if (!capabilities.embedCorpus()) {
            LOGGER.info("No chat model configured; retrieval is plain BM25 without "
                + "query expansion");
        }
        return QueryExpander.identity();
    }

    /**
     * Says once, at startup, what {@code /v1/query} will actually do.
     *
     * <p>Every input to that decision is already logged separately - the embedding model, the
     * chunk index opening, the expansion model - and none of them answers the question. A node
     * can hold both signals and read one of them, and the difference is a configuration key
     * rather than anything visible in the data directory. An operator who has to infer it from
     * three other lines will infer it wrong.</p>
     *
     * @param config        The configuration, for whether fusion is enabled
     * @param capabilities  What the configured models allow
     * @param hasChunkIndex Whether the lexical index opened
     */
    static void logRetrievalMode(Config config,
                                 RetrievalCapabilities capabilities,
                                 boolean hasChunkIndex) {
        if (!capabilities.embedCorpus()) {
            LOGGER.info("/v1/query retrieval: {}", hasChunkIndex
                ? "lexical - BM25 over the chunk index"
                : "unavailable - no embedding model and no chunk index");
            return;
        }
        if (!hasChunkIndex) {
            LOGGER.info("/v1/query retrieval: vector - no chunk index to fuse with");
            return;
        }
        LOGGER.info("/v1/query retrieval: {}", config.query().isHybridRetrieval()
            ? "hybrid - BM25 fused with vector search by reciprocal rank"
            : "vector - the chunk index is maintained but not read, because "
                + "excalibur.query.hybrid-retrieval is false");
    }

    /**
     * Points ingestion at the graph, so published documents become facts.
     *
     * <p>Extraction is wired only when it is asked for - it is a chat-model call per document,
     * which is the most expensive thing ingestion can do, and a node doing vector search should
     * not start paying for a graph nobody asked for. With it off the sink finds no facts, so it
     * stages nothing and opens no namespace's graph.</p>
     *
     * <p>The sink routes rather than pointing at one tenant. A source belongs to the namespace
     * that registered it, and the sink stages each published document into that namespace's
     * graph for the length of one borrow; a source registered without a namespace stages into
     * the shared one, which is the whole of what a single-tenant node ever does.</p>
     *
     * @param server          The server holding the ingestion orchestrator
     * @param llmClientConfig The LLM configuration, for the extractor's client
     * @return true if documents now reach the graph, false if there was no orchestrator to wire
     */
    private boolean configureGraphIngest(ExcaliburDBServer server,
                                         LLMClientConfig llmClientConfig) {
        IngestionOrchestrator orchestrator = server.getIngestionOrchestrator();
        if (orchestrator == null) {
            LOGGER.warn("No ingestion orchestrator; documents will not reach the graph");
            return false;
        }

        boolean extractionEnabled = config.graph().isExtractionEnabled();
        RelationExtractor extractor = RelationExtractor.none();
        if (extractionEnabled) {
            extractor = new LlmRelationExtractor(
                new LLMHttpClient(llmClientConfig),
                config.graph().getExtractorVersion());
        }

        NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(
            graphTenants, sourceId -> namespaceOf(server, sourceId), extractor);
        orchestrator.setGraphSink(sink);
        // The same sink, so the documents it drops are counted where an operator looks.
        server.withGraphIngestSink(sink);
        LOGGER.info("Graph ingest wired: extraction {} (extractor version {}), staged into the "
            + "namespace each source was registered in", extractionEnabled ? "enabled"
            : "disabled", config.graph().getExtractorVersion());
        return true;
    }

    /**
     * Asks the server which namespace a source's documents belong to.
     *
     * <p>Read through the server on every call rather than captured once, because the registry
     * is replaced when the security policy is applied and a sink holding the one that existed at
     * attachment time would answer from a registry nothing registers into. Supplied documents
     * resolve too, to the shared namespace, which is what lets facts extracted from them be
     * staged at all. Anything else answers empty, which the sink reads as "no namespace to stage
     * into" rather than as the shared one.</p>
     *
     * @param server   The server holding the registry
     * @param sourceId The source id
     * @return Its namespace, or empty if this node holds nothing under that id
     */
    private static Optional<NamespaceId> namespaceOf(ExcaliburDBServer server, String sourceId) {
        return server.namespaceOfSource(sourceId);
    }
}
