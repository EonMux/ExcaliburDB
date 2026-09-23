package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.worker.CuckooMetricsWorker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The observability surface, driven through a real socket.
 *
 * <h2>The auth posture each route is registered with, and why</h2>
 * <ul>
 *   <li>{@code /health/ready} is <b>unauthenticated</b>, and still rate limited, for exactly
 *       the reason {@code /health} is. Its consumer is infrastructure the operator often does
 *       not control - a cloud load balancer's target-group health check, a service mesh, a
 *       kubelet - and several of those cannot attach an Authorization header at all. A
 *       readiness route stricter than the liveness route beside it would be adopted by
 *       nobody, and a probe that cannot reach it reads the timeout as a dead node.</li>
 *   <li>{@code /metrics} is <b>authenticated as a read</b>. Its consumer is a scraper the
 *       operator does control, and every scraper in the ecosystem supports a bearer token in
 *       its scrape config, so the cost of requiring one is a line of configuration. The
 *       exposition carries disk capacity, corpus size, segment counts and error rates -
 *       reconnaissance an anonymous caller should not be handed. Decisively:
 *       {@code READ} degrades to open on a node with no token configured, so a development
 *       deployment still scrapes with no setup, whereas {@code PUBLIC} could never be closed
 *       on a node that does configure one.</li>
 *   <li>{@code /v1/version} is <b>authenticated as a read</b>. No probe needs it, and build
 *       identity is the first thing an attacker wants.</li>
 *   <li>{@code /v1/query/status} is <b>authenticated as a read</b>, like every other
 *       {@code /status} route; it names in-flight work.</li>
 *   <li>{@code /v1/drain} is <b>authenticated as a mutation</b>. It changes how this node
 *       presents itself to a load balancer; an anonymous caller able to drain a node is a
 *       denial of service with one request.</li>
 * </ul>
 */
@DisplayName("Observability routes")
class ObservabilityRouteTest {

    private static final String TOKEN = "test-bearer-token";

    private ExcaliburDBServer server;

