package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.llm.client.CircuitBreakerState;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That every graph a node serves reranks and embeds through one client, and one breaker, each.
 *
 * <p>A breaker describes an endpoint, and every graph on a node calls the same one with the same
 * key. When each opened graph built its own client, a provider outage had to trip a breaker per
 * namespace before the node stopped calling it, a reopen after a build or an eviction reset that
 * namespace's breaker to closed, and there was no one state the node could report.</p>
 */
@DisplayName("GraphModelClients")
class GraphModelClientsTest {

    private static Config keyed() {
        return new Config(new HashMap<>(Map.of(
            "excalibur.llm.api-key", "test-key",
            "excalibur.llm.base-url", "http://127.0.0.1:9")));
    }

    @Test
    @DisplayName("a node without an API key has neither client, and never will")
    void noKeyNoClients() {
        GraphModelClients models = GraphModelClients.forConfig(new Config(new HashMap<>()));

        assertThat(models.rerankClient()).isNull();
        assertThat(models.queryEmbeddingClient()).isNull();
        assertThat(models.rerankBreaker()).isNull();
        assertThat(models.queryEmbeddingBreaker()).isNull();
        assertThat(models.buildEmbeddingClient()).isNull();
        assertThat(models.buildEmbeddingBreaker()).isNull();
        assertThat(models.entityEmbedder())
            .as("a node with no key builds graphs without vectors")
            .isNull();
    }

    @Test
    @DisplayName("a keyless local server gets both clients: it needs no key")
    void keylessLocalServerHasClients() {
        GraphModelClients models = GraphModelClients.forConfig(new Config(new HashMap<>(Map.of(
            "excalibur.llm.base-url", "http://127.0.0.1:9"))));

        assertThat(models.rerankClient()).isNotNull();
        assertThat(models.queryEmbeddingClient()).isNotNull();
        assertThat(models.entityEmbedder()).isNotNull();
    }

    @Test
    @DisplayName("each role is decided alone: an embedding server with chat switched off")
    void rolesAreDecidedApart() {
        GraphModelClients models = GraphModelClients.forConfig(new Config(new HashMap<>(Map.of(
            "excalibur.llm.embedding-base-url", "http://127.0.0.1:9"))));

        assertThat(models.rerankClient())
            .as("chat is still on the hosted default with no key")
            .isNull();
        assertThat(models.queryEmbeddingClient()).isNotNull();
        assertThat(models.entityEmbedder()).isNotNull();
        assertThat(models.toString()).contains("rerank=no model").contains("queryEmbedding=");
    }

    @Test
    @DisplayName("the rerank client is addressed at the chat endpoint, not the embedding one")
    void rerankUsesTheChatEndpoint() {
        GraphModelClients models = GraphModelClients.forConfig(new Config(new HashMap<>(Map.of(
            "excalibur.llm.chat-base-url", "http://127.0.0.1:9",
            "excalibur.llm.embeddings-enabled", "false"))));

        assertThat(models.rerankClient()).isNotNull();
        assertThat(models.queryEmbeddingClient()).isNull();
        assertThat(models.entityEmbedder()).isNull();
    }

    @Test
    @DisplayName("a client exists from the first graph that asks for it, and is the only one")
    void createdOnceOnFirstUse() {
        GraphModelClients models = GraphModelClients.forConfig(keyed());
        assertThat(models.rerankBreaker())
            .as("nothing has reranked through it yet, so the node has no such client")
            .isNull();
        assertThat(models.queryEmbeddingBreaker()).isNull();

        LLMHttpClient rerank = models.rerankClient();
        EmbeddingClient embedding = models.queryEmbeddingClient();

        assertThat(models.rerankClient()).isSameAs(rerank);
        assertThat(models.queryEmbeddingClient()).isSameAs(embedding);
        assertThat(models.rerankBreaker()).isSameAs(rerank.getCircuitBreaker());
        assertThat(models.queryEmbeddingBreaker()).isSameAs(embedding.getCircuitBreaker());
        assertThat(models.buildEmbeddingBreaker())
            .as("no build has embedded anything, so the node has no build embedding client")
            .isNull();

        EmbeddingClient build = models.buildEmbeddingClient();
        assertThat(models.buildEmbeddingClient()).isSameAs(build);
        assertThat(models.buildEmbeddingBreaker()).isSameAs(build.getCircuitBreaker());
    }

