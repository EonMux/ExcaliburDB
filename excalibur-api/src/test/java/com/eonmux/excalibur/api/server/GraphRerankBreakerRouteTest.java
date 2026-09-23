package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.MinimalGraph;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;
import com.eonmux.excalibur.tenancy.NamespaceId;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That the breakers in front of the graph route's model calls are reported.
 *
 * <p>{@code POST /v1/graph/query} reranks its lexical candidates with the chat model and, for a
 * graph built with entity vectors, embeds the question for an exact rerank. Both calls sit behind
 * a breaker, both fall back silently when it opens - to the BM25 order, and to no vector rerank -
 * and neither breaker was readable anywhere. These drive the socket on a server assembled the way
 * the application assembles one, over tenants holding graphs on disk, so what is tested is that
 * the server finds the breakers where the tenants keep them.</p>
 *
 * <p>A build embeds too: each entity's text, for the vectors the question is compared against.
 * That call has its own node-wide breaker, and when it opens a build publishes its entities
 * without vectors - a graph that answers, but ranks by term overlap until the next build.</p>
 */
@DisplayName("Graph rerank breakers on the wire")
class GraphRerankBreakerRouteTest {

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

    private static Config config(boolean withKey) {
        Map<String, String> properties = new HashMap<>();
        if (withKey) {
            properties.put("excalibur.llm.api-key", "test-key");
            // Nothing listens here, and nothing in these tests calls it: opening a graph
            // builds the clients, it does not use them.
            properties.put("excalibur.llm.base-url", "http://127.0.0.1:9");
            // A build does call it, and must be told at once that nothing is there.
            properties.put("excalibur.llm.max-retries", "0");
        }
        return new Config(properties);
    }

    private static Path graphOf(Path root, String namespace) {
        return root.resolve(namespace).resolve(GraphTenant.GRAPH_SUBDIRECTORY);
    }

    private ExcaliburDBServer server() {
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
        built.withGraphSupport(tenants);
        return built;
    }

    private static void trip(CircuitBreaker breaker) {
        for (int i = 0; i < breaker.getFailureThreshold(); i++) {
            breaker.recordFailure();
        }
    }

