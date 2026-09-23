package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP endpoint for the /v1/stats API.
 *
 * <p>StatsEndpoint handles GET requests to /v1/stats for system statistics.
 * Provides observability into system state for monitoring and debugging.</p>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "segments": {
 *     "total": 1024,
 *     "active": 980,
 *     "indexing": 40,
 *     "warming": 2,
 *     "compacting": 2,
 *     "tombstoned": 0,
 *     "purged": 0
 *   },
 *   "entries": { "total": 15000000 },
 *   "volumes": {
 *     "count": 3,
 *     "totalBytes": 5368709120,
 *     "usedBytes": 3221225472,
 *     "availableBytes": 2147483648
 *   },
 *   "hnsw": {
 *     "queueDepth": 50000,
 *     "queueDepthLimit": 100000,
 *     "vectorsIntegrated": 14000000
 *   },
 *   "visibility": {
 *     "epoch": 42,
 *     "registeredSegments": 1024,
 *     "visibleSegments": 1024
 *   },
 *   "coverage": 1.0000,
 *   "workers": { "indexOrchestratorRunning": true },
 *   "vectorIndex": { "segmentsWithoutIndex": 0 },
 *   "breakers": {
 *     "embedding": "CLOSED",
 *     "llmRelevancy": "CLOSED",
 *     "queryExpansion": "CLOSED",
 *     "graphExtraction": "OPEN",
 *     "graphRerank": "CLOSED",
 *     "graphQueryEmbedding": "CLOSED",
 *     "graphBuildEmbedding": "CLOSED"
 *   },
 *   "lexical": {
 *     "engine": "native",
 *     "indexed": 15000000,
 *     "segments": 12,
 *     "liveChunks": 14980000,
 *     "erasedChunks": 20000,
 *     "erasedRatio": 0.0013,
 *     "erasureRequests": 3,
 *     "oldestErasureAgeMs": 412000,
 *     "compactionDue": false,
 *     "unreadableChunksDropped": 0,
 *     "unsearchableChunks": 0,
 *     "unreadableSegments": 0,
 *     "outdatedSegments": 0
 *   },
 *   "queryCache": {
 *     "hits": 1204,
 *     "misses": 8431,
 *     "evictions": 0,
 *     "invalidations": 7900,
 *     "size": 512,
 *     "capacity": 1024,
 *     "hitRatio": 0.1249
 *   },
 *   "graph": {
 *     "namespaces": { "open": 3, "maxOpen": 64, "evictions": 12 },
 *     "budget": {
 *       "graphMappings": 81, "processMappings": 20412,
 *       "mappingHighWater": 45871, "mappingLimit": 65530, "limitRead": true,
 *       "graphDescriptors": 6, "processDescriptors": 311,
 *       "descriptorHighWater": 716, "descriptorLimit": 1024
 *     },
 *     "builds": { "running": false, "completed": 4, "failed": 0, "cancelled": 1 },
 *     "erasure": {
 *       "namespacesOwing": 1, "outstanding": 3, "overdueNamespaces": 0,
 *       "unreadableNamespaces": 0, "oldestAgeMs": 412000
 *     },
 *     "ingest": {
 *       "documentsSeen": 1200, "factsStaged": 5400, "unresolvedDocuments": 0,
 *       "extractionFailures": 2, "tenantFailures": 0, "sinkRefusals": 0,
 *       "withdrawalFailures": 0
 *     }
 *   }
 * }
 * }</pre>
 *
 * <h2>The graph, when this node builds one</h2>
 * <p>Absent on a node that serves no graph of its own. Every figure is a total across
 * namespaces, never a row per namespace. {@code budget} carries both the graphs' share of the
 * mapping and descriptor limits and the whole process's, because the limits are the process's
 * and eviction can only give back the graphs' share. {@code erasure} is absent when the graph
 * root could not be listed, and {@code ingest} on a node whose ingestion stages no facts.
 * {@code ingest.sinkRefusals} counts documents the sink threw on instead of counting them under
 * one of its own reasons, which the sink is required never to do: a published document it threw
 * on is not offered to the graph again unless it changes, and a removed one it threw on keeps
 * its staged facts with nothing owing their withdrawal. {@code ingest.withdrawalFailures}, by
 * contrast, counts withdrawals that failed and are owed, and retried until they are made.</p>
 *
 * <h2>The query cache, when there is one</h2>
 * <p>Absent unless {@code excalibur.query.cache.enabled} is on, and absent is a different
 * statement from a cache that has never been hit. {@code hitRatio} says whether it is
 * earning its place; {@code invalidations} says why it is not, when it is not. Entries
 * discarded because the corpus moved are not a capacity problem and raising the capacity
 * will not help - that figure is the node ingesting faster than its queries repeat.</p>
 *
 * <h2>Chunks the lexical index gave up on</h2>
 * <p>{@code lexical.unreadableChunksDropped} counts chunks a compaction of the native index
 * dropped because their stored text failed its checksum or could not be decoded - damage that
 * no retry repairs. Dropping them is what lets the rest of their group be rewritten and any
 * erasure owed there be met; the cost is that each one stops being found by keyword search
 * while still being served by vector search. Anything above zero means storage holds damaged
 * segments: the chunks' documents want reindexing from their source, and the disk under them
 * wants looking at. A read that failed for any other reason is not counted - its group is
 * abandoned whole and retried by the next compaction.</p>
 *
 * <h2>Lexical segments that could not be opened</h2>
 * <p>A native-index segment that fails its checksums is kept out of every query rather than
 * answered from, so its chunks are counted in {@code lexical.unsearchableChunks} and not in
 * {@code liveChunks}: {@code liveChunks} stays what a query can match. {@code unreadableSegments}
 * counts such segments and {@code unreadable} names each, with its chunk count, whether the next
 * compaction can rebuild it from stored text, and what is wrong with it. The node keeps serving
 * through this, as it does through the three partial failures below, so it is reported here and
 * not in readiness. {@code outdatedSegments} counts segments still in an older format, which
 * compaction rewrites a few at a time.</p>
 *
 * <h2>The three blocks that report a partial failure</h2>
 * <p>{@code workers}, {@code vectorIndex} and {@code breakers} describe states this node keeps
 * serving through, and each of them is invisible in every other field. A dead index
 * orchestrator leaves the HNSW queue growing while coverage stays at one; a segment whose
 * vector index would not attach answers no vector search while returning its key-value data
 * intact; an open embedding breaker silently drops retrieval to lexical-only. In all three the
 * node answers 200 to everything it is asked.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>All counts are non-negative</li>
 *   <li>Coverage is in range [0.0, 1.0]</li>
 *   <li>Epoch is non-negative</li>
 *   <li>Volume bytes are non-negative</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All dependencies are accessed via thread-safe methods.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
