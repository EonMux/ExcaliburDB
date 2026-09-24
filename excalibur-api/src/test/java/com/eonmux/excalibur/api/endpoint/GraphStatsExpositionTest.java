package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRoutingGraphIngestSink;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.ProcessResourceUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The graph half of a node, as {@code /v1/stats} and {@code /metrics} report it.
 *
 * <p>Neither route said anything about the graph. A node evicting a tenant on every request, a
 * node whose ingestion had been dropping every document's facts, and a node past its erasure
 * deadline all looked, from outside, like a healthy one - every one of those figures was counted
 * somewhere, and nowhere reported.</p>
 *
 * <p>These tests fix the figures at known values and read them back out of both renderings, so
 * what they pin is the mapping from each source to each field and series, and that an unknown
 * figure is reported as unknown rather than as a zero an alert would trust.</p>
 */
@DisplayName("Graph stats exposition")
class GraphStatsExpositionTest {

    /** Stands in for a process that has mapped and opened a known amount. */
    private record FixedProcess(OptionalLong mappings, OptionalLong descriptors)
            implements ProcessResourceUsage {
    }

    private GraphTenants tenants;
    private GraphBuildRunner builds;
    private GraphAutoBuilder autoBuilds;
    private NamespaceRoutingGraphIngestSink sink;
    private NodeStatsCollector collector;

    @BeforeEach
    void setUp() throws IOException {
        SegmentCatalog catalog = new SegmentCatalog();
        IndexOrchestrator orchestrator = mock(IndexOrchestrator.class);
        StorageEngine storage = mock(StorageEngine.class);
        when(orchestrator.getVisibilityTracker()).thenReturn(new VisibilityTracker(catalog));
        when(orchestrator.getHnswQueueDepthLimit()).thenReturn(100_000);
        when(storage.getCatalog()).thenReturn(catalog);
        when(storage.segmentsWithoutVectorIndex()).thenReturn(Map.of());
        collector = new NodeStatsCollector(orchestrator, storage, null);

        MappingBudget budget = MappingBudget.of(1_000, 0.7, 500,
            new FixedProcess(OptionalLong.of(400), OptionalLong.of(120)));
        budget.acquire(30, 6);

        tenants = mock(GraphTenants.class);
        when(tenants.openCount()).thenReturn(2);
        when(tenants.maxOpenNamespaces()).thenReturn(8);
        when(tenants.evictionCount()).thenReturn(5L);
        when(tenants.budget()).thenReturn(budget);
        when(tenants.erasureBacklog()).thenReturn(new GraphTenants.ErasureBacklog(3, 7, 1, 1,
            Optional.of(Instant.now().minus(Duration.ofMinutes(90))), Duration.ofHours(1)));

        builds = mock(GraphBuildRunner.class);
        when(builds.counts()).thenReturn(new GraphBuildRunner.Counts(true, 4, 2, 1));

        autoBuilds = mock(GraphAutoBuilder.class);
        when(autoBuilds.enabled()).thenReturn(true);
        when(autoBuilds.counts()).thenReturn(new GraphAutoBuilder.Counts(2, 11, 3, 6, 1, 8));

        sink = mock(NamespaceRoutingGraphIngestSink.class);
        when(sink.documentsSeen()).thenReturn(100L);
        when(sink.factsStaged()).thenReturn(640L);
        when(sink.unresolvedDocuments()).thenReturn(3L);
        when(sink.extractionFailures()).thenReturn(9L);
        when(sink.tenantFailures()).thenReturn(2L);
        when(sink.withdrawalFailures()).thenReturn(5L);
    }

    /** A reader over every source, on a node whose ingestion stages facts. */
    private GraphStatsReader reader() {
        return new GraphStatsReader(tenants, builds, autoBuilds, sink, () -> 4L);
    }

