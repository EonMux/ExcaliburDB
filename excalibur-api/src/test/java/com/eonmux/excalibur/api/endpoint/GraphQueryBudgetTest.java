package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.planner.QueryClassifier;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import com.eonmux.excalibur.retrieval.HybridRetriever;
import com.eonmux.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import com.eonmux.excalibur.retrieval.fusion.ScorePassthrough;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LuceneRanker;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;
import com.eonmux.excalibur.retrieval.pipeline.LexicalEntityLinker;
import com.eonmux.excalibur.retrieval.pipeline.WalkLimits;
import com.eonmux.excalibur.retrieval.serialize.BpeTokenCounter;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.TripleSubgraphSerializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code POST /v1/graph/query}'s deadline, its place in {@code GET /v1/query/status}, its
 * withdrawal by {@code DELETE /v1/query/{id}}, and what its walk limits report.
 */
@Timeout(value = 60)
class GraphQueryBudgetTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ADA = 1;
    private static final int BABBAGE = 2;
    private static final int MENABREA = 3;
    private static final int SOMERVILLE = 4;
    private static final int COLLABORATED_WITH = 0;
    private static final long MAX_TIMEOUT_MS = 60_000;
    private static final String QUESTION = "{\"query\":\"Who did Ada Lovelace collaborate with?\"";

    private static final Map<Long, String> LABELS = Map.of(
        (long) ADA, "Ada Lovelace", (long) BABBAGE, "Charles Babbage",
        (long) MENABREA, "Luigi Menabrea", (long) SOMERVILLE, "Mary Somerville");

    private MappedEdgeStore edgeStore;
    private LexicalIndex documentIndex;
    private LexicalIndex labelIndex;
    private QueryRegistry registry;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        writer.add(ADA, COLLABORATED_WITH, BABBAGE, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.USER_ASSERTED, 1, 96, 7));
        writer.add(ADA, COLLABORATED_WITH, MENABREA, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.USER_ASSERTED, 2, 80, 7));
        writer.add(ADA, COLLABORATED_WITH, SOMERVILLE, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.USER_ASSERTED, 3, 70, 7));
        writer.build(root.resolve("graph"), 8);
        edgeStore = MappedEdgeStore.open(root.resolve("graph"));

        documentIndex = LexicalIndex.open(root.resolve("docs"), 16.0);
        for (Map.Entry<Long, String> label : LABELS.entrySet()) {
            documentIndex.put(label.getKey(), label.getValue() + " mathematician");
        }
        documentIndex.refresh();

        labelIndex = LexicalIndex.open(root.resolve("labels"), 16.0);
        for (Map.Entry<Long, String> label : LABELS.entrySet()) {
            LexicalEntityLinker.index(labelIndex, label.getKey(), label.getValue());
        }
        labelIndex.refresh();

        registry = new QueryRegistry();
    }

    @AfterEach
    void tearDown() {
        edgeStore.close();
        documentIndex.close();
        labelIndex.close();
    }

    private GraphRagPipeline pipeline(WalkLimits limits, GraphRagPipeline.QueryEmbedder embedder) {
        return new GraphRagPipeline(
            new QueryClassifier(),
            new LexicalEntityLinker(labelIndex),
            edgeStore,
            new HybridRetriever(List.of(new LuceneRanker(documentIndex)), new ScorePassthrough(),
                HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH),
            new TripleSubgraphSerializer(
                id -> LABELS.getOrDefault(id, "E" + id), relation -> "collaboratedWith"),
            new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5),
            new SerializationBudget(2000, BpeTokenCounter.defaultCounter()),
            10,
            embedder,
            null,
            id -> LABELS.getOrDefault(id, "E" + id),
            limits);
    }

    private GraphQueryEndpoint endpoint(WalkLimits limits,
                                        GraphRagPipeline.QueryEmbedder embedder) {
        return new GraphQueryEndpoint(pipeline(limits, embedder), registry, 30_000,
            MAX_TIMEOUT_MS);
    }

    /** A question embedder that answers only once it is interrupted, noting that it was. */
    private static GraphRagPipeline.QueryEmbedder hanging(CountDownLatch started,
                                                          AtomicBoolean interrupted) {
        return text -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return new float[] {1f};
        };
    }

    private record Response(int status, String body) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new AssertionError("not valid JSON: " + body, e);
            }
        }
    }

    private static Response call(HttpHandler handler, String method, String body)
            throws IOException {
        HttpExchange exchange = Mockito.mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] status = new int[1];
        Mockito.when(exchange.getRequestMethod()).thenReturn(method);
        Mockito.when(exchange.getRequestURI()).thenReturn(URI.create("/v1/graph/query"));
        Mockito.when(exchange.getRequestHeaders()).thenReturn(new Headers());
        Mockito.when(exchange.getRequestBody())
            .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        Mockito.when(exchange.getResponseHeaders()).thenReturn(new Headers());
        Mockito.when(exchange.getResponseBody()).thenReturn(out);
        Mockito.doAnswer(invocation -> {
            status[0] = invocation.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(Mockito.anyInt(), Mockito.anyLong());
        handler.handle(exchange);
        return new Response(status[0], out.toString(StandardCharsets.UTF_8));
    }

    private Response ask(GraphQueryEndpoint endpoint, String extraFields) throws IOException {
        return call(endpoint, "POST", QUESTION + extraFields + "}");
    }

    @Test
    @DisplayName("every answer names its query id and says it neither timed out nor was cancelled")
    void answerCarriesTheQueryFields() throws IOException {
        Response response = ask(endpoint(WalkLimits.defaults(), null), "");

        assertThat(response.status()).isEqualTo(200);
        JsonNode json = response.json();
        assertThat(json.path("status").asText()).isEqualTo("answered");
        assertThat(UUID.fromString(json.path("query_id").asText())).isNotNull();
        assertThat(json.path("timed_out").asBoolean(true)).isFalse();
        assertThat(json.path("cancelled").asBoolean(true)).isFalse();
        assertThat(json.path("walk").path("truncated").asBoolean(true)).isFalse();
        assertThat(registry.getActiveQueryCount())
            .as("a finished question is no longer listed").isZero();
    }

    @Test
    @DisplayName("a timeout_ms that is not positive, too long, or not a number is refused 400")
    void timeoutIsValidatedLikeQuery() throws IOException {
        GraphQueryEndpoint endpoint = endpoint(WalkLimits.defaults(), null);

        Response zero = ask(endpoint, ",\"timeout_ms\":0");
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.body()).contains("Field 'timeout_ms' must be positive: 0");

        Response tooLong = ask(endpoint, ",\"timeout_ms\":" + (MAX_TIMEOUT_MS + 1));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body())
            .contains("Field 'timeout_ms' must be at most 60000: " + (MAX_TIMEOUT_MS + 1));

        assertThat(ask(endpoint, ",\"timeout_ms\":\"soon\"").status()).isEqualTo(400);

        Response inRange = ask(endpoint, ",\"timeout_ms\":" + MAX_TIMEOUT_MS);
        assertThat(inRange.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a model slower than the deadline is abandoned and the partial answer flagged")
    void slowModelHonoursTheDeadline() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        long begun = System.nanoTime();
        Response response = ask(endpoint(WalkLimits.defaults(), hanging(started, interrupted)),
            ",\"timeout_ms\":250");
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun);

        assertThat(tookMillis).as("bounded by timeout_ms, not the model").isLessThan(10_000);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        JsonNode json = response.json();
        assertThat(response.status()).isEqualTo(200);
        assertThat(json.path("timed_out").asBoolean()).isTrue();
        assertThat(json.path("cancelled").asBoolean(true)).isFalse();
        assertThat(json.path("status").asText())
            .as("the walk finished in time, so its facts still answer")
            .isEqualTo("answered");
        awaitTrue(interrupted);
    }

    @Test
    @DisplayName("a running graph question is listed by /v1/query/status as kind graph")
    void listedWhileRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        GraphQueryEndpoint endpoint = endpoint(WalkLimits.defaults(), hanging(started, interrupted));

        CompletableFuture<Response> running = CompletableFuture.supplyAsync(() -> {
            try {
                return ask(endpoint, ",\"timeout_ms\":1000");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        JsonNode status = call(new QueryStatusEndpoint(registry), "GET", "").json();
        assertThat(status.path("active").asInt()).isEqualTo(1);
        JsonNode listed = status.path("queries").get(0);
        assertThat(listed.path("kind").asText()).isEqualTo("graph");
        assertThat(listed.path("cancelled").asBoolean(true)).isFalse();

        Response response = running.get(10, TimeUnit.SECONDS);
        assertThat(response.json().path("query_id").asText())
            .isEqualTo(listed.path("query_id").asText());
    }

    @Test
    @DisplayName("DELETE /v1/query/{id} withdraws a graph question, interrupting its model call")
    void cancelledMidFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        GraphQueryEndpoint endpoint = endpoint(WalkLimits.defaults(), hanging(started, interrupted));

        CompletableFuture<Response> running = CompletableFuture.supplyAsync(() -> {
            try {
                return ask(endpoint, ",\"timeout_ms\":60000");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        String queryId = registry.getActiveQueries().get(0).queryId();

        long begun = System.nanoTime();
        assertThat(registry.cancel(queryId)).isTrue();
        Response response = running.get(10, TimeUnit.SECONDS);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun);

        assertThat(tookMillis).as("stopped promptly, not at the deadline").isLessThan(5_000);
        assertThat(response.status()).isEqualTo(200);
        JsonNode json = response.json();
        assertThat(json.path("status").asText()).isEqualTo("cancelled");
        assertThat(json.path("cancelled").asBoolean()).isTrue();
        assertThat(json.path("timed_out").asBoolean(true)).isFalse();
        assertThat(json.path("query_id").asText()).isEqualTo(queryId);
        assertThat(json.has("context")).isFalse();
        assertThat(json.path("timings_ms").has("total")).isTrue();
        awaitTrue(interrupted);
        assertThat(registry.isRegistered(queryId)).isFalse();
    }

    @Test
    @DisplayName("a hub's walk is capped, and the answer says how much it left out")
    void walkTruncationIsReported() throws IOException {
        JsonNode json = ask(endpoint(new WalkLimits(1, 1000), null), "").json();

        assertThat(json.path("status").asText()).isEqualTo("answered");
        assertThat(json.path("context").asText()).contains("Charles Babbage")
            .doesNotContain("Mary Somerville").doesNotContain("Luigi Menabrea");
        JsonNode walk = json.path("walk");
        assertThat(walk.path("truncated").asBoolean()).isTrue();
        assertThat(walk.path("entities_dropped").asLong()).isEqualTo(2);
        assertThat(walk.path("facts_dropped").asLong()).isZero();
    }

    @Test
    @DisplayName("a deadline that passes before the walk is declined as timed out")
    void declinedWhenNothingWasWalked() throws IOException {
        LexicalEntityLinker labels = new LexicalEntityLinker(labelIndex);
        // Linking outlasts the deadline, so it has passed by the time the walk would start.
        GraphRagPipeline slowLinking = new GraphRagPipeline(new QueryClassifier(),
            mention -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return labels.link(mention);
            },
            edgeStore,
            new HybridRetriever(List.of(new LuceneRanker(documentIndex)), new ScorePassthrough(),
                HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH),
            new TripleSubgraphSerializer(id -> "E" + id, relation -> "collaboratedWith"),
            new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5),
            new SerializationBudget(2000, BpeTokenCounter.defaultCounter()), 10, null, null,
            null, WalkLimits.defaults());

        Response response = ask(
            new GraphQueryEndpoint(slowLinking, registry, 30_000, MAX_TIMEOUT_MS),
            ",\"timeout_ms\":20");

        assertThat(response.status()).isEqualTo(200);
        JsonNode json = response.json();
        assertThat(json.path("status").asText()).isEqualTo("declined");
        assertThat(json.path("reason").asText()).isEqualTo("timed_out");
        assertThat(json.path("timed_out").asBoolean()).isTrue();
        assertThat(json.has("walk")).as("nothing was walked").isFalse();
    }

    @Test
    @DisplayName("a default timeout longer than the maximum, or not positive, is refused")
    void timeoutsAreValidated() {
        GraphRagPipeline pipeline = pipeline(WalkLimits.defaults(), null);

        assertThatThrownBy(() -> new GraphQueryEndpoint(pipeline, registry, 60_001, 60_000))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds");
        assertThatThrownBy(() -> new GraphQueryEndpoint(pipeline, registry, 0, 60_000))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> new GraphQueryEndpoint(pipeline, registry, 1, -1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    private static void awaitTrue(AtomicBoolean flag) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!flag.get() && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(flag.get()).as("the model call was interrupted").isTrue();
    }
}
