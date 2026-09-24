package com.eonmux.excalibur.integration;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.api.endpoint.HealthEndpoint;
import com.eonmux.excalibur.api.endpoint.ReadinessEndpoint;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.HealthConfig;
import com.eonmux.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for health configuration options.
 *
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Health thresholds from configuration are actually used by HealthEndpoint</li>
 *   <li>Custom readiness and healthy thresholds affect health status correctly</li>
 *   <li>Config values loaded from properties/environment work end-to-end</li>
 *   <li>Domain correctness: thresholds in [0.0, 1.0], coverage in [0.0, 1.0]</li>
 * </ul>
 *
 * <h2>Health Configuration Options</h2>
 * <p>This test validates that health configuration options from excalibur.properties
 * or environment variables are correctly applied to the health endpoint behavior.</p>
 */
class HealthConfigurationIntegrationTest {

    private SegmentCatalog segmentCatalog;
    private VisibilityTracker visibilityTracker;
    private IndexOrchestrator mockOrchestrator;

    @BeforeEach
    void setUp() {
        segmentCatalog = new SegmentCatalog();
        visibilityTracker = new VisibilityTracker(segmentCatalog);
        mockOrchestrator = mock(IndexOrchestrator.class);
        when(mockOrchestrator.getVisibilityTracker()).thenReturn(visibilityTracker);
    }

    @Nested
    @DisplayName("Config-driven health thresholds")
    class ConfigDrivenThresholds {

        @Test
        @DisplayName("Config readiness threshold affects health status")
        void configReadinessThresholdAffectsHealthStatus() throws IOException {
            // Create config with custom readiness threshold of 0.8
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "0.8");
            props.put("excalibur.health.healthy-threshold", "0.9");
            Config config = new Config(props);

            // Add segments: 5 a query reads, 5 stranded -> coverage = 5/10 = 0.5
            for (int i = 1; i <= 5; i++) {
                addActiveSegment(segmentCatalog, i);
            }
            for (int i = 6; i <= 10; i++) {
                addStrandedSegment(segmentCatalog, i);
            }