    @Test
    @DisplayName("one entry each, however many namespaces are open, tripping independently")
    void bothBreakersAreReported(@TempDir Path root) throws IOException {
        MinimalGraph.write(graphOf(root, "shared"), true);
        MinimalGraph.write(graphOf(root, "acme"), true);
        MinimalGraph.write(graphOf(root, "globex"), false);
        tenants = new GraphTenants(root, config(true), MappingBudget.detect(), 4);
        tenants.borrow(NamespaceId.of("acme")).close();
        tenants.borrow(NamespaceId.of("globex")).close();
        server = server();
        server.start();

        trip(tenants.models().rerankBreaker());

        JsonNode breakers = new ObjectMapper().readTree(get("/v1/stats")).path("breakers");
        assertThat(breakers.path("graphRerank").asText()).isEqualTo("OPEN");
        assertThat(breakers.path("graphQueryEmbedding").asText())
            .as("the rerank client failed; the embedding client did not")
            .isEqualTo("CLOSED");

        String metrics = get("/metrics");
        assertThat(metrics)
            .contains("excalibur_circuit_breaker_state{breaker=\"graph_rerank\"} 2")
            .contains("excalibur_circuit_breaker_state{breaker=\"graph_query_embedding\"} 0");
        assertThat(occurrences(metrics, "breaker=\"graph_rerank\""))
            .as("three namespaces open, one breaker, one series")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a graph without entity vectors has no query embedder to report")
    void noVectorsNoEmbeddingBreaker(@TempDir Path root) throws IOException {
        MinimalGraph.write(graphOf(root, "shared"), false);
        tenants = new GraphTenants(root, config(true), MappingBudget.detect(), 4);
        server = server();
        server.start();

        JsonNode breakers = new ObjectMapper().readTree(get("/v1/stats")).path("breakers");
        assertThat(breakers.path("graphRerank").asText()).isEqualTo("CLOSED");
        assertThat(breakers.has("graphQueryEmbedding")).isFalse();
        assertThat(get("/metrics"))
            .contains("excalibur_circuit_breaker_state{breaker=\"graph_rerank\"} 0")
            .doesNotContain("breaker=\"graph_query_embedding\"");
    }

    @Test
    @DisplayName("a node with no chat model reports neither")
    void absentWithoutAKey(@TempDir Path root) throws IOException {
        MinimalGraph.write(graphOf(root, "shared"), true);
        tenants = new GraphTenants(root, config(false), MappingBudget.detect(), 4);
        server = server();
        server.start();

        JsonNode stats = new ObjectMapper().readTree(get("/v1/stats"));
        assertThat(stats.path("breakers").has("graphRerank")).isFalse();
        assertThat(stats.path("breakers").has("graphQueryEmbedding")).isFalse();
        assertThat(stats.path("breakers").has("graphBuildEmbedding")).isFalse();
        assertThat(get("/metrics"))
            .doesNotContain("breaker=\"graph_rerank\"")
            .doesNotContain("breaker=\"graph_query_embedding\"")
            .doesNotContain("breaker=\"graph_build_embedding\"");
    }

    @Test
    @DisplayName("a node that has opened no graph yet has no client, and says so")
    void absentUntilAGraphIsServed(@TempDir Path root) throws IOException {
        tenants = new GraphTenants(root, config(true), MappingBudget.detect(), 4);
        server = server();
        server.start();

        assertThat(new ObjectMapper().readTree(get("/v1/stats")).path("breakers")
            .has("graphRerank")).isFalse();

        // A graph appears - built by another process, here written directly - and the first
        // namespace to serve it brings the client into existence.
        MinimalGraph.write(graphOf(root, "acme"), false);
        tenants.borrow(NamespaceId.of("acme")).close();

        assertThat(new ObjectMapper().readTree(get("/v1/stats")).path("breakers")
            .path("graphRerank").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("the build embedding breaker appears with the first build, one for the node")
    void theBuildEmbeddingBreakerIsReported(@TempDir Path root) throws IOException {
        tenants = new GraphTenants(root, config(true), MappingBudget.detect(), 4);
        server = server();
        server.start();

        assertThat(new ObjectMapper().readTree(get("/v1/stats")).path("breakers")
            .has("graphBuildEmbedding"))
            .as("no build has embedded anything, so there is no client and no breaker")
            .isFalse();
        assertThat(get("/metrics")).doesNotContain("breaker=\"graph_build_embedding\"");

        stageAndBuild(NamespaceId.of("acme"));
        stageAndBuild(NamespaceId.of("globex"));

        JsonNode breakers = new ObjectMapper().readTree(get("/v1/stats")).path("breakers");
        assertThat(breakers.path("graphBuildEmbedding").asText())
            .as("two failed batches, under the failure threshold")
            .isEqualTo("CLOSED");

        trip(tenants.models().buildEmbeddingBreaker());

        breakers = new ObjectMapper().readTree(get("/v1/stats")).path("breakers");
        assertThat(breakers.path("graphBuildEmbedding").asText()).isEqualTo("OPEN");
        assertThat(breakers.path("graphQueryEmbedding").asText())
            .as("the graphs just built have entity vectors, so a question is embedded - "
                + "through a breaker the build's failures do not reach")
            .isEqualTo("CLOSED");
        String metrics = get("/metrics");
        assertThat(metrics)
            .contains("excalibur_circuit_breaker_state{breaker=\"graph_build_embedding\"} 2");
        assertThat(occurrences(metrics, "breaker=\"graph_build_embedding\""))
            .as("two namespaces built, one breaker, one series")
            .isEqualTo(1);
    }

    /** Stages one fact with prose for both ends into a namespace, and builds it. */
    private void stageAndBuild(NamespaceId namespace) throws IOException {
        try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(namespace)) {
            GraphTenant tenant = lease.resource();
            GraphStagingStore staging = tenant.staging();
            Provenance from = new Provenance(SourceType.DOCUMENT, 1, 90, 1);
            int alice = staging.entityIdOf("Alice");
            int bob = staging.entityIdOf("Bob");
            staging.append(ExtractedTriple.alwaysValid("Alice", "knows", "Bob", from));
            staging.appendEntityText(alice, "Alice studies tidal pools", from);
            staging.appendEntityText(bob, "Bob repairs clocks", from);
            staging.flush();
            assertThat(tenant.buildService().build().published()).isTrue();
        }
    }

    private static int occurrences(String text, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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
