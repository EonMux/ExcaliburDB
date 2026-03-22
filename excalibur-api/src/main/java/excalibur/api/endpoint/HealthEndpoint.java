package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * HTTP endpoint for the /health API.
 *
 * <p>HealthEndpoint handles GET requests to /health for system health checks.
 * Used by load balancers and Kubernetes for liveness and readiness probes.</p>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "live": true,
 *   "ready": true,
 *   "epoch": 42,
 *   "coverage": 0.85
 * }
 * }</pre>
 *
 * <h2>Response Fields</h2>
 * <ul>
 *   <li>{@code live} - Always true if the process can respond (HTTP 200)</li>
 *   <li>{@code ready} - true if coverage >= readiness threshold (default 0.5)</li>
 *   <li>{@code epoch} - Current visibility epoch (monotonically increasing)</li>
 *   <li>{@code coverage} - Estimated corpus coverage (0.0 to 1.0)</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Always returns HTTP 200 OK if process is alive</li>
 *   <li>{@code live} is always true (process wouldn't respond otherwise)</li>
 *   <li>{@code ready} computed from coverage vs threshold</li>
 *   <li>{@code epoch} is current visibility epoch (non-negative)</li>
 *   <li>{@code coverage} is clamped to [0.0, 1.0]</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All dependencies are accessed via thread-safe methods.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
public class HealthEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthEndpoint.class);

    /**
     * Default readiness threshold.
     * System is considered ready when coverage >= this value.
     */
    public static final double DEFAULT_READINESS_THRESHOLD = 0.5;

    private final VisibilityTracker visibilityTracker;
    private final SegmentCatalog segmentCatalog;
    private final double readinessThreshold;

    /**
     * Creates a new HealthEndpoint with default readiness threshold.
     *
     * @param indexOrchestrator The index orchestrator for visibility tracking
     * @throws NullPointerException if indexOrchestrator is null
     */
    public HealthEndpoint(IndexOrchestrator indexOrchestrator) {
        this(indexOrchestrator, DEFAULT_READINESS_THRESHOLD);
    }

    /**
     * Creates a new HealthEndpoint with custom readiness threshold.
     *
     * @param indexOrchestrator The index orchestrator for visibility tracking
     * @param readinessThreshold The coverage threshold for readiness (0.0 to 1.0)
     * @throws NullPointerException if indexOrchestrator is null
     * @throws IllegalArgumentException if readinessThreshold is out of range
     */
    public HealthEndpoint(IndexOrchestrator indexOrchestrator, double readinessThreshold) {
        Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        if (Double.isNaN(readinessThreshold) || readinessThreshold < 0.0 || readinessThreshold > 1.0) {
            throw new IllegalArgumentException(
                "Readiness threshold must be in range [0.0, 1.0]: " + readinessThreshold);
        }

        this.visibilityTracker = indexOrchestrator.getVisibilityTracker();
        this.segmentCatalog = getCatalogFromOrchestrator(indexOrchestrator);
        this.readinessThreshold = readinessThreshold;

        LOGGER.debug("HealthEndpoint created with readinessThreshold={}", readinessThreshold);
    }

    /**
     * Creates a new HealthEndpoint with explicit dependencies.
     *
     * <p>This constructor is useful for testing or when dependencies are
     * managed separately from the orchestrator.</p>
     *
     * @param visibilityTracker The visibility tracker for epoch information
     * @param segmentCatalog The segment catalog for coverage information
     * @param readinessThreshold The coverage threshold for readiness
     * @throws NullPointerException if any required parameter is null
     * @throws IllegalArgumentException if readinessThreshold is out of range
     */
    public HealthEndpoint(VisibilityTracker visibilityTracker, 
                          SegmentCatalog segmentCatalog, 
                          double readinessThreshold) {
        Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        Objects.requireNonNull(segmentCatalog, "SegmentCatalog cannot be null");
        if (Double.isNaN(readinessThreshold) || readinessThreshold < 0.0 || readinessThreshold > 1.0) {
            throw new IllegalArgumentException(
                "Readiness threshold must be in range [0.0, 1.0]: " + readinessThreshold);
        }

        this.visibilityTracker = visibilityTracker;
        this.segmentCatalog = segmentCatalog;
        this.readinessThreshold = readinessThreshold;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            HealthStatus status = computeHealthStatus();
            sendResponse(exchange, status);
        } catch (Exception e) {
            LOGGER.error("Error computing health status, returning degraded status: {}", e.getMessage(), e);
            sendDegradedResponse(exchange);
        }
    }

    /**
     * Computes the current health status.
     *
     * <p>Domain correctness guarantees:</p>
     * <ul>
     *   <li>{@code live} is always true (process is responding)</li>
     *   <li>{@code epoch} is current visibility epoch (non-negative)</li>
     *   <li>{@code coverage} is clamped to [0.0, 1.0]</li>
     *   <li>{@code ready} = coverage >= readinessThreshold</li>
     * </ul>
     *
     * @return The current health status
     */
    private HealthStatus computeHealthStatus() {
        long epoch = visibilityTracker.currentEpoch();
        double coverage = computeCoverage();
        boolean ready = coverage >= readinessThreshold;

        return new HealthStatus(true, ready, epoch, coverage);
    }

    /**
     * Computes estimated corpus coverage from the segment catalog.
     *
     * <p>Coverage is computed as the ratio of queryable segments to total non-purged segments.
     * The value is clamped to [0.0, 1.0] to ensure domain correctness.</p>
     *
     * <p>Queryable segments are those in states: INDEXING, WARMING, ACTIVE, COMPACTING</p>
     *
     * @return Coverage ratio in range [0.0, 1.0]
     */
    private double computeCoverage() {
        if (segmentCatalog == null) {
            return 0.0;
        }

        SegmentCatalog.CatalogMetadata metadata = segmentCatalog.getCatalogMetadata();
        
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
     * Extracts the segment catalog from the index orchestrator.
     *
     * <p>This uses reflection as a fallback since the catalog may not be
     * directly exposed. Returns null if the catalog cannot be accessed.</p>
     *
     * @param orchestrator The index orchestrator
     * @return The segment catalog, or null if not accessible
     */
    private SegmentCatalog getCatalogFromOrchestrator(IndexOrchestrator orchestrator) {
        try {
            java.lang.reflect.Field catalogField = IndexOrchestrator.class.getDeclaredField("catalog");
            catalogField.setAccessible(true);
            return (SegmentCatalog) catalogField.get(orchestrator);
        } catch (Exception e) {
            LOGGER.warn("Could not access catalog from orchestrator: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Sends a JSON response with the health status.
     */
    private void sendResponse(HttpExchange exchange, HealthStatus status) throws IOException {
        String json = toJson(status);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Sends a degraded health response when the system cannot compute full status.
     *
     * <p>This method is called when an exception occurs while computing health status.
     * It still returns HTTP 200 because the process is alive and responding - the
     * health endpoint's primary purpose is to indicate liveness.</p>
     *
     * <p>The response indicates the process is live but not ready, with epoch 0 and
     * coverage 0.0 to signal degraded state.</p>
     */
    private void sendDegradedResponse(HttpExchange exchange) throws IOException {
        HealthStatus degraded = new HealthStatus(true, false, 0, 0.0);
        sendResponse(exchange, degraded);
    }

    /**
     * Converts HealthStatus to JSON.
     *
     * <p>Hand-serialized JSON following the project's minimal dependency approach.</p>
     */
    private String toJson(HealthStatus status) {
        return String.format(
            "{\"live\": %s, \"ready\": %s, \"epoch\": %d, \"coverage\": %.4f}",
            status.live(),
            status.ready(),
            status.epoch(),
            status.coverage()
        );
    }

    /**
     * Immutable record representing health status.
     *
     * @param live     Always true if process is alive
     * @param ready    true if coverage >= readiness threshold
     * @param epoch    Current visibility epoch
     * @param coverage Estimated corpus coverage (0.0 to 1.0)
     */
    public record HealthStatus(
        boolean live,
        boolean ready,
        long epoch,
        double coverage
    ) {
        /**
         * Validates domain constraints on construction.
         */
        public HealthStatus {
            if (epoch < 0) {
                throw new IllegalArgumentException("Epoch must be non-negative: " + epoch);
            }
            if (Double.isNaN(coverage) || coverage < 0.0 || coverage > 1.0) {
                throw new IllegalArgumentException("Coverage must be in range [0.0, 1.0]: " + coverage);
            }
        }
    }
}
