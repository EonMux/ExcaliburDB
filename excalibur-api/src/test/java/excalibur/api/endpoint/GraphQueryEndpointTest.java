package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;

import io.excalibur.core.trace.QueryTrace;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.store.MappedEdgeStore;
import io.excalibur.graph.temporal.TemporalFilter;
import io.excalibur.planner.QueryClassifier;
import io.excalibur.retrieval.HybridRetriever;
import io.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.lexical.LuceneRanker;
import io.excalibur.retrieval.pipeline.GraphRagPipeline;
import io.excalibur.retrieval.pipeline.LexicalEntityLinker;
import io.excalibur.retrieval.serialize.BpeTokenCounter;
import io.excalibur.retrieval.serialize.SerializationBudget;
import io.excalibur.retrieval.serialize.TripleSubgraphSerializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

class GraphQueryEndpointTest {

    private static final int ADA = 1;
    private static final int BABBAGE = 2;
    private static final int COLLABORATED_WITH = 0;

    private static final Map<Long, String> LABELS =
        Map.of((long) ADA, "Ada Lovelace", (long) BABBAGE, "Charles Babbage");

    private MappedEdgeStore edgeStore;
    private LexicalIndex documentIndex;
    private LexicalIndex labelIndex;
    private GraphQueryEndpoint endpoint;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        writer.add(ADA, COLLABORATED_WITH, BABBAGE, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.KNOWLEDGE_BASE, 1, 96, 7));
        writer.build(root.resolve("graph"), 8);
        edgeStore = MappedEdgeStore.open(root.resolve("graph"));

        documentIndex = LexicalIndex.open(root.resolve("docs"), 16.0);
        documentIndex.put(ADA, "Ada Lovelace mathematician");
        documentIndex.put(BABBAGE, "Charles Babbage engineer");
        documentIndex.refresh();

        labelIndex = LexicalIndex.open(root.resolve("labels"), 16.0);
        LexicalEntityLinker.index(labelIndex, ADA, "Ada Lovelace");
        LexicalEntityLinker.index(labelIndex, BABBAGE, "Charles Babbage");
        labelIndex.refresh();

        endpoint = new GraphQueryEndpoint(new GraphRagPipeline(
            new QueryClassifier(),
            new LexicalEntityLinker(labelIndex),
            edgeStore,
            new HybridRetriever(List.of(new LuceneRanker(documentIndex))),
            new TripleSubgraphSerializer(
                id -> LABELS.getOrDefault(id, "E" + id), relation -> "collaboratedWith"),
            new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5),
            new SerializationBudget(2000, BpeTokenCounter.defaultCounter()),
            10));
    }

    @AfterEach
    void tearDown() {
        if (edgeStore != null) {
            edgeStore.close();
        }
        if (documentIndex != null) {
            documentIndex.close();
        }
        if (labelIndex != null) {
            labelIndex.close();
        }
    }

    /** Drives the handler and returns {@code [status, body]}. */
    private Response call(String method, String body) throws IOException {
        HttpExchange exchange = Mockito.mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] status = new int[1];

        Mockito.when(exchange.getRequestMethod()).thenReturn(method);
        Mockito.when(exchange.getRequestURI()).thenReturn(URI.create("/v1/graph/query"));
        Mockito.when(exchange.getRequestBody())
            .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        Mockito.when(exchange.getResponseHeaders()).thenReturn(new Headers());
        Mockito.when(exchange.getResponseBody()).thenReturn(out);
        Mockito.doAnswer(invocation -> {
            status[0] = invocation.getArgument(0);
            return null;
        }).when(exchange).sendResponseHeaders(Mockito.anyInt(), Mockito.anyLong());

        endpoint.handle(exchange);
        return new Response(status[0], out.toString(StandardCharsets.UTF_8));
    }

    private record Response(int status, String body) {
    }

    /**
     * Parses the body, failing if it is not valid JSON.
     *
     * <p>The response is assembled by hand, so "looks right in a substring assertion" and "is
     * valid JSON" are different properties — and it was the second that broke first.
     */
    private static com.fasterxml.jackson.databind.JsonNode parse(Response response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("response body is not valid JSON: " + response.body(), e);
        }
    }

    @Test
    @DisplayName("every response shape is valid JSON")
    void everyResponseIsValidJson() throws IOException {
        // One per branch of the renderer, because each builds the body differently.
        var answered = parse(call("POST", "{\"query\": \"Who did Ada Lovelace collaborate with?\"}"));
        assertThat(answered.get("status").asText()).isEqualTo("answered");
        assertThat(answered.get("context").isTextual()).isTrue();
        assertThat(answered.get("context").asText()).contains("Charles Babbage");
        assertThat(answered.get("confidence").isNumber()).isTrue();
        assertThat(answered.get("timings_ms").isObject()).isTrue();
        assertThat(answered.get("plan").isTextual()).isTrue();

        var needsInput = parse(call("POST", "{\"query\": \"Who did she collaborate with?\"}"));
        assertThat(needsInput.get("status").asText()).isEqualTo("needs_input");
        assertThat(needsInput.get("question").isTextual()).isTrue();
        assertThat(needsInput.get("candidates").isArray()).isTrue();

        var declined = parse(call("POST", "{\"query\": \"Who did Grace Hopper collaborate with?\"}"));
        assertThat(declined.get("status").asText()).isEqualTo("declined");
        assertThat(declined.get("reason").isTextual()).isTrue();

        assertThat(parse(call("POST", "{}")).get("error").isTextual()).isTrue();
        assertThat(parse(call("GET", "")).get("error").isTextual()).isTrue();
    }

    @Test
    @DisplayName("a quote in the question does not break the response body")
    void quotesAreEscaped() throws IOException {
        var node = parse(call("POST",
            "{\"query\": \"Tell me about \\\"Note G\\\" and Ada Lovelace\"}"));

        assertThat(node.get("status").isTextual()).isTrue();
    }

    @Test
    @DisplayName("a question about a known entity is answered with context")
    void answersFromTheGraph() throws IOException {
        Response response = call("POST",
            "{\"query\": \"Who did Ada Lovelace collaborate with?\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
            .contains("\"status\":\"answered\"")
            .contains("Charles Babbage")
            .contains("\"confidence\":")
            .contains("\"tokens\":");
    }

    @Test
    @DisplayName("provenance is carried into the response context")
    void provenanceInResponse() throws IOException {
        Response response = call("POST",
            "{\"query\": \"Who did Ada Lovelace collaborate with?\"}");

        assertThat(response.body()).contains("knowledge_base").contains("conf=96");
    }

    @Test
    @DisplayName("per-stage timings are returned, so a slow query can be attributed")
    void timingsAreReturned() throws IOException {
        Response response = call("POST",
            "{\"query\": \"Who did Ada Lovelace collaborate with?\"}");

        assertThat(response.body())
            .contains("\"timings_ms\":")
            .contains("\"plan\":")
            .contains("\"link\":")
            .contains("\"traverse\":")
            .contains("\"total\":");
    }

    @Test
    @DisplayName("an unbound pronoun returns needs_input with the question to ask")
    void unboundPronounNeedsInput() throws IOException {
        // 200, not an error: the request was well formed and the system is working as intended.
        Response response = call("POST", "{\"query\": \"Who did she collaborate with?\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
            .contains("\"status\":\"needs_input\"")
            .contains("unbound_pronoun")
            .contains("she");
    }

    @Test
    @DisplayName("client-supplied context binds a pronoun")
    void contextEntitiesBindPronouns() throws IOException {
        // The client owns the history; the server holds no session, so a client can correct a
        // mis-bound pronoun by editing what it sends.
        Response response = call("POST",
            "{\"query\": \"Who did she collaborate with?\","
                + "\"context_entities\": [\"Ada Lovelace\"]}");

        assertThat(response.body())
            .contains("\"status\":\"answered\"")
            .contains("Charles Babbage");
    }

    @Test
    @DisplayName("an unknown entity is declined rather than answered emptily")
    void unknownEntityDeclines() throws IOException {
        Response response = call("POST",
            "{\"query\": \"Who did Grace Hopper collaborate with?\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"declined\"").contains("\"reason\":");
    }

    @Test
    @DisplayName("a missing query is a 400")
    void missingQuery() throws IOException {
        assertThat(call("POST", "{}").status()).isEqualTo(400);
        assertThat(call("POST", "{\"query\": \"\"}").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("an over-long query is refused rather than started")
    void overLongQuery() throws IOException {
        Response response = call("POST",
            "{\"query\": \"" + "a".repeat(5000) + "\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("exceeds");
    }

    @Test
    @DisplayName("more context entities than the bound is refused rather than bound one by one")
    void tooManyContextEntities() throws IOException {
        // The question is bounded and the body is bounded, but a body of that size holds tens of
        // thousands of mentions - each one a candidate every pronoun in the question is compared
        // against, and every one of them a name in the "did you mean" the endpoint may answer with.
        String entities = java.util.stream.IntStream.range(0, 200)
            .mapToObj(i -> "\"E" + i + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        Response response = call("POST",
            "{\"query\": \"Who did Ada Lovelace collaborate with?\", \"context_entities\": ["
                + entities + "]}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body())
            .as("a caller has to know which field was too big and what the bound is")
            .contains("context_entities")
            .contains("128");
    }

    @Test
    @DisplayName("a context entity longer than the bound is refused")
    void overLongContextEntity() throws IOException {
        Response response = call("POST",
            "{\"query\": \"Who did Ada Lovelace collaborate with?\", "
                + "\"context_entities\": [\"" + "a".repeat(300) + "\"]}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("context_entities");
    }

    @Test
    @DisplayName("a context at the bound is still answered")
    void contextAtTheBoundIsAccepted() throws IOException {
        String entities = java.util.stream.IntStream.range(0, 127)
            .mapToObj(i -> "\"E" + i + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        Response response = call("POST",
            "{\"query\": \"Who did Ada Lovelace collaborate with?\", \"context_entities\": ["
                + entities + ",\"Ada Lovelace\"]}");

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a non-POST method is a 405")
    void wrongMethod() throws IOException {
        assertThat(call("GET", "").status()).isEqualTo(405);
    }

    /**
     * What a question about a namespace may and may not do to the node.
     *
     * <p>A read that opens a namespace creates it: the tenant writes its staging directory as it
     * opens, takes a slot in a bounded registry and a share of the mapping budget, and evicts
     * whichever real tenant was least recently used to get them. The namespace comes from a
     * request header, so the set of names a caller can spend that on is the caller's to choose.</p>
     */
    @Nested
    @DisplayName("Namespaces")
    class Namespaces {

        private Path root;
        private excalibur.api.bootstrap.GraphTenants tenants;
        private GraphQueryEndpoint tenantEndpoint;

        @BeforeEach
        void openTenants(@TempDir Path tempDir) throws IOException {
            root = tempDir;
            tenants = new excalibur.api.bootstrap.GraphTenants(
                tempDir,
                new io.excalibur.config.Config(new java.util.HashMap<>(
                    Map.of("excalibur.graph.ram-buffer-mb", "4.0"))),
                io.excalibur.tenancy.MappingBudget.detect(),
                4);
            tenantEndpoint = new GraphQueryEndpoint(tenants);
        }

        @AfterEach
        void closeTenants() {
            if (tenants != null) {
                tenants.close();
            }
        }

        @Test
        @DisplayName("a namespace nobody has built is answered without being created")
        void anUnknownNamespaceIsNotCreated() throws IOException {
            Response response = ask("never-built");

            assertThat(response.status()).isEqualTo(503);
            assertThat(root.resolve("never-built")).doesNotExist();
            assertThat(tenants.openNamespaces())
                .containsExactly(io.excalibur.tenancy.NamespaceId.SHARED);
        }

        @Test
        @DisplayName("a namespace that has never existed is answered as an unbuilt one is")
        void anUnknownNamespaceIsIndistinguishableFromAnUnbuiltOne() throws IOException {
            tenants.borrow(io.excalibur.tenancy.NamespaceId.of("empty")).close();

            assertThat(ask("never-built"))
                .as("every tenant presents the same bearer token, so a reply that separated "
                    + "'no such namespace' from 'nothing built there' would answer which "
                    + "namespaces other tenants have")
                .isEqualTo(ask("empty"));
        }

        @Test
        @DisplayName("a namespace on disk is reopened to answer, not skipped")
        void anExistingNamespaceIsReopened() throws IOException {
            io.excalibur.tenancy.NamespaceId acme = io.excalibur.tenancy.NamespaceId.of("acme");
            tenants.borrow(acme).close();
            tenants.invalidate(acme);

            assertThat(ask("acme").status()).isEqualTo(503);
            assertThat(tenants.openNamespaces())
                .as("closed but still on disk: the read has to reopen it, or a tenant would stop "
                    + "being answerable the moment eviction reached it")
                .contains(acme);
        }

        @Test
        @DisplayName("a node that never sees the header answers from the shared namespace")
        void noHeaderMeansShared() throws IOException {
            assertThat(ask(null).status()).isEqualTo(503);
            assertThat(tenants.openNamespaces())
                .containsExactly(io.excalibur.tenancy.NamespaceId.SHARED);
        }

        @Test
        @DisplayName("a namespace that is not a namespace is a 400")
        void anInvalidNamespaceIsRefused() throws IOException {
            assertThat(ask("Not A Namespace").status()).isEqualTo(400);
        }

        private Response ask(String namespace) throws IOException {
            HttpExchange exchange = Mockito.mock(HttpExchange.class);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int[] status = new int[1];
            Headers requestHeaders = new Headers();
            if (namespace != null) {
                requestHeaders.set(
                    excalibur.api.bootstrap.GraphTenants.NAMESPACE_HEADER, namespace);
            }

            Mockito.when(exchange.getRequestMethod()).thenReturn("POST");
            Mockito.when(exchange.getRequestURI()).thenReturn(URI.create("/v1/graph/query"));
            Mockito.when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
            Mockito.when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(
                "{\"query\": \"Who did Ada Lovelace collaborate with?\"}"
                    .getBytes(StandardCharsets.UTF_8)));
            Mockito.when(exchange.getResponseHeaders()).thenReturn(new Headers());
            Mockito.when(exchange.getResponseBody()).thenReturn(out);
            Mockito.doAnswer(invocation -> {
                status[0] = invocation.getArgument(0);
                return null;
            }).when(exchange).sendResponseHeaders(Mockito.anyInt(), Mockito.anyLong());

            tenantEndpoint.handle(exchange);
            return new Response(status[0], out.toString(StandardCharsets.UTF_8));
        }
    }
}