    @Test
    @DisplayName("a build and a question embed through separate breakers, tripping apart")
    void buildAndQuestionEmbeddingTripApart() {
        GraphModelClients models = GraphModelClients.forConfig(keyed());
        EmbeddingClient question = models.queryEmbeddingClient();
        EmbeddingClient build = models.buildEmbeddingClient();

        assertThat(build).isNotSameAs(question);
        trip(models.buildEmbeddingBreaker());

        assertThat(models.buildEmbeddingBreaker().getState())
            .isEqualTo(CircuitBreakerState.OPEN);
        assertThat(models.queryEmbeddingBreaker().getState())
            .as("a build's bulk batches failing must not take the vector rerank away from "
                + "every question on the node")
            .isEqualTo(CircuitBreakerState.CLOSED);
    }

    @Test
    @DisplayName("the entity embedder creates the build client only when a build embeds")
    void theEntityEmbedderCreatesTheClientLazily() {
        GraphModelClients models = GraphModelClients.forConfig(keyed());

        assertThat(models.entityEmbedder()).isNotNull();
        assertThat(models.buildEmbeddingBreaker())
            .as("handing a tenant an embedder is not embedding anything")
            .isNull();
    }

    @Test
    @DisplayName("every tenant builds through the node's one client, which outlives a reopen")
    void buildsShareTheNodesClient(@TempDir Path root) throws IOException {
        Config config = keyedWithoutRetries();
        GraphModelClients models = GraphModelClients.forConfig(config);

        try (GraphTenant acme = GraphTenant.open(NamespaceId.of("acme"),
                root.resolve("acme").resolve(GraphTenant.GRAPH_SUBDIRECTORY), config, models)) {
            assertThat(models.buildEmbeddingBreaker())
                .as("opening a tenant embeds nothing")
                .isNull();
            stageAndBuild(acme);
        }
        CircuitBreaker afterFirst = models.buildEmbeddingBreaker();
        assertThat(afterFirst)
            .as("the build embedded its entities, through the node's client")
            .isNotNull();
        assertThat(afterFirst.getFailureCount())
            .as("nothing listens at the endpoint, so that batch failed and was counted")
            .isEqualTo(1);

        try (GraphTenant globex = GraphTenant.open(NamespaceId.of("globex"),
                root.resolve("globex").resolve(GraphTenant.GRAPH_SUBDIRECTORY), config, models)) {
            stageAndBuild(globex);
        }
        try (GraphTenant acme = GraphTenant.open(NamespaceId.of("acme"),
                root.resolve("acme").resolve(GraphTenant.GRAPH_SUBDIRECTORY), config, models)) {
            stageAndBuild(acme);
        }

        assertThat(models.buildEmbeddingBreaker())
            .as("another namespace, and the first one reopened, build through the same breaker")
            .isSameAs(afterFirst);
        assertThat(afterFirst.getFailureCount())
            .as("a reopen no longer hands the namespace a fresh, closed breaker")
            .isEqualTo(3);
    }

