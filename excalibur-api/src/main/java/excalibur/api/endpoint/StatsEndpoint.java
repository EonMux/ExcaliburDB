package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import excalibur.shard.allocator.VolumeManager;
import io.excalibur.metrics.FixedBucketHistogram;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

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
 *   "entries": {
 *     "total": 15000000
 *   },
 *   "volumes": {
 *     "count": 3,
 *     "totalBytes": 5368709120,
 *     "usedBytes": 3221225472
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
 *   }
 * }
 * }</pre>
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

    private final VisibilityTracker visibilityTracker;
    private final SegmentCatalog segmentCatalog;
    private final IndexOrchestrator indexOrchestrator;
    private final StorageEngine storageEngine;
    private final VolumeManager volumeManager;

    /**
     * The chunk lexical index, when this node keeps one.
     *
     * <p>Optional and set after construction, because whether there is an index at all depends on
     * configuration this endpoint does not read. Absent, the response simply carries no
     * {@code lexical} block, which is a different statement from an index reporting nothing.</p>
     */
    private volatile excalibur.ingest.lexical.ChunkIndex lexicalIndex;

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
        Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");

        this.indexOrchestrator = indexOrchestrator;
        this.storageEngine = storageEngine;
        this.visibilityTracker = indexOrchestrator.getVisibilityTracker();
        this.segmentCatalog = storageEngine.getCatalog();
        this.volumeManager = volumeManager;

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
        Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        Objects.requireNonNull(segmentCatalog, "SegmentCatalog cannot be null");
        Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");

        this.visibilityTracker = visibilityTracker;
        this.segmentCatalog = segmentCatalog;
        this.indexOrchestrator = indexOrchestrator;
        this.storageEngine = storageEngine;
        this.volumeManager = volumeManager;
    }

    /**
     * Reports the chunk lexical index alongside the rest of the node's state.
     *
     * <p>Its erasure backlog is the one thing here that is a promise with a deadline attached, and
     * nothing else in this response would show it going unmet.</p>
     *
     * @param lexicalIndex The index, or null for a node that keeps none
     */
    public void withLexicalIndex(excalibur.ingest.lexical.ChunkIndex lexicalIndex) {
        this.lexicalIndex = lexicalIndex;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if (!"GET".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            SystemStats stats = computeStats();
            sendResponse(exchange, stats);
        } catch (Exception e) {
            LOGGER.error("Error computing stats: {}", e.getMessage(), e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Computes the current system statistics.
     *
     * <p>Domain correctness guarantees:</p>
     * <ul>
     *   <li>All counts are non-negative</li>
     *   <li>Coverage is clamped to [0.0, 1.0]</li>
     *   <li>Epoch is non-negative</li>
     * </ul>
     *
     * @return The current system statistics
     */
    private SystemStats computeStats() {
        SegmentCatalog.CatalogMetadata catalogMeta = segmentCatalog.getCatalogMetadata();

        SegmentStats segments = new SegmentStats(
            catalogMeta.getTotalSegments(),
            catalogMeta.getActiveSegments(),
            catalogMeta.getIndexingSegments(),
            catalogMeta.getWarmSegments(),
            catalogMeta.getCompactingSegments(),
            catalogMeta.getTombstonedSegments(),
            catalogMeta.getPurgedSegments()
        );

        EntryStats entries = new EntryStats(catalogMeta.getTotalEntryCount());

        VolumeStats volumes = computeVolumeStats();

        HnswStats hnsw = new HnswStats(
            indexOrchestrator.getHnswQueueDepth(),
            indexOrchestrator.getHnswQueueDepthLimit(),
            indexOrchestrator.getTotalVectorsIntegrated()
        );

        excalibur.filter.HierarchicalCuckooFilter membership =
            indexOrchestrator.getHierarchicalCuckooFilter();
        CuckooStats cuckoo = new CuckooStats(
            indexOrchestrator.getPendingCuckooDeletions(),
            indexOrchestrator.getTotalCuckooDeletions(),
            FixedBucketHistogram.getInstance().getAverageFpRate(),
            FixedBucketHistogram.getInstance().getMaxFpRate(),
            membership != null && membership.isSaturated(),
            membership == null ? 0 : membership.saturatedSegmentCount()
        );

        VisibilityTracker.VisibilityStats visStats = visibilityTracker.getStats();
        VisibilityStats visibility = new VisibilityStats(
            visStats.currentEpoch(),
            visStats.totalSegments(),
            visStats.visibleSegments()
        );

        double coverage = computeCoverage(catalogMeta);

        return new SystemStats(segments, entries, volumes, hnsw, cuckoo, visibility, coverage,
            computeLexicalStats());
    }

    /**
     * Reads the lexical index's own account of itself, or null when there is no index.
     *
     * <p>A failure here is reported as an absent block rather than as a failed request: the rest
     * of the response is what most callers came for, and losing all of it to one subsystem that
     * could not answer is the worse outcome.</p>
     */
    private excalibur.ingest.lexical.ChunkIndexStats computeLexicalStats() {
        excalibur.ingest.lexical.ChunkIndex index = this.lexicalIndex;
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
     * Computes volume statistics from the VolumeManager or storage engine.
     *
     * <p>If VolumeManager is available, uses it for accurate disk space tracking via
     * {@link Files#getFileStore(Path)}. Otherwise, falls back to estimating from
     * segment catalog (which may be inaccurate as it uses directory metadata size).</p>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>totalBytes: Actual disk capacity from FileStore.getTotalSpace()</li>
     *   <li>usedBytes: Actual used space (totalBytes - availableBytes)</li>
     *   <li>Values represent real disk usage, not directory metadata size</li>
     * </ul>
     *
     * @return Volume statistics
     */
    private VolumeStats computeVolumeStats() {
        // Use VolumeManager for accurate disk space tracking when available
        if (volumeManager != null) {
            VolumeManager.VolumeStats aggregatedStats = volumeManager.getAggregatedVolumeStats();
            int volumeCount = volumeManager.getVolumeCount();

            return new VolumeStats(
                volumeCount,
                aggregatedStats.totalBytes(),
                aggregatedStats.usedBytes()
            );
        }

        // Fallback: compute from segment catalog (less accurate)
        // WARNING: This uses Files.size(volumePath) which returns directory metadata size,
        // not actual disk usage. This is kept for backward compatibility.
        return computeVolumeStatsFallback();
    }

    /**
     * Fallback method for computing volume statistics from segment catalog.
     *
     * <p><strong>Warning:</strong> This method uses {@link Files#size(Path)} which
     * returns directory metadata size (typically 4096 bytes), not actual disk usage.
     * Use VolumeManager for accurate disk space tracking.</p>
     *
     * @return Volume statistics computed from segment catalog
     */
    private VolumeStats computeVolumeStatsFallback() {
        Map<Long, SegmentCatalog.SegmentMetadata> allSegments = segmentCatalog.getAll();

        int volumeCount = 0;
        long totalBytes = 0;
        long usedBytes = 0;

        java.util.Set<Path> seenVolumes = new java.util.HashSet<>();

        for (SegmentCatalog.SegmentMetadata metadata : allSegments.values()) {
            Path volumePath = metadata.getVolumePath();
            if (volumePath != null && !seenVolumes.contains(volumePath)) {
                seenVolumes.add(volumePath);
                volumeCount++;

                try {
                    if (Files.exists(volumePath)) {
                        // WARNING: Files.size() returns directory metadata size, not disk usage
                        // This is intentionally kept for backward compatibility but is inaccurate
                        long size = Files.size(volumePath);
                        totalBytes += size;
                        usedBytes += size;
                    }
                } catch (IOException e) {
                    LOGGER.trace("Could not get size for volume {}: {}", volumePath, e.getMessage());
                }
            }
        }

        return new VolumeStats(volumeCount, totalBytes, usedBytes);
    }

    /**
     * Computes estimated corpus coverage from the segment catalog.
     *
     * <p>Coverage is computed as the ratio of queryable segments to total non-purged segments.
     * The value is clamped to [0.0, 1.0] to ensure domain correctness.</p>
     *
     * @param metadata The catalog metadata
     * @return Coverage ratio in range [0.0, 1.0]
     * @see SegmentCatalog.CatalogMetadata#getEstimatedCoverage()
     */
    private double computeCoverage(SegmentCatalog.CatalogMetadata metadata) {
        return metadata.getEstimatedCoverage();
    }

    /**
     * Sends a JSON response with the system statistics.
     */
    private void sendResponse(HttpExchange exchange, SystemStats stats) throws IOException {
        String json = toJson(stats);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, 200, bytes);
    }

    /**
     * Sends an error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String json = String.format("{\"error\": \"%s\"}", JsonText.escape(message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
    }

    /**
     * Converts SystemStats to JSON.
     *
     * <p>Hand-serialized JSON following the project's minimal dependency approach.</p>
     */
    private String toJson(SystemStats stats) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{");

        sb.append("\"segments\": {");
        sb.append("\"total\": ").append(stats.segments().total()).append(", ");
        sb.append("\"active\": ").append(stats.segments().active()).append(", ");
        sb.append("\"indexing\": ").append(stats.segments().indexing()).append(", ");
        sb.append("\"warming\": ").append(stats.segments().warming()).append(", ");
        sb.append("\"compacting\": ").append(stats.segments().compacting()).append(", ");
        sb.append("\"tombstoned\": ").append(stats.segments().tombstoned()).append(", ");
        sb.append("\"purged\": ").append(stats.segments().purged());
        sb.append("}, ");

        sb.append("\"entries\": {");
        sb.append("\"total\": ").append(stats.entries().total());
        sb.append("}, ");

        sb.append("\"volumes\": {");
        sb.append("\"count\": ").append(stats.volumes().count()).append(", ");
        sb.append("\"totalBytes\": ").append(stats.volumes().totalBytes()).append(", ");
        sb.append("\"usedBytes\": ").append(stats.volumes().usedBytes());
        sb.append("}, ");

        sb.append("\"hnsw\": {");
        sb.append("\"queueDepth\": ").append(stats.hnsw().queueDepth()).append(", ");
        sb.append("\"queueDepthLimit\": ").append(stats.hnsw().queueDepthLimit()).append(", ");
        sb.append("\"vectorsIntegrated\": ").append(stats.hnsw().vectorsIntegrated());
        sb.append("}, ");

        sb.append("\"cuckoo\": {");
        sb.append("\"pendingDeletions\": ").append(stats.cuckoo().pendingDeletions()).append(", ");
        sb.append("\"totalDeletions\": ").append(stats.cuckoo().totalDeletions()).append(", ");
        sb.append("\"avgFpRate\": ").append(JsonText.number(stats.cuckoo().avgFpRate(), 6)).append(", ");
        sb.append("\"maxFpRate\": ").append(JsonText.number(stats.cuckoo().maxFpRate(), 6)).append(", ");
        sb.append("\"saturated\": ").append(stats.cuckoo().saturated()).append(", ");
        sb.append("\"saturatedSegments\": ").append(stats.cuckoo().saturatedSegments());
        sb.append("}, ");

        sb.append("\"visibility\": {");
        sb.append("\"epoch\": ").append(stats.visibility().epoch()).append(", ");
        sb.append("\"registeredSegments\": ").append(stats.visibility().registeredSegments()).append(", ");
        sb.append("\"visibleSegments\": ").append(stats.visibility().visibleSegments());
        sb.append("}, ");

        sb.append("\"coverage\": ").append(JsonText.number(stats.coverage(), 4));

        if (stats.lexical() != null) {
            excalibur.ingest.lexical.ChunkIndexStats lexical = stats.lexical();
            sb.append(", \"lexical\": {");
            sb.append("\"engine\": \"").append(JsonText.escape(lexical.engine())).append("\", ");
            sb.append("\"indexed\": ").append(lexical.indexed()).append(", ");
            sb.append("\"segments\": ").append(lexical.segments()).append(", ");
            sb.append("\"liveChunks\": ").append(lexical.liveDocuments()).append(", ");
            sb.append("\"erasedChunks\": ").append(lexical.erasedDocuments()).append(", ");
            sb.append("\"erasedRatio\": ").append(JsonText.number(lexical.erasedRatio(), 4)).append(", ");
            sb.append("\"erasureRequests\": ").append(lexical.erasureRequests()).append(", ");
            // Milliseconds since the oldest unmet request was accepted: the number an operator
            // compares against the configured latency bound to see whether it is being met.
            sb.append("\"oldestErasureAgeMs\": ").append(lexical.oldestErasureRequest()
                .map(at -> String.valueOf(Math.max(0, System.currentTimeMillis() - at.toEpochMilli())))
                .orElse("null")).append(", ");
            sb.append("\"compactionDue\": ").append(lexical.compactionDue());
            sb.append("}");
        }

        sb.append("}");
        return sb.toString();
    }


    /**
     * Immutable record representing complete system statistics.
     */
    public record SystemStats(
        SegmentStats segments,
        EntryStats entries,
        VolumeStats volumes,
        HnswStats hnsw,
        CuckooStats cuckoo,
        VisibilityStats visibility,
        double coverage,
        excalibur.ingest.lexical.ChunkIndexStats lexical
    ) {
        /**
         * Validates domain constraints on construction.
         */
        public SystemStats {
            Objects.requireNonNull(segments, "segments cannot be null");
            Objects.requireNonNull(entries, "entries cannot be null");
            Objects.requireNonNull(volumes, "volumes cannot be null");
            Objects.requireNonNull(hnsw, "hnsw cannot be null");
            Objects.requireNonNull(cuckoo, "cuckoo cannot be null");
            Objects.requireNonNull(visibility, "visibility cannot be null");

            if (Double.isNaN(coverage) || coverage < 0.0 || coverage > 1.0) {
                throw new IllegalArgumentException("Coverage must be in range [0.0, 1.0]: " + coverage);
            }
        }
    }

    /**
     * Segment lifecycle statistics.
     */
    public record SegmentStats(
        long total,
        long active,
        long indexing,
        long warming,
        long compacting,
        long tombstoned,
        long purged
    ) {
        public SegmentStats {
            if (total < 0 || active < 0 || indexing < 0 || warming < 0 ||
                compacting < 0 || tombstoned < 0 || purged < 0) {
                throw new IllegalArgumentException("All segment counts must be non-negative");
            }
        }
    }

    /**
     * Entry statistics.
     */
    public record EntryStats(long total) {
        public EntryStats {
            if (total < 0) {
                throw new IllegalArgumentException("Entry count must be non-negative: " + total);
            }
        }
    }

    /**
     * Volume statistics.
     */
    public record VolumeStats(
        int count,
        long totalBytes,
        long usedBytes
    ) {
        public VolumeStats {
            if (count < 0) {
                throw new IllegalArgumentException("Volume count must be non-negative: " + count);
            }
            if (totalBytes < 0) {
                throw new IllegalArgumentException("Total bytes must be non-negative: " + totalBytes);
            }
            if (usedBytes < 0) {
                throw new IllegalArgumentException("Used bytes must be non-negative: " + usedBytes);
            }
        }
    }

    /**
     * HNSW graph statistics.
     */
    public record HnswStats(
        long queueDepth,
        int queueDepthLimit,
        long vectorsIntegrated
    ) {
        public HnswStats {
            if (queueDepth < 0) {
                throw new IllegalArgumentException("Queue depth must be non-negative: " + queueDepth);
            }
            if (queueDepthLimit < 0) {
                throw new IllegalArgumentException("Queue depth limit must be non-negative: " + queueDepthLimit);
            }
            if (vectorsIntegrated < 0) {
                throw new IllegalArgumentException("Vectors integrated must be non-negative: " + vectorsIntegrated);
            }
        }
    }

    /**
     * Cuckoo filter statistics.
     */
    public record CuckooStats(
        int pendingDeletions,
        long totalDeletions,
        double avgFpRate,
        double maxFpRate,
        boolean saturated,
        int saturatedSegments
    ) {
        public CuckooStats {
            if (pendingDeletions < 0) {
                throw new IllegalArgumentException("Pending deletions must be non-negative: " + pendingDeletions);
            }
            if (totalDeletions < 0) {
                throw new IllegalArgumentException("Total deletions must be non-negative: " + totalDeletions);
            }
            if (saturatedSegments < 0) {
                throw new IllegalArgumentException("Saturated segments must be non-negative: " + saturatedSegments);
            }
            if (Double.isNaN(avgFpRate) || avgFpRate < 0.0 || avgFpRate > 1.0) {
                throw new IllegalArgumentException("Average FP rate must be in range [0.0, 1.0]: " + avgFpRate);
            }
            if (Double.isNaN(maxFpRate) || maxFpRate < 0.0 || maxFpRate > 1.0) {
                throw new IllegalArgumentException("Max FP rate must be in range [0.0, 1.0]: " + maxFpRate);
            }
        }
    }

    /**
     * Visibility tracking statistics.
     */
    public record VisibilityStats(
        long epoch,
        int registeredSegments,
        int visibleSegments
    ) {
        public VisibilityStats {
            if (epoch < 0) {
                throw new IllegalArgumentException("Epoch must be non-negative: " + epoch);
            }
            if (registeredSegments < 0) {
                throw new IllegalArgumentException("Registered segments must be non-negative: " + registeredSegments);
            }
            if (visibleSegments < 0) {
                throw new IllegalArgumentException("Visible segments must be non-negative: " + visibleSegments);
            }
        }
    }
}
