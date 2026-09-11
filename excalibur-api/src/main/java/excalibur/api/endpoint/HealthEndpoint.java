package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.metrics.Metrics;
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
 *   "coverage": 1.0000,
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
 *   <li>{@code coverage} - The share of this node's live segments that are in a state a query
 *       reads, 0.0 to 1.0. Not a warm-up gauge: a segment still absorbing its write log is
 *       searched, so it counts, and an empty segment counts as readily as a full one. See
 *       {@link SegmentCatalog.CatalogMetadata#getEstimatedCoverage()}</li>
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
 *   <li>{@code latencyMs} is non-negative; exceeding maxLatencyMs makes {@code healthy} false</li>
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
        this.segmentCatalog = indexOrchestrator.getCatalog();
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
        // GET, like the documented contract and like every other read route on this server.
        // Answering 200 to any method made a POST to this path look like agreement, and left
        // a client that had the wrong verb with nothing to tell it so.
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        try {
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
     *   <li>{@code healthy} = coverage >= healthyThreshold and latencyMs <= maxLatencyMs</li>
     *   <li>{@code latencyMs} is non-negative</li>
     * </ul>
     *
     * <p>Latency is part of {@code healthy} rather than only reported beside it. A node that
     * takes longer than the configured maximum to answer what it holds is not a healthy node,
     * and leaving the comparison to whoever reads the field makes
     * {@code excalibur.health.max-latency-ms} a setting that changes nothing on the server that
     * carries it. Readiness is left on coverage alone: a probe that waited that long has
     * already timed out, and taking the node out of rotation for a slow answer to this endpoint
     * would be deciding on the strength of the one request that is not serving traffic.</p>
     *
     * @param startTime The start time of the health check (for latency calculation)
     * @return The current health status
     */
    private HealthStatus computeHealthStatus(long startTime) {
        long epoch = visibilityTracker.currentEpoch();
        double coverage = computeCoverage();
        boolean ready = coverage >= readinessThreshold;
        long latencyMs = System.currentTimeMillis() - startTime;
        boolean healthy = coverage >= healthyThreshold && latencyMs <= maxLatencyMs;

        publishHealthScore(coverage);

        return new HealthStatus(true, ready, healthy, epoch, coverage, latencyMs);
    }

    /**
     * Publishes what this endpoint just measured to the {@code SystemHealth} MBean.
     *
     * <p>The MBean's score is otherwise the 0.5 the process starts at and never leaves, and
     * its {@code isHealthy} attribute is derived from that score - so a JMX client watching a
     * node through a total loss of its corpus sees a health score that never moves and a flag
     * that never falls. Nothing else writes it: the latency-based updater it was designed
     * around has no caller.</p>
     *
     * <p>The score is the coverage, which is the one health signal this endpoint measures on a
     * scale the MBean can carry. The MBean's own threshold for {@code isHealthy} is a fixed
     * 0.5, so it answers a coarser question than the configurable {@code healthy} in the
     * response body; both are read off the same number.</p>
     *
     * @param coverage The coverage just computed, in [0, 1]
     */
    private void publishHealthScore(double coverage) {
        try {
            Metrics.setHealthScore(coverage);
        } catch (RuntimeException e) {
            // A metrics sink that cannot take the value must not turn a health check into a
            // degraded response: the node's health does not depend on being able to report it.
            LOGGER.debug("Could not publish the health score: {}", e.toString());
        }
    }

    /**
     * Computes estimated corpus coverage from the segment catalog.
     *
     * <p>The share of the live corpus a query could read - see
     * {@link SegmentCatalog.CatalogMetadata#getEstimatedCoverage()}, which is where the rule
     * lives. Deriving it here as well is how the two came to disagree.</p>
     *
     * @return Coverage ratio in range [0.0, 1.0]
     */
    private double computeCoverage() {
        if (segmentCatalog == null) {
            return 0.0;
        }
        return segmentCatalog.getCatalogMetadata().getEstimatedCoverage();
    }

    /**
     * Refuses a method this route does not serve.
     *
     * <p>A liveness probe is a read: it must not be able to change anything, and a client that
     * sent the wrong verb should learn that here rather than from a 200.</p>
     */
    private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"error\": \"Method Not Allowed - Use GET\"}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Allow", "GET");
        ResponseBody.send(exchange, 405, bytes);
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
     * <p>The response indicates the process is live but not ready, with coverage 0.0 to
     * signal the degraded state. The epoch is read again rather than reported as 0: what
     * realistically fails here is the catalog, and the epoch is an atomic counter that is
     * still perfectly readable in that case. A client watching the epoch for monotonicity
     * reads a 0 as a restart that lost the node's visibility history, so reporting one the
     * node has not had is worse than the missing coverage that prompted this response. Only
     * a second failure - reading the counter itself - falls back to 0.</p>
     *
     * @param exchange The HTTP exchange
     * @param latencyMs The time taken before the error occurred
     */
    private void sendDegradedResponse(HttpExchange exchange, long latencyMs) throws IOException {
        HealthStatus degraded = new HealthStatus(true, false, false, readEpochOrZero(), 0.0, latencyMs);
        sendResponse(exchange, degraded);
    }

    /**
     * The current visibility epoch, or 0 if even that cannot be read.
     *
     * @return A non-negative epoch
     */
    private long readEpochOrZero() {
        try {
            long epoch = visibilityTracker.currentEpoch();
            return Math.max(0, epoch);
        } catch (RuntimeException e) {
            LOGGER.debug("Could not read the visibility epoch for the degraded response: {}",
                e.toString());
            return 0;
        }
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
