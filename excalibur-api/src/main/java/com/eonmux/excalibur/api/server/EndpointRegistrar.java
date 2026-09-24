package com.eonmux.excalibur.api.server;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.eonmux.excalibur.api.audit.AuditLog;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRoutingGraphIngestSink;
import com.eonmux.excalibur.api.bootstrap.SourceRestoration;
import com.eonmux.excalibur.api.endpoint.CompactionCancelEndpoint;
import com.eonmux.excalibur.api.endpoint.CompactionEndpoint;
import com.eonmux.excalibur.api.endpoint.CompactionRunner;
import com.eonmux.excalibur.api.endpoint.CompactionStatusEndpoint;
import com.eonmux.excalibur.api.endpoint.DocumentErasureEndpoint;
import com.eonmux.excalibur.api.endpoint.DocumentErasureStatusEndpoint;

import com.eonmux.excalibur.api.endpoint.DocumentFetchEndpoint;
import com.eonmux.excalibur.api.endpoint.DocumentListEndpoint;
import com.eonmux.excalibur.api.endpoint.DocumentUpsertEndpoint;
import com.eonmux.excalibur.api.endpoint.DrainEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphBuildCancelEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphBuildEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphAutoBuilder;
import com.eonmux.excalibur.api.endpoint.GraphBuildRunner;
import com.eonmux.excalibur.api.endpoint.GraphBuildStatusEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphEntitiesEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphErasureEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphErasureStatusEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphFactsEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphNamespaceDeleteEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphNamespacesEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphQueryEndpoint;
import com.eonmux.excalibur.api.endpoint.GraphStatsReader;
import com.eonmux.excalibur.api.endpoint.HealthEndpoint;
import com.eonmux.excalibur.api.endpoint.InMemorySourceRegistry;
import com.eonmux.excalibur.api.endpoint.IngestEndpoint;
import com.eonmux.excalibur.api.endpoint.IngestCancelEndpoint;
import com.eonmux.excalibur.api.endpoint.IngestStatusEndpoint;
import com.eonmux.excalibur.api.endpoint.InlineSourceIds;
import com.eonmux.excalibur.api.endpoint.MetricsEndpoint;
import com.eonmux.excalibur.api.endpoint.NodeStatsCollector;
import com.eonmux.excalibur.api.endpoint.QueryCancelEndpoint;
import com.eonmux.excalibur.api.endpoint.QueryEndpoint;
import com.eonmux.excalibur.api.endpoint.QueryStatusEndpoint;
import com.eonmux.excalibur.api.endpoint.ReadinessEndpoint;
import com.eonmux.excalibur.api.endpoint.ReindexEndpoint;
import com.eonmux.excalibur.api.endpoint.ReindexStatusEndpoint;
import com.eonmux.excalibur.api.endpoint.ServiceReadiness;
import com.eonmux.excalibur.api.endpoint.SourceDeregisterEndpoint;
import com.eonmux.excalibur.api.endpoint.SourceListEndpoint;
import com.eonmux.excalibur.api.endpoint.SourceFactory;
import com.eonmux.excalibur.api.endpoint.SourceRegistry;
import com.eonmux.excalibur.api.endpoint.StatsEndpoint;
import com.eonmux.excalibur.api.endpoint.VersionEndpoint;
import com.eonmux.excalibur.api.reindex.ReindexCoordinator;
import com.eonmux.excalibur.api.retrieval.ChunkTextResolver;
import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.security.IngestTargetValidator;
import com.eonmux.excalibur.api.security.RequestGuard;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.ErasureReceiptFile;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.cache.QueryResultCache;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.query.scorer.LlmRelevancyScorer;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.GraphConfig;
import com.eonmux.excalibur.config.VisibilityConfig;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.Map;

import static com.eonmux.excalibur.api.server.HttpRouting.createMethodRouter;
import static com.eonmux.excalibur.api.server.HttpRouting.exactPath;
import static com.eonmux.excalibur.api.server.HttpRouting.methodNotAllowed;
import static com.eonmux.excalibur.api.server.HttpRouting.pathOrChild;
import static com.eonmux.excalibur.api.server.HttpRouting.refusingNamespace;
import static com.eonmux.excalibur.api.server.HttpRouting.unavailable;
import static com.eonmux.excalibur.api.server.HttpRouting.wrapHandler;

/**
 * Builds the node's HTTP surface and registers it on an {@link HttpServer}.
 *
 * <h2>A snapshot, not a view</h2>
 * <p>Every collaborator a route needs is taken once, in {@link Inputs}, and the route table is
 * built from that. {@code ExcaliburDBServer.start()} is synchronized and its configuration
 * methods refuse once the server is running, so the snapshot is what those methods left behind;
 * a setter that does not refuse and is called after {@code start()} is ignored, exactly as it
 * was when the routes read the fields directly.</p>
 *
 * <h2>Every path goes through the guard</h2>
 * <p>{@code /health} and {@code /health/ready} are the two unauthenticated routes, because the
 * infrastructure that reads them - a kubelet, a service mesh, a cloud load balancer's
 * target-group health check - is often not the operator's to configure and several of those
 * cannot attach an Authorization header at all. Both are still rate limited. Everything else
 * added for observability is authenticated: {@code /metrics} is read by a scraper the operator
 * does control, and every scraper supports a bearer token, so requiring one costs a line of
 * configuration and keeps disk capacity, corpus size and error rates away from an anonymous
 * caller - and {@code READ} degrades to open on a node with no token configured, which
 * {@code PUBLIC} could never be closed on a node that has one.</p>
 *
 * <p>Every context below is pinned to its own path: HttpServer matches on the longest prefix,
 * so without that a path that merely begins with a route's name is served by that route -
 * {@code /healthz} reads back as healthy and {@code /v1/statsfoo} returns the statistics.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe, and does not need to be: one instance serves one {@code start()}.</p>
 *
 * @author ExcaliburDB
 */
