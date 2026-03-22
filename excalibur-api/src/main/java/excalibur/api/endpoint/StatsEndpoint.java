package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
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
 *     "totalDeletions": 5000
 *   },
 *   "visibility": {
 *     "epoch": 42,
 *     "registeredSegments": 1024,
 *     "visibleSegments": 1024
 *   },
 *   "coverage": 0.96
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

    /**
     * Creates a new StatsEndpoint with required dependencies.
     *
     * @param indexOrchestrator The index orchestrator for worker metrics
     * @param storageEngine The storage engine for volume information
     * @throws NullPointerException if any parameter is null
     */
    public StatsEndpoint(IndexOrchestrator indexOrchestrator, StorageEngine storageEngine) {
        Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");

        this.indexOrchestrator = indexOrchestrator;
        this.storageEngine = storageEngine;
        this.visibilityTracker = indexOrchestrator.getVisibilityTracker();
        this.segmentCatalog = storageEngine.getCatalog();

        LOGGER.debug("StatsEndpoint created");
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
        Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        Objects.requireNonNull(segmentCatalog, "SegmentCatalog cannot be null");
        Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");

        this.visibilityTracker = visibilityTracker;
        this.segmentCatalog = segmentCatalog;
        this.indexOrchestrator = indexOrchestrator;
        this.storageEngine = storageEngine;
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

        CuckooStats cuckoo = new CuckooStats(
            indexOrchestrator.getPendingCuckooDeletions(),
            indexOrchestrator.getTotalCuckooDeletions()
        );

        VisibilityTracker.VisibilityStats visStats = visibilityTracker.getStats();
        VisibilityStats visibility = new VisibilityStats(
            visStats.currentEpoch(),
            visStats.totalSegments(),
            visStats.visibleSegments()
        );

        double coverage = computeCoverage(catalogMeta);

        return new SystemStats(segments, entries, volumes, hnsw, cuckoo, visibility, coverage);
    }

    /**
     * Computes volume statistics from the storage engine.
     *
     * @return Volume statistics
     */
    private VolumeStats computeVolumeStats() {
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
     */
    private double computeCoverage(SegmentCatalog.CatalogMetadata metadata) {
        long totalSegments = metadata.getTotalSegments();
        long purgedSegments = metadata.getPurgedSegments();
        long nonPurgedSegments = totalSegments - purgedSegments;

        if (nonPurgedSegments <= 0) {
            return 0.0;
        }

        long queryableSegments = metadata.getActiveSegments() +
                                 metadata.getIndexingSegments() +
                                 metadata.getWarmSegments() +
                                 metadata.getCompactingSegments();

        double rawCoverage = (double) queryableSegments / nonPurgedSegments;

        return Math.max(0.0, Math.min(1.0, rawCoverage));
    }

    /**
     * Sends a JSON response with the system statistics.
     */
    private void sendResponse(HttpExchange exchange, SystemStats stats) throws IOException {
        String json = toJson(stats);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Sends an error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String json = String.format("{\"error\": \"%s\"}", escapeJson(message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
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
        sb.append("\"totalDeletions\": ").append(stats.cuckoo().totalDeletions());
        sb.append("}, ");

        sb.append("\"visibility\": {");
        sb.append("\"epoch\": ").append(stats.visibility().epoch()).append(", ");
        sb.append("\"registeredSegments\": ").append(stats.visibility().registeredSegments()).append(", ");
        sb.append("\"visibleSegments\": ").append(stats.visibility().visibleSegments());
        sb.append("}, ");

        sb.append("\"coverage\": ").append(String.format("%.4f", stats.coverage()));

        sb.append("}");
        return sb.toString();
    }

    /**
     * Escapes special characters for JSON strings.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
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
        double coverage
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
        long totalDeletions
    ) {
        public CuckooStats {
            if (pendingDeletions < 0) {
                throw new IllegalArgumentException("Pending deletions must be non-negative: " + pendingDeletions);
            }
            if (totalDeletions < 0) {
                throw new IllegalArgumentException("Total deletions must be non-negative: " + totalDeletions);
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
