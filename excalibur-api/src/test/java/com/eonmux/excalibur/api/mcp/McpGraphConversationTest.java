package com.eonmux.excalibur.api.mcp;

import com.sun.net.httpserver.HttpServer;

import com.eonmux.excalibur.api.endpoint.GraphQueryEndpoint;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.planner.QueryClassifier;
import com.eonmux.excalibur.retrieval.HybridRetriever;
import com.eonmux.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import com.eonmux.excalibur.retrieval.fusion.ScorePassthrough;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LuceneRanker;
import com.eonmux.excalibur.retrieval.pipeline.EntityLinker;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;
import com.eonmux.excalibur.retrieval.pipeline.RelationResolver;
import com.eonmux.excalibur.retrieval.serialize.BpeTokenCounter;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.TripleSubgraphSerializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code search_graph} against the real graph query endpoint, not a stub.
 *
 * <p>{@link McpToolsTest} shows what the tool sends. This shows that what it sends is read the way
 * the model meant it: a shape rules a binding out and a later turn wins, through the tool, the
 * HTTP client and the endpoint, over a graph where each binding answers differently.</p>
 */
@DisplayName("search_graph conversation, end to end")
class McpGraphConversationTest {

    private static final int ADA = 1;
    private static final int BABBAGE = 2;
    private static final int NOTE_G = 3;
    private static final int ENGINE = 4;
    private static final int AUTHORED = 0;
    private static final int DESIGNED = 1;

    private final Map<Long, String> labels = Map.of(
        (long) ADA, "Ada Lovelace", (long) BABBAGE, "Charles Babbage",
        (long) NOTE_G, "Note G", (long) ENGINE, "Analytical Engine");
    private final Map<Integer, String> relations = Map.of(AUTHORED, "authored",
        DESIGNED, "designed");

    private MappedEdgeStore store;
    private LexicalIndex documents;
    private HttpServer server;
    private McpTools tools;

    @BeforeEach
    void start(@TempDir Path root) throws IOException {
        Provenance curated = new Provenance(SourceType.USER_ASSERTED, 1, 96, 7);
        EdgeStoreWriter writer = new EdgeStoreWriter();
        writer.add(ADA, AUTHORED, NOTE_G, Integer.MIN_VALUE, TemporalFilter.OPEN, curated);
        writer.add(BABBAGE, DESIGNED, ENGINE, Integer.MIN_VALUE, TemporalFilter.OPEN, curated);
        writer.build(root.resolve("graph"), 16);
        store = MappedEdgeStore.open(root.resolve("graph"));

        documents = LexicalIndex.open(root.resolve("docs"), 16.0);
        documents.put(ADA, "Ada Lovelace mathematician who wrote Note G");
        documents.put(BABBAGE, "Charles Babbage designed the Analytical Engine");
        documents.put(NOTE_G, "Note G the first published algorithm");
        documents.put(ENGINE, "Analytical Engine mechanical computer");
        documents.refresh();

        EntityLinker linker = mention -> labels.entrySet().stream()
            .filter(entry -> entry.getValue().toLowerCase(Locale.ROOT)
                .contains(mention.toLowerCase(Locale.ROOT)))
            .mapToLong(Map.Entry::getKey)
            .sorted()
            .toArray();
        GraphRagPipeline pipeline = new GraphRagPipeline(
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
            id -> labels.getOrDefault(id, "E" + id));

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/graph/query", new GraphQueryEndpoint(pipeline));
        server.start();
        tools = new McpTools(ExcaliburClient.fromEnvironment(Map.of("EXCALIBUR_URL",
            "http://127.0.0.1:" + server.getAddress().getPort())));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (store != null) {
            store.close();
        }
        if (documents != null) {
            documents.close();
        }
    }

    private Object ask(Map<String, Object> arguments) {
        McpTools.ToolResult result = tools.call(McpTools.SEARCH_GRAPH, arguments);
        assertThat(result.isError()).as(result.text()).isFalse();
        return Json.parse(result.text());
    }

    @Test
    @DisplayName("a shape rules a binding out")
    void shapeResolves() {
        List<Object> entities = List.of(
            Map.of("name", "Analytical Engine", "grammar", "singular_thing"),
            Map.of("name", "Ada Lovelace", "grammar", "singular_person"));

        Object she = ask(Map.of("query", "What did she write?", "context_entities", entities));
        assertThat(Json.stringAt(she, "status")).isEqualTo("answered");
        assertThat(Json.stringAt(she, "context")).contains("Note G");

        Object it = ask(Map.of("query", "Who designed it?", "context_entities", entities));
        assertThat(Json.stringAt(it, "status")).isEqualTo("answered");
        assertThat(Json.stringAt(it, "context")).contains("Charles Babbage");
    }

    @Test
    @DisplayName("names alone leave the pronoun ambiguous, as they do over HTTP")
    void namesAloneAreAmbiguous() {
        Object answer = ask(Map.of("query", "What did she write?",
            "context_entities", List.of("Analytical Engine", "Ada Lovelace")));

        assertThat(Json.stringAt(answer, "status")).isEqualTo("needs_input");
        assertThat(Json.stringAt(answer, "reason")).isEqualTo("ambiguous_pronoun");
    }

    @Test
    @DisplayName("the later turn wins between two people")
    void recencyResolves() {
        // Forward only, so each binding answers differently: Ada Lovelace's own facts lead to
        // Note G, and nothing Charles Babbage asserts does.
        Object ada = ask(Map.of("query", "What did she write?", "direction", "forward",
            "context_entities", List.of(
                Map.of("name", "Charles Babbage", "grammar", "singular_person", "turn", 0),
                Map.of("name", "Ada Lovelace", "grammar", "singular_person", "turn", 1))));
        assertThat(Json.stringAt(ada, "status")).isEqualTo("answered");
        assertThat(Json.stringAt(ada, "context")).contains("Note G");

        Object babbage = ask(Map.of("query", "What did she write?", "direction", "forward",
            "context_entities", List.of(
                Map.of("name", "Ada Lovelace", "grammar", "singular_person", "turn", 0),
                Map.of("name", "Charles Babbage", "grammar", "singular_person", "turn", 1))));
        assertThat(Json.stringAt(babbage, "status")).isNotEqualTo("needs_input");
        assertThat(String.valueOf(Json.at(babbage, "context"))).doesNotContain("Note G");
    }
}
