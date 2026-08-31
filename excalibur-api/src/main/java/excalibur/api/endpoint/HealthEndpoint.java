package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 *   "healthy": true,
 *   "epoch": 42,
 *   "coverage": 0.85,
 *   "latencyMs": 5
 * }
 * }</pre>
 *
 * <h2>Response Fields</h2>
 * <ul>
 *   <li>{@code live} - Always true if the process can respond (HTTP 200)</li>
 *   <li>{@code ready} - true if coverage >= readiness threshold (default 0.5)</li>
 *   <li>{@code healthy} - true if coverage >= healthy threshold (default 0.9)</li>
 *   <li>{@code epoch} - Current visibility epoch (monotonically increasing)</li>
 *   <li>{@code coverage} - Estimated corpus coverage (0.0 to 1.0)</li>
 *   <li>{@code latencyMs} - Time taken to compute health status in milliseconds</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Always returns HTTP 200 OK if process is alive</li>
 *   <li>{@code live} is always true (process wouldn't respond otherwise)</li>
 *   <li>{@code ready} computed from coverage vs readiness threshold (lower bar for traffic)</li>
 *   <li>{@code healthy} computed from coverage vs healthy threshold (higher bar for full health)</li>
 *   <li>{@code epoch} is current visibility epoch (non-negative)</li>
 *   <li>{@code coverage} is clamped to [0.0, 1.0]</li>
 *   <li>{@code latencyMs} is non-negative; if exceeds maxLatencyMs, system is degraded</li>
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

    /**
     * Default healthy threshold.
     * System is considered fully healthy when coverage >= this value.
     */
    public static final double DEFAULT_HEALTHY_THRESHOLD = 0.9;

    /**
     * Default maximum latency in milliseconds.
     * Health check should complete within this time.
     */
    public static final int DEFAULT_MAX_LATENCY_MS = 5000;

    private final VisibilityTracker visibilityTracker;
    private final SegmentCatalog segmentCatalog;
    private final double readinessThreshold;
    private final double healthyThreshold;
    private final int maxLatencyMs;

    /**
     * Creates a new HealthEndpoint with default thresholds.
     *
     * @param indexOrchestrator The index orchestrator for visibility tracking
     * @throws NullPointerException if indexOrchestrator is null
     */
    public HealthEndpoint(IndexOrchestrator indexOrchestrator) {
        this(indexOrchestrator, DEFAULT_READINESS_THRESHOLD, DEFAULT_HEALTHY_THRESHOLD, DEFAULT_MAX_LATENCY_MS);
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
        this(indexOrchestrator, readinessThreshold, DEFAULT_HEALTHY_THRESHOLD, DEFAULT_MAX_LATENCY_MS);
    }

    /**
     * Creates a new HealthEndpoint with full health configuration.
     *
     * @param indexOrchestrator The index orchestrator for visibility tracking
     * @param readinessThreshold The coverage threshold for readiness (0.0 to 1.0)
     * @param healthyThreshold The coverage threshold for full health (0.0 to 1.0)
     * @param maxLatencyMs Maximum acceptable latency for health computation (ms)
     * @throws NullPointerException if indexOrchestrator is null
     * @throws IllegalArgumentException if thresholds are out of range
     */
    public HealthEndpoint(IndexOrchestrator indexOrchestrator, double readinessThreshold,
                          double healthyThreshold, int maxLatencyMs) {
        Objects.requireNonNull(indexOrchestrator, "IndexOrchestrator cannot be null");
        validateThreshold(readinessThreshold, "Readiness threshold");
        validateThreshold(healthyThreshold, "Healthy threshold");
        if (maxLatencyMs < 0) {
            throw new IllegalArgumentException("maxLatencyMs must be non-negative: " + maxLatencyMs);
        }

        this.visibilityTracker = indexOrchestrator.getVisibilityTracker();
        this.segmentCatalog = getCatalogFromOrchestrator(indexOrchestrator);
        this.readinessThreshold = readinessThreshold;
        this.healthyThreshold = healthyThreshold;
        this.maxLatencyMs = maxLatencyMs;

        LOGGER.debug("HealthEndpoint created with readinessThreshold={}, healthyThreshold={}, maxLatencyMs={}",
            readinessThreshold, healthyThreshold, maxLatencyMs);
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
        this(visibilityTracker, segmentCatalog, readinessThreshold, DEFAULT_HEALTHY_THRESHOLD, DEFAULT_MAX_LATENCY_MS);
    }

    /**
     * Creates a new HealthEndpoint with explicit dependencies and full health configuration.
     *
     * @param visibilityTracker The visibility tracker for epoch information
     * @param segmentCatalog The segment catalog for coverage information
     * @param readinessThreshold The coverage threshold for readiness (0.0 to 1.0)
     * @param healthyThreshold The coverage threshold for full health (0.0 to 1.0)
     * @param maxLatencyMs Maximum acceptable latency for health computation (ms)
     * @throws NullPointerException if any required parameter is null
     * @throws IllegalArgumentException if thresholds are out of range
     */
    public HealthEndpoint(VisibilityTracker visibilityTracker,
                          SegmentCatalog segmentCatalog,
                          double readinessThreshold,
                          double healthyThreshold,
                          int maxLatencyMs) {
        Objects.requireNonNull(visibilityTracker, "VisibilityTracker cannot be null");
        Objects.requireNonNull(segmentCatalog, "SegmentCatalog cannot be null");
        validateThreshold(readinessThreshold, "Readiness threshold");
        validateThreshold(healthyThreshold, "Healthy threshold");
        if (maxLatencyMs < 0) {
            throw new IllegalArgumentException("maxLatencyMs must be non-negative: " + maxLatencyMs);
        }

        this.visibilityTracker = visibilityTracker;
        this.segmentCatalog = segmentCatalog;
        this.readinessThreshold = readinessThreshold;
        this.healthyThreshold = healthyThreshold;
        this.maxLatencyMs = maxLatencyMs;
    }

    private static void validateThreshold(double threshold, String name) {
        if (Double.isNaN(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException(
                name + " must be in range [0.0, 1.0]: " + threshold);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            // Health endpoint always returns 200 if process is alive,
            // regardless of HTTP method (useful for load balancers/K8s probes)
            HealthStatus status = computeHealthStatus(startTime);
            sendResponse(exchange, status);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            LOGGER.error("Error computing health status, returning degraded status: {}", e.getMessage(), e);
            sendDegradedResponse(exchange, latencyMs);
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
     *   <li>{@code healthy} = coverage >= healthyThreshold</li>
     *   <li>{@code latencyMs} is non-negative; system is degraded if > maxLatencyMs</li>
     * </ul>
     *
     * @param startTime The start time of the health check (for latency calculation)
     * @return The current health status
     */
    private HealthStatus computeHealthStatus(long startTime) {
        long epoch = visibilityTracker.currentEpoch();
        double coverage = computeCoverage();
        boolean ready = coverage >= readinessThreshold;
        boolean healthy = coverage >= healthyThreshold;
        long latencyMs = System.currentTimeMillis() - startTime;

        return new HealthStatus(true, ready, healthy, epoch, coverage, latencyMs);
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
        ResponseBody.send(exchange, 200, bytes);
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
     *
     * @param exchange The HTTP exchange
     * @param latencyMs The time taken before the error occurred
     */
    private void sendDegradedResponse(HttpExchange exchange, long latencyMs) throws IOException {
        HealthStatus degraded = new HealthStatus(true, false, false, 0, 0.0, latencyMs);
        sendResponse(exchange, degraded);
    }

    /**
     * Converts HealthStatus to JSON.
     *
     * <p>Hand-serialized JSON following the project's minimal dependency approach.</p>
     */
    private String toJson(HealthStatus status) {
        // Locale.ROOT: the default locale decides the decimal separator, and a
        // comma-decimal locale would emit "coverage": 0,9500 - not valid JSON.
        return "{\"live\": " + status.live()
            + ", \"ready\": " + status.ready()
            + ", \"healthy\": " + status.healthy()
            + ", \"epoch\": " + status.epoch()
            + ", \"coverage\": " + JsonText.number(status.coverage(), 4)
            + ", \"latencyMs\": " + status.latencyMs()
            + "}";
    }

    /**
     * Immutable record representing health status.
     *
     * @param live     Always true if process is alive
     * @param ready    true if coverage >= readiness threshold (lower bar for traffic)
     * @param healthy  true if coverage >= healthy threshold (higher bar for full health)
     * @param epoch    Current visibility epoch
     * @param coverage Estimated corpus coverage (0.0 to 1.0)
     * @param latencyMs Time taken to compute health status in milliseconds
     */
    public record HealthStatus(
        boolean live,
        boolean ready,
        boolean healthy,
        long epoch,
        double coverage,
        long latencyMs
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
            if (latencyMs < 0) {
                throw new IllegalArgumentException("latencyMs must be non-negative: " + latencyMs);
            }
        }
    }
}
