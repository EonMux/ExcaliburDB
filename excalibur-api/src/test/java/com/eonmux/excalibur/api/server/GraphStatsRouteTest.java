package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRoutingGraphIngestSink;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.ingest.graph.RelationExtractor;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That a node serving a graph reports it through {@code /v1/stats} and {@code /metrics}.
 *
 * <p>The renderers are unit-tested against fixed figures, which proves what each writes and
 * nothing about whether the server hands them a graph to write about. These drive the socket on
 * a server assembled the way the application assembles one: the graph tenants and the ingest sink
 * are handed to the server, and the figures they hold have to come out of both routes.</p>
 */
@DisplayName("Graph stats on the wire")
class GraphStatsRouteTest {

    private static final String TOKEN = "test-bearer-token";

    private ExcaliburDBServer server;
    private GraphTenants tenants;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (tenants != null) {
            tenants.close();
        }
    }

    private ExcaliburDBServer server(Path root, boolean withGraph,
                                     NamespaceRoutingGraphIngestSink sink) throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        IndexOrchestrator indexOrchestrator = mock(IndexOrchestrator.class);
        StorageEngine storageEngine = mock(StorageEngine.class);
        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker()).thenReturn(new VisibilityTracker(catalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(indexOrchestrator.getHnswQueueDepthLimit()).thenReturn(100_000);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);

        ExcaliburDBServer built = new ExcaliburDBServer(0, indexOrchestrator, storageEngine,
            mock(QueryPipeline.class));
        built.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        if (withGraph) {
            built.withGraphSupport(tenants);
        }
        if (sink != null) {
            built.withGraphIngestSink(sink);
        }
        return built;
    }

    @Test
    @DisplayName("both routes carry the graph's figures, the ingest sink's included")
    void bothRoutesCarryTheGraph(@TempDir Path root) throws IOException {
        tenants = new GraphTenants(root, new Config(new HashMap<>()), MappingBudget.detect(), 4);
        NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(tenants,
            sourceId -> Optional.empty(), RelationExtractor.none());
        // A document whose source this node does not hold: seen, and dropped for that reason.
        sink.onDocumentPublished("gone-source:doc-1", "Ada Lovelace wrote the first program.");
        server = server(root, true, sink);
        server.start();

        JsonNode graph = new ObjectMapper().readTree(get("/v1/stats")).path("graph");
        assertThat(graph.path("namespaces").path("maxOpen").asInt()).isEqualTo(4);
        assertThat(graph.path("namespaces").path("open").asInt())
            .as("the shared namespace is held open for the life of the node")
            .isGreaterThanOrEqualTo(1);
        assertThat(graph.path("budget").path("mappingLimit").asLong()).isPositive();
        assertThat(graph.path("builds").path("running").asBoolean()).isFalse();
        assertThat(graph.path("erasure").path("outstanding").asLong()).isZero();
        assertThat(graph.path("ingest").path("documentsSeen").asLong()).isEqualTo(1);
        assertThat(graph.path("ingest").path("unresolvedDocuments").asLong()).isEqualTo(1);

        String metrics = get("/metrics");
        assertThat(metrics).contains("excalibur_graph_namespaces_open_limit 4");
        assertThat(metrics).contains("excalibur_graph_ingest_documents_total 1");
        assertThat(metrics)
            .contains("excalibur_graph_ingest_failures_total{reason=\"unresolved_source\"} 1");
        assertThat(metrics).contains("excalibur_graph_erasure_obligations 0");
        assertThat(metrics).contains("excalibur_mappings_limit");
    }

    @Test
    @DisplayName("a graph without an ingest sink reports the graph and no ingest block")
    void aGraphWithoutIngest(@TempDir Path root) throws IOException {
        tenants = new GraphTenants(root, new Config(new HashMap<>()), MappingBudget.detect(), 4);
        server = server(root, true, null);
        server.start();

        JsonNode graph = new ObjectMapper().readTree(get("/v1/stats")).path("graph");
        assertThat(graph.isMissingNode()).isFalse();
        assertThat(graph.has("ingest")).isFalse();
        assertThat(get("/metrics")).doesNotContain("excalibur_graph_ingest");
    }

    @Test
    @DisplayName("a node without a graph reports none")
    void aNodeWithoutAGraph(@TempDir Path root) throws IOException {
        server = server(root, false, null);
        server.start();

        assertThat(new ObjectMapper().readTree(get("/v1/stats")).has("graph")).isFalse();
        assertThat(get("/metrics")).doesNotContain("excalibur_graph_");
    }

    @Test
    @DisplayName("the ingest sink cannot be handed over once the server is serving")
    void theSinkIsFixedAtStart(@TempDir Path root) throws IOException {
        tenants = new GraphTenants(root, new Config(new HashMap<>()), MappingBudget.detect(), 4);
        server = server(root, true, null);
        server.start();
        NamespaceRoutingGraphIngestSink sink = new NamespaceRoutingGraphIngestSink(tenants,
            sourceId -> Optional.empty(), RelationExtractor.none());

        assertThatThrownBy(() -> server.withGraphIngestSink(sink))
            .isInstanceOf(IllegalStateException.class);
    }

    private String get(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path)
            .toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        try {
            assertThat(connection.getResponseCode()).as(path).isEqualTo(200);
            return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
