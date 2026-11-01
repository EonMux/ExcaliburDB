package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.api.bootstrap.CoreComponents;
import com.eonmux.excalibur.api.bootstrap.DocumentRegistryFactory;
import com.eonmux.excalibur.api.bootstrap.GraphRagFactory;
import com.eonmux.excalibur.api.bootstrap.GraphRagHolder;
import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.IngestionWiring;
import com.eonmux.excalibur.api.bootstrap.NodeShutdown;
import com.eonmux.excalibur.api.bootstrap.QueryPipelineFactory;
import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.api.retrieval.HybridFusion;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.api.server.ShutdownBudget;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.ConfigValidation;
import com.eonmux.excalibur.config.QueryConfig;
import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.planner.ConversationContext;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagResult;
import com.eonmux.excalibur.retrieval.pipeline.TraversalDirection;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A node, composed in-process the way {@link ExcaliburApplication} composes one, that the
 * model-backed strategies run against.
 *
 * <p>What those strategies measure is the product's own answer: the corpus embedded and its facts
 * extracted by the ingestion pipeline, the graph built by the build route, hybrid questions
 * answered by {@code /v1/query} - its concurrency, its fusion, its expansion policy. A harness that
 * reassembled those from parts would measure its own reassembly, and would silently stop matching
 * the node the day either changed. So the node here is the real composition, spoken to over its
 * real HTTP routes on loopback.
 *
 * <p>The graph is the one exception, and for a reason the route imposes: {@code /v1/graph/query}
 * answers with context text, not with the facts' provenance, and provenance is the only way to
 * say which document an answer rests on. So graph questions go to the pipeline that route answers
 * from, borrowed exactly as the route borrows it.
 */
final class EvaluationNode implements AutoCloseable {

    /**
     * What a query or an expansion is allowed while evaluating.
     *
     * <p>Generous on purpose: a served query is bounded because a client is waiting, and an
     * evaluation that inherited that bound would record a slow endpoint as a weak strategy.
     */
    static final long QUERY_DEADLINE_MS = CorpusEvaluationRunner.EXPANSION_DEADLINE_MS;

    /** Requests per second the node allows its one client: well above what a run sends. */
    private static final String UNTHROTTLED = "100000";

    private static final Duration POLL = Duration.ofMillis(100);

    /**
     * How long a start that failed before its server existed waits for the background work it
     * already started: the segment workers, then the storage engine's splits and lifecycle
     * checks, one wait for both. A node that has only just come up has nothing long in flight,
     * so this is ample; work still running after it keeps storage open rather than having it
     * closed underneath.
     */
    private static final Duration FAILED_START_STOP_WAIT = Duration.ofSeconds(30);
    private static final long PROGRESS_EVERY_NANOS = Duration.ofSeconds(30).toNanos();

    /**
     * How a node answered one question on {@code /v1/query}.
     *
     * @param ids       The results' identifiers, best first
     * @param retrieval The route the node reports answering by
     * @param vector    Whether any result was returned by the vector signal
     */
    record Answer(List<String> ids, String retrieval, boolean vector) {
        Answer {
            ids = List.copyOf(ids);
        }
    }

    /** How ingesting the corpus went. */
    record Ingestion(long chunks, double seconds, String error) {
        boolean failed() {
            return error != null;
        }
    }

    /** How building the graph went. */
    record Build(String state, boolean published, long edges, long entities, String problem,
                 double seconds) {
        boolean servable() {
            return "completed".equals(state) && published;
        }
    }

    private final Config config;
    private final CoreComponents core;
    private final DocumentVisibilityTracker tracker;
    private final GraphTenants tenants;
    private final IngestionWiring.Attachment attachment;
    private final ExcaliburDBServer server;
    private final HttpClient http;

