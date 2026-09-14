package excalibur.api.server;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import excalibur.api.bootstrap.GraphTenants;
import excalibur.api.bootstrap.SourceRestoration;
import excalibur.api.endpoint.CompactionEndpoint;
import excalibur.api.endpoint.CompactionRunner;
import excalibur.api.endpoint.CompactionStatusEndpoint;
import excalibur.api.endpoint.DocumentErasureEndpoint;
import excalibur.api.endpoint.DocumentFetchEndpoint;
import excalibur.api.endpoint.DocumentUpsertEndpoint;
import excalibur.api.endpoint.GraphBuildEndpoint;
import excalibur.api.endpoint.GraphErasureEndpoint;
import excalibur.api.endpoint.GraphQueryEndpoint;
import excalibur.api.endpoint.HealthEndpoint;
import excalibur.api.endpoint.InMemorySourceRegistry;
import excalibur.api.endpoint.IngestEndpoint;
import excalibur.api.endpoint.IngestStatusEndpoint;
import excalibur.api.endpoint.InlineSourceIds;
import excalibur.api.endpoint.QueryCancelEndpoint;
import excalibur.api.endpoint.QueryEndpoint;
import excalibur.api.endpoint.ReindexEndpoint;
import excalibur.api.endpoint.ReindexStatusEndpoint;
import excalibur.api.endpoint.SourceDeregisterEndpoint;
import excalibur.api.endpoint.SourceFactory;
import excalibur.api.endpoint.SourceRegistry;
import excalibur.api.endpoint.StatsEndpoint;
import excalibur.api.reindex.ReindexCoordinator;
import excalibur.api.retrieval.ChunkTextResolver;
import excalibur.api.retrieval.LexicalQueryService;
import excalibur.api.security.ApiSecurityConfig;
import excalibur.api.security.IngestTargetValidator;
import excalibur.api.security.RequestGuard;
import excalibur.ingest.erasure.DocumentErasureService;
import excalibur.ingest.lexical.ChunkIndex;
import excalibur.ingest.lexical.StoredChunkText;
import excalibur.ingest.pipeline.DocumentReindexer;
import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.registry.SourceCatalog;
import excalibur.ingest.source.SourceResolver;
import excalibur.llm.embedding.EmbeddingClient;
import excalibur.query.executor.QueryRegistry;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.allocator.VolumeManager;
import excalibur.shard.worker.SegmentReindexScheduler;
import io.excalibur.config.Config;
import io.excalibur.retrieval.pipeline.GraphRagPipeline;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Function;