    @Test
    @DisplayName("a node told not to build entity vectors never creates the build client")
    void noEntityVectorsNoBuildClient(@TempDir Path root) throws IOException {
        Map<String, String> properties = new HashMap<>(Map.of(
            "excalibur.llm.api-key", "test-key",
            "excalibur.llm.base-url", "http://127.0.0.1:9",
            "excalibur.graph.entity-vectors-enabled", "false"));
        Config config = new Config(properties);
        GraphModelClients models = GraphModelClients.forConfig(config);

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED,
                root.resolve(GraphTenant.GRAPH_SUBDIRECTORY), config, models)) {
            stageAndBuild(tenant);
        }

        assertThat(models.buildEmbeddingBreaker()).isNull();
    }

    /** A keyed node whose embedding calls fail at once rather than after a backoff. */
    private static Config keyedWithoutRetries() {
        return new Config(new HashMap<>(Map.of(
            "excalibur.llm.api-key", "test-key",
            "excalibur.llm.base-url", "http://127.0.0.1:9",
            "excalibur.llm.max-retries", "0",
            "excalibur.graph.ram-buffer-mb", "4.0")));
    }

    /** Stages one fact with prose for both ends, and builds. */
    static void stageAndBuild(GraphTenant tenant) throws IOException {
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

    private static void trip(CircuitBreaker breaker) {
        for (int i = 0; i < breaker.getFailureThreshold(); i++) {
            breaker.recordFailure();
        }
    }

    @Test
    @DisplayName("two graphs opened through one set share the rerank breaker")
    void graphsShareTheBreaker(@TempDir Path root) throws IOException {
        MinimalGraph.write(root.resolve("a"), false);
        MinimalGraph.write(root.resolve("b"), false);
        Config config = keyed();
        GraphModelClients models = GraphModelClients.forConfig(config);

        try (GraphRagFactory.GraphRag first =
                 GraphRagFactory.openAt(config, root.resolve("a"), models)) {
            LLMHttpClient afterFirst = models.rerankClient();
            try (GraphRagFactory.GraphRag second =
                     GraphRagFactory.openAt(config, root.resolve("b"), models)) {
                assertThat(first).isNotNull();
                assertThat(second).isNotNull();
                assertThat(models.rerankClient()).isSameAs(afterFirst);
                assertThat(models.rerankBreaker()).isNotNull();
                assertThat(models.queryEmbeddingBreaker())
                    .as("neither graph has entity vectors, so neither embeds a question")
                    .isNull();
            }
        }
    }

    @Test
    @DisplayName("a graph with entity vectors brings the query embedder into existence")
    void vectorsBringTheEmbedder(@TempDir Path root) throws IOException {
        MinimalGraph.write(root, true);
        Config config = keyed();
        GraphModelClients models = GraphModelClients.forConfig(config);

        try (GraphRagFactory.GraphRag graph = GraphRagFactory.openAt(config, root, models)) {
            assertThat(graph).isNotNull();
            assertThat(models.queryEmbeddingBreaker()).isNotNull();
        }
    }

    @Test
    @DisplayName("every namespace a node opens reranks through the node's one client")
    void tenantsShareTheNodesClients(@TempDir Path root) throws IOException {
        MinimalGraph.write(root.resolve("shared").resolve(GraphTenant.GRAPH_SUBDIRECTORY), false);
        MinimalGraph.write(root.resolve("acme").resolve(GraphTenant.GRAPH_SUBDIRECTORY), true);

        try (GraphTenants tenants = new GraphTenants(root, keyed(), MappingBudget.detect(), 4)) {
            assertThat(tenants.models().rerankBreaker())
                .as("the shared namespace's graph was opened, and reranks with the chat model")
                .isNotNull();
            LLMHttpClient rerank = tenants.models().rerankClient();
            assertThat(tenants.models().queryEmbeddingBreaker())
                .as("only the shared namespace is open, and its graph has no vectors")
                .isNull();

            try (var lease = tenants.borrow(NamespaceId.of("acme"))) {
                assertThat(lease.resource().cost()[0]).as("acme serves a graph").isPositive();
                assertThat(tenants.models().rerankClient()).isSameAs(rerank);
                assertThat(tenants.models().queryEmbeddingBreaker()).isNotNull();
            }
        }
    }
}
