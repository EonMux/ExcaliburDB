package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * HTTP endpoint for {@code GET /health/ready}: can this node serve?
 *
 * <h2>Why this is not {@code /health}</h2>
 * <p>{@code /health} answers 200 whatever it finds - on its degraded path as well - and its
 * {@code ready} field is derived from coverage alone. Coverage counts indexing, warming and
 * compacting segments as queryable, so it reads 1.0000 the moment the catalog is recovered and
 * long before anything can be searched; the project's own troubleshooting table records
 * exactly that, a fresh node returning nothing while reporting ready and healthy. The
 * documented workaround was to read the field rather than the status code, which no standard
 * Kubernetes {@code httpGet} probe, and no cloud load balancer health check, can do.</p>
 *
 * <p>So this is a second route rather than a change to the first: {@code /health} keeps the
 * shape it is documented with and the clients that already parse it, and readiness gets a
 * status code an unmodified probe acts on - 200 to stay in rotation, 503 to leave it.</p>
 *
 * <h2>What it actually checks</h2>
 * <ol>
 *   <li><b>drained</b> - an operator asked, through {@code /v1/drain}. First, because it is
 *       the only check that is a decision rather than a measurement, and an operator draining
 *       a node expects it out of rotation whatever else is true of it.</li>
 *   <li><b>indexer</b> - the worker that drains the HNSW queue is alive. If it dies the queue
 *       grows without bound while coverage stays at one and {@code /health} stays healthy, so
 *       nothing else on this node would ever report it.</li>
 *   <li><b>disk</b> - the volumes have room left. Measured since the volume manager was
 *       written and reported on {@code /v1/stats}, but never connected to anything: a node
 *       that cannot allocate a replacement segment cannot compact and cannot reindex, and
 *       will begin failing writes whatever its coverage says.</li>
 *   <li><b>coverage</b> - the share of live segments a query reads is above the configured
 *       readiness threshold. Kept, because it is the check the threshold was added for, but
 *       last: it is the weakest of the four.</li>
 * </ol>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * HTTP/1.1 503 Service Unavailable
 *
 * {
 *   "ready": false,
 *   "failed": ["drained"],
 *   "draining": true,
 *   "coverage": 1.0000,
 *   "indexerRunning": true,
 *   "diskAvailableRatio": 0.4200
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every check is read through a thread-safe supplier.</p>
 *
 * @author ExcaliburDB
 */
public final class ReadinessEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadinessEndpoint.class);

    /**
     * The share of volume capacity below which this node stops calling itself ready.
     *
     * <p>Deliberately small. It is not a "disk is filling up" warning - that is what the
     * {@code volumes} block on {@code /v1/stats} is for - it is the point past which the node
     * can no longer be relied on to write the replacement segment a compaction or a rolling
     * reindex produces, and taking it out of rotation is then better than letting it accept
     * writes it will fail.</p>
     *
     * <p>What a node started without a configuration file uses. A deployment sets
     * {@code excalibur.health.min-free-disk-ratio} instead; the two are one number, declared in
     * the configuration layer so that the default and the key cannot drift apart.</p>
     */
    public static final double DEFAULT_MIN_FREE_DISK_RATIO =
        com.eonmux.excalibur.config.HealthConfig.DEFAULT_MIN_FREE_DISK_RATIO;

    private final ServiceReadiness readiness;
    private final DoubleSupplier coverage;
    private final BooleanSupplier indexerRunning;
    private final DoubleSupplier freeDiskRatio;
    private final double readinessThreshold;
    private final double minFreeDiskRatio;

    /**
     * Creates the endpoint over the four things readiness is decided from.
     *
     * @param readiness          The operator-controlled drain flag
     * @param coverage           The share of live segments a query reads, in [0, 1]
     * @param indexerRunning     Whether the index orchestrator is alive
     * @param freeDiskRatio      The share of volume capacity still free, in [0, 1]
     * @param readinessThreshold The coverage at or above which this node is ready
     * @param minFreeDiskRatio   The free share below which this node is not ready
     * @throws NullPointerException     if any collaborator is null
     * @throws IllegalArgumentException if a threshold is outside [0, 1]
     */
    public ReadinessEndpoint(ServiceReadiness readiness,
                             DoubleSupplier coverage,
                             BooleanSupplier indexerRunning,
                             DoubleSupplier freeDiskRatio,
                             double readinessThreshold,
                             double minFreeDiskRatio) {
        this.readiness = Objects.requireNonNull(readiness, "readiness cannot be null");
        this.coverage = Objects.requireNonNull(coverage, "coverage cannot be null");
        this.indexerRunning =
            Objects.requireNonNull(indexerRunning, "indexerRunning cannot be null");
        this.freeDiskRatio =
            Objects.requireNonNull(freeDiskRatio, "freeDiskRatio cannot be null");
        this.readinessThreshold = requireRatio(readinessThreshold, "readinessThreshold");
        this.minFreeDiskRatio = requireRatio(minFreeDiskRatio, "minFreeDiskRatio");
    }

    private static double requireRatio(double value, String name) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0.0, 1.0]: " + value);
        }
        return value;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // GET, like /health and like every other read route. A probe that is allowed to
        // change something is not a probe.
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            ResponseBody.sendError(exchange, 405, "Method Not Allowed - Use GET");
            return;
        }
        try {
            Verdict verdict = evaluate();
            ResponseBody.sendJson(exchange, verdict.failed().isEmpty() ? 200 : 503,
                toJson(verdict));
        } catch (RuntimeException e) {
            // A node whose own state cannot be read is exactly a node that should leave the
            // rotation, so this is a 503 rather than a 500. A probe treats a 500 from a
            // readiness route as "the route is broken" and keeps sending traffic.
            LOGGER.error("Readiness could not be computed, reporting unready: {}",
                e.getMessage(), e);
            ResponseBody.sendJson(exchange, 503,
                "{\"ready\": false, \"failed\": [\"unreadable\"]}");
        }
    }

    /**
     * What this node currently reports about itself.
     *
     * @param failed             Every check not satisfied, in the order they are evaluated
     * @param draining           Whether an operator has drained the node
     * @param coverage           The coverage measured, in [0, 1]
     * @param indexerRunning     Whether the index orchestrator is alive
     * @param diskAvailableRatio The share of volume capacity still free, in [0, 1]
     */
    private record Verdict(List<String> failed, boolean draining, double coverage,
                           boolean indexerRunning, double diskAvailableRatio) {
    }

    /**
     * Runs the four checks.
     *
     * @return The verdict, with every failed check named
     */
    private Verdict evaluate() {
        boolean draining = readiness.isDraining();
        boolean indexerAlive = indexerRunning.getAsBoolean();
        double free = clampRatio(freeDiskRatio.getAsDouble());
        double queryable = clampRatio(coverage.getAsDouble());

        List<String> failed = new ArrayList<>(4);
        if (draining) {
            failed.add("drained");
        }
        if (!indexerAlive) {
            failed.add("indexer");
        }
        if (free < minFreeDiskRatio) {
            failed.add("disk");
        }
        if (queryable < readinessThreshold) {
            failed.add("coverage");
        }
        return new Verdict(failed, draining, queryable, indexerAlive, free);
    }

    /**
     * Holds a ratio inside the range the thresholds are expressed in.
     *
     * <p>A NaN maps to zero rather than through the comparison, where it would compare false
     * against every threshold and make an unmeasurable node read as ready.</p>
     *
     * @param value A measured ratio
     * @return The value in [0, 1]
     */
    private static double clampRatio(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, value));
    }

    /**
     * Renders a verdict.
     *
     * <p>The measurements are reported beside the verdict, not instead of it: a 503 that only
     * says "not ready" sends an operator to three other endpoints to find out why.</p>
     *
     * @param verdict What the checks found
     * @return The response body
     */
    private static String toJson(Verdict verdict) {
        StringBuilder json = new StringBuilder(192);
        json.append("{\"ready\": ").append(verdict.failed().isEmpty())
            .append(", \"failed\": [");
        for (int i = 0; i < verdict.failed().size(); i++) {
            json.append(i == 0 ? "" : ", ")
                .append('"').append(JsonText.escape(verdict.failed().get(i))).append('"');
        }
        return json.append("], \"draining\": ").append(verdict.draining())
            .append(", \"coverage\": ").append(JsonText.number(verdict.coverage(), 4))
            .append(", \"indexerRunning\": ").append(verdict.indexerRunning())
            .append(", \"diskAvailableRatio\": ")
            .append(JsonText.number(verdict.diskAvailableRatio(), 4))
            .append('}').toString();
    }
}
