package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRoutingGraphIngestSink;
import com.eonmux.excalibur.api.endpoint.InMemorySourceRegistry;
import com.eonmux.excalibur.api.endpoint.IngestStatusEndpoint;
import com.eonmux.excalibur.api.endpoint.ReindexEndpoint;
import com.eonmux.excalibur.api.endpoint.ReindexRun;
import com.eonmux.excalibur.api.endpoint.SourceRegistry;
import com.eonmux.excalibur.api.reindex.ReindexCoordinator;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lsh.LshIngestionService;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.registry.SourceStatusFile;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * What a node is made of, as it is assembled before the listener is bound.
 *
 * <p>A node comes together in two steps: construction supplies the collaborators every
 * deployment has, and a run of {@code with...} calls supplies the ones only some do - a
 * reindexer, an ingestion pipeline, a lexical index, a graph, a catalog that outlives the
 * process. None of that is about serving HTTP. It is written before anything is bound and read
 * once, when the routes are registered, which leaves {@link ExcaliburDBServer} two unrelated
 * jobs in one class: deciding what this node is, and running the socket it is served on. This
 * class takes the first, so the server keeps only the second.</p>
 *
 * <p>Several of these attachments imply more than a stored reference - a reindexer also has to
 * reach the scheduler, an ingestion pipeline also decides whether the atomicity filter goes on
 * the query pipeline, an LSH service also has to reach the index orchestrator and the storage
 * engine. Those consequences belong next to the state they follow from rather than spread
 * across the methods a caller happens to use, which is why they are here and not on the
 * server.</p>
 *
 * <p>The "not after {@code start()}" rule stays on the server. Whether it is too late to attach
 * something is a fact about the listener, which this class does not own; it accepts whatever it
 * is handed, and the server decides when to stop handing.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>Safe for the use it is put to.</strong> Assembly happens on one thread before the
 * server starts, and {@link #endpointInputs()} reads the result from inside the server's
 * synchronized {@code start()}. The two setters that deliberately do not refuse a late call -
 * {@link #withChunkIndex} and {@link #withLexicalQueryService} - write volatile fields, so a
 * late write is visible rather than a data race; it is still ignored, because the routes read
 * those fields while registering and not since. No field here is written by a request thread.</p>
 *
 * @author ExcaliburDB
 */
final class ServerWiring {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerWiring.class);

    private final IndexOrchestrator indexOrchestrator;
    private final StorageEngine storageEngine;
    private final VolumeManager volumeManager;
    private final Config config;
    private final QueryRegistry queryRegistry;
    private final ReindexEndpoint.ReindexStatusRegistry reindexStatusRegistry;
    private final IngestStatusEndpoint.InMemoryIngestionStatusRegistry ingestionStatusRegistry;

    /**
     * The single mutual exclusion for reindexing, shared by {@code POST /v1/reindex}
     * and the scheduled reindex handler.
     */
    private final ReindexCoordinator reindexCoordinator = new ReindexCoordinator();

    private QueryPipeline queryPipeline;

    /**
     * Security policy for the HTTP surface: bind address, bearer token, rate limits
     * and the ingest allow-lists. Never null.
     */
    private ApiSecurityConfig security;

    /**
     * Where ingestion sources are registered.
     *
     * <p>Not final: its bound comes from the security policy, and {@link #withSecurity} can
     * replace that policy before the server starts. Rebuilding it there is what makes the
     * configured bound apply however the policy was supplied - it is always empty at that
     * point, because the server refuses the call once it is running.</p>
     */
    private volatile SourceRegistry ingestSourceRegistry;

    // Optional reindexing components; null when POST /v1/reindex answers 503
    private DocumentReindexer documentReindexer;
    private SourceResolver sourceResolver;

    /** Builds an ingestion pipeline and everything that follows from having one. */
    private final IngestionPipelineWiring ingestion;

    // Optional ingestion pipeline components; null when sources register but nothing crawls them
    private EmbeddingClient embeddingClient;
    private IngestionOrchestrator ingestionOrchestrator;

    /**
     * Lexical retrieval over ingested chunks, attached by the application when it keeps an index.
     *
     * <p>Held here rather than passed to the constructor because the index is created from the
     * data directory, which the application owns, and because it is optional.</p>
     */
    private volatile LexicalQueryService lexicalQueryService;

    /**
     * The chunk lexical index, so {@code GET /v1/stats} can report what it owes.
     *
     * <p>Held beside the query service rather than read out of it: what the stats endpoint needs
     * is the index itself, and routing that through the thing that happens to search it would
     * make the report depend on retrieval being wired.</p>
     */
    private volatile ChunkIndex chunkIndex;

    /**
     * The service behind {@code POST /v1/erasure}.
     *
     * <p>Without one the route is not registered at all, rather than registered and answering that
     * it cannot erase: a caller under an obligation should get a 404 they can escalate, not a 200
     * that removed nothing.</p>
     */
    private volatile DocumentErasureService documentErasure;

    /** What this node has ingested, or null on a node that keeps no record of it. */
    private volatile DocumentRegistry documentRegistry;

    /** The expander the lexical half widens queries with, or null on a node with none. */
    private volatile com.eonmux.excalibur.retrieval.expand.QueryExpander queryExpander;

    /**
     * Where registered ingest sources are written down, so they outlive the process.
     *
     * <p>Optional. Without one the registry keeps its sources in memory alone, and a restart
     * leaves the node holding an indexed corpus that nothing re-crawls - which is the state
     * every {@code /v1/ingest} caller was told it had avoided.</p>
     */
    private volatile SourceCatalog sourceCatalog;

    /**
     * The graph-RAG path, or null where no graph store is configured.
     *
     * <p>Optional rather than required, so a deployment that only does vector search is unaffected
     * by its absence: the route is simply not registered, and an unregistered route already
     * answers 404 rather than failing at startup.
     */
    private GraphRagPipeline graphPipeline;

    /** Every namespace's graph, when this node builds its own. */
    private GraphTenants graphTenants;

    /** What ingestion stages facts through, for the stats side to report, or null. */
    private NamespaceRoutingGraphIngestSink graphIngestSink;

    /**
     * Assembles the collaborators every node has, and the registries derived from them.
     *
     * @param indexOrchestrator The index orchestrator for visibility and worker metrics
     * @param storageEngine     The storage engine for volume information
     * @param volumeManager     Optional volume manager for accurate disk space tracking
     * @param config            Optional configuration for endpoint defaults
     * @param queryPipeline     The query pipeline for executing queries
     * @throws NullPointerException if a required collaborator is null
     */
    ServerWiring(IndexOrchestrator indexOrchestrator,
                 StorageEngine storageEngine,
                 VolumeManager volumeManager,
                 Config config,
                 QueryPipeline queryPipeline) {
        this.indexOrchestrator =
            Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        this.storageEngine =
            Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.volumeManager = volumeManager;
        this.config = config;
        this.queryPipeline =
            Objects.requireNonNull(queryPipeline, "QueryPipeline cannot be null");
        this.security = resolveSecurity(config);

        // Initialize registries for ingestion endpoints. Bounded by the configured limit rather
        // than by the compiled-in default: excalibur.ingest.max-sources was parsed, validated and
        // documented while reaching nothing, so setting it in either direction had no effect and
        // an authenticated caller could always register the default 256 - each a retained source,
        // and for an HTTP source a pinned client and its selector thread.
        this.ingestSourceRegistry = new InMemorySourceRegistry(security.maxRegisteredSources());
        this.reindexStatusRegistry = new ReindexEndpoint.InMemoryReindexStatusRegistry();
        this.ingestionStatusRegistry = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();

        // Initialize query registry for cancellation support
        this.queryRegistry = new QueryRegistry();

        this.ingestion = new IngestionPipelineWiring(
            config, this.storageEngine, this.indexOrchestrator, this.ingestionStatusRegistry);
    }

    /**
     * Resolves the security policy from the application configuration.
     *
     * <p>{@link Config} exposes typed sections only, so the API keys are read from the
     * same properties file and the same {@code EXCALIBUR_*} environment the rest of the
     * configuration comes from. With no configuration at all the safe default applies:
     * loopback binding, no authentication, both ingest allow-lists empty.</p>
     *
     * @param config The application configuration (may be null)
     * @return The resolved policy, never null
     */
    private static ApiSecurityConfig resolveSecurity(Config config) {
        if (config == null) {
            return ApiSecurityConfig.defaults();
        }
        return ApiSecurityConfig.fromConfig(config);
    }

    // ==================== What the lifecycle reads ====================

    /** @return The index orchestrator this node starts, stops and reports on */
    IndexOrchestrator indexOrchestrator() {
        return indexOrchestrator;
    }

    /** @return The storage engine whose own background work a shutdown drains last */
    StorageEngine storageEngine() {
        return storageEngine;
    }

    /** @return The query pipeline as it now stands, scorers and filters included */
    QueryPipeline queryPipeline() {
        return queryPipeline;
    }

    /** @return The policy the listener is bound under and every route is guarded by */
    ApiSecurityConfig security() {
        return security;
    }

    /** @return Where ingestion sources are registered */
    SourceRegistry ingestSourceRegistry() {
        return ingestSourceRegistry;
    }

    /** @return Background ingestion, or null when no ingestion pipeline is wired */
    IngestionOrchestrator ingestionOrchestrator() {
        return ingestionOrchestrator;
    }

    /** @return The service behind {@code POST /v1/erasure}, or null when none is wired */
    DocumentErasureService documentErasure() {
        return documentErasure;
    }

    /**
     * The startup banner for a node wired this way.
     *
     * <p>Assembled here because the conditions it branches on are these fields, and an operator
     * reading a banner that names a route this node did not register has been misinformed by
     * the one inventory of the HTTP surface they are given.</p>
     *
     * @return One line per route this node serves
     */
    List<String> bannerLines() {
        return EndpointBanner.lines(new EndpointBanner.Routes(
            documentErasure != null,
            graphTenants != null,
            graphTenants == null && graphPipeline != null,
            ingestionOrchestrator != null,
            documentRegistry != null));
    }

    // ==================== Attachment ====================

    /**
     * Replaces the policy resolved from configuration, and the registry bound that follows it.
     *
     * @param securityConfig The policy to enforce
     * @throws NullPointerException if securityConfig is null
     */
    void withSecurity(ApiSecurityConfig securityConfig) {
        this.security = Objects.requireNonNull(securityConfig, "securityConfig cannot be null");
        // The registry's bound comes from the policy, and the one built in the constructor was
        // sized by whatever policy applied then. The server is not running, so nothing has been
        // registered yet.
        this.ingestSourceRegistry = new InMemorySourceRegistry(security.maxRegisteredSources());
        LOGGER.info("API security policy configured: {}", securityConfig);
    }

    /** @param index The chunk lexical index {@code GET /v1/stats} reports on, or null */
    void withChunkIndex(ChunkIndex index) {
        this.chunkIndex = index;
    }

    /** @param service Lexical retrieval over ingested chunks, or null for none */
    void withLexicalQueryService(LexicalQueryService service) {
        this.lexicalQueryService = service;
    }

    /** @param expander The expander the lexical half widens queries with, or null for none */
    void withQueryExpander(com.eonmux.excalibur.retrieval.expand.QueryExpander expander) {
        this.queryExpander = expander;
    }

    /** @param service The service behind {@code POST /v1/erasure}, or null to omit the route */
    void withDocumentErasure(DocumentErasureService service) {
        this.documentErasure = service;
    }

    /** @param registry What this node holds, or null to omit {@code GET /v1/documents/list} */
    void withDocumentRegistry(DocumentRegistry registry) {
        this.documentRegistry = registry;
    }

    /** @param catalog Where source registrations are recorded across restarts, or null */
    void withSourceCatalog(SourceCatalog catalog) {
        this.sourceCatalog = catalog;
    }

    /**
     * Makes what each source's last run ended with outlast the process.
     *
     * <p>Handed to the status registry now, before the endpoints are built, because the catalog's
     * sources are restored while they are - and a restored source takes its status from what this
     * file held.</p>
     *
     * @param statusFile Where each source's last finished run is written
     * @throws NullPointerException if the file is null
     */
    void withSourceStatus(SourceStatusFile statusFile) {
        ingestionStatusRegistry.persistTo(statusFile);
    }

    /**
     * Wraps the query pipeline with an LLM scorer for stage 4 relevancy scoring.
     *
     * @param llmScorer The scorer, or null to disable LLM scoring
     */
    void withLlmScorer(QueryPipeline.LlmScorer llmScorer) {
        this.queryPipeline = queryPipeline.withLlmScorer(llmScorer);
        LOGGER.debug("LLM scorer configured: {}", llmScorer != null ? "enabled" : "disabled");
    }

    /** @param pipeline A graph pipeline somebody else owns and closes, or null for none */
    void withGraphPipeline(GraphRagPipeline pipeline) {
        this.graphPipeline = pipeline;
    }

    /**
     * @param tenants Every namespace's graph: what it serves, what it has staged, what it owes
     * @throws NullPointerException if tenants is null
     */
    void withGraphSupport(GraphTenants tenants) {
        this.graphTenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    /** @param sink What ingestion stages facts through, or null on a node that stages none */
    void withGraphIngestSink(NamespaceRoutingGraphIngestSink sink) {
        this.graphIngestSink = sink;
    }

    // ==================== Reindexing ====================

    /**
     * Attaches a reindexer and the resolver it looks source ids up in, or clears both.
     *
     * @param reindexer The document reindexer, or null to have reindex requests answered 503
     * @param resolver  The source resolver, or null alongside a null reindexer
     * @throws IllegalArgumentException if only one parameter is null
     */
    void withReindexer(DocumentReindexer reindexer, SourceResolver resolver) {
        if ((reindexer == null) != (resolver == null)) {
            throw new IllegalArgumentException(
                "DocumentReindexer and SourceResolver must both be null or both be non-null");
        }
        this.documentReindexer = reindexer;
        this.sourceResolver = resolver;
        shareGraphReporting();
        wireScheduledReindex();
        LOGGER.info("Document reindexer configured: {}",
            reindexer != null ? "enabled" : "disabled (POST /v1/reindex answers 503)");
    }

    /**
     * Attaches a reindexer that resolves its sources from the ingest registry.
     *
     * @param reindexer The document reindexer for executing reindex operations
     * @throws NullPointerException if reindexer is null
     */
    void withReindexer(DocumentReindexer reindexer) {
        this.documentReindexer =
            Objects.requireNonNull(reindexer, "documentReindexer cannot be null");
        this.sourceResolver = createSourceResolver();
        shareGraphReporting();
        wireScheduledReindex();
        LOGGER.info(
            "Document reindexer configured with automatic source resolution and scheduled reindex support");
    }

    /**
     * Has the reindexer report to the graph through the ingestion pipeline, once both are here.
     *
     * <p>Called whichever of the two is attached second, since neither order is the only one a
     * caller uses. Without it a reindex publishes and removes documents the graph never hears
     * of: a changed page goes on being answered for with what it used to say, and a removed one
     * with what it said before it went.</p>
     */
    private void shareGraphReporting() {
        if (documentReindexer != null && ingestionOrchestrator != null) {
            ingestionOrchestrator.shareGraphReporting(documentReindexer);
        }
    }

    /**
     * Points the scheduler at the reindexer that is now attached, or at nothing.
     *
     * <p>{@code POST /v1/reindex} and the scheduler are two triggers for one operation, so the
     * scheduler is given a runner that takes the same per-source claim the endpoint does.
     * Clearing the handler when the reindexer is cleared matters as much as setting it: a
     * scheduler left pointing at a reindexer the deployment has just disowned would keep
     * rewriting segments nobody asked it to.</p>
     */
    private void wireScheduledReindex() {
        if (documentReindexer == null || sourceResolver == null) {
            indexOrchestrator.setReindexOperationHandler(null);
            return;
        }
        SourceResolver resolver = sourceResolver;
        indexOrchestrator.setReindexOperationHandler(new ScheduledReindexRunner(
            reindexCoordinator,
            new ReindexRun(documentReindexer, resolver, volumeManager,
                storageEngine.getCatalog(), reindexStatusRegistry, ingestionStatusRegistry),
            reindexStatusRegistry,
            // Whether the source still resolves, asked through the same resolver the run reads it
            // with: the default one reads the ingest registry, so a removed source is gone here
            // the moment it is gone there.
            sourceId -> resolver.resolve(sourceId) != null));
    }

    /**
     * A resolver that reads the sources {@code POST /v1/ingest} registered.
     *
     * @return A SourceResolver that resolves sources from the ingest registry
     */
    SourceResolver createSourceResolver() {
        return sourceId -> ingestSourceRegistry.get(sourceId);
    }

    // ==================== Ingestion ====================

    /**
     * Whether a document's chunks become visible to queries together.
     *
     * @return true when the atomicity filter should run
     * @see IngestionPipelineWiring#isDocumentAtomicityEnabled()
     */
    boolean isDocumentAtomicityEnabled() {
        return ingestion.isDocumentAtomicityEnabled();
    }

    /**
     * Attaches an ingestion pipeline and the LSH index stage 1 of a query names candidates from.
     *
     * @param textChunker        The text chunker for splitting documents into chunks
     * @param embeddings         The embedding client, or null for lexical-only ingestion
     * @param lshIngestionService The LSH ingestion service, or null to disable LSH
     * @param visibilityTracker  The document visibility tracker, or null for none
     * @throws NullPointerException if textChunker is null
     */
    void withIngestionPipeline(TextChunker textChunker, EmbeddingClient embeddings,
                               LshIngestionService lshIngestionService,
                               DocumentVisibilityTracker visibilityTracker) {
        adopt(ingestion.attach(
            textChunker, embeddings, lshIngestionService, visibilityTracker, queryPipeline));
    }

    /**
     * Takes on what attaching an ingestion pipeline produced.
     *
     * <p>The query pipeline is reassigned rather than left alone because the atomicity filter
     * is a wrapper: a node that asked for atomic visibility gets a different pipeline back, and
     * keeping the old one here is how the filter would end up installed on nothing.</p>
     *
     * @param attached The orchestrator, the embedding client and the pipeline to hold from now on
     */
    private void adopt(IngestionPipelineWiring.Attachment attached) {
        this.ingestionOrchestrator = attached.orchestrator();
        this.embeddingClient = attached.embeddings();
        this.queryPipeline = attached.queryPipeline();
        shareGraphReporting();
    }

    // ==================== The snapshot ====================

    /**
     * The collaborators the route table is built from, as they stand now.
     *
     * <p>Taken once rather than read per route. The server's {@code start()} is synchronized and
     * its configuration methods refuse once the server is running, so this is what they left
     * behind.</p>
     *
     * @return What {@link EndpointRegistrar} builds the endpoints from
     */
    EndpointRegistrar.Inputs endpointInputs() {
        // Auto-create the source resolver if one was not set explicitly. Only the reindex
        // endpoint reads it, and only when a reindexer was configured.
        SourceResolver resolver = sourceResolver != null ? sourceResolver : createSourceResolver();
        return new EndpointRegistrar.Inputs(
            security, config, indexOrchestrator, storageEngine, volumeManager,
            queryPipeline, queryRegistry, embeddingClient, lexicalQueryService, queryExpander,
            chunkIndex,
            ingestSourceRegistry, sourceCatalog, ingestionStatusRegistry, ingestionOrchestrator,
            reindexStatusRegistry, documentReindexer, resolver, reindexCoordinator,
            documentErasure, graphTenants, graphIngestSink, graphPipeline, documentRegistry);
    }

    /**
     * The collaborators a caller in the same process searches, supplies and erases through.
     *
     * <p>Read at the same point {@link #endpointInputs()} is, and for the same reason: after
     * every attachment, so the pipeline is the one ingestion wrapped and not the one it was
     * handed.</p>
     *
     * @return The node's services, as the routes would have used them
     * @throws IllegalStateException if the node was assembled without ingestion, a document
     *                               registry or an erasure service, which an in-process caller
     *                               has no other way to reach
     */
    InProcessServices inProcessServices() {
        if (ingestionOrchestrator == null || documentRegistry == null || documentErasure == null) {
            throw new IllegalStateException("An in-process node needs its ingestion pipeline, its "
                + "document registry and its erasure service attached before it starts");
        }
        return new InProcessServices(config, storageEngine, queryPipeline, embeddingClient,
            lexicalQueryService, ingestionOrchestrator, documentRegistry, documentErasure,
            sourceCatalog);
    }
}