import static excalibur.api.server.HttpRouting.createMethodRouter;
import static excalibur.api.server.HttpRouting.exactPath;
import static excalibur.api.server.HttpRouting.methodNotAllowed;
import static excalibur.api.server.HttpRouting.pathOrChild;
import static excalibur.api.server.HttpRouting.unavailable;
import static excalibur.api.server.HttpRouting.wrapHandler;

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
 * <p>{@code /health} is the one unauthenticated route, because a liveness probe cannot carry a
 * token, and it is still rate limited. Every context below is pinned to its own path: HttpServer
 * matches on the longest prefix, so without that a path that merely begins with a route's name
 * is served by that route - {@code /healthz} reads back as healthy and {@code /v1/statsfoo}
 * returns the statistics.</p>
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
     * @param chunkIndex           The chunk lexical index stats report on, or null
     * @param ingestSourceRegistry Where ingestion sources are registered
     * @param sourceCatalog        Where registrations are recorded across restarts, or null
     * @param ingestionStatus      What {@code GET /v1/ingest/status} serves
     * @param ingestionOrchestrator Background ingestion, or null in stub mode
     * @param reindexStatus        What {@code GET /v1/reindex/status} serves
     * @param documentReindexer    Executes a reindex, or null in stub mode
     * @param sourceResolver       Resolves a source id for the reindexer
     * @param reindexCoordinator   The claim shared with the scheduled reindex handler
     * @param documentErasure      The service behind {@code POST /v1/erasure}, or null
     * @param graphTenants         Every namespace's graph, or null
     * @param graphPipeline        A graph pipeline somebody else owns, or null
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
                  GraphRagPipeline graphPipeline) {
    }

    /**
     * The endpoints that own executors, so {@code stop()} can drain them.
     *
     * @param query      Serves {@code POST /v1/query}
     * @param reindex    Serves {@code POST} and {@code DELETE /v1/reindex}
     * @param compaction Runs what {@code POST /v1/compact} asked for, behind the response
     */
    record Endpoints(QueryEndpoint query, ReindexEndpoint reindex, CompactionRunner compaction) {
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
        StatsEndpoint statsEndpoint = new StatsEndpoint(
            in.indexOrchestrator(), in.storageEngine(), in.volumeManager());
        statsEndpoint.withLexicalIndex(in.chunkIndex());

        // Where a result's text is read back from when a request asks for it. The indexes hold
        // postings and vectors and no text, so without this a search answers with identifiers a
        // caller can only resolve against the store the corpus was ingested from - which, for a
        // crawled corpus, it does not have. One reader for every route that quotes the corpus,
        // so a query and a fetch of the same chunk cannot disagree about what it says.
        Function<String, String> chunkText = StoredChunkText.of(in.storageEngine());
        QueryEndpoint queryHandler = newQueryEndpoint(chunkText);

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
        registerQuery(target, notFound, queryHandler);
        registerIngest(target, notFound, ingestEndpoint);
        registerDocuments(target, notFound, chunkText, suppliedSources);
        registerReindex(target, notFound, reindexHandler);
        registerErasure(target, notFound, suppliedSources);
        CompactionRunner compactionRunner = new CompactionRunner(
            in.indexOrchestrator()::compactNow);
        registerCompaction(target, notFound, compactionRunner);
        registerGraph(target, notFound);
        target.createContext("/", wrapHandler(notFound));

        return new Endpoints(queryHandler, reindexHandler, compactionRunner);
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
     * @param chunkText Reads a chunk's text back out of the store
     * @return The endpoint behind {@code POST /v1/query}
     */
    private QueryEndpoint newQueryEndpoint(Function<String, String> chunkText) {
        QueryEndpoint queryHandler = new QueryEndpoint(in.queryPipeline(), in.config(), null,
            in.queryRegistry(), in.embeddingClient(), in.security());
        // Lexical retrieval, when this node keeps a chunk index. On a node with no embedding
        // client it is what /v1/query answers from instead of refusing the query; on a node
        // that has one it is the second signal, fused with vector search by rank. Both name
        // the same thing - the chunk id ingestion assigned - which is what makes them fusible
        // rather than two id spaces to reconcile.
        queryHandler.setLexicalQueryService(in.lexicalQueryService());
        queryHandler.setChunkText(chunkText);
        return queryHandler;
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
            suppliedSources
        );
        LOGGER.info("IngestEndpoint configured with status registry (orchestrator: {})",
            in.ingestionOrchestrator() != null ? "enabled" : "disabled (stub mode)");
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
        LOGGER.info("IngestSourceRegistry wired to ReindexScheduler with interval={}ms", reindexIntervalMs);

        // The catalog is attached before its records are read back, so a source restored here
        // stays recorded rather than being re-written on the way in.
        if (in.sourceCatalog() != null) {
            inMemRegistry.setSourceCatalog(in.sourceCatalog());
            SourceRestoration.restore(
                in.sourceCatalog(),
                new SourceFactory(new IngestTargetValidator(in.security())),
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
            in.reindexCoordinator()           // Shared with the scheduled reindex handler
        );
        boolean hasRollingReplacement =
            in.volumeManager() != null && in.storageEngine().getCatalog() != null;
        LOGGER.info("ReindexEndpoint configured with DocumentReindexer for actual reindex operations, rolling replacement: {}",
            hasRollingReplacement ? "enabled" : "disabled");
        return reindexHandler;
    }

    // ==================== Route registration ====================

    private void registerCore(HttpServer target, HttpHandler notFound,
                              HealthEndpoint healthEndpoint, StatsEndpoint statsEndpoint) {
        target.createContext("/health",
            wrapHandler(exactPath("/health",
                guard.protect(RequestGuard.Access.PUBLIC, healthEndpoint), notFound)));
        target.createContext("/v1/stats",
            wrapHandler(exactPath("/v1/stats",
                guard.protect(RequestGuard.Access.READ, statsEndpoint), notFound)));
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
                guard.protect(RequestGuard.Access.READ, queryHandler), notFound),
            "DELETE", guard.protect(RequestGuard.Access.MUTATE, queryCancelEndpoint),
            exactPath("/v1/query", guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                "Method Not Allowed - Use POST for queries or DELETE for cancellation")),
                notFound)), notFound)));
    }

    /**
     * Registers {@code /v1/ingest}: POST registers a source, DELETE stops crawling one.
     *
     * <p>Both mutate, and both are charged against the mutate budget; the same shape
     * {@code /v1/query} uses for query and cancel. {@code /v1/ingest/status} is the readback.</p>
     */
    private void registerIngest(HttpServer target, HttpHandler notFound,
                                IngestEndpoint ingestEndpoint) {
        target.createContext("/v1/ingest", wrapHandler(exactPath("/v1/ingest", createMethodRouter(
            "POST", guard.protect(RequestGuard.Access.MUTATE, ingestEndpoint),
            "DELETE", guard.protect(RequestGuard.Access.MUTATE,
                new SourceDeregisterEndpoint(in.ingestSourceRegistry())),
            guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                "Method Not Allowed - Use POST to register a source "
                    + "or DELETE to stop crawling one"))), notFound)));
        target.createContext("/v1/ingest/status",
            wrapHandler(exactPath("/v1/ingest/status", guard.protect(RequestGuard.Access.READ,
                new IngestStatusEndpoint(in.ingestionStatus())), notFound)));
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
        target.createContext("/v1/documents/fetch",
            wrapHandler(exactPath("/v1/documents/fetch",
                guard.protect(RequestGuard.Access.READ, documentFetch), notFound)));
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
        HttpHandler read = guard.protect(RequestGuard.Access.READ, documentFetch);
        if (in.ingestionOrchestrator() == null) {
            // MUTATE, not PUBLIC, even though there is nothing behind it. The guard checks
            // the rate limit and then authentication, so a PUBLIC 503 here answered an
            // anonymous caller while a node that does have a pipeline answers 401 - which
            // tells a prober which deployment it is looking at, and describes it.
            return createMethodRouter("GET", read,
                "POST", guard.protect(RequestGuard.Access.MUTATE, unavailable(
                    "Supplying documents directly requires an ingestion pipeline, "
                        + "which this node was started without")),
                wrongMethod);
        }
        return createMethodRouter("GET", read,
            "POST", guard.protect(RequestGuard.Access.MUTATE,
                new DocumentUpsertEndpoint(in.ingestionOrchestrator(), in.ingestSourceRegistry(),
                    suppliedSources, MAX_SUPPLIED_DOCUMENTS)),
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
                "POST", guard.protect(RequestGuard.Access.MUTATE, reindexHandler),
                "DELETE", guard.protect(RequestGuard.Access.MUTATE, reindexHandler),
                guard.protect(RequestGuard.Access.PUBLIC, methodNotAllowed(
                    "Method Not Allowed - Use POST to start a reindex or DELETE to stop one"))),
            notFound)));
        // The readback for /v1/reindex, which answers 202 and works in the background. Without
        // it the run's verdict reached the node's log and nothing else. A longer path than
        // /v1/reindex, so the server's longest-prefix matching routes it here rather than to
        // the reindex handler, which answers 405 to a GET.
        target.createContext("/v1/reindex/status",
            wrapHandler(exactPath("/v1/reindex/status", guard.protect(RequestGuard.Access.READ,
                new ReindexStatusEndpoint(in.reindexStatus())), notFound)));
    }

    /** A mutation, and the most consequential one: it removes content rather than adding it. */
    private void registerErasure(HttpServer target, HttpHandler notFound,
                                 InlineSourceIds suppliedSources) {
        if (in.documentErasure() == null) {
            return;
        }
        target.createContext("/v1/erasure", wrapHandler(exactPath("/v1/erasure",
            guard.protect(RequestGuard.Access.MUTATE,
                new DocumentErasureEndpoint(in.documentErasure(), in.ingestSourceRegistry(),
                    suppliedSources)), notFound)));
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
            guard.protect(RequestGuard.Access.MUTATE, new CompactionEndpoint(runner)),
            notFound)));
        // A longer path than /v1/compact, so the server's longest-prefix matching routes it
        // here rather than to the handler above, which answers 405 to a GET.
        target.createContext("/v1/compact/status",
            wrapHandler(exactPath("/v1/compact/status", guard.protect(RequestGuard.Access.READ,
                new CompactionStatusEndpoint(runner)), notFound)));
    }

    /**
     * Registers the graph routes, only when a graph store is configured.
     *
     * <p>The query is a read, not a mutation: it walks the graph and returns context, and
     * charging it against the smaller mutate budget would throttle ordinary questions.</p>
     */
    private void registerGraph(HttpServer target, HttpHandler notFound) {
        if (in.graphTenants() != null) {
            target.createContext("/v1/graph/query", wrapHandler(exactPath("/v1/graph/query",
                guard.protect(RequestGuard.Access.READ,
                    new GraphQueryEndpoint(in.graphTenants())), notFound)));
            // Both mutations: they rewrite the served graph.
            target.createContext("/v1/graph/build", wrapHandler(exactPath("/v1/graph/build",
                guard.protect(RequestGuard.Access.MUTATE,
                    new GraphBuildEndpoint(in.graphTenants())), notFound)));
            target.createContext("/v1/graph/erasure", wrapHandler(exactPath("/v1/graph/erasure",
                guard.protect(RequestGuard.Access.MUTATE,
                    new GraphErasureEndpoint(in.graphTenants())), notFound)));
        } else if (in.graphPipeline() != null) {
            target.createContext("/v1/graph/query", wrapHandler(exactPath("/v1/graph/query",
                guard.protect(RequestGuard.Access.READ,
                    new GraphQueryEndpoint(in.graphPipeline())), notFound)));
        }
    }
}
