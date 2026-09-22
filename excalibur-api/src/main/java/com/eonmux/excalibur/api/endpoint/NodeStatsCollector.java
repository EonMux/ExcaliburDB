package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.metrics.FixedBucketHistogram;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reads the node's current state into one {@link NodeStats}.
 *
 * <p>Split out of {@code StatsEndpoint} when a second renderer appeared. {@code /v1/stats} and
 * {@code /metrics} report the same node, and the way two views of one system come to disagree
 * is by each gathering its own; here there is one gatherer and two renderers, so a figure that
 * moves moves in both at once.</p>
 *
 * <h2>A subsystem that cannot answer is absent, not zero</h2>
 * <p>Five of these blocks come from components that can fail independently of the node's
 * ability to serve a request - a lexical index, a storage engine's record of its vector
 * indexes, a query result cache, and the two circuit breakers, each behind a client that may
 * not exist. Each is
 * caught here and reported as absent. Letting one of them fail the whole response would lose
 * the segment counts and the queue depths an operator came for, and reporting a zero the
 * collector did not measure would be worse still: "no segment is missing its vector index"
 * and "I could not ask" are answers with opposite consequences.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every field is final or volatile, and every collaborator is read through a
 * thread-safe accessor.</p>
 *
 * @author ExcaliburDB
 */