    @BeforeEach
    void setUp() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        // A node with a segment in service. An empty catalog reports coverage 0.0, which
        // readiness treats the same way /health's documented `ready` field already does:
        // a node holding nothing a query can read is not ready for query traffic.
        var segment = catalog.add(1L, java.nio.file.Path.of("/tmp/observability-segment"));
        segment.transitionToIndexing();
        segment.transitionToWarming();
        segment.transitionToActive();
        IndexOrchestrator indexOrchestrator = mock(IndexOrchestrator.class);
        when(indexOrchestrator.getCuckooFpRates()).thenReturn(CuckooMetricsWorker.FpRates.NONE);
        StorageEngine storageEngine = mock(StorageEngine.class);
        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(catalog));
        when(indexOrchestrator.getCatalog()).thenReturn(catalog);
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(indexOrchestrator.isRunning()).thenReturn(true);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);
        when(storageEngine.segmentsWithoutVectorIndex()).thenReturn(Map.of());
        when(storageEngine.segmentsWithoutVectorIndexCount()).thenReturn(0);

        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine,
            mock(QueryPipeline.class));
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a node holding nothing a query can read reports itself unready")
    void anEmptyNodeIsNotReady() throws IOException {
        // The same verdict /health's `ready` field gives, with a status code a probe acts on
        // instead of a 200 it has to parse. This is the case the troubleshooting table
        // records: a fresh node that answers nothing while reporting itself healthy.
        SegmentCatalog empty = new SegmentCatalog();
        IndexOrchestrator orchestrator = mock(IndexOrchestrator.class);
        when(orchestrator.getCuckooFpRates()).thenReturn(CuckooMetricsWorker.FpRates.NONE);
        StorageEngine storage = mock(StorageEngine.class);
        EpochTickScheduler ticks = mock(EpochTickScheduler.class);
        when(orchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(empty));
        when(orchestrator.getCatalog()).thenReturn(empty);
        when(orchestrator.getEpochTickScheduler()).thenReturn(ticks);
        when(orchestrator.isRunning()).thenReturn(true);
        when(ticks.getTickIntervalMs()).thenReturn(100L);
        when(storage.getCatalog()).thenReturn(empty);

        ExcaliburDBServer fresh = new ExcaliburDBServer(0, orchestrator, storage,
            mock(QueryPipeline.class));
        fresh.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        fresh.start();
        try {
            HttpURLConnection connection = (HttpURLConnection) URI
                .create("http://127.0.0.1:" + fresh.getPort() + "/health/ready")
                .toURL().openConnection();
            assertThat(connection.getResponseCode()).isEqualTo(503);
            assertThat(new String(connection.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8)).contains("\"coverage\"");
            connection.disconnect();
        } finally {
            fresh.stop(0);
        }
    }

    @Test
    @DisplayName("the disk floor readiness uses is the configured one")
    void theDiskFloorIsConfigurable() throws IOException {
        // Every other readiness threshold is a property; this one was compiled in, so an
        // operator running volumes that sit at 80% by design had no way to say so and a node
        // that was working perfectly well reported itself out of rotation - or, with the floor
        // the other way round, kept accepting writes it could not complete.
        SegmentCatalog catalog = new SegmentCatalog();
        var segment = catalog.add(1L, java.nio.file.Path.of("/tmp/disk-floor-segment"));
        segment.transitionToIndexing();
        segment.transitionToWarming();
        segment.transitionToActive();
        IndexOrchestrator orchestrator = mock(IndexOrchestrator.class);
        when(orchestrator.getCuckooFpRates()).thenReturn(CuckooMetricsWorker.FpRates.NONE);
        StorageEngine storage = mock(StorageEngine.class);
        EpochTickScheduler ticks = mock(EpochTickScheduler.class);
        when(orchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(catalog));
        when(orchestrator.getCatalog()).thenReturn(catalog);
        when(orchestrator.getEpochTickScheduler()).thenReturn(ticks);
        when(orchestrator.isRunning()).thenReturn(true);
        when(ticks.getTickIntervalMs()).thenReturn(100L);
        when(storage.getCatalog()).thenReturn(catalog);

        // Half the capacity free: comfortably above the compiled-in 5% floor, well below the
        // 90% this node is configured to require.
        com.eonmux.excalibur.shard.allocator.VolumeManager volumes =
            mock(com.eonmux.excalibur.shard.allocator.VolumeManager.class);
        when(volumes.getAggregatedVolumeStats()).thenReturn(
            new com.eonmux.excalibur.shard.allocator.VolumeManager.VolumeStats(1000L, 500L, 500L));

        java.util.Map<String, String> properties = new java.util.HashMap<>();
        properties.put("excalibur.health.min-free-disk-ratio", "0.9");
        ExcaliburDBServer configured = new ExcaliburDBServer(0, 16, orchestrator, storage,
            volumes, new com.eonmux.excalibur.config.Config(properties), mock(QueryPipeline.class));
        configured.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        configured.start();
        try {
            HttpURLConnection connection = (HttpURLConnection) URI
                .create("http://127.0.0.1:" + configured.getPort() + "/health/ready")
                .toURL().openConnection();
            assertThat(connection.getResponseCode())
                .as("half the disk free is below the floor this node was configured with")
                .isEqualTo(503);
            assertThat(new String(connection.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8)).contains("\"disk\"");
            connection.disconnect();
        } finally {
            configured.stop(0);
        }
    }

    @Nested
    @DisplayName("Reachability")
    class Reachability {

        @Test
        @DisplayName("a probe with no credentials can read readiness")
        void readinessIsUnauthenticated() throws IOException {
            Response response = send("GET", "/health/ready", null);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).contains("\"ready\":");
        }

        @Test
        @DisplayName("a scraper is answered the exposition format")
        void metricsAreServed() throws IOException {
            Response response = send("GET", "/metrics", TOKEN);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).contains("# TYPE excalibur_coverage_ratio gauge");
        }

        @Test
        @DisplayName("the version, the query status and the drain routes answer")
        void theRestAnswer() throws IOException {
            assertThat(send("GET", "/v1/version", TOKEN).status()).isEqualTo(200);
            assertThat(send("GET", "/v1/query/status", TOKEN).status()).isEqualTo(200);
            assertThat(send("POST", "/v1/drain", TOKEN).status()).isEqualTo(200);
            assertThat(send("DELETE", "/v1/drain", TOKEN).status()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Authentication")
    class Authentication {

        @Test
        @DisplayName("refuses an anonymous scrape, an anonymous version and an anonymous drain")
        void refusesAnonymousCallers() throws IOException {
            assertThat(send("GET", "/metrics", null).status()).isEqualTo(401);
            assertThat(send("GET", "/v1/version", null).status()).isEqualTo(401);
            assertThat(send("GET", "/v1/query/status", null).status()).isEqualTo(401);
            assertThat(send("POST", "/v1/drain", null).status())
                .as("an anonymous caller must not be able to take a node out of rotation")
                .isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("Draining")
    class Draining {

        @Test
        @DisplayName("a drained node answers 503 to readiness and keeps serving everything else")
        void drainingChangesOnlyReadiness() throws IOException {
            assertThat(send("GET", "/health/ready", null).status()).isEqualTo(200);

            send("POST", "/v1/drain?reason=rolling%20restart", TOKEN);

            Response readiness = send("GET", "/health/ready", null);
            assertThat(readiness.status())
                .as("a status code a Kubernetes httpGet probe acts on")
                .isEqualTo(503);
            assertThat(readiness.body()).contains("\"drained\"");

            // Still serving. The traffic still arriving during a drain is the traffic that
            // has not noticed yet, and refusing it would cause the outage the drain avoids.
            assertThat(send("GET", "/health", null).status()).isEqualTo(200);
            assertThat(send("GET", "/v1/stats", TOKEN).status()).isEqualTo(200);

            send("DELETE", "/v1/drain", TOKEN);
            assertThat(send("GET", "/health/ready", null).status()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a path that merely starts with one of these names is not that route")
    void prefixesAreNotTheRoute() throws IOException {
        assertThat(send("GET", "/metricsfoo", TOKEN).status()).isEqualTo(404);
        assertThat(send("GET", "/health/readyz", null).status()).isEqualTo(404);
        assertThat(send("GET", "/v1/versions", TOKEN).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("counts the responses it has just written")
    void countsItsOwnResponses() throws IOException {
        send("GET", "/health", null);
        send("GET", "/metrics", null);

        // Both are visible in the scrape: the choke point counts the refusal as well as the
        // success, which is the whole point of counting there.
        String body = send("GET", "/metrics", TOKEN).body();
        assertThat(body).contains("excalibur_http_responses_total{class=\"2xx\"}");
        assertThat(body).contains("excalibur_http_rejections_total{reason=\"unauthenticated\"}");
    }

    private record Response(int status, String body) {
    }

    private Response send(String method, String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path)
            .toURL().openConnection();
        connection.setRequestMethod(method);
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, body);
    }
}