    /** A reader on a node whose ingestion stages nothing. */
    private GraphStatsReader readerWithoutIngest() {
        return new GraphStatsReader(tenants, builds, autoBuilds, null, null);
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("reads each block from the component that owns it")
        void readsEveryBlock() {
            GraphStats stats = reader().read();

            assertThat(stats.namespaces()).isEqualTo(new GraphStats.NamespaceStats(2, 8, 5));
            assertThat(stats.budget()).isEqualTo(new GraphStats.BudgetStats(30,
                OptionalLong.of(400), 700, 1_000, true, 6, OptionalLong.of(120), 350, 500));
            assertThat(stats.builds()).isEqualTo(new GraphStats.BuildStats(true, 4, 2, 1));
            assertThat(stats.automaticBuilds())
                .isEqualTo(new GraphStats.AutomaticBuildStats(true, 2, 11, 3, 6, 1, 8));
            assertThat(stats.erasure().namespacesOwing()).isEqualTo(3);
            assertThat(stats.erasure().outstanding()).isEqualTo(7);
            assertThat(stats.erasure().overdueNamespaces()).isEqualTo(1);
            assertThat(stats.erasure().unreadableNamespaces()).isEqualTo(1);
            assertThat(stats.erasure().oldestAgeMs().getAsLong())
                .isGreaterThanOrEqualTo(Duration.ofMinutes(90).toMillis());
            assertThat(stats.ingest()).isEqualTo(new GraphStats.IngestStats(100, 640, 3, 9, 2, 4, 5));
        }

        @Test
        @DisplayName("an erasure backlog that could not be read is absent, never zero")
        void unreadableBacklogIsAbsent() throws IOException {
            when(tenants.erasureBacklog()).thenThrow(new IOException("root unreadable"));

            GraphStats stats = reader().read();

            assertThat(stats.erasure()).isNull();
            assertThat(stats.namespaces()).isNotNull();
        }

        @Test
        @DisplayName("a node whose ingestion stages nothing has no ingest block")
        void noSinkNoIngest() {
            assertThat(readerWithoutIngest().read().ingest()).isNull();
        }

        @Test
        @DisplayName("nothing owed is an empty age, not an age of zero")
        void nothingOwedHasNoAge() throws IOException {
            when(tenants.erasureBacklog()).thenReturn(new GraphTenants.ErasureBacklog(0, 0, 0, 0,
                Optional.empty(), Duration.ofHours(1)));

            GraphStats stats = reader().read();

            assertThat(stats.erasure().oldestAgeMs()).isEmpty();
        }

        @Test
        @DisplayName("needs the tenants and the builds")
        void requiresItsSources() {
            assertThatThrownBy(() ->
                    new GraphStatsReader(null, builds, autoBuilds, sink, () -> 4L))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                    new GraphStatsReader(tenants, null, autoBuilds, sink, () -> 4L))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                    new GraphStatsReader(tenants, builds, null, sink, () -> 4L))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                    new GraphStatsReader(tenants, builds, autoBuilds, sink, null))
                .as("a node that stages facts must say where refusals are counted")
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a graph that fails to report leaves the rest of the stats standing")
        void aFailingGraphIsAbsent() {
            when(tenants.budget()).thenThrow(new IllegalStateException("closed"));
            collector.withGraph(reader());

            NodeStats stats = collector.collect();

            assertThat(stats.graph()).isNull();
            assertThat(stats.segments()).isNotNull();
        }
    }

    @Nested
    @DisplayName("as JSON")
    class Json {

        @Test
        @DisplayName("carries every block, under the names the docs give them")
        void rendersEveryBlock() throws IOException {
            collector.withGraph(reader());

            JsonNode graph = stats().path("graph");

            assertThat(graph.path("namespaces").path("open").asInt()).isEqualTo(2);
            assertThat(graph.path("namespaces").path("maxOpen").asInt()).isEqualTo(8);
            assertThat(graph.path("namespaces").path("evictions").asLong()).isEqualTo(5);

            JsonNode budget = graph.path("budget");
            assertThat(budget.path("graphMappings").asLong()).isEqualTo(30);
            assertThat(budget.path("processMappings").asLong()).isEqualTo(400);
            assertThat(budget.path("mappingHighWater").asLong()).isEqualTo(700);
            assertThat(budget.path("mappingLimit").asLong()).isEqualTo(1_000);
            assertThat(budget.path("limitRead").asBoolean()).isTrue();
            assertThat(budget.path("graphDescriptors").asLong()).isEqualTo(6);
            assertThat(budget.path("processDescriptors").asLong()).isEqualTo(120);
            assertThat(budget.path("descriptorHighWater").asLong()).isEqualTo(350);
            assertThat(budget.path("descriptorLimit").asLong()).isEqualTo(500);

            JsonNode buildBlock = graph.path("builds");
            assertThat(buildBlock.path("running").asBoolean()).isTrue();
            assertThat(buildBlock.path("completed").asLong()).isEqualTo(4);
            assertThat(buildBlock.path("failed").asLong()).isEqualTo(2);
            assertThat(buildBlock.path("cancelled").asLong()).isEqualTo(1);

            JsonNode automatic = graph.path("automaticBuilds");
            assertThat(automatic.path("enabled").asBoolean()).isTrue();
            assertThat(automatic.path("running").asInt()).isEqualTo(2);
            assertThat(automatic.path("completed").asLong()).isEqualTo(11);
            assertThat(automatic.path("unchanged").asLong()).isEqualTo(3);
            assertThat(automatic.path("failed").asLong()).isEqualTo(6);
            assertThat(automatic.path("cancelled").asLong()).isEqualTo(1);
            assertThat(automatic.path("superseded").asLong()).isEqualTo(8);

            JsonNode erasure = graph.path("erasure");
            assertThat(erasure.path("namespacesOwing").asInt()).isEqualTo(3);
            assertThat(erasure.path("outstanding").asLong()).isEqualTo(7);
            assertThat(erasure.path("overdueNamespaces").asInt()).isEqualTo(1);
            assertThat(erasure.path("unreadableNamespaces").asInt()).isEqualTo(1);
            assertThat(erasure.path("oldestAgeMs").asLong())
                .isGreaterThanOrEqualTo(Duration.ofMinutes(90).toMillis());

            JsonNode ingest = graph.path("ingest");
            assertThat(ingest.path("documentsSeen").asLong()).isEqualTo(100);
            assertThat(ingest.path("factsStaged").asLong()).isEqualTo(640);
            assertThat(ingest.path("unresolvedDocuments").asLong()).isEqualTo(3);
            assertThat(ingest.path("extractionFailures").asLong()).isEqualTo(9);
            assertThat(ingest.path("tenantFailures").asLong()).isEqualTo(2);
            assertThat(ingest.path("sinkRefusals").asLong()).isEqualTo(4);
            assertThat(ingest.path("withdrawalFailures").asLong()).isEqualTo(5);
        }

        @Test
        @DisplayName("automatic builds turned off still report, as disabled")
        void automaticBuildsDisabled() throws IOException {
            when(autoBuilds.enabled()).thenReturn(false);
            when(autoBuilds.counts()).thenReturn(new GraphAutoBuilder.Counts(0, 0, 0, 0, 0, 0));
            collector.withGraph(reader());

            JsonNode automatic = stats().path("graph").path("automaticBuilds");

            assertThat(automatic.path("enabled").asBoolean()).isFalse();
            assertThat(automatic.path("completed").asLong()).isZero();
        }

        @Test
        @DisplayName("an unknown figure is null, and an absent block is missing")
        void unknownIsNull() throws IOException {
            MappingBudget unmeasured = MappingBudget.of(1_000, 0.7, 0);
            when(tenants.budget()).thenReturn(unmeasured);
            when(tenants.erasureBacklog()).thenReturn(new GraphTenants.ErasureBacklog(0, 0, 0, 0,
                Optional.empty(), Duration.ofHours(1)));
            collector.withGraph(readerWithoutIngest());

            JsonNode graph = stats().path("graph");

            assertThat(graph.path("budget").path("processMappings").isNull()).isTrue();
            assertThat(graph.path("budget").path("processDescriptors").isNull()).isTrue();
            assertThat(graph.path("erasure").path("oldestAgeMs").isNull()).isTrue();
            assertThat(graph.has("ingest")).isFalse();
        }

        @Test
        @DisplayName("a node without a graph has no graph block")
        void noGraphNoBlock() throws IOException {
            assertThat(stats().has("graph")).isFalse();
        }

        private JsonNode stats() throws IOException {
            return new ObjectMapper().readTree(send(new StatsEndpoint(collector), "/v1/stats"));
        }
    }

    @Nested
    @DisplayName("as Prometheus text")
    class Prometheus {

        @Test
        @DisplayName("exposes every figure as a series, totalled across namespaces")
        void exposesEverySeries() throws IOException {
            collector.withGraph(reader());

            String body = scrape();

            assertThat(body).contains(
                "excalibur_graph_namespaces_open 2",
                "excalibur_graph_namespaces_open_limit 8",
                "excalibur_graph_namespace_evictions_total 5",
                "excalibur_graph_build_running 1",
                "excalibur_graph_builds_total{outcome=\"completed\"} 4",
                "excalibur_graph_builds_total{outcome=\"failed\"} 2",
                "excalibur_graph_builds_total{outcome=\"cancelled\"} 1",
                "excalibur_graph_auto_build_enabled 1",
                "excalibur_graph_auto_builds_running 2",
                "excalibur_graph_auto_builds_total{outcome=\"completed\"} 11",
                "excalibur_graph_auto_builds_total{outcome=\"unchanged\"} 3",
                "excalibur_graph_auto_builds_total{outcome=\"failed\"} 6",
                "excalibur_graph_auto_builds_total{outcome=\"cancelled\"} 1",
                "excalibur_graph_auto_builds_total{outcome=\"superseded\"} 8",
                "excalibur_mappings{scope=\"graphs\"} 30",
                "excalibur_mappings{scope=\"process\"} 400",
                "excalibur_mappings_high_water 700",
                "excalibur_mappings_limit 1000",
                "excalibur_descriptors{scope=\"graphs\"} 6",
                "excalibur_descriptors{scope=\"process\"} 120",
                "excalibur_descriptors_high_water 350",
                "excalibur_descriptors_limit 500",
                "excalibur_graph_erasure_obligations 7",
                "excalibur_graph_erasure_namespaces{state=\"owing\"} 3",
                "excalibur_graph_erasure_namespaces{state=\"overdue\"} 1",
                "excalibur_graph_erasure_namespaces{state=\"unreadable\"} 1",
                "excalibur_graph_ingest_documents_total 100",
                "excalibur_graph_ingest_facts_staged_total 640",
                "excalibur_graph_ingest_failures_total{reason=\"unresolved_source\"} 3",
                "excalibur_graph_ingest_failures_total{reason=\"extraction\"} 9",
                "excalibur_graph_ingest_failures_total{reason=\"namespace_unavailable\"} 2",
                "excalibur_graph_ingest_failures_total{reason=\"sink_refused\"} 4",
                "excalibur_graph_ingest_withdrawal_failures_total 5");
            double age = body.lines()
                .filter(line -> line.startsWith("excalibur_graph_erasure_oldest_age_seconds "))
                .mapToDouble(line -> Double.parseDouble(line.split(" ")[1]))
                .findFirst().orElseThrow();
            assertThat(age).isGreaterThanOrEqualTo(90 * 60);
        }

        @Test
        @DisplayName("no series carries a namespace, so the series count is fixed")
        void noNamespaceLabel() throws IOException {
            collector.withGraph(reader());

            assertThat(scrape()).doesNotContain("namespace=");
        }

        @Test
        @DisplayName("omits what it cannot measure rather than exporting a zero")
        void omitsTheUnknown() throws IOException {
            when(tenants.budget()).thenReturn(MappingBudget.of(1_000, 0.7, 0));
            collector.withGraph(readerWithoutIngest());

            String body = scrape();

            assertThat(body).contains("excalibur_mappings{scope=\"graphs\"} 0");
            assertThat(body).doesNotContain("scope=\"process\"");
            assertThat(body).doesNotContain("excalibur_descriptors_limit");
            assertThat(body).doesNotContain("excalibur_descriptors_high_water");
            assertThat(body).doesNotContain("excalibur_graph_ingest");
        }

        @Test
        @DisplayName("omits the erasure series when the backlog could not be read")
        void omitsAnUnreadableBacklog() throws IOException {
            when(tenants.erasureBacklog()).thenThrow(new IOException("root unreadable"));
            collector.withGraph(reader());

            assertThat(scrape()).doesNotContain("excalibur_graph_erasure");
        }

        @Test
        @DisplayName("a node without a graph exposes no graph series")
        void noGraphNoSeries() throws IOException {
            String body = scrape();

            assertThat(body).doesNotContain("excalibur_graph_");
            assertThat(body).doesNotContain("excalibur_mappings");
        }

        @Test
        @DisplayName("still declares each family once and writes every sample well formed")
        void staysWellFormed() throws IOException {
            collector.withGraph(reader());

            String body = scrape();

            List<String> declared = body.lines()
                .filter(line -> line.startsWith("# TYPE "))
                .map(line -> line.split(" ")[2])
                .toList();
            assertThat(declared).doesNotHaveDuplicates();
            assertThat(body.lines().filter(line -> !line.startsWith("#")))
                .allMatch(line -> line.matches(
                    "[a-zA-Z_:][a-zA-Z0-9_:]*(\\{[^}]*})? -?([0-9.eE+]+|NaN|\\+Inf|-Inf)"));
        }

        private String scrape() throws IOException {
            return send(new MetricsEndpoint(collector, new QueryRegistry(),
                new ServiceReadiness()), "/metrics");
        }
    }

    @Test
    @DisplayName("every block refuses a negative figure or a missing one")
    void blocksValidate() {
        GraphStats.NamespaceStats namespaces = new GraphStats.NamespaceStats(0, 0, 0);
        GraphStats.BudgetStats budget = new GraphStats.BudgetStats(0, OptionalLong.empty(), 0, 1,
            false, 0, OptionalLong.empty(), 0, 0);
        GraphStats.BuildStats buildStats = new GraphStats.BuildStats(false, 0, 0, 0);
        GraphStats.AutomaticBuildStats automatic =
            new GraphStats.AutomaticBuildStats(true, 0, 0, 0, 0, 0, 0);

        assertThatThrownBy(() -> new GraphStats(null, budget, buildStats, automatic, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphStats(namespaces, null, buildStats, automatic, null,
            null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphStats(namespaces, budget, null, automatic, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphStats(namespaces, budget, buildStats, null, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphStats.AutomaticBuildStats(true, -1, 0, 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphStats.AutomaticBuildStats(true, 0, 0, 0, 0, 0, -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphStats.NamespaceStats(-1, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphStats.NamespaceStats(0, 0, -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphStats.BudgetStats(-1, OptionalLong.empty(), 0, 1,
            false, 0, OptionalLong.empty(), 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphStats.BudgetStats(0, null, 0, 1,
            false, 0, OptionalLong.empty(), 0, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphStats.BudgetStats(0, OptionalLong.empty(), 0, 1,
            false, 0, null, 0, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphStats.BuildStats(false, 0, -1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphStats.ErasureStats(0, -1, 0, 0, OptionalLong.empty()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphStats.ErasureStats(0, 0, 0, 0, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphStats.IngestStats(0, 0, 0, -1, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static String send(com.sun.net.httpserver.HttpHandler handler, String path)
            throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(out);
        handler.handle(exchange);
        org.mockito.Mockito.verify(exchange).sendResponseHeaders(
            org.mockito.ArgumentMatchers.eq(200), anyLong());
        return out.toString(StandardCharsets.UTF_8);
    }
}