public final class NodeStatsCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeStatsCollector.class);

    private final VisibilityTracker visibilityTracker;
    private final SegmentCatalog segmentCatalog;
    private final IndexOrchestrator indexOrchestrator;
    private final StorageEngine storageEngine;
    private final VolumeManager volumeManager;

    /**
     * The chunk lexical index, when this node keeps one.
     *
     * <p>Volatile and set after construction, because whether there is an index at all depends
     * on configuration this collector does not read.</p>
     */
    private volatile ChunkIndex lexicalIndex;

    /** Reads the embedding circuit breaker's state, or null on a node with no embeddings. */
    private volatile Supplier<String> embeddingBreaker;

    /**
     * Reads the stage-5 relevancy breaker's state, or null on a node that does no scoring.
     *
     * <p>A separate breaker from the embedding one and separately reportable: they guard
     * different endpoints of the provider, and either can be open while the other is
     * closed.</p>
     */
    private volatile Supplier<String> llmRelevancyBreaker;

    /**
     * The query result cache, when this node runs one.
     *
     * <p>Volatile and set after construction, for the same reason the lexical index is:
     * whether there is a cache at all depends on configuration this collector does not
     * read.</p>
     */
    private volatile com.eonmux.excalibur.query.cache.QueryResultCache queryResultCache;

    /**
     * Creates a collector over the components a node always has.
     *
     * @param indexOrchestrator Visibility, worker metrics and the membership filters
     * @param storageEngine     The segment catalog and the record of vector index failures
     * @param volumeManager     Disk space, or null to fall back to the catalog
     * @throws NullPointerException if indexOrchestrator or storageEngine is null
     */
    public NodeStatsCollector(IndexOrchestrator indexOrchestrator, StorageEngine storageEngine,
                              VolumeManager volumeManager) {
        this(Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null")
                .getVisibilityTracker(),
            Objects.requireNonNull(storageEngine, "StorageEngine cannot be null").getCatalog(),
            indexOrchestrator, storageEngine, volumeManager);
    }

    /**
     * Creates a collector with the visibility tracker and catalog supplied separately.
     *
     * @param visibilityTracker The tracker the epoch is read from
     * @param segmentCatalog    The catalog the segment counts are read from
     * @param indexOrchestrator Worker metrics and the membership filters
     * @param storageEngine     The record of vector index failures
     * @param volumeManager     Disk space, or null to fall back to the catalog
     * @throws NullPointerException if any required collaborator is null
     */
    public NodeStatsCollector(VisibilityTracker visibilityTracker,
                              SegmentCatalog segmentCatalog,
                              IndexOrchestrator indexOrchestrator,
                              StorageEngine storageEngine,
                              VolumeManager volumeManager) {
        this.visibilityTracker =
            Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        this.segmentCatalog =
            Objects.requireNonNull(segmentCatalog, "SegmentCatalog cannot be null");
        this.indexOrchestrator =
            Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        this.storageEngine =
            Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.volumeManager = volumeManager;
    }

    /**
     * Reports the chunk lexical index alongside the rest of the node's state.
     *
     * @param index The index, or null for a node that keeps none
     */
    public void withLexicalIndex(ChunkIndex index) {
        this.lexicalIndex = index;
    }

    /**
     * Reports the embedding circuit breaker's state.
     *
     * @param breaker Reads the breaker's state, or null on a node with no embedding client
     */
    public void withEmbeddingBreaker(Supplier<String> breaker) {
        this.embeddingBreaker = breaker;
    }

    /**
     * Reports the LLM relevancy circuit breaker's state.
     *
     * <p>The breaker in front of stage-5 relevancy scoring, configured by
     * {@code excalibur.llm.failure-threshold} and {@code excalibur.llm.cooldown-period-ms}.
     * When it opens the pipeline keeps the vector ranking and the node goes on answering 200,
     * so every ranking it returns changes with nothing in the response saying so. Reported the
     * way the embedding breaker is, and absent for the same reason: a node with no scorer has
     * no breaker, which is a different fact from a closed one.</p>
     *
     * @param breaker Reads the breaker's state, or null on a node with no LLM scorer
     */
    public void withLlmRelevancyBreaker(Supplier<String> breaker) {
        this.llmRelevancyBreaker = breaker;
    }

    /**
     * Reports the query result cache alongside the rest of the node's state.
     *
     * <p>Nothing else in this snapshot would show a cache that is not working. One that never
     * hits makes the node slower than it would be without it, and one whose entries are
     * invalidated as fast as they are stored is paying for a corpus fingerprint on every
     * request to fill a map nothing reads; both look like ordinary latency from outside.</p>
     *
     * @param cache The cache, or null for a node that runs without one
     */
    public void withQueryResultCache(com.eonmux.excalibur.query.cache.QueryResultCache cache) {
        this.queryResultCache = cache;
    }

    /**
     * Reads everything, now.
     *
     * @return One consistent-enough snapshot; each figure is read once
     */
    public NodeStats collect() {
        SegmentCatalog.CatalogMetadata catalogMeta = segmentCatalog.getCatalogMetadata();
        VisibilityTracker.VisibilityStats visStats = visibilityTracker.getStats();

        return new NodeStats(
            new NodeStats.SegmentStats(
                catalogMeta.getTotalSegments(),
                catalogMeta.getActiveSegments(),
                catalogMeta.getIndexingSegments(),
                catalogMeta.getWarmSegments(),
                catalogMeta.getCompactingSegments(),
                catalogMeta.getTombstonedSegments(),
                catalogMeta.getPurgedSegments()),
            new NodeStats.EntryStats(catalogMeta.getTotalEntryCount()),
            volumeStats(),
            new NodeStats.HnswStats(
                indexOrchestrator.getHnswQueueDepth(),
                indexOrchestrator.getHnswQueueDepthLimit(),
                indexOrchestrator.getTotalVectorsIntegrated()),
            cuckooStats(),
            new NodeStats.VisibilityStats(
                visStats.currentEpoch(), visStats.totalSegments(), visStats.visibleSegments()),
            catalogMeta.getEstimatedCoverage(),
            lexicalStats(),
            vectorIndexStats(),
            indexOrchestrator.isRunning(),
            breakerState(this.embeddingBreaker, "Embedding"),
            breakerState(this.llmRelevancyBreaker, "LLM relevancy"),
            queryCacheStats());
    }

    /**
     * The query result cache's own account of itself, or null when there is no cache.
     *
     * @return The cache's stats, or null
     */
    private com.eonmux.excalibur.query.cache.QueryCacheStats queryCacheStats() {
        com.eonmux.excalibur.query.cache.QueryResultCache cache = this.queryResultCache;
        if (cache == null) {
            return null;
        }
        try {
            return cache.stats();
        } catch (RuntimeException e) {
            LOGGER.warn("Query cache stats unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** The membership filters, which report saturation only when one is attached. */
    private NodeStats.CuckooStats cuckooStats() {
        com.eonmux.excalibur.filter.HierarchicalCuckooFilter membership =
            indexOrchestrator.getHierarchicalCuckooFilter();
        return new NodeStats.CuckooStats(
            indexOrchestrator.getPendingCuckooDeletions(),
            indexOrchestrator.getTotalCuckooDeletions(),
            FixedBucketHistogram.getInstance().getAverageFpRate(),
            FixedBucketHistogram.getInstance().getMaxFpRate(),
            membership != null && membership.isSaturated(),
            membership == null ? 0 : membership.saturatedSegmentCount());
    }

    /**
     * The lexical index's own account of itself, or null when there is no index.
     *
     * @return The index's stats, or null
     */
    private ChunkIndexStats lexicalStats() {
        ChunkIndex index = this.lexicalIndex;
        if (index == null) {
            return null;
        }
        try {
            return index.stats();
        } catch (RuntimeException e) {
            LOGGER.warn("Lexical index stats unavailable: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Which segments are serving without a vector index, or null if the engine cannot say.
     *
     * @return The failures, or null
     */
    private NodeStats.VectorIndexStats vectorIndexStats() {
        try {
            return new NodeStats.VectorIndexStats(
                storageEngine.segmentsWithoutVectorIndexCount(),
                storageEngine.segmentsWithoutVectorIndex());
        } catch (RuntimeException e) {
            LOGGER.warn("Vector index failure list unavailable: {}", e.getMessage());
            return null;
        }
    }

    /**
     * One circuit breaker's state, or null where there is no breaker to ask.
     *
     * <p>A breaker that throws is reported as absent rather than as closed, and says so in
     * the log: "there is no such breaker" and "the breaker could not be asked" are both
     * safer to read as unknown than as healthy, and the rest of the snapshot is what most
     * callers came for.</p>
     *
     * @param breaker Reads the breaker's state, or null where this node has no such breaker
     * @param name    Which breaker, for the warning a failed read leaves behind
     * @return The state name, or null
     */
    private static String breakerState(Supplier<String> breaker, String name) {
        if (breaker == null) {
            return null;
        }
        try {
            return breaker.get();
        } catch (RuntimeException e) {
            LOGGER.warn("{} circuit breaker state unavailable: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Disk, from the volume manager where one is configured.
     *
     * <p>The manager reads {@link Files#getFileStore(Path)}, which is actual capacity. The
     * fallback below reads {@link Files#size(Path)} on each volume directory, which is the
     * directory's own metadata size - typically 4096 bytes whatever the volume holds.</p>
     *
     * @return Volume statistics
     */
    private NodeStats.VolumeStats volumeStats() {
        if (volumeManager != null) {
            VolumeManager.VolumeStats aggregated = volumeManager.getAggregatedVolumeStats();
            return new NodeStats.VolumeStats(volumeManager.getVolumeCount(),
                aggregated.totalBytes(), aggregated.usedBytes());
        }
        return volumeStatsFromCatalog();
    }

    /**
     * Disk as the catalog sees it, for a node started without a volume manager.
     *
     * <p><strong>Inaccurate by construction</strong> - see {@link #volumeStats()}. Kept because
     * the volume count it produces is still the right count, and reporting no volumes at all
     * on a node that has them would read as a misconfiguration rather than a missing manager.</p>
     *
     * @return Volume statistics computed from the segment catalog
     */
    private NodeStats.VolumeStats volumeStatsFromCatalog() {
        Map<Long, SegmentCatalog.SegmentMetadata> allSegments = segmentCatalog.getAll();
        Set<Path> seenVolumes = new HashSet<>();
        long totalBytes = 0;

        for (SegmentCatalog.SegmentMetadata metadata : allSegments.values()) {
            Path volumePath = metadata.getVolumePath();
            if (volumePath == null || !seenVolumes.add(volumePath)) {
                continue;
            }
            totalBytes += directorySize(volumePath);
        }
        return new NodeStats.VolumeStats(seenVolumes.size(), totalBytes, totalBytes);
    }

    /**
     * The size the filesystem reports for a volume directory, or zero.
     *
     * @param volumePath The volume directory
     * @return A non-negative size
     */
    private static long directorySize(Path volumePath) {
        try {
            return Files.exists(volumePath) ? Files.size(volumePath) : 0;
        } catch (IOException e) {
            LOGGER.trace("Could not get size for volume {}: {}", volumePath, e.getMessage());
            return 0;
        }
    }
}