            // Create HealthEndpoint with config values
            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker,
                segmentCatalog,
                config.health().getReadinessThreshold(),
                config.health().getHealthyThreshold(),
                config.health().getMaxLatencyMs()
            );

            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // coverage = 0.5 < 0.8 (readiness threshold from config)
            assertThat(response).contains("\"ready\": false");
            // coverage = 0.5 < 0.9 (healthy threshold from config)
            assertThat(response).contains("\"healthy\": false");
        }

        @Test
        @DisplayName("Config healthy threshold affects health status independently")
        void configHealthyThresholdAffectsHealthStatus() throws IOException {
            // Create config with custom thresholds
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "0.4");
            props.put("excalibur.health.healthy-threshold", "0.8");
            Config config = new Config(props);

            // Add segments: 5 a query reads, 5 stranded -> coverage = 5/10 = 0.5
            for (int i = 1; i <= 5; i++) {
                addActiveSegment(segmentCatalog, i);
            }
            for (int i = 6; i <= 10; i++) {
                addStrandedSegment(segmentCatalog, i);
            }

            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker,
                segmentCatalog,
                config.health().getReadinessThreshold(),
                config.health().getHealthyThreshold(),
                config.health().getMaxLatencyMs()
            );

            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // coverage = 0.5 >= 0.4 (readiness threshold)
            assertThat(response).contains("\"ready\": true");
            // coverage = 0.5 < 0.8 (healthy threshold)
            assertThat(response).contains("\"healthy\": false");
        }

        @Test
        @DisplayName("Config values are actually used (not hardcoded defaults)")
        void configValuesActuallyUsed() throws IOException {
            // Create config with NON-DEFAULT values
            Map<String, String> props = new HashMap<>();
            // Default readiness is 0.5, we use 0.3
            props.put("excalibur.health.readiness-threshold", "0.3");
            // Default healthy is 0.9, we use 0.4
            props.put("excalibur.health.healthy-threshold", "0.4");
            Config config = new Config(props);

            // Verify config values are NOT defaults
            assertThat(config.health().getReadinessThreshold()).isEqualTo(0.3);
            assertThat(config.health().getHealthyThreshold()).isEqualTo(0.4);

            // Add segments: 3 a query reads, 2 stranded -> coverage = 3/5 = 0.6
            for (int i = 1; i <= 3; i++) {
                addActiveSegment(segmentCatalog, i);
            }
            for (int i = 4; i <= 5; i++) {
                addStrandedSegment(segmentCatalog, i);
            }

            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker,
                segmentCatalog,
                config.health().getReadinessThreshold(),
                config.health().getHealthyThreshold(),
                config.health().getMaxLatencyMs()
            );

            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // coverage = 0.6 >= 0.3 (custom readiness)
            assertThat(response).contains("\"ready\": true");
            // coverage = 0.6 >= 0.4 (custom healthy)
            assertThat(response).contains("\"healthy\": true");
        }

        @Test
        @DisplayName("Boundary values work correctly (0.0 and 1.0)")
        void boundaryValuesWorkCorrectly() throws IOException {
            // Test with thresholds at boundaries
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "0.0");
            props.put("excalibur.health.healthy-threshold", "1.0");
            Config config = new Config(props);

            // Empty catalog -> coverage = 0.0
            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker,
                segmentCatalog,
                config.health().getReadinessThreshold(),
                config.health().getHealthyThreshold(),
                config.health().getMaxLatencyMs()
            );

            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // coverage = 0.0 >= 0.0 (readiness)
            assertThat(response).contains("\"ready\": true");
            // coverage = 0.0 < 1.0 (healthy)
            assertThat(response).contains("\"healthy\": false");
        }
    }

    @Nested
    @DisplayName("Domain correctness validation")
    class DomainCorrectnessValidation {

        @Test
        @DisplayName("Coverage is clamped to [0.0, 1.0]")
        void coverageClampedToValidRange() throws IOException {
            // Add only tombstoned segments -> should result in 0.0 coverage
            for (int i = 1; i <= 5; i++) {
                addTombstonedSegment(segmentCatalog, i);
            }

            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker, segmentCatalog, 0.5, 0.9, 5000);

            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // Coverage should be 0.0 (all segments tombstoned)
            assertThat(response).contains("\"coverage\": 0.0000");
            assertThat(response).contains("\"ready\": false");
            assertThat(response).contains("\"healthy\": false");
        }

        @Test
        @DisplayName("Health status with various segment distributions")
        void healthStatusWithVariousSegmentDistributions() throws IOException {
            // Test different coverage levels and verify thresholds work correctly

            // 25% coverage: 1 a query reads, 3 stranded
            SegmentCatalog catalog25 = new SegmentCatalog();
            VisibilityTracker tracker25 = new VisibilityTracker(catalog25);
            addActiveSegment(catalog25, 1);
            for (int i = 2; i <= 4; i++) {
                addStrandedSegment(catalog25, i);
            }

            HealthEndpoint endpoint25 = new HealthEndpoint(tracker25, catalog25, 0.2, 0.3, 5000);
            HttpExchange exchange25 = createMockExchange("GET");
            ByteArrayOutputStream baos25 = new ByteArrayOutputStream();
            when(exchange25.getResponseBody()).thenReturn(baos25);
            endpoint25.handle(exchange25);
            String response25 = baos25.toString(StandardCharsets.UTF_8);
            // coverage = 0.25 >= 0.2 -> ready=true, >= 0.3 -> healthy=false
            assertThat(response25).contains("\"ready\": true");
            assertThat(response25).contains("\"healthy\": false");

            // 75% coverage: 3 a query reads, 1 stranded
            SegmentCatalog catalog75 = new SegmentCatalog();
            VisibilityTracker tracker75 = new VisibilityTracker(catalog75);
            for (int i = 1; i <= 3; i++) {
                addActiveSegment(catalog75, i);
            }
            addStrandedSegment(catalog75, 4);

            HealthEndpoint endpoint75 = new HealthEndpoint(tracker75, catalog75, 0.5, 0.8, 5000);
            HttpExchange exchange75 = createMockExchange("GET");
            ByteArrayOutputStream baos75 = new ByteArrayOutputStream();
            when(exchange75.getResponseBody()).thenReturn(baos75);
            endpoint75.handle(exchange75);
            String response75 = baos75.toString(StandardCharsets.UTF_8);
            // coverage = 0.75 >= 0.5 -> ready=true, < 0.8 -> healthy=false
            assertThat(response75).contains("\"ready\": true");
            assertThat(response75).contains("\"healthy\": false");
        }

        @Test
        @DisplayName("Latency is non-negative in health response")
        void latencyIsNonNegative() throws IOException {
            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker, segmentCatalog, 0.5, 0.9, 5000);

            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            // Extract latency value and verify it's non-negative
            assertThat(response).contains("\"latencyMs\":");
            // The latency should be 0 or a small positive number
            assertThat(response).doesNotContain("\"latencyMs\": -");
        }
    }

    @Nested
    @DisplayName("End-to-end config loading")
    class EndToEndConfigLoading {

        @Test
        @DisplayName("HealthConfig has correct defaults when no properties set")
        void configHealthHasCorrectDefaults() {
            Config config = new Config(new HashMap<>());

            // Verify defaults match HealthEndpoint constants
            assertThat(config.health().getReadinessThreshold())
                .isEqualTo(HealthEndpoint.DEFAULT_READINESS_THRESHOLD);
            assertThat(config.health().getHealthyThreshold())
                .isEqualTo(HealthEndpoint.DEFAULT_HEALTHY_THRESHOLD);
            assertThat(config.health().getMaxLatencyMs())
                .isEqualTo(HealthEndpoint.DEFAULT_MAX_LATENCY_MS);
            assertThat(config.health().getMinFreeDiskRatio())
                .as("the configured floor and the compiled-in one are one number, and a node "
                    + "started without a properties file must use the same value as one with an "
                    + "empty key")
                .isEqualTo(ReadinessEndpoint.DEFAULT_MIN_FREE_DISK_RATIO);
        }

        @Test
        @DisplayName("HealthConfig parses properties correctly")
        void configHealthParsesPropertiesCorrectly() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "0.7");
            props.put("excalibur.health.healthy-threshold", "0.95");
            props.put("excalibur.health.max-latency-ms", "3000");
            props.put("excalibur.health.min-free-disk-ratio", "0.2");

            Config config = new Config(props);

            assertThat(config.health().getReadinessThreshold()).isEqualTo(0.7);
            assertThat(config.health().getMinFreeDiskRatio()).isEqualTo(0.2);
            assertThat(config.health().getHealthyThreshold()).isEqualTo(0.95);
            assertThat(config.health().getMaxLatencyMs()).isEqualTo(3000);
        }

        @Test
        @DisplayName("Full integration: config -> HealthEndpoint -> correct health status")
        void fullIntegrationConfigToHealthEndpoint() throws IOException {
            // This is the critical test: verify that config values flow through
            // to actual health status computation

            // Set up config with specific thresholds
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "0.6");
            props.put("excalibur.health.healthy-threshold", "0.85");
            Config config = new Config(props);

            // Create segment distribution: 6 a query reads, 4 stranded -> coverage = 0.6
            for (int i = 1; i <= 6; i++) {
                addActiveSegment(segmentCatalog, i);
            }
            for (int i = 7; i <= 10; i++) {
                addStrandedSegment(segmentCatalog, i);
            }

            // Create HealthEndpoint using config values (as ExcaliburDBServer does)
            HealthEndpoint endpoint = new HealthEndpoint(
                visibilityTracker,
                segmentCatalog,
                config.health().getReadinessThreshold(),
                config.health().getHealthyThreshold(),
                config.health().getMaxLatencyMs()
            );

            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);

            // coverage = 0.6 >= 0.6 (readiness) -> AT the threshold, so ready=true
            assertThat(response).contains("\"ready\": true");
            // coverage = 0.6 < 0.85 (healthy) -> below healthy threshold
            assertThat(response).contains("\"healthy\": false");
            // Verify coverage is reported correctly
            assertThat(response).contains("\"coverage\": 0.6000");
        }
    }

    // Helper methods

    private HttpExchange createMockExchange(String method) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/health"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private void addActiveSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
    }

    /**
     * A segment whose file is registered but which no transition ever brought up: live, and in
     * no state a query reads. A retired segment is not the way to arrange a partial coverage -
     * compaction retiring a segment does not make a node less ready.
     */
    private void addStrandedSegment(SegmentCatalog catalog, long segmentId) {
        catalog.registerFile(segmentId, Path.of("/tmp/segment" + segmentId));
    }

    private void addTombstonedSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToTombstoned();
    }
}
