package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRoutingGraphIngestSink;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.graph.RelationExtractor;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.retrieval.expand.CachingQueryExpander;
import com.eonmux.excalibur.retrieval.expand.LlmQueryExpander;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.shard.worker.CuckooMetricsWorker;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That the breakers in front of query expansion and relation extraction are reported.
 *
 * <p>Each holds its own chat client, and so its own breaker, and neither was readable anywhere:
 * an open expansion breaker narrows every query to the words typed, an open extraction breaker
 * stops the graph growing, and in both the node goes on answering 200. These drive the socket on
 * a server assembled the way the application assembles one - the expander wrapped in its result
 * cache, the extractor inside the graph ingest sink - so what is tested is that the server finds
 * the breakers where the application puts them, not only that a renderer can print a state.</p>
 */
@DisplayName("Chat-model breakers on the wire")
class ChatBreakerRouteTest {

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

    private ExcaliburDBServer server(QueryExpander expander, NamespaceRoutingGraphIngestSink sink) {
        SegmentCatalog catalog = new SegmentCatalog();
        IndexOrchestrator indexOrchestrator = mock(IndexOrchestrator.class);
        when(indexOrchestrator.getCuckooFpRates()).thenReturn(CuckooMetricsWorker.FpRates.NONE);
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
        if (expander != null) {
            built.withQueryExpander(expander);
        }
        if (tenants != null) {
            built.withGraphSupport(tenants);
        }
        if (sink != null) {
            built.withGraphIngestSink(sink);
        }
        return built;
    }

    private NamespaceRoutingGraphIngestSink sink(Path root, RelationExtractor extractor)
            throws IOException {
        tenants = new GraphTenants(root, new Config(new HashMap<>()), MappingBudget.detect(), 4);
        return new NamespaceRoutingGraphIngestSink(tenants, sourceId -> Optional.empty(),
            extractor);
    }

    private static void trip(CircuitBreaker breaker) {
        for (int i = 0; i < breaker.getFailureThreshold(); i++) {
            breaker.recordFailure();
        }
    }

    @Test
    @DisplayName("both breakers appear, each under its own name, and trip independently")
    void bothBreakersAreReported(@TempDir Path root) throws IOException {
        LLMHttpClient expansionClient = new LLMHttpClient(LLMClientConfig.defaultLocal());
        LLMHttpClient extractionClient = new LLMHttpClient(LLMClientConfig.defaultLocal());
        server = server(CachingQueryExpander.over(new LlmQueryExpander(expansionClient)),
            sink(root, new LlmRelationExtractor(extractionClient, 1)));
        server.start();

        trip(extractionClient.getCircuitBreaker());

        JsonNode breakers = new ObjectMapper().readTree(get("/v1/stats")).path("breakers");
        assertThat(breakers.path("queryExpansion").asText()).isEqualTo("CLOSED");
        assertThat(breakers.path("graphExtraction").asText())
            .as("the extractor's client failed; the expander's did not")
            .isEqualTo("OPEN");

        String metrics = get("/metrics");
        assertThat(metrics)
            .contains("excalibur_circuit_breaker_state{breaker=\"query_expansion\"} 0")
            .contains("excalibur_circuit_breaker_state{breaker=\"graph_extraction\"} 2");
    }

    @Test
    @DisplayName("an expander used without its result cache is still found")
    void anUncachedExpanderIsFound() throws IOException {
        LLMHttpClient client = new LLMHttpClient(LLMClientConfig.defaultLocal());
        server = server(new LlmQueryExpander(client), null);
        server.start();
        trip(client.getCircuitBreaker());

        JsonNode breakers = new ObjectMapper().readTree(get("/v1/stats")).path("breakers");
        assertThat(breakers.path("queryExpansion").asText()).isEqualTo("OPEN");
        assertThat(breakers.has("graphExtraction")).isFalse();
    }

    @Test
    @DisplayName("a node without a chat model behind either reports neither")
    void absentWithoutAChatModel(@TempDir Path root) throws IOException {
        // An expander with no model, and a sink whose extraction is off: no breaker exists, and
        // reporting a closed one would read as a healthy component that is not there.
        QueryExpander plain = query -> List.of();
        server = server(CachingQueryExpander.over(plain), sink(root, RelationExtractor.none()));
        server.start();

        JsonNode stats = new ObjectMapper().readTree(get("/v1/stats"));
        assertThat(stats.path("breakers").has("queryExpansion")).isFalse();
        assertThat(stats.path("breakers").has("graphExtraction")).isFalse();
        String metrics = get("/metrics");
        assertThat(metrics).doesNotContain("breaker=\"query_expansion\"")
            .doesNotContain("breaker=\"graph_extraction\"");
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
