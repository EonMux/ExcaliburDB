package excalibur.api.server;

import excalibur.api.bootstrap.GraphRagFactory;
import excalibur.api.security.ApiSecurityConfig;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.EpochTickScheduler;
import excalibur.shard.IndexOrchestrator;
import io.excalibur.config.Config;
import io.excalibur.graph.dictionary.LabelDictionaryWriter;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.temporal.TemporalFilter;
import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.pipeline.LexicalEntityLinker;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that {@code POST /v1/graph/query} is actually reachable on a running server.
 *
 * <p>It was not. The route is registered only when a graph pipeline has been handed to the
 * server, and nothing ever handed it one - so the whole graph-RAG layer, and a documented
 * endpoint, answered 404 on every deployment while every unit test of its components passed.
 * A test that drives the real socket is the one that notices that.
 */
@DisplayName("ExcaliburDBServer graph route")
class ExcaliburDBServerGraphRouteTest {

    private static final String TOKEN = "test-bearer-token";
    private static final int ADA = 1;
    private static final int BABBAGE = 2;
    private static final int COLLABORATED_WITH = 0;

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private ExcaliburDBServer server;
    private GraphRagFactory.GraphRag graph;

    @BeforeEach
    void setUp() {
        SegmentCatalog catalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        queryPipeline = mock(QueryPipeline.class);

        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new excalibur.shard.VisibilityTracker(catalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (graph != null) {
            graph.close();
        }
    }

    private void startWithGraph(Path dataDir) throws Exception {
        Path directory = dataDir.resolve(GraphRagFactory.DEFAULT_DIRECTORY);

        EdgeStoreWriter edges = new EdgeStoreWriter();
        edges.add(ADA, COLLABORATED_WITH, BABBAGE, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.KNOWLEDGE_BASE, 1, 96, 7));
        edges.build(directory, 8);

        try (LexicalIndex documents = LexicalIndex.open(
                directory.resolve(GraphRagFactory.DOCUMENT_INDEX), 16.0)) {
            documents.put(ADA, "Ada Lovelace mathematician");
            documents.put(BABBAGE, "Charles Babbage engineer");
            documents.refresh();
        }
        try (LexicalIndex labels = LexicalIndex.open(
                directory.resolve(GraphRagFactory.LABEL_INDEX), 16.0)) {
            LexicalEntityLinker.index(labels, ADA, "Ada Lovelace");
            LexicalEntityLinker.index(labels, BABBAGE, "Charles Babbage");
            labels.refresh();
        }
        new LabelDictionaryWriter().put(ADA, "Ada Lovelace").put(BABBAGE, "Charles Babbage")
            .write(directory, GraphRagFactory.ENTITY_DICTIONARY);
        new LabelDictionaryWriter().put(COLLABORATED_WITH, "collaboratedWith")
            .write(directory, GraphRagFactory.RELATION_DICTIONARY);

        graph = GraphRagFactory.open(new Config(new HashMap<>()), dataDir);
        assertThat(graph).isNotNull();

        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.withGraphPipeline(graph.pipeline());
        server.start();
    }

    private void startWithoutGraph() throws IOException {
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.start();
    }

    private record Response(int status, String body) {
    }

    private Response post(String body, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + "/v1/graph/query")
            .toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String responseBody = stream == null
            ? ""
            : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, responseBody);
    }

    @Test
    @DisplayName("answers a question over HTTP when a graph is configured")
    void answersOverHttp(@TempDir Path dataDir) throws Exception {
        startWithGraph(dataDir);

        Response response = post("{\"query\": \"Who did Ada Lovelace collaborate with?\"}", TOKEN);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("answered");
        assertThat(body.get("context").asText()).contains("Charles Babbage");
        assertThat(body.get("timings_ms").isObject()).isTrue();
    }

    @Test
    @DisplayName("requires a bearer token like every other non-probe endpoint")
    void requiresAToken(@TempDir Path dataDir) throws Exception {
        startWithGraph(dataDir);

        assertThat(post("{\"query\": \"anything\"}", null).status()).isEqualTo(401);
        assertThat(post("{\"query\": \"anything\"}", "wrong-token").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("is 404 when the node has no graph, not an error")
    void notRegisteredWithoutAGraph() throws Exception {
        startWithoutGraph();

        // A vector-only deployment. The route is absent rather than present-and-broken.
        assertThat(post("{\"query\": \"anything\"}", TOKEN).status()).isEqualTo(404);
    }
}