    private EvaluationNode(Config config, CoreComponents core,
                           DocumentVisibilityTracker tracker, GraphTenants tenants,
                           IngestionWiring.Attachment attachment, ExcaliburDBServer server) {
        this.config = config;
        this.core = core;
        this.tracker = tracker;
        this.tenants = tenants;
        this.attachment = attachment;
        this.server = server;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * The configuration an evaluation node runs with.
     *
     * <p>Everything a run chose is set from its arguments; everything else is left at the node's
     * default, because the defaults are what is being evaluated. The exceptions are the limits
     * that exist to protect a node from its clients - rate, depth and deadline - which would
     * otherwise throttle or truncate the only client it has, and the query cache, which would
     * answer a repeated question from memory and report that as its latency.
     *
     * @param arguments    The run's arguments
     * @param engine       The lexical engine this node indexes with
     * @param corpus       The corpus directory, as its real path
     * @param expandHybrid Whether hybrid queries widen their BM25 half with a chat model
     * @throws IllegalStateException if a node would refuse the configuration
     */
    static Config configFor(EvaluationArguments arguments, LexicalEngine engine, Path corpus,
                            boolean expandHybrid) {
        // The deepest question a run asks: a fused strategy reads its base ranking at the depth
        // a node's own hybrid reads each signal at.
        int depth = arguments.k() * QueryConfig.DEFAULT_PER_SIGNAL_OVER_FETCH;
        Map<String, String> properties = new HashMap<>();
        properties.put("excalibur.ingest.lexical-engine", engine.id());
        properties.put(ApiSecurityConfig.KEY_ALLOWED_ROOTS, corpus.toString());
        properties.put(ApiSecurityConfig.KEY_READ_RATE, UNTHROTTLED);
        properties.put(ApiSecurityConfig.KEY_READ_BURST, UNTHROTTLED);
        properties.put(ApiSecurityConfig.KEY_MAX_TOP_K, String.valueOf(Math.max(1000, depth)));
        properties.put(ApiSecurityConfig.KEY_MAX_QUERY_TIMEOUT_MS,
            String.valueOf(QUERY_DEADLINE_MS));
        properties.put("excalibur.query.cache.enabled", "false");

        // Each role is switched on by naming its model, not by the key: a local endpoint needs
        // none, and the node calls a keyless server whenever one is configured.
        properties.put("excalibur.llm.api-key", arguments.apiKey());
        if (arguments.baseUrl() != null) {
            properties.put("excalibur.llm.base-url", arguments.baseUrl());
        }
        if (arguments.hasChatModel()) {
            properties.put("excalibur.llm.chat-model", arguments.chatModel());
        }
        properties.put("excalibur.llm.chat-enabled", String.valueOf(arguments.hasChatModel()));
        properties.put("excalibur.llm.embeddings-enabled",
            String.valueOf(arguments.hasEmbeddingModel()));
        if (arguments.hasEmbeddingModel()) {
            properties.put("excalibur.llm.embedding-model", arguments.embeddingModel());
        }
        // Both widths, because a node refuses to start when they differ: one is what the model
        // returns, the other what the vector index is laid out for.
        String dimensions = String.valueOf(arguments.embeddingDimensions());
        properties.put("excalibur.llm.embeddings-dimensions", dimensions);
        properties.put("excalibur.query.vector-dimensions", dimensions);

        properties.put("excalibur.graph.extraction-enabled",
            String.valueOf(arguments.hasChatModel()));
        properties.put("excalibur.graph.entity-vectors-enabled",
            String.valueOf(arguments.hasEmbeddingModel()));

        properties.put("excalibur.query.expand-hybrid-queries", String.valueOf(expandHybrid));
        // The longest a node accepts, for the reason the query deadline is raised.
        properties.put("excalibur.query.expansion-timeout-ms",
            String.valueOf(QueryConfig.MAX_EXPANSION_TIMEOUT_MS));
        Config config = new Config(properties);
        ConfigValidation.validate(config);
        return config;
    }

    /**
     * Composes and starts a node over a data directory.
     *
     * @throws IOException if the node's stores cannot be opened
     */
    static EvaluationNode start(Config config, Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        CoreComponents core = CoreComponents.create(config, dataDir,
            HnswConfigFactory.fromShardConfig(config.shard()));
        DocumentVisibilityTracker tracker = null;
        GraphTenants tenants = null;
        IngestionWiring.Attachment attachment = null;
        ExcaliburDBServer server = null;
        try {
            tracker = new DocumentVisibilityTracker(DocumentRegistryFactory.open(dataDir),
                core.indexOrchestrator().getVisibilityTracker());
            QueryPipeline queryPipeline = QueryPipelineFactory.create(config,
                core.storageEngine(), core.indexOrchestrator(),
                new DocumentVisibilityTrackerAdapter(tracker));
            tenants = new GraphTenants(GraphRagFactory.tenantRootFor(config, dataDir), config,
                MappingBudget.detect(), config.graph().getMaxOpenNamespaces());
            server = new ExcaliburDBServer(0, ExcaliburDBServer.DEFAULT_BACKLOG,
                core.indexOrchestrator(), core.storageEngine(), core.volumeManager(), config,
                queryPipeline);
            server.withSecurity(ApiSecurityConfig.fromConfig(config));
            server.withSourceCatalog(SourceCatalog.open(dataDir));
            attachment = new IngestionWiring(config, dataDir, core.storageEngine(),
                core.indexOrchestrator(), tracker, tenants)
                .attachTo(server);
            server.withDocumentRegistry(tracker.getRegistry());
            server.withGraphSupport(tenants);
            server.start();
            return new EvaluationNode(config, core, tracker, tenants, attachment, server);
        } catch (IOException | RuntimeException e) {
            if (server != null && tracker != null) {
                shutdown(config, server, core, attachment, tracker, tenants);
            } else {
                if (tenants != null) {
                    tenants.close();
                }
                releaseAfterFailedStart(core);
            }
            throw e;
        }
    }

    /**
     * Takes down what a start that failed before its server existed brought up.
     *
     * <p>Storage and its arena are closed only once the work writing into them has ended: a
     * worker still writing into a mapping whose arena has been closed ends the process rather
     * than failing it, and the failure the caller is about to see would be lost with it.</p>
     *
     * @param core What the start had brought up
     */
    private static void releaseAfterFailedStart(CoreComponents core) {
        long deadline = System.nanoTime() + FAILED_START_STOP_WAIT.toNanos();
        boolean workersEnded = core.indexOrchestrator().shutdown(FAILED_START_STOP_WAIT);
        if (workersEnded) {
            // Everything it would wait for has ended, so this only releases the executors.
            core.indexOrchestrator().close(Duration.ZERO);
        }
        boolean storageWorkEnded = core.storageEngine().stopBackgroundWork(
            Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        if (!workersEnded || !storageWorkEnded) {
            System.err.println("Background work was still running " + FAILED_START_STOP_WAIT
                .toSeconds() + "s after a failed start; storage is left open under it");
            return;
        }
        core.storageEngine().close();
        if (core.arena() != null) {
            core.arena().close();
        }
    }

    Config config() {
        return config;
    }

    /**
     * Ingests a directory through {@code /v1/ingest}, and waits until what it wrote is searchable.
     *
     * @param sourceId The source id to register it under
     * @param root     The directory, as its real path
     */
    Ingestion ingest(String sourceId, Path root) throws IOException, InterruptedException {
        long start = System.nanoTime();
        Map<String, Object> source = Json.object();
        source.put("source_id", sourceId);
        source.put("root_path", root.toString());
        Map<String, Object> request = Json.object();
        request.put("source_type", "filesystem");
        request.put("config", source);
        Reply registered = send("POST", "/v1/ingest", Json.write(request));
        if (registered.status() / 100 != 2) {
            return new Ingestion(0, seconds(start), "the node refused the ingest: HTTP "
                + registered.status() + " " + registered.body());
        }

        String path = "/v1/ingest/status?source_id=" + sourceId;
        long reported = System.nanoTime();
        Object status = send("GET", path, null).json();
        while (!ingestFinished(status)) {
            if (System.nanoTime() - reported > PROGRESS_EVERY_NANOS) {
                System.out.printf("    ingesting: %s, %.0f%%, %d chunks%n",
                    Json.stringAt(status, "state"), 100 * number(status, "progress"),
                    (long) number(status, "entries_ingested"));
                reported = System.nanoTime();
            }
            Thread.sleep(POLL.toMillis());
            status = send("GET", path, null).json();
        }

        // The run reports idle once its last document is published, and a publish becomes
        // visible to queries at the next epoch. Waiting one tick past the one read here is what
        // makes the first question see the whole corpus rather than most of it.
        long published = tracker.currentEpoch();
        while (tracker.currentEpoch() <= published) {
            Thread.sleep(POLL.toMillis());
        }
        String error = Json.stringAt(status, "last_error");
        return new Ingestion((long) number(status, "entries_ingested"), seconds(start),
            error == null || error.isBlank() ? null : error);
    }

    /**
     * Whether an ingest run has ended.
     *
     * <p>A source reads {@code idle} from the moment it is registered, before its run begins, so
     * idle alone is not the end. A run that finished reports full progress, one that failed an
     * error, and one this node cannot run at all {@code unconfigured}.
     */
    private static boolean ingestFinished(Object status) {
        String state = Json.stringAt(status, "state");
        String error = Json.stringAt(status, "last_error");
        return "unconfigured".equals(state)
            || ("idle".equals(state) && (number(status, "progress") >= 1.0
                || (error != null && !error.isBlank())));
    }

    /** Builds the graph through {@code /v1/graph/build}, and waits for the build to end. */
    Build buildGraph() throws IOException, InterruptedException {
        long start = System.nanoTime();
        Reply started = send("POST", "/v1/graph/build", null);
        if (started.status() / 100 != 2) {
            return new Build("refused", false, 0, 0,
                "HTTP " + started.status() + " " + started.body(), seconds(start));
        }
        long reported = System.nanoTime();
        Object status = send("GET", "/v1/graph/build/status", null).json();
        while (!buildFinished(Json.stringAt(status, "state"))) {
            if (System.nanoTime() - reported > PROGRESS_EVERY_NANOS) {
                System.out.printf("    building the graph: %s%n",
                    Json.stringAt(status, "state"));
                reported = System.nanoTime();
            }
            Thread.sleep(POLL.toMillis());
            status = send("GET", "/v1/graph/build/status", null).json();
        }
        String problem = Json.stringAt(status, "error");
        if (problem == null) {
            problem = Json.stringAt(status, "message");
        }
        return new Build(Json.stringAt(status, "state"),
            Boolean.TRUE.equals(Json.at(status, "published")),
            (long) number(status, "edges"), (long) number(status, "entities"), problem,
            seconds(start));
    }

    private static boolean buildFinished(String state) {
        return "completed".equals(state) || "cancelled".equals(state) || "failed".equals(state);
    }

    /**
     * Asks {@code /v1/query}, with relevancy scoring off.
     *
     * <p>Scoring is off because it is not retrieval: it reorders a candidate set with a chat
     * model after retrieval has chosen it, and would make the hybrid's row a measurement of the
     * scorer's judgement - and of a chat model the run may not have named.
     *
     * @throws IllegalStateException if the node did not answer 200
     */
    Answer query(String text, int topK) {
        Map<String, Object> request = Json.object();
        request.put("query", text);
        request.put("top_k", (long) topK);
        request.put("enable_llm_scoring", false);
        request.put("timeout_ms", QUERY_DEADLINE_MS);
        Reply reply;
        try {
            reply = send("POST", "/v1/query", Json.write(request));
        } catch (IOException e) {
            throw new IllegalStateException("the evaluation node did not answer: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while querying the evaluation node", e);
        }
        if (reply.status() != 200) {
            throw new IllegalStateException("/v1/query answered HTTP " + reply.status() + ": "
                + reply.body());
        }
        List<String> ids = new ArrayList<>();
        boolean vector = false;
        if (Json.at(reply.json(), "results") instanceof List<?> results) {
            for (Object result : results) {
                String id = Json.stringAt(result, "document_id");
                if (id != null) {
                    ids.add(id);
                }
                vector |= Json.at(result, "signals") instanceof List<?> signals
                    && signals.contains(HybridFusion.DENSE_SIGNAL);
            }
        }
        return new Answer(ids, Json.stringAt(reply.json(), "retrieval"), vector);
    }

    /**
     * Asks the graph a question, the way {@code /v1/graph/query} asks it with no conversation,
     * no {@code as_of} and no direction: through the shared namespace's served pipeline.
     *
     * @return The answer, or empty when there is no graph to answer from
     */
    Optional<GraphRagResult> askGraph(String question) {
        Optional<NamespaceRegistry.Lease<GraphTenant>> borrowed =
            tenants.borrowIfPresent(GraphTenants.namespaceOf(null));
        if (borrowed.isEmpty()) {
            return Optional.empty();
        }
        try (NamespaceRegistry.Lease<GraphTenant> lease = borrowed.get()) {
            Optional<GraphRagHolder.Reading> reading = lease.resource().holder().acquire();
            if (reading.isEmpty()) {
                return Optional.empty();
            }
            try (GraphRagHolder.Reading held = reading.get()) {
                return Optional.of(held.pipeline().answer(question, new ConversationContext(),
                    null, TraversalDirection.BOTH, new QueryTrace()));
            }
        }
    }

    @Override
    public void close() {
        http.close();
        shutdown(config, server, core, attachment, tracker, tenants);
    }

    private static void shutdown(Config config, ExcaliburDBServer server, CoreComponents core,
                                 IngestionWiring.Attachment attachment,
                                 DocumentVisibilityTracker tracker, GraphTenants tenants) {
        new NodeShutdown(server, core.indexOrchestrator(),
            attachment == null ? null : attachment.lexicalErasureSweep(),
            attachment == null ? null : attachment.chunkIndex(),
            tracker, tenants, attachment == null ? null : attachment.documentDecoder(),
            core.storageEngine(), core.arena(),
            ShutdownBudget.configuredTimeout(config.getEffectiveProperties())).run();
    }

    private record Reply(int status, Object json, String body) {
    }

    private Reply send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getPort() + path))
            .timeout(Duration.ofMillis(QUERY_DEADLINE_MS + 30_000L));
        if (body != null) {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response =
            http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Object json = response.body().isBlank() ? null : Json.parse(response.body());
        return new Reply(response.statusCode(), json, response.body());
    }

    private static double number(Object json, String field) {
        return Json.at(json, field) instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double seconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }
}