final class EndpointRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(EndpointRegistrar.class);

    /**
     * Most documents one {@code POST /v1/documents} may carry.
     *
     * <p>The request body is already bounded, so this is not about bytes. Each document is a
     * chunking pass, an embedding call and a set of index writes, all inside one request a
     * caller is waiting on, so the bound is on the work rather than on the payload. A caller
     * with more than this has a loop, not a request.</p>
     */
    private static final int MAX_SUPPLIED_DOCUMENTS = 256;

    private final Inputs in;
    private final RequestGuard guard;

    private EndpointRegistrar(Inputs in) {
        this.in = in;
        this.guard = new RequestGuard(in.security());
    }

    /**
     * Everything the route table is built from, as it stood when {@code start()} ran.
     *
     * @param security             The policy every route is guarded by
     * @param config               Endpoint defaults, or null for the compiled-in ones
     * @param indexOrchestrator    Visibility and worker metrics
     * @param storageEngine        Where chunk text and the segment catalog are read from
     * @param volumeManager        Disk space for stats and rolling replacement, or null
     * @param queryPipeline        What {@code POST /v1/query} executes
     * @param queryRegistry        In-flight queries, for cancellation
     * @param embeddingClient      Embeds raw query text, or null when this node cannot
     * @param lexicalQueryService  Lexical retrieval, or null when this node keeps no index
     * @param queryExpander       The expander the lexical half widens queries with, or null
     * @param chunkIndex           The chunk lexical index stats report on, or null
     * @param ingestSourceRegistry Where ingestion sources are registered
     * @param sourceCatalog        Where registrations are recorded across restarts, or null
     * @param ingestionStatus      What {@code GET /v1/ingest/status} serves
     * @param ingestionOrchestrator Background ingestion, or null when no pipeline is wired
     * @param reindexStatus        What {@code GET /v1/reindex/status} serves
     * @param documentReindexer    Executes a reindex, or null when reindexing answers 503
     * @param sourceResolver       Resolves a source id for the reindexer
     * @param reindexCoordinator   The claim shared with the scheduled reindex handler
     * @param documentErasure      The service behind {@code POST /v1/erasure}, or null
     * @param graphTenants         Every namespace's graph, or null
     * @param graphIngestSink      What ingestion stages facts through, or null
     * @param graphPipeline        A graph pipeline somebody else owns, or null
     * @param documentRegistry     What this node has ingested, or null
     * @param erasureReceipts      Where each document erasure leaves the receipt
     *                             {@code GET /v1/erasure/{request_id}} reads, or null for none
     */
    record Inputs(ApiSecurityConfig security,
                  Config config,
                  IndexOrchestrator indexOrchestrator,
                  StorageEngine storageEngine,
                  VolumeManager volumeManager,
                  QueryPipeline queryPipeline,
                  QueryRegistry queryRegistry,
                  EmbeddingClient embeddingClient,
                  LexicalQueryService lexicalQueryService,
                  com.eonmux.excalibur.retrieval.expand.QueryExpander queryExpander,
                  ChunkIndex chunkIndex,
                  SourceRegistry ingestSourceRegistry,
                  SourceCatalog sourceCatalog,
                  IngestStatusEndpoint.IngestionStatusRegistry ingestionStatus,
                  IngestionOrchestrator ingestionOrchestrator,
                  ReindexEndpoint.ReindexStatusRegistry reindexStatus,
                  DocumentReindexer documentReindexer,
                  SourceResolver sourceResolver,
                  ReindexCoordinator reindexCoordinator,
                  DocumentErasureService documentErasure,
                  GraphTenants graphTenants,
                  NamespaceRoutingGraphIngestSink graphIngestSink,
                  GraphRagPipeline graphPipeline,
                  DocumentRegistry documentRegistry,
                  ErasureReceiptFile erasureReceipts) {
    }

    /**
     * The endpoints that own executors, so {@code stop()} can drain them.
     *
     * @param query      Serves {@code POST /v1/query}
     * @param reindex    Serves {@code POST} and {@code DELETE /v1/reindex}
     * @param compaction Runs what {@code POST /v1/compact} asked for, behind the response
     * @param graphBuild Runs what {@code POST /v1/graph/build} asked for, behind the response,
     *                   or null on a node that serves no graph of its own
     * @param graphAutoBuild Rebuilds each namespace whose graph falls behind its staging, or
     *                   null on a node that serves no graph of its own
     * @param suppliedSources The ids documents have been supplied under, which the graph sink
     *                   reads to find the namespace a supplied document's facts belong to
     */
    record Endpoints(QueryEndpoint query, ReindexEndpoint reindex, CompactionRunner compaction,
                     GraphBuildRunner graphBuild, GraphAutoBuilder graphAutoBuild,
                     InlineSourceIds suppliedSources) {
    }

    /**
     * Registers every route this node serves on the given server.
     *
     * @param target The HTTP server to register contexts on
     * @param in     The collaborators the routes are built from
     * @return The endpoints the caller must drain on shutdown
     * @throws NullPointerException if either argument is null
     */
    static Endpoints register(HttpServer target, Inputs in) {
        Objects.requireNonNull(target, "target cannot be null");
        Objects.requireNonNull(in, "in cannot be null");
        return new EndpointRegistrar(in).registerOn(target);
    }

    private Endpoints registerOn(HttpServer target) {
        HealthEndpoint healthEndpoint = newHealthEndpoint();
        // One collector, two renderings. /v1/stats serves it as JSON and /metrics in the
        // Prometheus text exposition format; the way two views of one node come to disagree
        // is by each gathering its own.
        NodeStatsCollector collector = newStatsCollector();
        StatsEndpoint statsEndpoint = new StatsEndpoint(collector);

        // Only where there are namespaces to build. A node serving somebody else's pipeline
        // has no staged facts of its own, and a runner with no work is a thread to drain.
        // Created before the routes because the stats read the same runner the routes drive:
        // a second one would report builds nothing ever started.
        GraphBuildRunner graphBuildRunner = in.graphTenants() != null
            ? new GraphBuildRunner(in.graphTenants())
            : null;
        // Beside the runner, which it defers to: a namespace an explicit build is running for
        // is left to that build.
        GraphAutoBuilder graphAutoBuilder = graphBuildRunner != null
            ? GraphAutoBuilder.start(in.graphTenants(), graphBuildRunner, in.config() != null
                ? in.config().graph() : new GraphConfig(Map.of()))
            : null;
        if (graphBuildRunner != null) {
            IngestionOrchestrator ingestion = in.ingestionOrchestrator();
            collector.withGraph(new GraphStatsReader(in.graphTenants(), graphBuildRunner,
                in.graphIngestSink(),
                ingestion != null ? ingestion::graphReportFailures : () -> 0L));
        }

        // Where a result's text is read back from when a request asks for it. The indexes hold
        // postings and vectors and no text, so without this a search answers with identifiers a
        // caller can only resolve against the store the corpus was ingested from - which, for a
        // crawled corpus, it does not have. One reader for every route that quotes the corpus,
        // so a query and a fetch of the same chunk cannot disagree about what it says.
        // One instance, two holders. The endpoint fills it and /v1/stats reports it; a second
        // instance would report a cache nothing is using, which is worse than reporting none.
        QueryResultCache queryResultCache = newQueryResultCache();
        collector.withQueryResultCache(queryResultCache);

        Function<String, String> chunkText = StoredChunkText.of(in.storageEngine());
        QueryEndpoint queryHandler = newQueryEndpoint(chunkText, queryResultCache);

        // Shared by the two routes that can write under one source id, so that neither takes an
        // id the other is already using. The crawl half of the guard reads the source registry;
        // this is the supplied half, which leaves no registered source behind to be found.
        InlineSourceIds suppliedSources =
            new InlineSourceIds(in.security().maxRegisteredSources());

        IngestEndpoint ingestEndpoint = newIngestEndpoint(suppliedSources);
        wireSourceRegistry();
        ReindexEndpoint reindexHandler = newReindexEndpoint();

        HttpHandler notFound = guard.protect(RequestGuard.Access.PUBLIC, HttpRouting::handleNotFound);
        registerCore(target, notFound, healthEndpoint, statsEndpoint);
        registerObservability(target, notFound, collector);
        registerQuery(target, notFound, queryHandler);
        registerIngest(target, notFound, ingestEndpoint);
        registerDocuments(target, notFound, chunkText, suppliedSources);
        registerReindex(target, notFound, reindexHandler);
        registerErasure(target, notFound, suppliedSources);
        CompactionRunner compactionRunner = new CompactionRunner(
            in.indexOrchestrator()::compactNow, lexicalCompactor(chunkText));
        registerCompaction(target, notFound, compactionRunner);
        registerGraph(target, notFound, graphBuildRunner, graphAutoBuilder);
        target.createContext("/", wrapHandler(notFound));

        return new Endpoints(queryHandler, reindexHandler, compactionRunner, graphBuildRunner,
            graphAutoBuilder, suppliedSources);
    }

    // ==================== Endpoint construction ====================

    /** Health with the configured thresholds, or the compiled-in ones where none were given. */
    private HealthEndpoint newHealthEndpoint() {
        Config config = in.config();
        double readinessThreshold = config != null
            ? config.health().getReadinessThreshold()
            : HealthEndpoint.DEFAULT_READINESS_THRESHOLD;
        double healthyThreshold = config != null
            ? config.health().getHealthyThreshold()
            : HealthEndpoint.DEFAULT_HEALTHY_THRESHOLD;
        int maxLatencyMs = config != null
            ? config.health().getMaxLatencyMs()
            : HealthEndpoint.DEFAULT_MAX_LATENCY_MS;
        return new HealthEndpoint(
            in.indexOrchestrator(), readinessThreshold, healthyThreshold, maxLatencyMs);
    }

    /**
     * The query endpoint, with every retrieval signal this node has.
     *
     * <p>Config supplies the defaults, the query registry makes a query cancellable, and the
     * embedding client is what lets a client submit raw query text - the recommended path -
     * instead of computing embeddings itself.</p>
     *
     * @param chunkText   Reads a chunk's text back out of the store
     * @param resultCache Answers a repeated query without searching again, or null
     * @return The endpoint behind {@code POST /v1/query}
     */
    private QueryEndpoint newQueryEndpoint(Function<String, String> chunkText,
                                           QueryResultCache resultCache) {
        QueryEndpoint queryHandler = new QueryEndpoint(in.queryPipeline(), in.config(), null,
            in.queryRegistry(), in.embeddingClient(), in.security());
        // Lexical retrieval, when this node keeps a chunk index. On a node with no embedding
        // client it is what /v1/query answers from instead of refusing the query; on a node
        // that has one it is the second signal, fused with vector search by rank. Both name
        // the same thing - the chunk id ingestion assigned - which is what makes them fusible
        // rather than two id spaces to reconcile.
        queryHandler.setLexicalQueryService(in.lexicalQueryService());
        // The same instance the index was given, so an explained answer can say what the
        // query was widened with without generating a second, different expansion.
        queryHandler.setQueryExpander(in.queryExpander());
        queryHandler.setChunkText(chunkText);
        // Valid only while the corpus fingerprint an answer was computed against is unchanged,
        // which is re-read from the live corpus on every lookup rather than trusted from when
        // the entry was stored.
        queryHandler.setQueryResultCache(resultCache);
        return queryHandler;
    }

    /**
     * The chunk index's half of {@code POST /v1/compact}, or null on a node that keeps none.
     *
     * <p>A forced run rewrites every live segment of the index, so chunks the store no longer
     * holds are dropped even where too few were tombstoned for the index's own policy to act; an
     * ordinary run applies that policy. Either way the index reads each surviving chunk's text
     * back out of the store, the same reader queries use - which throws, rather than answering
     * that a chunk is gone, when storage cannot be read, so the rewrite leaves that part of the
     * index as it is.</p>
     *
     * @param chunkText Reads a chunk's text back out of the store
     * @return What compacts the chunk index on demand, or null
     */
    private CompactionRunner.LexicalCompactor lexicalCompactor(Function<String, String> chunkText) {
        ChunkIndex chunkIndex = in.chunkIndex();
        if (chunkIndex == null) {
            return null;
        }
        // The stop signal reaches the rewrite itself, so a cancel or a shutdown ends it at the
        // next segment group or chunk rather than after every segment has been read back. Whether
        // it reads storage is the index's to say: a shutdown keeps storage open under a rewrite
        // that does, and only the index's files under one that does not.
        return new CompactionRunner.LexicalCompactor() {
            @Override
            public com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult compact(
                    boolean force, java.util.function.BooleanSupplier stopRequested)
                    throws java.io.IOException {
                return force
                    ? chunkIndex.rewriteAll(chunkText, stopRequested)
                    : chunkIndex.compactIfDue(chunkText, stopRequested);
            }

            @Override
            public boolean readsStorage() {
                return chunkIndex.compactionReadsStorage();
            }
        };
    }

    /**
     * The rate this deployment paces crawls at.
     *
     * <p>Read here rather than in the factory, because a restored source and a newly registered
     * one have to be paced the same: a crawl that came back from the catalog at startup is the
     * same crawl against the same host.</p>
     *
     * @return {@code excalibur.ingest.crawl-delay-ms}, or the built-in default with no config
     */
    private long minCrawlDelayMillis() {
        return in.config() != null
            ? in.config().ingest().getCrawlDelayMs()
            : com.eonmux.excalibur.config.IngestConfig.DEFAULT_CRAWL_DELAY_MS;
    }

    /**
     * The ingest endpoint, wired to both the status registry and the orchestrator.
     *
     * <p>The registry is what makes a status entry appear as soon as a source is registered;
     * the orchestrator, where one is configured, is what then reports progress against it.</p>
     *
     * @param suppliedSources The ids {@code POST /v1/documents} has taken
     * @return The endpoint behind {@code POST /v1/ingest}
     */
    private IngestEndpoint newIngestEndpoint(InlineSourceIds suppliedSources) {
        IngestEndpoint ingestEndpoint = new IngestEndpoint(
            in.ingestSourceRegistry(),
            in.ingestionStatus(),
            in.ingestionOrchestrator(),
            new IngestTargetValidator(in.security()),
            suppliedSources,
            minCrawlDelayMillis()
        );
        LOGGER.info("IngestEndpoint configured with status registry (orchestrator: {})",
            in.ingestionOrchestrator() != null ? "enabled"
                : "disabled (sources register, status reports unconfigured)");
        return ingestEndpoint;
    }

    /**
     * Attaches the periodic reindex scheduler, the reindex status registry and the catalog to
     * the source registry, and restores what the catalog already holds.
     *
     * <p>A source registered via {@code /v1/ingest} is then reindexed periodically at the
     * configured interval without any further call.</p>
     */
    private void wireSourceRegistry() {
        if (!(in.ingestSourceRegistry() instanceof InMemorySourceRegistry inMemRegistry)) {
            return;
        }
        long reindexIntervalMs = in.config() != null
            ? in.config().ingest().getReindexIntervalMs()
            : SegmentReindexScheduler.DEFAULT_REINDEX_INTERVAL_MS;
        inMemRegistry.setReindexScheduler(
            in.indexOrchestrator().getReindexScheduler(),
            reindexIntervalMs
        );
        // So that deregistering a source forgets what its reindex runs did, the way it
        // already forgets what its ingestion did. /v1/reindex/status serves this registry,
        // and without the wiring it keeps answering for sources that are gone.
        inMemRegistry.setReindexStatusRegistry(in.reindexStatus());
        // And so that deregistering it - or erasing it, which deregisters first - stops a
        // re-crawl already running over it and waits for that to end, rather than leaving it to
        // write the source's documents back after they are gone.
        inMemRegistry.setReindexCoordinator(in.reindexCoordinator());
        LOGGER.info("IngestSourceRegistry wired to ReindexScheduler with interval={}ms", reindexIntervalMs);

        // The catalog is attached before its records are read back, so a source restored here
        // stays recorded rather than being re-written on the way in.
        if (in.sourceCatalog() != null) {
            inMemRegistry.setSourceCatalog(in.sourceCatalog());
            SourceRestoration.restore(
                in.sourceCatalog(),
                new SourceFactory(new IngestTargetValidator(in.security()), minCrawlDelayMillis()),
                inMemRegistry
            );
        }
    }

    /**
     * The reindex endpoint, on the same source registry every other route uses.
     *
     * <p>With a reindexer it performs actual reindexes, and with a volume manager and a segment
     * catalog it does so by rolling replacement. Without a reindexer the route exists and
     * answers 503, rather than being absent.</p>
     *
     * @return The endpoint behind {@code POST} and {@code DELETE /v1/reindex}
     */
    private ReindexEndpoint newReindexEndpoint() {
        if (in.documentReindexer() == null) {
            LOGGER.info("ReindexEndpoint configured without a DocumentReindexer; "
                + "POST /v1/reindex will answer 503 until one is wired");
            return new ReindexEndpoint(in.ingestSourceRegistry(), in.reindexStatus());
        }
        ReindexEndpoint reindexHandler = new ReindexEndpoint(
            in.ingestSourceRegistry(),
            in.reindexStatus(),
            in.documentReindexer(),
            in.sourceResolver(),
            in.volumeManager(),               // For selecting volumes for new segments
            in.storageEngine().getCatalog(),  // For segment lifecycle management
            in.reindexCoordinator(),          // Shared with the scheduled reindex handler
            in.ingestionStatus()              // Where a run records what it passed over
        );
        boolean hasRollingReplacement =
            in.volumeManager() != null && in.storageEngine().getCatalog() != null;
        LOGGER.info("ReindexEndpoint configured with DocumentReindexer for actual reindex operations, rolling replacement: {}",
            hasRollingReplacement ? "enabled" : "disabled");
        return reindexHandler;
    }

    /**
     * The one reader of this node's state, shared by {@code /v1/stats} and {@code /metrics}.
     *
     * @return A collector with every optional signal this deployment has attached
     */
    private NodeStatsCollector newStatsCollector() {
        NodeStatsCollector collector = new NodeStatsCollector(
            in.indexOrchestrator(), in.storageEngine(), in.volumeManager());
        collector.withLexicalIndex(in.chunkIndex());
        collector.withEmbeddingBreaker(embeddingBreakerState());
        collector.withLlmRelevancyBreaker(llmRelevancyBreakerState());
        collector.withQueryExpansionBreaker(queryExpansionBreakerState());
        collector.withGraphExtractionBreaker(graphExtractionBreakerState());
        collector.withGraphRerankBreaker(graphRerankBreakerState());
        collector.withGraphQueryEmbeddingBreaker(graphQueryEmbeddingBreakerState());
        collector.withGraphBuildEmbeddingBreaker(graphBuildEmbeddingBreakerState());
        return collector;
    }

    /**
     * The query result cache, or null on a deployment that has not asked for one.
     *
     * <p>Off unless {@code excalibur.query.cache.enabled} says otherwise. Null is not a cache
     * of size zero: with no cache the endpoint takes no corpus fingerprint at all, and a
     * request costs exactly what it did before one existed.</p>
     *
     * @return The cache, or null
     */
    private QueryResultCache newQueryResultCache() {
        Config config = in.config();
        if (config == null || !config.query().isCacheEnabled()) {
            return null;
        }
        int entries = config.query().getCacheMaxEntries();
        LOGGER.info("Query result cache enabled, holding at most {} answers; every lookup "
            + "re-reads the corpus fingerprint, so an entry is discarded as soon as anything "
            + "it was computed against changes", entries);
        return new QueryResultCache(entries);
    }

    /**
     * Reads the embedding circuit breaker's state, or null on a node with no embeddings.
     *
     * <p>The breaker is the node's main degradation mode and was readable only from log
     * statements: when it opens, retrieval silently drops to lexical-only and the node keeps
     * answering 200, so a caller gets worse results with no signal anywhere that it has. The
     * embedding client is the one place in this process it is reachable from.</p>
     *
     * @return A supplier of the state's name, or null where there is no breaker
     */
    private Supplier<String> embeddingBreakerState() {
        EmbeddingClient embeddings = in.embeddingClient();
        if (embeddings == null) {
            return null;
        }
        return () -> stateOf(embeddings.getCircuitBreaker());
    }

    /**
     * Reads the stage-4 relevancy breaker's state, or null on a node that does no scoring.
     *
     * <p>The scorer arrives as a {@code QueryPipeline.LlmScorer}, and only the shipped
     * implementation owns a breaker; anything else - a no-op scorer, a test double - reports
     * absence, which is the same statement a node with no chat model makes. Read through the
     * pipeline the registrar was handed, which is the pipeline the query route will execute:
     * {@code withLlmScorer} replaces it before {@code start()} takes this snapshot.</p>
     *
     * @return A supplier of the state's name, or null where there is no breaker
     */
    private Supplier<String> llmRelevancyBreakerState() {
        QueryPipeline pipeline = in.queryPipeline();
        if (pipeline == null
            || !(pipeline.getLlmScorer() instanceof LlmRelevancyScorer scorer)) {
            return null;
        }
        return () -> stateOf(scorer.getCircuitBreaker());
    }

    /**
     * Reads the query-expansion breaker's state, or null on a node that expands no queries.
     *
     * <p>Read through the result cache the expander is normally wrapped in, which keeps no
     * breaker of its own. Only the shipped chat-model expander owns one; any other expander -
     * or none - reports absence, the same statement a node without a chat model makes.</p>
     *
     * @return A supplier of the state's name, or null where there is no breaker
     */
    private Supplier<String> queryExpansionBreakerState() {
        com.eonmux.excalibur.retrieval.expand.QueryExpander expander = in.queryExpander();
        if (expander instanceof com.eonmux.excalibur.retrieval.expand.CachingQueryExpander cached) {
            expander = cached.delegate();
        }
        if (!(expander instanceof com.eonmux.excalibur.retrieval.expand.LlmQueryExpander llm)) {
            return null;
        }
        return () -> stateOf(llm.getCircuitBreaker());
    }

    /**
     * Reads the relation-extraction breaker's state, or null on a node that extracts no facts.
     *
     * <p>A graph sink over {@code RelationExtractor.none()} - a node with no chat model - has no
     * breaker, and reports absence rather than a closed one.</p>
     *
     * @return A supplier of the state's name, or null where there is no breaker
     */
    private Supplier<String> graphExtractionBreakerState() {
        NamespaceRoutingGraphIngestSink sink = in.graphIngestSink();
        if (sink == null || !(sink.extractor()
                instanceof com.eonmux.excalibur.ingest.graph.LlmRelationExtractor extractor)) {
            return null;
        }
        return () -> stateOf(extractor.getCircuitBreaker());
    }

    /**
     * Reads the graph route's rerank breaker's state, or null on a node that serves no graph.
     *
     * <p>Read from the tenants rather than from any one graph, because the breaker is the
     * node's: every namespace's graph reranks through the one chat client the tenants hold. The
     * supplier answers null until a graph that reranks with a model has been opened, and a node
     * serving a pipeline it did not open itself has no tenants to ask.</p>
     *
     * @return A supplier of the state's name, or null where there is no graph to rerank
     */
    private Supplier<String> graphRerankBreakerState() {
        GraphTenants tenants = in.graphTenants();
        if (tenants == null) {
            return null;
        }
        return () -> stateOf(tenants.models().rerankBreaker());
    }

    /**
     * Reads the graph route's question-embedding breaker's state, or null on a node that serves
     * no graph.
     *
     * <p>As {@link #graphRerankBreakerState()}, and answering null until a graph built with
     * entity vectors - the only kind that embeds a question - has been opened.</p>
     *
     * @return A supplier of the state's name, or null where there is no graph to rerank
     */
    private Supplier<String> graphQueryEmbeddingBreakerState() {
        GraphTenants tenants = in.graphTenants();
        if (tenants == null) {
            return null;
        }
        return () -> stateOf(tenants.models().queryEmbeddingBreaker());
    }

    /**
     * Reads the graph build's entity-embedding breaker's state, or null on a node that serves
     * no graph.
     *
     * <p>As {@link #graphRerankBreakerState()}, and answering null until a build has embedded
     * an entity - a node whose builds write no vectors never creates the client.</p>
     *
     * @return A supplier of the state's name, or null where there is no graph to build
     */
    private Supplier<String> graphBuildEmbeddingBreakerState() {
        GraphTenants tenants = in.graphTenants();
        if (tenants == null) {
            return null;
        }
        return () -> stateOf(tenants.models().buildEmbeddingBreaker());
    }

    /** A breaker's state by name, or null where there is no breaker. */
    private static String stateOf(CircuitBreaker breaker) {
        return breaker == null ? null : breaker.getState().name();
    }

    // ==================== Route registration ====================

    /**
     * Guards a route over the corpus, which is one corpus and not one per namespace.
     *
     * <p>The guard first, so an anonymous caller is answered 401 and learns nothing from the
     * header; then {@link HttpRouting#refusingNamespace}, so a caller that named a namespace is
     * told this route cannot serve one rather than being handed the shared index as though it
     * could. Every route that reads or rewrites the corpus goes through here, which is what
     * keeps the answer the same across all of them.</p>
     *
     * @param access  What the route costs a caller
     * @param handler The route
     * @return The guarded handler
     */
    private HttpHandler corpusRoute(RequestGuard.Access access, HttpHandler handler) {
        return guard.protect(access, refusingNamespace(handler));
    }

    /**
     * Routes a path that serves one method.
     *
     * <p>Every other method is answered 405 by a handler of its own, behind the public guard,
     * as the multi-method routes do. Left to the route's handler, the 405 would be served beneath
     * the route's guard instead: an anonymous caller would be answered 401 for a method the path
     * does not serve at all, and a wrong method on a mutating route would be charged to the much
     * smaller mutate budget.</p>
     *
     * @param method  The one method the path serves
     * @param guarded The path's handler, already guarded
     * @return A handler dispatching {@code method} to the route and refusing the rest
     */
    /**
     * Records every request reaching a mutating route in the audit trail.
     *
     * <p>Outside the guard, so a request it refuses - unauthenticated, or over the mutate budget -
     * is recorded as the attempt it was. Every route that changes what this node holds or how it
     * behaves goes through here; see {@link AuditLog}.</p>
     *
     * @param action  The action the route performs, as the audit event names it
     * @param guarded The route, already guarded
     * @return The audited route
     */
    private static HttpHandler audited(String action, HttpHandler guarded) {
        return AuditLog.audited(action, guarded);
    }

    private HttpHandler onlyMethod(String method, HttpHandler guarded) {

        return createMethodRouter(Map.of(method, guarded),
            guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                "Method Not Allowed - Use " + method)));
    }

    private void registerCore(HttpServer target, HttpHandler notFound,
                              HealthEndpoint healthEndpoint, StatsEndpoint statsEndpoint) {
        target.createContext("/health",
            wrapHandler(exactPath("/health",
                guard.protect(RequestGuard.Access.PUBLIC, healthEndpoint), notFound)));
        target.createContext("/v1/stats",
            wrapHandler(exactPath("/v1/stats",
                onlyMethod("GET", corpusRoute(RequestGuard.Access.READ, statsEndpoint)), notFound)));
    }

    /**
     * Registers the routes a monitoring system, a probe and an operator read.
     *
     * <p>{@code /health/ready}, {@code /v1/version} and {@code /v1/query/status} are longer
     * paths than the routes they sit under, so the server's longest-prefix matching sends
     * them here rather than to {@code /health} or {@code /v1/query}.</p>
     *
     * <p>Auth posture, route by route. {@code /health/ready} is PUBLIC for the reason
     * {@code /health} is - it is read by infrastructure that frequently cannot send a header -
     * and is charged against the read budget like every other unauthenticated request.
     * {@code /metrics} is READ: its consumer is a scraper the operator configures, every
     * scraper supports a bearer token, and the exposition names disk capacity, corpus size and
     * error rates. {@code /v1/version} is READ because no probe needs it and build identity is
     * the first thing worth knowing about a host you intend to attack.
     * {@code /v1/query/status} is READ, like the ingest and reindex status routes it matches.
     * {@code /v1/drain} is MUTATE: it changes how this node presents itself to a load
     * balancer, and an anonymous caller able to drain a node has a one-request outage.</p>
     *
     * @param target    The server to register on
     * @param notFound  What answers every other path under these contexts
     * @param collector The shared reader of this node's state
     */
    private void registerObservability(HttpServer target, HttpHandler notFound,
                                       NodeStatsCollector collector) {
        ServiceReadiness readiness = new ServiceReadiness();

        target.createContext("/health/ready",
            wrapHandler(exactPath("/health/ready", guard.protect(RequestGuard.Access.PUBLIC,
                newReadinessEndpoint(readiness)), notFound)));
        target.createContext("/metrics",
            wrapHandler(exactPath("/metrics", onlyMethod("GET", guard.protect(RequestGuard.Access.READ,
                new MetricsEndpoint(collector, in.queryRegistry(), readiness))), notFound)));
        target.createContext("/v1/version",
            wrapHandler(exactPath("/v1/version", onlyMethod("GET", guard.protect(RequestGuard.Access.READ,
                new VersionEndpoint())), notFound)));
        target.createContext("/v1/query/status",
            // A corpus route, like the /v1/query whose calls it lists: a caller naming a tenant
            // would otherwise be shown every query on the node as though they were its own.
            wrapHandler(exactPath("/v1/query/status", onlyMethod("GET", corpusRoute(RequestGuard.Access.READ,
                new QueryStatusEndpoint(in.queryRegistry()))), notFound)));

        // Two verbs on one noun, the shape /v1/ingest and /v1/reindex already use: beginning
        // a drain and ending one are one resource. Each branch carries its own guard, so the
        // 405 is charged rather than served below it.
        DrainEndpoint drain = new DrainEndpoint(readiness);
        target.createContext("/v1/drain", wrapHandler(exactPath("/v1/drain", createMethodRouter(
            "POST", audited("node.drain", guard.protect(RequestGuard.Access.MUTATE, drain)),
            "DELETE", audited("node.undrain", guard.protect(RequestGuard.Access.MUTATE, drain)),
            guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                "Method Not Allowed - Use POST to drain or DELETE to undrain"))), notFound)));
    }

    /**
     * Readiness over the four signals it is decided from.
     *
     * <p>The coverage threshold is the one {@code /health} uses, so the two agree about that
     * half of the question. The free-disk floor is compiled in: it is not a tuning knob but
     * the point past which a compaction or a rolling reindex can no longer be relied on to
     * write its replacement segment.</p>
     *
     * @param readiness The operator-controlled drain flag
     * @return The endpoint behind {@code GET /health/ready}
     */
    private ReadinessEndpoint newReadinessEndpoint(ServiceReadiness readiness) {
        double readinessThreshold = in.config() != null
            ? in.config().health().getReadinessThreshold()
            : HealthEndpoint.DEFAULT_READINESS_THRESHOLD;
        double minFreeDiskRatio = in.config() != null
            ? in.config().health().getMinFreeDiskRatio()
            : ReadinessEndpoint.DEFAULT_MIN_FREE_DISK_RATIO;
        return new ReadinessEndpoint(readiness, this::coverage,
            in.indexOrchestrator()::isRunning, freeDiskRatio(), readinessThreshold,
            minFreeDiskRatio);
    }

    /**
     * The share of live segments a query reads, from the same catalog {@code /health} asks.
     *
     * @return Coverage in [0, 1], or 0 on a node with no catalog
     */
    private double coverage() {
        SegmentCatalog catalog = in.storageEngine().getCatalog();
        return catalog == null ? 0.0 : catalog.getCatalogMetadata().getEstimatedCoverage();
    }

    /**
     * The share of volume capacity still free.
     *
     * <p>A node started without a volume manager has not been told where its disks are, which
     * is not the same as having run out of them: it reports full capacity rather than none,
     * so an absent manager cannot by itself take a node out of rotation.</p>
     *
     * @return A supplier of a ratio in [0, 1]
     */
    private DoubleSupplier freeDiskRatio() {
        VolumeManager volumes = in.volumeManager();
        if (volumes == null) {
            return () -> 1.0;
        }
        return () -> {
            VolumeManager.VolumeStats stats = volumes.getAggregatedVolumeStats();
            return stats == null ? 1.0 : stats.availableRatio();
        };
    }

    /**
     * Registers {@code /v1/query}: POST runs a query, DELETE cancels one.
     *
     * <p>Any other method is answered by a handler of its own, so the 405 is charged against the
     * read budget rather than served below the guard. POST is the query itself and takes no
     * sub-path; DELETE carries the query id in one, so the context serves its own path and its
     * children and each method is pinned within.</p>
     */
    private void registerQuery(HttpServer target, HttpHandler notFound, QueryEndpoint queryHandler) {
        // Registered at the same path as the query itself, and reached only by DELETE.
        QueryCancelEndpoint queryCancelEndpoint = new QueryCancelEndpoint(in.queryRegistry());
        target.createContext("/v1/query", wrapHandler(pathOrChild("/v1/query", createMethodRouter(
            "POST", exactPath("/v1/query",
                corpusRoute(RequestGuard.Access.READ, queryHandler), notFound),
            "DELETE", audited("query.cancel",
                corpusRoute(RequestGuard.Access.MUTATE, queryCancelEndpoint)),
            exactPath("/v1/query", guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                "Method Not Allowed - Use POST for queries or DELETE for cancellation")),
                notFound)), notFound)));
    }

    /**
     * Registers {@code /v1/ingest}: POST registers a source, PUT replaces its configuration, GET
     * reads the registrations back, DELETE stops crawling one.
     *
     * <p>The two that mutate are charged against the mutate budget and the read-back against the
     * read one; the same shape {@code /v1/query} uses for query and cancel.
     * {@code /v1/ingest/status} is the progress readback, and {@code /v1/ingest/run} stops the
     * run in progress without deregistering the source - a crawl hammering a site has to be
     * stoppable without also throwing away what the source was configured as.</p>
     */
    private void registerIngest(HttpServer target, HttpHandler notFound,
                                IngestEndpoint ingestEndpoint) {
        target.createContext("/v1/ingest", wrapHandler(exactPath("/v1/ingest", createMethodRouter(
            Map.of(
                "POST", audited("source.register",
                    guard.protect(RequestGuard.Access.MUTATE, ingestEndpoint)),
                // The same endpoint and the same namespace-honouring guard as POST: a replacement
                // is validated exactly as a registration is, and the header is how it proves it
                // is not moving the source to another tenant.
                "PUT", audited("source.update",
                    guard.protect(RequestGuard.Access.MUTATE, ingestEndpoint)),
                // The read budget, like every other read-back: an operator asking what a source
                // was registered as is not changing anything, and charging it against the
                // mutate budget would let a status poll starve an ingestion.
                // Namespace-refusing, unlike the POST above it. Registration is what reads the
                // header - it decides which tenant a source stages into - but the registry it
                // writes to is one per node, so reading it back, stopping a crawl and
                // deregistering are all node-wide. Honouring the header on those would hand a
                // caller who named tenant A the whole node's sources and let them deregister a
                // source belonging to tenant B, with nothing in the answer to say so.
                "GET", corpusRoute(RequestGuard.Access.READ,
                    new SourceListEndpoint(in.ingestSourceRegistry(), in.sourceCatalog())),
                "DELETE", audited("source.deregister", corpusRoute(RequestGuard.Access.MUTATE,
                    new SourceDeregisterEndpoint(in.ingestSourceRegistry())))),
            guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                "Method Not Allowed - Use GET to read the registrations, POST to register a "
                    + "source, PUT to replace its configuration or DELETE to stop crawling it"))),
            notFound)));
        target.createContext("/v1/ingest/run",
            wrapHandler(exactPath("/v1/ingest/run", onlyMethod("DELETE", audited("ingest.cancel",
                corpusRoute(RequestGuard.Access.MUTATE,
                    new IngestCancelEndpoint(in.ingestSourceRegistry())))), notFound)));
        target.createContext("/v1/ingest/status",
            wrapHandler(exactPath("/v1/ingest/status", onlyMethod("GET", corpusRoute(RequestGuard.Access.READ,
                new IngestStatusEndpoint(in.ingestionStatus()))), notFound)));
    }

    /**
     * Registers {@code /v1/documents}: GET reads back the text behind an identifier
     * {@code /v1/query} answered with, POST supplies documents to index.
     *
     * <p>The read is charged against the read budget - it is the second half of a search, and a
     * caller rationed for retrieving what it just retrieved would be rationed for retrieving -
     * and the write against the mutate budget, like every other route that changes the
     * corpus.</p>
     */
    private void registerDocuments(HttpServer target, HttpHandler notFound,
                                   Function<String, String> chunkText,
                                   InlineSourceIds suppliedSources) {
        DocumentFetchEndpoint documentFetch = new DocumentFetchEndpoint(
            ChunkTextResolver.over(chunkText, in.security().maxResultTextChars()),
            // Bounded by the same top_k ceiling the search is, so one page of results is
            // always fetchable in one request and never more than one.
            in.security().maxTopK());
        target.createContext("/v1/documents", wrapHandler(exactPath("/v1/documents",
            documentsRoute(documentFetch, suppliedSources), notFound)));
        // The batch form of the read. A longer path than /v1/documents, so longest-prefix
        // matching routes it here rather than to the POST above, which supplies documents.
        //
        // POST only. The endpoint behind it also understands GET, because it is the same object
        // serving GET /v1/documents, and leaving the method open here published a second,
        // undocumented way to read one chunk - on a path whose whole reason to exist is reading
        // several, and whose own 405 names the other route.
        target.createContext("/v1/documents/fetch",
            wrapHandler(exactPath("/v1/documents/fetch",
                createMethodRouter(
                    Map.of("POST",
                        corpusRoute(RequestGuard.Access.READ, documentFetch)),
                    guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                        "Method Not Allowed - Use POST to read a page of chunks here, "
                            + "or GET /v1/documents to read one"))),
                notFound)));
        // Only on a node that keeps a document registry. Without one the node holds no record
        // of what it ingested, and an empty listing would read as an empty corpus rather than
        // as an unanswerable question.
        if (in.documentRegistry() != null) {
            target.createContext("/v1/documents/list",
                wrapHandler(exactPath("/v1/documents/list",
                    onlyMethod("GET", corpusRoute(RequestGuard.Access.READ,
                        // A document read with its text is the same read the batch fetch is,
                        // so it is clipped and bounded the same way.
                        new DocumentListEndpoint(in.documentRegistry(),
                            ChunkTextResolver.over(chunkText, in.security().maxResultTextChars()),
                            in.security().maxTopK()))), notFound)));
        }
    }

    /**
     * The method router behind {@code /v1/documents}, with or without an ingestion pipeline.
     *
     * @param documentFetch   Serves the GET half
     * @param suppliedSources The ids inline documents have taken
     * @return A handler dispatching GET and POST
     */
    private HttpHandler documentsRoute(DocumentFetchEndpoint documentFetch,
                                       InlineSourceIds suppliedSources) {
        HttpHandler wrongMethod = guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
            "Method Not Allowed - Use GET to read a document's text "
                + "or POST to supply documents"));
        HttpHandler read = corpusRoute(RequestGuard.Access.READ, documentFetch);
        if (in.ingestionOrchestrator() == null) {
            // MUTATE, not PUBLIC, even though there is nothing behind it. The guard checks
            // the rate limit and then authentication, so a PUBLIC 503 here answered an
            // anonymous caller while a node that does have a pipeline answers 401 - which
            // tells a prober which deployment it is looking at, and describes it.
            return createMethodRouter("GET", read,
                "POST", audited("documents.supply", corpusRoute(RequestGuard.Access.MUTATE,
                    unavailable("Supplying documents directly requires an ingestion pipeline, "
                        + "which this node was started without"))),
                wrongMethod);
        }
        return createMethodRouter("GET", read,
            "POST", audited("documents.supply", corpusRoute(RequestGuard.Access.MUTATE,
                new DocumentUpsertEndpoint(in.ingestionOrchestrator(), in.ingestSourceRegistry(),
                    suppliedSources, MAX_SUPPLIED_DOCUMENTS))),
            wrongMethod);
    }

    /**
     * Registers {@code /v1/reindex}: POST starts a reindex, DELETE asks a running one to stop.
     *
     * <p>Both mutate: one rewrites the served segments and the other stops a rewrite in
     * progress, and a stuck reindex an operator cannot clear is the reason the second exists.</p>
     */
    private void registerReindex(HttpServer target, HttpHandler notFound,
                                 ReindexEndpoint reindexHandler) {
        target.createContext("/v1/reindex", wrapHandler(exactPath("/v1/reindex",
            createMethodRouter(
                "POST", audited("reindex.start",
                    corpusRoute(RequestGuard.Access.MUTATE, reindexHandler)),
                "DELETE", audited("reindex.cancel",
                    corpusRoute(RequestGuard.Access.MUTATE, reindexHandler)),
                guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                    "Method Not Allowed - Use POST to start a reindex or DELETE to stop one"))),
            notFound)));
        // The readback for /v1/reindex, which answers 202 and works in the background. Without
        // it the run's verdict reached the node's log and nothing else. A longer path than
        // /v1/reindex, so the server's longest-prefix matching routes it here rather than to
        // the reindex handler, which answers 405 to a GET.
        target.createContext("/v1/reindex/status",
            wrapHandler(exactPath("/v1/reindex/status", onlyMethod("GET", corpusRoute(RequestGuard.Access.READ,
                new ReindexStatusEndpoint(in.reindexStatus()))), notFound)));
    }

    /** A mutation, and the most consequential one: it removes content rather than adding it. */
    private void registerErasure(HttpServer target, HttpHandler notFound,
                                 InlineSourceIds suppliedSources) {
        if (in.documentErasure() == null) {
            return;
        }
        // One context for the collection and its members: POST on the collection erases, GET on
        // a member reads back where an erasure stands. The readback is a read, so polling it is
        // not rationed against the mutate budget. A node wired without receipts answers it 503,
        // naming what it lacks, rather than 404 for a route the API has.
        HttpHandler status = onlyMethod("GET", corpusRoute(RequestGuard.Access.READ,
            in.erasureReceipts() == null
                ? unavailable("Service Unavailable - this node keeps no erasure receipts, so "
                    + "it cannot say where an erasure stands")
                : new DocumentErasureStatusEndpoint(in.erasureReceipts(), in.documentErasure(),
                    in.graphTenants())));
        target.createContext("/v1/erasure", wrapHandler(pathOrChild("/v1/erasure",
            exactPath("/v1/erasure",
                onlyMethod("POST", audited("erasure.documents",
                    corpusRoute(RequestGuard.Access.MUTATE,
                        new DocumentErasureEndpoint(in.documentErasure(),
                            in.ingestSourceRegistry(), suppliedSources, in.graphTenants(),
                            in.erasureReceipts(), Clock.systemUTC())))),
                status),
            notFound)));
    }

    /**
     * Registers {@code /v1/compact}: POST reclaims tombstoned space now, GET says what the last
     * run did.
     *
     * <p>A mutation, and charged as one: it rewrites every segment file it touches. The
     * readback is a read, like every other {@code /status} route, so polling for the verdict of
     * a run that takes minutes is not rationed against the much smaller mutate budget. Each
     * path serves one method and refuses the rest from behind its own guard, as the other
     * single-method routes do.</p>
     */
    private void registerCompaction(HttpServer target, HttpHandler notFound,
                                    CompactionRunner runner) {
        target.createContext("/v1/compact", wrapHandler(exactPath("/v1/compact",
            createMethodRouter(
                "POST", audited("compaction.start",
                    corpusRoute(RequestGuard.Access.MUTATE, new CompactionEndpoint(runner))),
                // A mutation too: it ends a rewrite of the served data. An anonymous caller
                // able to stop every compaction is a node that never reclaims anything.
                "DELETE", audited("compaction.cancel", corpusRoute(RequestGuard.Access.MUTATE,
                    new CompactionCancelEndpoint(runner))),
                guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                    "Method Not Allowed - Use POST to start a compaction or DELETE to stop "
                        + "the one in progress"))),
            notFound)));
        // A longer path than /v1/compact, so the server's longest-prefix matching routes it
        // here rather than to the handler above, which answers 405 to a GET.
        target.createContext("/v1/compact/status",
            wrapHandler(exactPath("/v1/compact/status", onlyMethod("GET", corpusRoute(RequestGuard.Access.READ,
                new CompactionStatusEndpoint(runner))), notFound)));
    }

    /**
     * How long a graph question may run when it names no {@code timeout_ms}: the same default
     * {@code POST /v1/query} applies, since both bound one caller's wait for one answer.
     *
     * @return The default timeout, in milliseconds
     */
    private long graphQueryTimeoutMs() {
        return in.config() != null
            ? in.config().visibility().getQueryTimeoutMs()
            : VisibilityConfig.DEFAULT_QUERY_TIMEOUT_MS;
    }

    /**
     * Registers the graph routes, only when a graph store is configured.
     *
     * <p>The query is a read, not a mutation: it walks the graph and returns context, and
     * charging it against the smaller mutate budget would throttle ordinary questions.</p>
     */
    private void registerGraph(HttpServer target, HttpHandler notFound,
                               GraphBuildRunner graphBuildRunner,
                               GraphAutoBuilder graphAutoBuilder) {
        if (in.graphTenants() != null) {
            target.createContext("/v1/graph/query", wrapHandler(exactPath("/v1/graph/query",
                onlyMethod("POST", guard.protect(RequestGuard.Access.READ,
                    new GraphQueryEndpoint(in.graphTenants(),
                        in.queryRegistry(), graphQueryTimeoutMs(), in.security().maxQueryTimeoutMs()))), notFound)));
            // Both mutations: they rewrite the served graph. The build answers 202 and runs
            // behind the response, so the DELETE beside it is how one started at the wrong
            // moment is ended - a mutation too, since an anonymous caller able to stop every
            // build is a node whose graph never changes.
            target.createContext("/v1/graph/build", wrapHandler(exactPath("/v1/graph/build",
                createMethodRouter(
                    "POST", audited("graph.build.start", guard.protect(RequestGuard.Access.MUTATE,
                        new GraphBuildEndpoint(graphBuildRunner))),
                    "DELETE", audited("graph.build.cancel",
                        guard.protect(RequestGuard.Access.MUTATE,
                            new GraphBuildCancelEndpoint(graphBuildRunner))),
                    guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                        "Method Not Allowed - Use POST to start a graph build or DELETE to "
                            + "stop the one in progress"))),
                notFound)));
            // A longer path than /v1/graph/build, so the server's longest-prefix matching
            // routes it here rather than to the handler above, which answers 405 to a GET. A
            // read, like every other /status route: polling for the verdict of a build that
            // takes minutes must not be rationed against the smaller mutate budget.
            target.createContext("/v1/graph/build/status", wrapHandler(
                exactPath("/v1/graph/build/status", onlyMethod("GET", guard.protect(RequestGuard.Access.READ,
                    new GraphBuildStatusEndpoint(graphBuildRunner, graphAutoBuilder))),
                notFound)));
            // One context for the collection and its members: POST on the collection records an
            // obligation, GET on a member reads back where one stands. The readback is a read, so
            // polling a deadline is not rationed against the mutate budget.
            target.createContext("/v1/graph/erasure", wrapHandler(pathOrChild("/v1/graph/erasure",
                exactPath("/v1/graph/erasure",
                    onlyMethod("POST", audited("erasure.graph",
                        guard.protect(RequestGuard.Access.MUTATE,
                            new GraphErasureEndpoint(in.graphTenants())))),
                    onlyMethod("GET", guard.protect(RequestGuard.Access.READ,
                        new GraphErasureStatusEndpoint(in.graphTenants())))),
                notFound)));
            // The served graph read by entity: a read like the query it sits beside, over the
            // same lease, and answered 503 exactly where the query is.
            target.createContext(GraphEntitiesEndpoint.PATH, wrapHandler(
                pathOrChild(GraphEntitiesEndpoint.PATH, onlyMethod("GET",
                    guard.protect(RequestGuard.Access.READ,
                        new GraphEntitiesEndpoint(in.graphTenants()))), notFound)));
            // Both mutations: asserting stages facts the next build serves, and retracting
            // records an erasure obligation.
            GraphFactsEndpoint facts = new GraphFactsEndpoint(in.graphTenants());
            target.createContext(GraphFactsEndpoint.PATH, wrapHandler(
                pathOrChild(GraphFactsEndpoint.PATH, createMethodRouter(
                    "POST", audited("graph.facts.assert",
                        guard.protect(RequestGuard.Access.MUTATE, facts)),
                    "DELETE", audited("graph.facts.retract",
                        guard.protect(RequestGuard.Access.MUTATE, facts)),
                    guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                        "Method Not Allowed - Use POST /v1/graph/facts to assert facts or "
                            + "DELETE /v1/graph/facts/{fact_id} to retract one"))),
                notFound)));
            // The namespace inventory and the drop. One context with pathOrChild, because the
            // collection is a GET and its members carry the namespace in the path. Registered
            // only where graphTenants is: a node serving somebody else's pipeline has no
            // namespaces of its own to list, let alone remove.
            target.createContext("/v1/graph/namespaces", wrapHandler(
                pathOrChild("/v1/graph/namespaces", createMethodRouter(
                    "GET", guard.protect(RequestGuard.Access.READ,
                        exactPath("/v1/graph/namespaces",
                            new GraphNamespacesEndpoint(in.graphTenants()), notFound)),
                    // A mutation, and the most destructive one this node offers: it removes a
                    // tenant's graph, its staged facts and the log of what it still owed.
                    "DELETE", audited("graph.namespace.delete",
                        guard.protect(RequestGuard.Access.MUTATE,
                            new GraphNamespaceDeleteEndpoint(in.graphTenants()))),
                    guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                        "Method Not Allowed - Use GET /v1/graph/namespaces to list them or "
                            + "DELETE /v1/graph/namespaces/{namespace} to drop one"))),
                notFound)));
        } else if (in.graphPipeline() != null) {
            target.createContext("/v1/graph/query", wrapHandler(exactPath("/v1/graph/query",
                onlyMethod("POST", guard.protect(RequestGuard.Access.READ,
                    new GraphQueryEndpoint(in.graphPipeline(),
                        in.queryRegistry(), graphQueryTimeoutMs(), in.security().maxQueryTimeoutMs()))), notFound)));
        }
    }
}
