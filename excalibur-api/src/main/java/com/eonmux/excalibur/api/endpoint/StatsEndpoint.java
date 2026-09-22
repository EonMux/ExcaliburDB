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
 *   "cuckoo": {
 *     "pendingDeletions": 100,
 *     "totalDeletions": 5000,
 *     "avgFpRate": 0.000500,
 *     "maxFpRate": 0.001000,
 *     "saturated": false,
 *     "saturatedSegments": 0
 *   },
 *   "visibility": {
 *     "epoch": 42,
 *     "registeredSegments": 1024,
 *     "visibleSegments": 1024
 *   },
 *   "coverage": 1.0000,
 *   "workers": { "indexOrchestratorRunning": true },
 *   "vectorIndex": { "segmentsWithoutIndex": 0 },
 *   "breakers": { "embedding": "CLOSED" },
 *   "lexical": {
 *     "engine": "native",
 *     "indexed": 15000000,
 *     "segments": 12,
 *     "liveChunks": 14980000,
 *     "erasedChunks": 20000,
 *     "erasedRatio": 0.0013,
 *     "erasureRequests": 3,
 *     "oldestErasureAgeMs": 412000,
 *     "compactionDue": false
 *   },
 *   "queryCache": {
 *     "hits": 1204,
 *     "misses": 8431,
 *     "evictions": 0,
 *     "invalidations": 7900,
 *     "size": 512,
 *     "capacity": 1024,
 *     "hitRatio": 0.1249
 *   }
 * }
 * }</pre>
 *
 * <h2>The query cache, when there is one</h2>
 * <p>Absent unless {@code excalibur.query.cache.enabled} is on, and absent is a different
 * statement from a cache that has never been hit. {@code hitRatio} says whether it is
 * earning its place; {@code invalidations} says why it is not, when it is not. Entries
 * discarded because the corpus moved are not a capacity problem and raising the capacity
 * will not help - that figure is the node ingesting faster than its queries repeat.</p>
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
     * <p>Stage 5's breaker. When it opens the pipeline keeps the vector ranking and the node
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
        StringBuilder sb = new StringBuilder(768);
        sb.append("{");
        appendSegments(sb, stats);
        appendStorage(sb, stats);
        appendIndexes(sb, stats);
        appendVisibility(sb, stats);
        appendDegradation(sb, stats);
        appendLexical(sb, stats.lexical());
        appendQueryCache(sb, stats.queryCache());
        sb.append("}");
        return sb.toString();
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

    /** The vector integration queue and the membership filters. */
    private static void appendIndexes(StringBuilder sb, NodeStats stats) {
        NodeStats.HnswStats hnsw = stats.hnsw();
        NodeStats.CuckooStats cuckoo = stats.cuckoo();
        sb.append("\"hnsw\": {")
            .append("\"queueDepth\": ").append(hnsw.queueDepth()).append(", ")
            .append("\"queueDepthLimit\": ").append(hnsw.queueDepthLimit()).append(", ")
            .append("\"vectorsIntegrated\": ").append(hnsw.vectorsIntegrated())
            .append("}, ")
            .append("\"cuckoo\": {")
            .append("\"pendingDeletions\": ").append(cuckoo.pendingDeletions()).append(", ")
            .append("\"totalDeletions\": ").append(cuckoo.totalDeletions()).append(", ")
            .append("\"avgFpRate\": ").append(JsonText.number(cuckoo.avgFpRate(), 6)).append(", ")
            .append("\"maxFpRate\": ").append(JsonText.number(cuckoo.maxFpRate(), 6)).append(", ")
            .append("\"saturated\": ").append(cuckoo.saturated()).append(", ")
            .append("\"saturatedSegments\": ").append(cuckoo.saturatedSegments())
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
     * <p>Two breakers and two different degradations: an open embedding breaker drops
     * retrieval to lexical-only, an open relevancy breaker bypasses stage-5 re-ranking. Either
     * can be open while the other is closed, and an omitted one means there is no such
     * component rather than a healthy one.</p>
     *
     * @param sb    The body being built
     * @param stats The snapshot
     */
    private static void appendBreakers(StringBuilder sb, NodeStats stats) {
        if (stats.embeddingBreaker() == null && stats.llmRelevancyBreaker() == null) {
            return;
        }
        sb.append(", \"breakers\": {");
        String separator = "";
        if (stats.embeddingBreaker() != null) {
            sb.append("\"embedding\": \"")
                .append(JsonText.escape(stats.embeddingBreaker())).append("\"");
            separator = ", ";
        }
        if (stats.llmRelevancyBreaker() != null) {
            sb.append(separator).append("\"llmRelevancy\": \"")
                .append(JsonText.escape(stats.llmRelevancyBreaker())).append("\"");
        }
        sb.append("}");
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
            .append("\"compactionDue\": ").append(lexical.compactionDue())
            .append("}");
    }
}