public class StatsEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatsEndpoint.class);

    /**
     * Most vector-index failures named individually in one response.
     *
     * <p>The count is always exact; only the reasons are capped. A node that lost its graphs
     * wholesale would otherwise produce a reason per segment, and a stats response that grows
     * with the size of the failure is one an operator cannot read during the incident it
     * describes.</p>
     */
    private static final int MAX_REPORTED_VECTOR_FAILURES = 32;

    /**
     * Unreadable lexical segments named individually before the list is cut short.
     *
     * <p>The same bound as the vector failures, for the same reason: past a few dozen, the
     * operator's next step is the log and the disk rather than the list, and the count beside it
     * is always the whole figure.</p>
     */
    private static final int MAX_REPORTED_UNREADABLE_SEGMENTS = 32;

    private final NodeStatsCollector collector;

    /**
     * Creates a new StatsEndpoint with required dependencies.
     *
     * @param indexOrchestrator The index orchestrator for worker metrics
     * @param storageEngine The storage engine for volume information
     * @throws NullPointerException if any parameter is null
     */
    public StatsEndpoint(IndexOrchestrator indexOrchestrator, StorageEngine storageEngine) {
        this(indexOrchestrator, storageEngine, null);
    }

    /**
     * Creates a new StatsEndpoint with required dependencies and optional VolumeManager.
     *
     * <p>If VolumeManager is provided, it will be used for accurate disk space tracking.
     * If null, volume stats will be computed from segment catalog (less accurate).</p>
     *
     * @param indexOrchestrator The index orchestrator for worker metrics
     * @param storageEngine The storage engine for volume information
     * @param volumeManager Optional volume manager for disk space tracking
     * @throws NullPointerException if indexOrchestrator or storageEngine is null
     */
    public StatsEndpoint(IndexOrchestrator indexOrchestrator, StorageEngine storageEngine,
                         VolumeManager volumeManager) {
        this(new NodeStatsCollector(indexOrchestrator, storageEngine, volumeManager));
        LOGGER.debug("StatsEndpoint created with VolumeManager: {}", volumeManager != null);
    }

    /**
     * Creates a new StatsEndpoint with explicit dependencies.
     *
     * <p>This constructor is useful for testing or when dependencies are
     * managed separately.</p>
     *
     * @param visibilityTracker The visibility tracker for epoch information
     * @param segmentCatalog The segment catalog for segment statistics
     * @param indexOrchestrator The index orchestrator for worker metrics
     * @param storageEngine The storage engine for volume information
     * @throws NullPointerException if any parameter is null
     */
    public StatsEndpoint(VisibilityTracker visibilityTracker,
                         SegmentCatalog segmentCatalog,
                         IndexOrchestrator indexOrchestrator,
                         StorageEngine storageEngine) {
        this(visibilityTracker, segmentCatalog, indexOrchestrator, storageEngine, null);
    }

    /**
     * Creates a new StatsEndpoint with explicit dependencies and VolumeManager.
     *
     * <p>This constructor is useful for testing or when dependencies are
     * managed separately.</p>
     *
     * @param visibilityTracker The visibility tracker for epoch information
     * @param segmentCatalog The segment catalog for segment statistics
     * @param indexOrchestrator The index orchestrator for worker metrics
     * @param storageEngine The storage engine for volume information
     * @param volumeManager The volume manager for disk space tracking
     * @throws NullPointerException if any required parameter is null
     */
    public StatsEndpoint(VisibilityTracker visibilityTracker,
                         SegmentCatalog segmentCatalog,
                         IndexOrchestrator indexOrchestrator,
                         StorageEngine storageEngine,
                         VolumeManager volumeManager) {
        this(new NodeStatsCollector(visibilityTracker, segmentCatalog, indexOrchestrator,
            storageEngine, volumeManager));
    }

    /**
     * Creates a new StatsEndpoint over a collector somebody else assembled.
     *
     * <p>How {@code /metrics} and this route are given the same snapshot source: whichever of
     * them is registered, the node is read once, the same way.</p>
     *
     * @param collector Reads the node's state
     * @throws NullPointerException if collector is null
     */
    public StatsEndpoint(NodeStatsCollector collector) {
        this.collector = java.util.Objects.requireNonNull(collector, "collector cannot be null");
    }

    /**
     * Reports the chunk lexical index alongside the rest of the node's state.
     *
     * <p>Its erasure backlog is the one thing here that is a promise with a deadline attached, and
     * nothing else in this response would show it going unmet.</p>
     *
     * @param lexicalIndex The index, or null for a node that keeps none
     */
    public void withLexicalIndex(com.eonmux.excalibur.ingest.lexical.ChunkIndex lexicalIndex) {
        collector.withLexicalIndex(lexicalIndex);
    }

    /**
     * Reports the embedding circuit breaker's state.
     *
     * <p>The breaker is the node's main degradation mode and was readable only from log
     * statements: when it opens, retrieval silently falls back to lexical-only and the node
     * keeps answering 200, so a caller sees worse results and no signal at all.</p>
     *
     * @param breaker Reads the breaker's state, or null on a node with no embedding client
     */
    public void withEmbeddingBreaker(Supplier<String> breaker) {
        collector.withEmbeddingBreaker(breaker);
    }

    /**
     * Reports the LLM relevancy circuit breaker's state.
     *
     * <p>Stage 4's breaker. When it opens the pipeline keeps the vector ranking and the node
     * goes on answering 200, so every ranking it returns changes with nothing in the response
     * saying so.</p>
     *
     * @param breaker Reads the breaker's state, or null on a node with no LLM scorer
     */
    public void withLlmRelevancyBreaker(Supplier<String> breaker) {
        collector.withLlmRelevancyBreaker(breaker);
    }

    /**
     * Reports the query result cache alongside the rest of the node's state.
     *
     * <p>The one component whose failure is invisible in every other figure here. A cache
     * that never hits is pure cost - a corpus fingerprint on every request, and memory held
     * for answers nobody asks for twice - and a cache whose entries are invalidated as fast
     * as they are stored is a node ingesting faster than it is being queried. Both read as
     * ordinary latency from outside, and the hit ratio against the invalidation count is what
     * tells the two apart.</p>
     *
     * @param cache The cache, or null for a node that runs without one
     */
    public void withQueryResultCache(com.eonmux.excalibur.query.cache.QueryResultCache cache) {
        collector.withQueryResultCache(cache);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            ResponseBody.sendJson(exchange, 200, toJson(collector.collect()));
        } catch (Exception e) {
            LOGGER.error("Error computing stats: {}", e.getMessage(), e);
            ResponseBody.sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Converts a snapshot to JSON.
     *
     * <p>Hand-serialized JSON following the project's minimal dependency approach.</p>
     *
     * @param stats What the collector read
     * @return The response body
     */
    private static String toJson(NodeStats stats) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{");
        appendSegments(sb, stats);
        appendStorage(sb, stats);
        appendIndexes(sb, stats);
        appendVisibility(sb, stats);
        appendDegradation(sb, stats);
        appendLexical(sb, stats.lexical());
        appendQueryCache(sb, stats.queryCache());
        appendGraph(sb, stats.graph());
        sb.append("}");
        return sb.toString();
    }

    /**
     * The graph tenants, when this node builds its own graph.
     *
     * @param sb    The body being built
     * @param graph The graph's figures, or null
     */
    private static void appendGraph(StringBuilder sb, GraphStats graph) {
        if (graph == null) {
            return;
        }
        GraphStats.NamespaceStats namespaces = graph.namespaces();
        GraphStats.BudgetStats budget = graph.budget();
        GraphStats.BuildStats builds = graph.builds();
        sb.append(", \"graph\": {")
            .append("\"namespaces\": {")
            .append("\"open\": ").append(namespaces.open()).append(", ")
            .append("\"maxOpen\": ").append(namespaces.maxOpen()).append(", ")
            .append("\"evictions\": ").append(namespaces.evictions())
            .append("}, ")
            .append("\"budget\": {")
            .append("\"graphMappings\": ").append(budget.graphMappings()).append(", ")
            .append("\"processMappings\": ").append(orNull(budget.processMappings()))
            .append(", ")
            .append("\"mappingHighWater\": ").append(budget.mappingHighWater()).append(", ")
            .append("\"mappingLimit\": ").append(budget.mappingLimit()).append(", ")
            .append("\"limitRead\": ").append(budget.limitRead()).append(", ")
            .append("\"graphDescriptors\": ").append(budget.graphDescriptors()).append(", ")
            .append("\"processDescriptors\": ").append(orNull(budget.processDescriptors()))
            .append(", ")
            .append("\"descriptorHighWater\": ").append(budget.descriptorHighWater())
            .append(", ")
            .append("\"descriptorLimit\": ").append(budget.descriptorLimit())
            .append("}, ")
            .append("\"builds\": {")
            .append("\"running\": ").append(builds.running()).append(", ")
            .append("\"completed\": ").append(builds.completed()).append(", ")
            .append("\"failed\": ").append(builds.failed()).append(", ")
            .append("\"cancelled\": ").append(builds.cancelled())
            .append("}");
        appendGraphErasure(sb, graph.erasure());
        appendGraphIngest(sb, graph.ingest());
        sb.append("}");
    }

    /**
     * Erasure obligations across every namespace, omitted where the root could not be listed.
     *
     * @param sb      The body being built
     * @param erasure The backlog, or null
     */
    private static void appendGraphErasure(StringBuilder sb, GraphStats.ErasureStats erasure) {
        if (erasure == null) {
            return;
        }
        sb.append(", \"erasure\": {")
            .append("\"namespacesOwing\": ").append(erasure.namespacesOwing()).append(", ")
            .append("\"outstanding\": ").append(erasure.outstanding()).append(", ")
            .append("\"overdueNamespaces\": ").append(erasure.overdueNamespaces()).append(", ")
            .append("\"unreadableNamespaces\": ").append(erasure.unreadableNamespaces())
            .append(", ")
            // Null rather than zero when nothing is owed: an age of zero is an obligation
            // accepted this instant, which is a different state from none at all.
            .append("\"oldestAgeMs\": ").append(orNull(erasure.oldestAgeMs()))
            .append("}");
    }

    /**
     * What ingestion has staged into the graph, omitted on a node whose ingestion stages none.
     *
     * @param sb     The body being built
     * @param ingest The sink's counts, or null
     */
    private static void appendGraphIngest(StringBuilder sb, GraphStats.IngestStats ingest) {
        if (ingest == null) {
            return;
        }
        sb.append(", \"ingest\": {")
            .append("\"documentsSeen\": ").append(ingest.documentsSeen()).append(", ")
            .append("\"factsStaged\": ").append(ingest.factsStaged()).append(", ")
            .append("\"unresolvedDocuments\": ").append(ingest.unresolvedDocuments())
            .append(", ")
            .append("\"extractionFailures\": ").append(ingest.extractionFailures()).append(", ")
            .append("\"tenantFailures\": ").append(ingest.tenantFailures()).append(", ")
            .append("\"sinkRefusals\": ").append(ingest.sinkRefusals()).append(", ")
            .append("\"withdrawalFailures\": ").append(ingest.withdrawalFailures())
            .append("}");
    }

    /**
     * A figure that may be unknown, as a JSON number or {@code null}.
     *
     * @param value The figure
     * @return Its JSON text
     */
    private static String orNull(java.util.OptionalLong value) {
        return value.isPresent() ? String.valueOf(value.getAsLong()) : "null";
    }

    /** The segment lifecycle and how much the corpus holds. */
    private static void appendSegments(StringBuilder sb, NodeStats stats) {
        NodeStats.SegmentStats segments = stats.segments();
        sb.append("\"segments\": {")
            .append("\"total\": ").append(segments.total()).append(", ")
            .append("\"active\": ").append(segments.active()).append(", ")
            .append("\"indexing\": ").append(segments.indexing()).append(", ")
            .append("\"warming\": ").append(segments.warming()).append(", ")
            .append("\"compacting\": ").append(segments.compacting()).append(", ")
            .append("\"tombstoned\": ").append(segments.tombstoned()).append(", ")
            .append("\"purged\": ").append(segments.purged())
            .append("}, ")
            .append("\"entries\": {\"total\": ").append(stats.entries().total()).append("}, ");
    }

    /** Disk, including what is left of it. */
    private static void appendStorage(StringBuilder sb, NodeStats stats) {
        NodeStats.VolumeStats volumes = stats.volumes();
        sb.append("\"volumes\": {")
            .append("\"count\": ").append(volumes.count()).append(", ")
            .append("\"totalBytes\": ").append(volumes.totalBytes()).append(", ")
            .append("\"usedBytes\": ").append(volumes.usedBytes()).append(", ")
            // Reported rather than left to be subtracted: it is the figure readiness is
            // decided on, and a node whose volumes are full fails writes whatever its
            // coverage says.
            .append("\"availableBytes\": ").append(volumes.availableBytes())
            .append("}, ");
    }

    /** The vector integration queue. */
    private static void appendIndexes(StringBuilder sb, NodeStats stats) {
        NodeStats.HnswStats hnsw = stats.hnsw();
        sb.append("\"hnsw\": {")
            .append("\"queueDepth\": ").append(hnsw.queueDepth()).append(", ")
            .append("\"queueDepthLimit\": ").append(hnsw.queueDepthLimit()).append(", ")
            .append("\"vectorsIntegrated\": ").append(hnsw.vectorsIntegrated())
            .append("}, ");
    }

    /** The epoch, what is visible in it, and the share of the corpus that is. */
    private static void appendVisibility(StringBuilder sb, NodeStats stats) {
        NodeStats.VisibilityStats visibility = stats.visibility();
        sb.append("\"visibility\": {")
            .append("\"epoch\": ").append(visibility.epoch()).append(", ")
            .append("\"registeredSegments\": ").append(visibility.registeredSegments()).append(", ")
            .append("\"visibleSegments\": ").append(visibility.visibleSegments())
            .append("}, ")
            .append("\"coverage\": ").append(JsonText.number(stats.coverage(), 4));
    }

    /**
     * The three states the node keeps serving through, each invisible in every other field.
     *
     * @param sb    The body being built
     * @param stats The snapshot
     */
    private static void appendDegradation(StringBuilder sb, NodeStats stats) {
        sb.append(", \"workers\": {\"indexOrchestratorRunning\": ")
            .append(stats.indexerRunning()).append("}");
        appendVectorIndex(sb, stats.vectorIndex());
        appendBreakers(sb, stats);
    }

    /**
     * The circuit breakers this node has, each omitted where there is none.
     *
     * <p>Seven breakers and seven different degradations: an open embedding breaker drops
     * retrieval to lexical-only, an open relevancy breaker bypasses stage-4 re-ranking, an open
     * query-expansion breaker searches every query as typed, an open graph-extraction breaker
     * indexes documents without adding their facts to the graph, an open graph-rerank breaker
     * leaves graph answers in the order the stage before the model gave them, an open
     * graph-query-embedding breaker skips the exact vector rerank of graph candidates, and an
     * open graph-build-embedding breaker publishes a build's entities without the vectors that
     * rerank compares against. Any of
     * them can be open while the others are closed, and an omitted one means there is no such
     * component rather than a healthy one.</p>
     *
     * <p>The three graph breakers are one each for the node, not one per namespace: every
     * tenant's graph reranks, embeds and builds through the same clients, because every tenant calls
     * the same endpoint, so one state is the truth for all of them and the block does not grow
     * with the number of tenants.</p>
     *
     * @param sb    The body being built
     * @param stats The snapshot
     */
    private static void appendBreakers(StringBuilder sb, NodeStats stats) {
        java.util.Map<String, String> present = new java.util.LinkedHashMap<>();
        putIfPresent(present, "embedding", stats.embeddingBreaker());
        putIfPresent(present, "llmRelevancy", stats.llmRelevancyBreaker());
        putIfPresent(present, "queryExpansion", stats.queryExpansionBreaker());
        putIfPresent(present, "graphExtraction", stats.graphExtractionBreaker());
        putIfPresent(present, "graphRerank", stats.graphRerankBreaker());
        putIfPresent(present, "graphQueryEmbedding", stats.graphQueryEmbeddingBreaker());
        putIfPresent(present, "graphBuildEmbedding", stats.graphBuildEmbeddingBreaker());
        if (present.isEmpty()) {
            return;
        }
        sb.append(", \"breakers\": {");
        String separator = "";
        for (java.util.Map.Entry<String, String> breaker : present.entrySet()) {
            sb.append(separator).append('"').append(breaker.getKey()).append("\": \"")
                .append(JsonText.escape(breaker.getValue())).append('"');
            separator = ", ";
        }
        sb.append("}");
    }

    /** Adds a breaker's state under its name, leaving out one this node does not have. */
    private static void putIfPresent(java.util.Map<String, String> breakers, String name,
                                     String state) {
        if (state != null) {
            breakers.put(name, state);
        }
    }

    /**
     * Which segments are serving without a vector index, and why.
     *
     * <p>Absent where the storage engine could not be asked, which is a different statement
     * from a count of zero.</p>
     *
     * @param sb          The body being built
     * @param vectorIndex The failures, or null
     */
    private static void appendVectorIndex(StringBuilder sb, NodeStats.VectorIndexStats vectorIndex) {
        if (vectorIndex == null) {
            return;
        }
        sb.append(", \"vectorIndex\": {\"segmentsWithoutIndex\": ")
            .append(vectorIndex.segmentsWithoutIndex());
        Map<Long, String> reasons = vectorIndex.orderedReasons();
        if (reasons.isEmpty()) {
            sb.append("}");
            return;
        }
        sb.append(", \"reasons\": {");
        int written = 0;
        for (Map.Entry<Long, String> reason : reasons.entrySet()) {
            if (written == MAX_REPORTED_VECTOR_FAILURES) {
                sb.append(", \"truncated\": true");
                break;
            }
            sb.append(written == 0 ? "" : ", ")
                .append('"').append(reason.getKey()).append("\": \"")
                .append(JsonText.escape(reason.getValue())).append('"');
            written++;
        }
        sb.append("}}");
    }

    /**
     * The query result cache, when this node runs one.
     *
     * <p>Omitted entirely on a node with no cache, which is a different statement from a
     * cache that has never been hit: the zeros of a cache nobody has used and the zeros of a
     * node that has none are identical, and only one of them is worth investigating.</p>
     *
     * <p>{@code hitRatio} is the figure that says whether the cache is earning its place, and
     * {@code invalidations} is the one that says why it is not: entries discarded because the
     * corpus moved cannot be fixed by raising the capacity, only by ingesting less often or
     * by accepting that this node's corpus changes faster than its queries repeat.</p>
     *
     * @param sb    The body being built
     * @param cache What the cache has done, or null
     */
    private static void appendQueryCache(StringBuilder sb,
                                         com.eonmux.excalibur.query.cache.QueryCacheStats cache) {
        if (cache == null) {
            return;
        }
        sb.append(", \"queryCache\": {")
            .append("\"hits\": ").append(cache.hits()).append(", ")
            .append("\"misses\": ").append(cache.misses()).append(", ")
            .append("\"evictions\": ").append(cache.evictions()).append(", ")
            .append("\"invalidations\": ").append(cache.invalidations()).append(", ")
            .append("\"size\": ").append(cache.size()).append(", ")
            .append("\"capacity\": ").append(cache.capacity()).append(", ")
            .append("\"hitRatio\": ").append(JsonText.number(cache.hitRatio(), 4))
            .append("}");
    }

    /**
     * The chunk lexical index, when this node keeps one.
     *
     * @param sb      The body being built
     * @param lexical The index's own account of itself, or null
     */
    private static void appendLexical(StringBuilder sb, ChunkIndexStats lexical) {
        if (lexical == null) {
            return;
        }
        sb.append(", \"lexical\": {")
            .append("\"engine\": \"").append(JsonText.escape(lexical.engine())).append("\", ")
            .append("\"indexed\": ").append(lexical.indexed()).append(", ")
            .append("\"segments\": ").append(lexical.segments()).append(", ")
            .append("\"liveChunks\": ").append(lexical.liveDocuments()).append(", ")
            .append("\"erasedChunks\": ").append(lexical.erasedDocuments()).append(", ")
            .append("\"erasedRatio\": ").append(JsonText.number(lexical.erasedRatio(), 4))
            .append(", ")
            .append("\"erasureRequests\": ").append(lexical.erasureRequests()).append(", ")
            // Milliseconds since the oldest unmet request was accepted: the number an operator
            // compares against the configured latency bound to see whether it is being met.
            .append("\"oldestErasureAgeMs\": ").append(lexical.oldestErasureRequest()
                .map(at -> String.valueOf(Math.max(0, System.currentTimeMillis() - at.toEpochMilli())))
                .orElse("null")).append(", ")
            .append("\"compactionDue\": ").append(lexical.compactionDue()).append(", ")
            .append("\"unreadableChunksDropped\": ").append(lexical.unreadableChunksDropped())
            .append(", ")
            // Chunks the index holds and no query can match, and the segments holding them:
            // without these a node missing part of its keyword corpus reports a smaller one.
            .append("\"unsearchableChunks\": ").append(lexical.unsearchableDocuments())
            .append(", ")
            .append("\"unreadableSegments\": ").append(lexical.unreadableSegments().size())
            .append(", ")
            .append("\"outdatedSegments\": ").append(lexical.outdatedSegments());
        appendUnreadableSegments(sb, lexical.unreadableSegments());
        sb.append("}");
    }

    /**
     * Names each lexical segment that could not be opened, when there is any.
     *
     * <p>Absent rather than empty on a healthy index, so the common response stays the shape it
     * was. Each entry says whether the next compaction can rebuild the segment, because that
     * decides whether an operator waits or restores.</p>
     *
     * @param sb         The body being built
     * @param unreadable The segments, in the index's order
     */
    private static void appendUnreadableSegments(
            StringBuilder sb, List<ChunkIndexStats.UnreadableSegment> unreadable) {
        if (unreadable.isEmpty()) {
            return;
        }
        sb.append(", \"unreadable\": [");
        int written = 0;
        for (ChunkIndexStats.UnreadableSegment segment : unreadable) {
            if (written == MAX_REPORTED_UNREADABLE_SEGMENTS) {
                break;
            }
            sb.append(written == 0 ? "" : ", ")
                .append("{\"segment\": \"").append(JsonText.escape(segment.name()))
                .append("\", \"chunks\": ").append(segment.chunks())
                .append(", \"rebuildable\": ").append(segment.rebuildable())
                .append(", \"reason\": \"").append(JsonText.escape(segment.reason()))
                .append("\"}");
            written++;
        }
        sb.append("]");
        if (unreadable.size() > MAX_REPORTED_UNREADABLE_SEGMENTS) {
            sb.append(", \"unreadableTruncated\": true");
        }
    }
}
