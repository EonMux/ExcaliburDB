package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;

import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.planner.QueryClassifier;
import com.eonmux.excalibur.retrieval.HybridRetriever;
import com.eonmux.excalibur.retrieval.fusion.ScorePassthrough;
import com.eonmux.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LuceneRanker;
import com.eonmux.excalibur.retrieval.pipeline.EntityLinker;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;
import com.eonmux.excalibur.retrieval.pipeline.LexicalEntityLinker;
import com.eonmux.excalibur.retrieval.pipeline.RelationResolver;
import com.eonmux.excalibur.retrieval.serialize.BpeTokenCounter;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.TripleSubgraphSerializer;

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
            new Provenance(SourceType.USER_ASSERTED, 1, 96, 7));
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
            new HybridRetriever(List.of(new LuceneRanker(documentIndex)), new ScorePassthrough(),
                HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH),
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
        return call(endpoint, method, body);
    }

    /** Drives {@code handler} and returns {@code [status, body]}. */
    private static Response call(GraphQueryEndpoint handler, String method, String body)
            throws IOException {
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

        handler.handle(exchange);
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

    @Nested
    @DisplayName("Answering at an instant the caller names")
    class AsOf {

        @Test
        @DisplayName("an ISO date is accepted and echoed back")
        void anIsoDateIsAccepted() throws IOException {
            // Valid time was reachable only through date words the classifier happened to
            // recognise, so a client that knew the instant it wanted had to phrase a question
            // around the heuristic and could not tell which instant it got.
            var node = parse(call("POST", "{\"query\": \"Who did Ada Lovelace collaborate with?\","
                + " \"as_of\": \"1843-01-01\"}"));

            assertThat(node.get("status").asText()).isEqualTo("answered");
            assertThat(node.get("as_of").asText()).isEqualTo("1843-01-01");
        }

        @Test
        @DisplayName("an ISO instant is accepted, at the UTC day it falls in")
        void anIsoInstantIsAccepted() throws IOException {
            var node = parse(call("POST", "{\"query\": \"Who did Ada Lovelace collaborate with?\","
                + " \"as_of\": \"1843-01-01T18:30:00Z\"}"));

            assertThat(node.get("status").asText()).isEqualTo("answered");
            assertThat(node.get("as_of").asText()).isEqualTo("1843-01-01");
        }

        @Test
        @DisplayName("\"all\" asks for the whole history rather than a snapshot of it")
        void allAsksForTheWholeHistory() throws IOException {
            var node = parse(call("POST", "{\"query\": \"Who did Ada Lovelace collaborate with?\","
                + " \"as_of\": \"all\"}"));

            assertThat(node.get("status").asText()).isEqualTo("answered");
            assertThat(node.get("as_of").asText()).isEqualTo("all");
        }

        @Test
        @DisplayName("naming an instant does not hide which plan ran")
        void namingAnInstantDoesNotHideThePlan() throws IOException {
            // "as of 2019" modifies a question without changing what is being asked, so the
            // planner wraps the plan rather than replacing it. Reporting the wrapper's name
            // meant every dated question came back as "temporal" whatever it actually did -
            // and the response already echoes as_of, so the wrapper was the one part of the
            // plan that carried nothing the caller did not already have.
            var undated = parse(call("POST",
                "{\"query\": \"Who did Ada Lovelace collaborate with?\"}"));
            var dated = parse(call("POST", "{\"query\": \"Who did Ada Lovelace collaborate with?\","
                + " \"as_of\": \"1843-01-01\"}"));

            assertThat(dated.get("plan").asText())
                .as("the same question asked at an instant is the same plan")
                .isEqualTo(undated.get("plan").asText())
                .isNotEqualTo("temporal");
        }

        @Test
        @DisplayName("a caller's instant is reported as the caller's")
        void aRequestedInstantSaysSo() throws IOException {
            var node = parse(call("POST", "{\"query\": \"Who did Ada Lovelace collaborate with?\","
                + " \"as_of\": \"1843-01-01\"}"));

            assertThat(node.get("as_of_source").asText()).isEqualTo("request");
        }

        @Test
        @DisplayName("a question with no as_of reports the instant it was answered at anyway")
        void anUnrequestedInstantIsStillReported() throws IOException {
            // Omitting it left a caller unable to tell an answer about today from an answer the
            // classifier dated off the question's own words. as_of_source is what makes echoing
            // the instant honest: it is reported, and it is reported as not having been asked for.
            var node = parse(call("POST",
                "{\"query\": \"Who did Ada Lovelace collaborate with?\"}"));

            assertThat(node.get("status").asText()).isEqualTo("answered");
            assertThat(node.get("as_of").asText())
                .isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
            assertThat(node.get("as_of_source").asText())
                .as("a node that answered at today was not asked to")
                .isEqualTo("default");
        }

        @Test
        @DisplayName("an instant read out of the question is reported as inferred")
        void aQuestionsOwnInstantSaysSo() throws IOException {
            var node = parse(call("POST",
                "{\"query\": \"Who did Ada Lovelace collaborate with as of 1843-01-01?\"}"));

            assertThat(node.get("as_of").asText()).isEqualTo("1843-01-01");
            assertThat(node.get("as_of_source").asText())
                .as("a date the classifier found is a guess at what the asker meant, and a "
                    + "client has to be able to see that it was one")
                .isEqualTo("question");
        }

        @Test
        @DisplayName("something that is not a date is refused rather than ignored")
        void nonsenseIsRefused() throws IOException {
            for (String bad : new String[] {"yesterday", "1843", "2024-13-01", ""}) {
                Response response = call("POST",
                    "{\"query\": \"Who did Ada Lovelace collaborate with?\", \"as_of\": \""
                        + bad + "\"}");

                assertThat(response.status())
                    .as("as_of: %s", bad)
                    .isEqualTo(400);
                assertThat(parse(response).get("error").asText()).contains("as_of");
            }
        }
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

        assertThat(response.body()).contains("user_asserted").contains("conf=96");
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
    @DisplayName("a context entity may carry its shape and turn, and still binds a pronoun")
    void anEntityObjectBinds() throws IOException {
        var node = parse(call("POST", "{\"query\": \"Who did she collaborate with?\","
            + " \"context_entities\": [{\"name\": \"Ada Lovelace\","
            + " \"grammar\": \"singular_person\", \"turn\": 2}]}"));

        assertThat(node.get("status").asText()).isEqualTo("answered");
        assertThat(node.get("context").asText()).contains("Charles Babbage");
    }

    @Test
    @DisplayName("a context entity with a bad name, shape or turn is a 400 naming the entry")
    void badEntityObjectsAreRefused() throws IOException {
        // Refused rather than read as absent: a shape or turn the node ignored would bind the
        // pronoun the caller sent it to rule out, and nothing in the answer would say so.
        String[] bad = {
            "{\"grammar\": \"singular_person\"}",
            "{\"name\": \"\"}",
            "{\"name\": 5}",
            "{\"name\": \"" + "a".repeat(300) + "\"}",
            "{\"name\": \"Ada Lovelace\", \"grammar\": \"person\"}",
            "{\"name\": \"Ada Lovelace\", \"grammar\": 1}",
            "{\"name\": \"Ada Lovelace\", \"turn\": -1}",
            "{\"name\": \"Ada Lovelace\", \"turn\": 1.5}",
            "{\"name\": \"Ada Lovelace\", \"turn\": \"2\"}",
            "{\"name\": \"Ada Lovelace\", \"turn\": 2147483647}",
            "{\"name\": \"Ada Lovelace\", \"turn\": 99999999999}",
        };
        for (String entry : bad) {
            Response response = call("POST", "{\"query\": \"Who did she collaborate with?\","
                + " \"context_entities\": [{\"name\": \"Charles Babbage\"}, " + entry + "]}");

            assertThat(response.status()).as("entry: %s", entry).isEqualTo(400);
            assertThat(parse(response).get("error").asText())
                .as("entry: %s", entry)
                .contains("context_entities");
        }
    }

    @Test
    @DisplayName("names and objects are not mixed in one context")
    void mixedFormsAreRefused() throws IOException {
        Response response = call("POST", "{\"query\": \"Who did she collaborate with?\","
            + " \"context_entities\": [\"Ada Lovelace\", {\"name\": \"Charles Babbage\"}]}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(parse(response).get("error").asText()).contains("context_entities");
    }

    @Test
    @DisplayName("the entry bound applies to objects as it does to names")
    void tooManyEntityObjects() throws IOException {
        String entities = java.util.stream.IntStream.range(0, 129)
            .mapToObj(i -> "{\"name\": \"E" + i + "\", \"turn\": " + i + "}")
            .collect(java.util.stream.Collectors.joining(","));
        Response response = call("POST",
            "{\"query\": \"Who did Ada Lovelace collaborate with?\", \"context_entities\": ["
                + entities + "]}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("128");
    }

    @Test
    @DisplayName("a non-POST method is a 405")
    void wrongMethod() throws IOException {
        assertThat(call("GET", "").status()).isEqualTo(405);
    }

    @Test
    @DisplayName("a direction that is not one of the three, or not a string, is a 400")
    void badDirectionIsRefused() throws IOException {
        for (String bad : new String[] {"\"outgoing\"", "\"\"", "1", "true", "[\"forward\"]"}) {
            Response response = call("POST",
                "{\"query\": \"Who did Ada Lovelace collaborate with?\", \"direction\": "
                    + bad + "}");

            assertThat(response.status()).as("direction: %s", bad).isEqualTo(400);
            assertThat(parse(response).get("error").asText()).contains("direction");
        }
    }

    @Test
    @DisplayName("a one-way walk this pipeline cannot take is a 422 naming why, not a 500")
    void anUnsupportedDirectionIsUnprocessable() throws IOException {
        // The endpoint under test is built the way an embedded pipeline without a relation
        // vocabulary is: it can walk both ways and nothing narrower. The request is well formed
        // and would be answered by a node that has the vocabulary, so it is neither a malformed
        // request nor a fault of this one - it is a request this node cannot carry out.
        for (String direction : new String[] {"forward", "reverse"}) {
            Response response = call("POST",
                "{\"query\": \"Who did Ada Lovelace collaborate with?\", \"direction\": \""
                    + direction + "\"}");

            assertThat(response.status()).as("direction: %s", direction).isEqualTo(422);
            String error = parse(response).get("error").asText();
            assertThat(error).contains(direction).contains("both");
            assertThat(response.body())
                .as("nothing of the server's internals reaches the caller")
                .doesNotContain("Exception")
                .doesNotContain("at com.");
        }

        assertThat(call("POST", "{\"query\": \"Who did Ada Lovelace collaborate with?\", "
            + "\"direction\": \"both\"}").status())
            .as("the walk it can take is still answered")
            .isEqualTo(200);
    }

    /**
     * The parts of an answer that need a graph with more in it than one collaboration: names two
     * entities share, facts with dates, and relations worth walking one way.
     */
    @Nested
    @DisplayName("Ambiguity, aggregates and direction")
    class RicherGraph {

        private static final int ADA_KING = 3;
        private static final int NOTE_G = 4;
        private static final int LONDON = 5;
        private static final int TURIN = 6;
        private static final int ANALYTICAL_ENGINE = 7;
        private static final int AUTHORED = 1;
        private static final int LIVED_IN = 2;
        private static final int DESIGNED = 3;

        private final Map<Long, String> labels = Map.of(
            (long) ADA, "Ada Lovelace", (long) BABBAGE, "Charles Babbage",
            (long) ADA_KING, "Ada King", (long) NOTE_G, "Note G", (long) LONDON, "London",
            (long) TURIN, "Turin", (long) ANALYTICAL_ENGINE, "Analytical Engine");
        private final Map<Integer, String> relations = Map.of(
            COLLABORATED_WITH, "collaboratedWith", AUTHORED, "authored", LIVED_IN, "livedIn",
            DESIGNED, "designed");

        private MappedEdgeStore store;
        private LexicalIndex documents;
        private GraphQueryEndpoint richer;

        private static int day(int year) {
            return Math.toIntExact(java.time.LocalDate.of(year, 1, 1).toEpochDay());
        }

        @BeforeEach
        void build(@TempDir Path root) throws IOException {
            Provenance curated = new Provenance(SourceType.USER_ASSERTED, 1, 96, 7);
            EdgeStoreWriter writer = new EdgeStoreWriter().supersessionPolicy(
                com.eonmux.excalibur.graph.temporal.SupersessionPolicy.forRelations(LIVED_IN));
            writer.add(ADA, COLLABORATED_WITH, BABBAGE, Integer.MIN_VALUE, TemporalFilter.OPEN,
                curated);
            writer.add(ADA, AUTHORED, NOTE_G, day(1843), TemporalFilter.OPEN, curated);
            writer.add(ADA, LIVED_IN, LONDON, day(1815), TemporalFilter.OPEN, curated);
            writer.add(ADA, LIVED_IN, TURIN, day(1841), TemporalFilter.OPEN, curated);
            writer.add(BABBAGE, DESIGNED, ANALYTICAL_ENGINE, day(1837), TemporalFilter.OPEN,
                curated);
            writer.build(root.resolve("graph"), 16);
            store = MappedEdgeStore.open(root.resolve("graph"));

            documents = LexicalIndex.open(root.resolve("docs"), 16.0);
            documents.put(ADA, "Ada Lovelace mathematician who wrote Note G");
            documents.put(ADA_KING, "Ada King countess who wrote long letters");
            documents.put(BABBAGE, "Charles Babbage designed the Analytical Engine");
            documents.put(NOTE_G, "Note G the first published algorithm");
            documents.put(LONDON, "London capital city");
            documents.put(TURIN, "Turin city in Piedmont");
            documents.put(ANALYTICAL_ENGINE, "Analytical Engine mechanical computer");
            documents.refresh();

            EntityLinker linker = mention -> labels.entrySet().stream()
                .filter(entry -> entry.getValue().toLowerCase(java.util.Locale.ROOT)
                    .contains(mention.toLowerCase(java.util.Locale.ROOT)))
                .mapToLong(Map.Entry::getKey)
                .sorted()
                .toArray();
            richer = new GraphQueryEndpoint(new GraphRagPipeline(
                new QueryClassifier(),
                linker,
                store,
                new HybridRetriever(List.of(new LuceneRanker(documents, "bm25",
                    LuceneRanker.Scale.ATTAINABLE_SHARE)), new ScorePassthrough(),
                    HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH),
                new TripleSubgraphSerializer(id -> labels.getOrDefault(id, "E" + id),
                    relation -> relations.getOrDefault(Math.toIntExact(relation), "R" + relation)),
                new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5),
                new SerializationBudget(2000, BpeTokenCounter.defaultCounter()),
                10,
                null,
                RelationResolver.over(relations),
                id -> labels.getOrDefault(id, "E" + id)));
        }

        @AfterEach
        void close() {
            if (store != null) {
                store.close();
            }
            if (documents != null) {
                documents.close();
            }
        }

        private com.fasterxml.jackson.databind.JsonNode ask(String body) throws IOException {
            Response response = call(richer, "POST", body);
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            return parse(response);
        }

        @Test
        @DisplayName("a name two entities share comes back as needs_input with both candidates")
        void ambiguityNeedsInput() throws IOException {
            var node = ask("{\"query\": \"What did Ada write?\"}");

            assertThat(node.get("status").asText())
                .as("the evidence was there; declining would tell the caller it was not")
                .isEqualTo("needs_input");
            assertThat(node.get("reason").asText()).isEqualTo("ambiguous_entity");
            assertThat(node.get("question").asText()).contains("Ada Lovelace").contains("Ada King");
            assertThat(node.get("candidates"))
                .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactlyInAnyOrder("Ada Lovelace", "Ada King");
            assertThat(node.get("as_of_source").asText())
                .as("an ambiguity found by walking the graph walked it at an instant")
                .isEqualTo("default");
        }

        @Test
        @DisplayName("sending a candidate back in context_entities answers the question")
        void candidateSentBackAnswers() throws IOException {
            var asked = ask("{\"query\": \"What did Ada write?\"}");
            String chosen = asked.get("candidates").get(0).asText();

            var answered = ask("{\"query\": \"What did Ada write?\", \"context_entities\": ["
                + "\"" + chosen + "\"]}");

            assertThat(answered.get("status").asText()).isEqualTo("answered");
            assertThat(answered.get("context").asText()).contains(chosen);
        }

        @Test
        @DisplayName("a count comes back as a number beside the context")
        void countIsReported() throws IOException {
            var node = ask("{\"query\": \"How many things is Ada Lovelace related to?\"}");

            assertThat(node.get("status").asText()).isEqualTo("answered");
            var aggregate = node.get("aggregate");
            assertThat(aggregate.get("function").asText()).isEqualTo("count");
            assertThat(aggregate.get("count").asInt()).isEqualTo(3);
            assertThat(aggregate.get("facts").asInt()).isEqualTo(3);
            assertThat(aggregate.get("entities"))
                .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactly("Charles Babbage", "Note G", "Turin");
            assertThat(aggregate.has("valid_from")).as("a count has no date").isFalse();
            assertThat(node.get("context").asText()).startsWith("# aggregate\ncount: 3");
        }

        @Test
        @DisplayName("an earliest honours as_of and reports the day the fact began")
        void earliestIsReportedAtTheInstant() throws IOException {
            var now = ask("{\"query\": \"What did Ada Lovelace do last?\"}");
            var then = ask("{\"query\": \"What did Ada Lovelace do last?\","
                + " \"as_of\": \"1830-01-01\"}");

            assertThat(now.get("aggregate").get("function").asText()).isEqualTo("latest");
            assertThat(now.get("aggregate").get("valid_from").asText()).isEqualTo("1843-01-01");
            assertThat(then.get("aggregate").get("valid_from").asText()).isEqualTo("1815-01-01");
            assertThat(then.get("aggregate").get("entities").get(0).asText()).isEqualTo("London");
        }

        @Test
        @DisplayName("an extremum with nothing dated reports a null start rather than a made-up one")
        void undatedExtremumIsNull() throws IOException {
            var node = ask("{\"query\": \"What did Charles Babbage do first?\","
                + " \"as_of\": \"1830-01-01\"}");

            assertThat(node.get("aggregate").get("valid_from").isNull()).isTrue();
            assertThat(node.get("aggregate").get("count").asInt()).isZero();
        }

        @Test
        @DisplayName("a pronoun binds only a mention of its shape")
        void shapeRulesOutABinding() throws IOException {
            String entities = "[{\"name\": \"Analytical Engine\", \"grammar\": \"singular_thing\"},"
                + " {\"name\": \"Ada Lovelace\", \"grammar\": \"SINGULAR_PERSON\"}]";

            var she = ask("{\"query\": \"What did she write?\", \"context_entities\": "
                + entities + "}");
            assertThat(she.get("status").asText())
                .as("\"she\" cannot mean the engine, so only Ada Lovelace is left")
                .isEqualTo("answered");
            assertThat(she.get("context").asText()).contains("Note G");

            var it = ask("{\"query\": \"Who designed it?\", \"context_entities\": "
                + entities + "}");
            assertThat(it.get("status").asText())
                .as("\"it\" cannot mean a person, so only the engine is left")
                .isEqualTo("answered");
            assertThat(it.get("context").asText()).contains("Charles Babbage").contains("designed");
        }

        @Test
        @DisplayName("names without a shape stay ambiguous, as they always were")
        void plainNamesKeepTheirMeaning() throws IOException {
            var node = ask("{\"query\": \"What did she write?\", \"context_entities\": "
                + "[\"Analytical Engine\", \"Ada Lovelace\"]}");

            assertThat(node.get("status").asText()).isEqualTo("needs_input");
            assertThat(node.get("reason").asText()).isEqualTo("ambiguous_pronoun");
            assertThat(node.get("candidates"))
                .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .containsExactlyInAnyOrder("Analytical Engine", "Ada Lovelace");
        }

        @Test
        @DisplayName("the most recent turn wins between two mentions of the same shape")
        void theLatestTurnWins() throws IOException {
            // Forward only, so the two bindings answer differently: Ada Lovelace's own facts
            // include Note G, and nothing Charles Babbage asserts leads there.
            String question = "\"query\": \"What did she write?\", \"direction\": \"forward\"";

            var ada = ask("{" + question + ", \"context_entities\": ["
                + "{\"name\": \"Charles Babbage\", \"turn\": 0},"
                + "{\"name\": \"Ada Lovelace\", \"turn\": 1}]}");
            assertThat(ada.get("status").asText()).isEqualTo("answered");
            assertThat(ada.get("context").asText()).contains("Note G");

            var babbage = ask("{" + question + ", \"context_entities\": ["
                + "{\"name\": \"Ada Lovelace\", \"turn\": 3},"
                + "{\"name\": \"Charles Babbage\", \"turn\": 4}]}");
            assertThat(babbage.get("status").asText())
                .as("bound, not asked about: the turns settled it")
                .isNotEqualTo("needs_input");
            assertThat(babbage.path("context").asText()).doesNotContain("Note G");
        }

        @Test
        @DisplayName("an entry without a turn belongs to the latest turn given")
        void anUnnumberedEntryIsTheLatestTurn() throws IOException {
            var node = ask("{\"query\": \"What did she write?\", \"context_entities\": ["
                + "{\"name\": \"Charles Babbage\", \"turn\": 5},"
                + "{\"name\": \"Ada Lovelace\"}]}");

            assertThat(node.get("status").asText()).isEqualTo("needs_input");
            assertThat(node.get("reason").asText())
                .as("both in turn 5, and nothing within one turn says which was meant")
                .isEqualTo("ambiguous_pronoun");
        }

        @Test
        @DisplayName("a mention older than the window is not bound to")
        void theWindowForgets() throws IOException {
            // The question is turn 4; a three-turn window reaches back to turn 2.
            var node = ask("{\"query\": \"What did she write?\", \"context_entities\": ["
                + "{\"name\": \"Ada Lovelace\", \"grammar\": \"singular_person\", \"turn\": 0},"
                + "{\"name\": \"London\", \"grammar\": \"singular_thing\", \"turn\": 3}]}");

            assertThat(node.get("status").asText()).isEqualTo("needs_input");
            assertThat(node.get("reason").asText()).isEqualTo("unbound_pronoun");
        }

        @Test
        @DisplayName("forward and reverse read the two ends of an edge apart; both reads each")
        void directionIsHonoured() throws IOException {
            String query = "\"query\": \"Who did Charles Babbage collaborate with?\"";
            var forward = ask("{" + query + ", \"direction\": \"forward\"}");
            var reverse = ask("{" + query + ", \"direction\": \"REVERSE\"}");
            var both = ask("{" + query + "}");

            assertThat(forward.get("context").asText()).contains("designed")
                .doesNotContain("collaboratedWith");
            assertThat(reverse.get("context").asText()).contains("collaboratedWith")
                .doesNotContain("designed");
            assertThat(both.get("context").asText()).contains("collaboratedWith")
                .contains("designed");
        }
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
        private com.eonmux.excalibur.api.bootstrap.GraphTenants tenants;
        private GraphQueryEndpoint tenantEndpoint;

        @BeforeEach
        void openTenants(@TempDir Path tempDir) throws IOException {
            root = tempDir;
            tenants = new com.eonmux.excalibur.api.bootstrap.GraphTenants(
                tempDir,
                new com.eonmux.excalibur.config.Config(new java.util.HashMap<>(
                    Map.of("excalibur.graph.ram-buffer-mb", "4.0"))),
                com.eonmux.excalibur.tenancy.MappingBudget.detect(),
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
            assertThat(response.body()).contains("\"code\": \"graph_not_served\"");
            assertThat(root.resolve("never-built")).doesNotExist();
            assertThat(tenants.openNamespaces())
                .containsExactly(com.eonmux.excalibur.tenancy.NamespaceId.SHARED);
        }

        @Test
        @DisplayName("a namespace that has never existed is answered as an unbuilt one is")
        void anUnknownNamespaceIsIndistinguishableFromAnUnbuiltOne() throws IOException {
            tenants.borrow(com.eonmux.excalibur.tenancy.NamespaceId.of("empty")).close();

            assertThat(ask("never-built"))
                .as("every tenant presents the same bearer token, so a reply that separated "
                    + "'no such namespace' from 'nothing built there' would answer which "
                    + "namespaces other tenants have")
                .isEqualTo(ask("empty"));
        }

        @Test
        @DisplayName("a namespace on disk is reopened to answer, not skipped")
        void anExistingNamespaceIsReopened() throws IOException {
            com.eonmux.excalibur.tenancy.NamespaceId acme = com.eonmux.excalibur.tenancy.NamespaceId.of("acme");
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
                .containsExactly(com.eonmux.excalibur.tenancy.NamespaceId.SHARED);
        }

        @Test
        @DisplayName("a bad direction is refused before any namespace is touched")
        void badDirectionIsRefusedFirst() throws IOException {
            Response response = ask("never-built",
                "{\"query\": \"Who did Ada Lovelace collaborate with?\", \"direction\": \"up\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(root.resolve("never-built")).doesNotExist();
        }

        @Test
        @DisplayName("a namespace that is not a namespace is a 400")
        void anInvalidNamespaceIsRefused() throws IOException {
            assertThat(ask("Not A Namespace").status()).isEqualTo(400);
        }

        private Response ask(String namespace) throws IOException {
            return ask(namespace, "{\"query\": \"Who did Ada Lovelace collaborate with?\"}");
        }

        private Response ask(String namespace, String body) throws IOException {
            HttpExchange exchange = Mockito.mock(HttpExchange.class);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int[] status = new int[1];
            Headers requestHeaders = new Headers();
            if (namespace != null) {
                requestHeaders.set(
                    com.eonmux.excalibur.api.bootstrap.GraphTenants.NAMESPACE_HEADER, namespace);
            }

            Mockito.when(exchange.getRequestMethod()).thenReturn("POST");
            Mockito.when(exchange.getRequestURI()).thenReturn(URI.create("/v1/graph/query"));
            Mockito.when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
            Mockito.when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8)));
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
