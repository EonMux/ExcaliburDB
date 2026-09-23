package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.api.bootstrap.CoreComponents;
import com.eonmux.excalibur.api.bootstrap.DocumentRegistryFactory;
import com.eonmux.excalibur.api.bootstrap.GraphRagFactory;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.IngestionWiring;
import com.eonmux.excalibur.api.bootstrap.QueryPipelineFactory;
import com.eonmux.excalibur.api.bootstrap.StorageChunkWriter;
import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.erasure.SegmentVectorEraser;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vector hits name the chunk they embed, on a node composed the way the application composes one.
 *
 * <p>Each graph node is attributed to the chunk it was embedded from, so a vector hit comes back
 * under the same {@code source:document:chunk:N} identifier the lexical index uses and the
 * chunk's text is stored under. What that buys is only visible end to end: a hybrid answer fuses
 * the two signals' hits on one chunk instead of listing the chunk and its document separately,
 * {@code include_text} reads the chunk the vector matched, a document posted again leaves no
 * vector of its earlier version rankable, an erasure leaves none of its vectors, and a data
 * directory an older build attributed by document is re-keyed rather than served as it was.</p>
 *
 * <p>The embedding and chat models are the one stand-in: {@link FakeEmbeddingServer} on
 * loopback. Everything between it and the HTTP responses is the production code, and the
 * integration worker that attributes nodes is the node's own, running on its own schedule.</p>
 */
@DisplayName("Chunk-attributed vectors on a running node")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ChunkAttributionNodeTest {

    private static final String TOKEN = "chunk-attribution-token";
    private static final int DIMENSION = 256;
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String SOURCE = "kb";

    /** One chunk's worth of text about tiering; every word differs from the other documents'. */
    private static final String TIERING =
        "The tiering policy moves cold segments onto cheaper disks overnight.";

    /** Another single-chunk document, sharing no word with the tiering one. */
    private static final String GRAPHS =
        "Hierarchical navigable graphs link every embedding with its nearest neighbours.";

    @TempDir
    Path dataDir;

    private FakeEmbeddingServer models;
    private Node node;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startModels() throws IOException {
        models = FakeEmbeddingServer.start(DIMENSION);
    }

    @AfterEach
    void stopEverything() {
        if (node != null) {
            node.close();
        }
        if (models != null) {
            models.close();
        }
    }

    /** One running node over {@link #dataDir}: what the application starts, and stops. */
    private final class Node implements AutoCloseable {
        final CoreComponents core;
        final GraphTenants tenants;
        final IngestionWiring.Attachment attachment;
        final ExcaliburDBServer server;

        Node() throws IOException {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.query.vector-dimensions", Integer.toString(DIMENSION));
            props.put("excalibur.llm.embeddings-dimensions", Integer.toString(DIMENSION));
            props.put("excalibur.llm.api-key", "integration-test-key");
            props.put("excalibur.llm.base-url", models.baseUrl());
            props.put("excalibur.llm.embeddings-enabled", "true");
            props.put("excalibur.graph.extraction-enabled", "false");
            props.put("excalibur.graph.entity-vectors-enabled", "false");
            props.put("excalibur.graph.ram-buffer-mb", "4.0");
            props.put(ApiSecurityConfig.KEY_AUTH_TOKEN, TOKEN);
            Config config = new Config(props);

            // As ExcaliburApplication composes a node, including the erasure service it wires
            // on every start.
            core = CoreComponents.create(config, dataDir, HnswConfigFactory.withDefaults());
            DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(
                DocumentRegistryFactory.open(dataDir),
                core.indexOrchestrator().getVisibilityTracker());
            QueryPipeline queryPipeline = QueryPipelineFactory.create(config,
                core.storageEngine(), core.indexOrchestrator(),
                new DocumentVisibilityTrackerAdapter(tracker));
            tenants = new GraphTenants(GraphRagFactory.tenantRootFor(config, dataDir), config,
                MappingBudget.detect(), config.graph().getMaxOpenNamespaces());

            server = new ExcaliburDBServer(0, ExcaliburDBServer.DEFAULT_BACKLOG,
                core.indexOrchestrator(), core.storageEngine(), core.volumeManager(), config,
                queryPipeline);
            server.withSecurity(ApiSecurityConfig.fromConfig(config));
            server.withSourceCatalog(SourceCatalog.open(dataDir));
            attachment = new IngestionWiring(config, dataDir, core.storageEngine(),
                core.indexOrchestrator(), tracker, tenants)
                .attachTo(server);
            server.withDocumentRegistry(tracker.getRegistry());
            server.withDocumentErasure(new DocumentErasureService(tracker.getRegistry(),
                StorageChunkWriter.tombstoneAdapter(core.storageEngine()),
                attachment.chunkIndex(), StoredChunkText.of(core.storageEngine()), tracker,
                SegmentVectorEraser.of(core.storageEngine())));
            server.withGraphSupport(tenants);
            server.start();
        }

        /** Every segment's graph, for what the node's own worker has attributed. */
        List<Map.Entry<Segment, HnswGraph>> graphs() {
            List<Map.Entry<Segment, HnswGraph>> graphs = new ArrayList<>();
            for (Segment segment : core.storageEngine().getSegments().values()) {
                HnswGraph graph = segment.getHnswGraph();
                if (graph != null) {
                    graphs.add(Map.entry(segment, graph));
                }
            }
            return graphs;
        }

        /** Whether every write the segments hold has been integrated into their graphs. */
        boolean integrated() {
            for (Map.Entry<Segment, HnswGraph> entry : graphs()) {
                if (entry.getValue().getLastIntegratedOffset()
                        < entry.getKey().getWriteLogHead()) {
                    return false;
                }
            }
            return true;
        }

        /** The chunks every live node of every graph is attributed to, one entry per node. */
        List<String> liveAttributions() {
            List<String> attributed = new ArrayList<>();
            for (Map.Entry<Segment, HnswGraph> entry : graphs()) {
                HnswGraph graph = entry.getValue();
                graph.getNodeDocumentMapping().getAllMappings().forEach((nodeId, key) -> {
                    if (!graph.isTombstoned(nodeId)) {
                        attributed.add(key);
                    }
                });
            }
            return attributed;
        }

        @Override
        public void close() {
            server.stop(0);
            if (attachment.lexicalErasureSweep() != null) {
                attachment.lexicalErasureSweep().close();
            }
            if (attachment.chunkIndex() != null) {
                attachment.chunkIndex().close();
            }
            tenants.close();
            try {
                core.indexOrchestrator().close();
            } catch (RuntimeException ignored) {
                // Best effort; the storage engine below is what holds the files.
            }
            core.storageEngine().close();
            if (core.arena() != null) {
                core.arena().close();
            }
        }
    }

    private record Reply(int status, Object json, String body) {
    }

    private Reply send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + node.server.getPort() + path))
            .header("Authorization", "Bearer " + TOKEN)
            .timeout(Duration.ofSeconds(30));
        if (body != null) {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response =
            http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Object json = response.body().isBlank() ? null : Json.parse(response.body());
        return new Reply(response.statusCode(), json, response.body());
    }

    /** Posts documents and returns how many chunks they were published as. */
    private long post(Map<String, String> documents) throws Exception {
        StringJoiner items = new StringJoiner(",");
        documents.forEach((id, text) -> items.add("{\"document_id\":" + Json.write(id)
            + ",\"text\":" + Json.write(text) + "}"));
        Reply reply = send("POST", "/v1/documents",
            "{\"source_id\":\"" + SOURCE + "\",\"documents\":[" + items + "]}");
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(((Number) Json.at(reply.json(), "documents_failed")).longValue()).isZero();
        return ((Number) Json.at(reply.json(), "chunks_ingested")).longValue();
    }

    /** Waits for the node's integration worker to have absorbed every write. */
    private void awaitIntegrated() throws InterruptedException {
        await(node::integrated, "the integration worker absorbed every write");
    }

    private static void await(BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    /**
     * What the node answered: its results, and the body they came in, which is what a failed
     * assertion about them has to show.
     */
    private record Answer(List<Map<String, Object>> results, String body) {

        Map<String, Object> top() {
            assertThat(results).as(body).isNotEmpty();
            return results.getFirst();
        }
    }

    /** Asks the node a question and returns its answer, text included. */
    private Answer query(String question) throws Exception {
        Reply reply = send("POST", "/v1/query", "{\"query\":" + Json.write(question)
            + ",\"top_k\":10,\"enable_llm_scoring\":false,\"include_text\":true,"
            + "\"snippet\":true,\"timeout_ms\":30000}");
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        // Both signals, and all of each: a vector half cut short by its deadline on a cold
        // node would pass for a vector hit that names nothing.
        assertThat(Json.stringAt(reply.json(), "retrieval")).as(reply.body()).isEqualTo("hybrid");
        assertThat(Json.at(reply.json(), "timed_out")).as(reply.body()).isEqualTo(Boolean.FALSE);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object result : (List<?>) Json.at(reply.json(), "results")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) result;
            results.add(item);
        }
        return new Answer(results, reply.body());
    }

    /**
     * Asks until the top result carries both signals, and returns the last answer either way.
     *
     * <p>A chunk is searchable by BM25 from the visibility epoch after the one its lexical
     * segment was published in, and the epoch advances on the node's own tick, so a question put
     * as soon as ingestion returns can be answered before the chunk is in the lexical half. The
     * assertions made on the answer are the test; this only waits out that lag.</p>
     */
    private Answer queryBothSignals(String question) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        Answer answer = query(question);
        while (!carriesBothSignals(answer) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            answer = query(question);
        }
        return answer;
    }

    private static boolean carriesBothSignals(Answer answer) {
        return !answer.results().isEmpty()
            && signalsOf(answer.results().getFirst()).containsAll(List.of("vector", "bm25"));
    }

    private static String idOf(Map<String, Object> result) {
        return (String) result.get("document_id");
    }

    private static List<Object> signalsOf(Map<String, Object> result) {
        Object signals = result.get("signals");
        return signals instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    /** A document long enough to be split into several chunks, in words no other text uses. */
    private static String longDocument(String stem, int words) {
        StringJoiner text = new StringJoiner(" ");
        for (int i = 0; i < words; i++) {
            text.add(stem + i);
        }
        return text + ".";
    }

    @Test
    @DisplayName("a vector hit names its chunk, fuses with the BM25 hit on it, and reads its text")
    void aVectorHitNamesItsChunk() throws Exception {
        node = new Node();
        post(Map.of("tiering.md", TIERING, "graphs.md", GRAPHS));
        awaitIntegrated();

        assertThat(node.liveAttributions())
            .as("each node is attributed to the chunk it embeds")
            .containsExactlyInAnyOrder(SOURCE + ":tiering.md:chunk:0",
                SOURCE + ":graphs.md:chunk:0");

        Answer answer =
            queryBothSignals("which tiering policy moves cold segments onto cheaper disks");

        Map<String, Object> top = answer.top();
        assertThat(idOf(top)).as(answer.body()).isEqualTo(SOURCE + ":tiering.md:chunk:0");
        assertThat(signalsOf(top))
            .as("vector search and BM25 named the same chunk, so fusion merged them: %s",
                answer.body())
            .containsExactlyInAnyOrder((Object) "vector", "bm25");
        assertThat(answer.results()).extracting(ChunkAttributionNodeTest::idOf)
            .as("a chunk is one result, and no result names a bare document")
            .doesNotHaveDuplicates()
            .allSatisfy(id -> assertThat(id).contains(":chunk:"));
        assertThat((String) top.get("text"))
            .as("include_text reads the chunk the vector matched")
            .isEqualTo(TIERING);
        assertThat((List<?>) top.get("highlights"))
            .as("the snippet locates the matched terms inside that chunk")
            .isNotEmpty();
    }

    @Test
    @DisplayName("a document posted again leaves no vector of its earlier version rankable")
    void aRepostLeavesNoStaleVector() throws Exception {
        node = new Node();
        String earlier = longDocument("orbital", 900);
        long earlierChunks = post(Map.of("notes.md", earlier, "graphs.md", GRAPHS));
        assertThat(earlierChunks).as("the earlier version needs a surplus to retire")
            .isGreaterThan(2);
        awaitIntegrated();

        post(Map.of("notes.md", TIERING));
        awaitIntegrated();

        assertThat(node.liveAttributions())
            .as("the rewritten chunk kept one node, the surplus chunks none")
            .containsExactlyInAnyOrder(SOURCE + ":notes.md:chunk:0",
                SOURCE + ":graphs.md:chunk:0");

        List<Map<String, Object>> results = query(earlier.substring(0, 200)).results();
        assertThat(results).extracting(ChunkAttributionNodeTest::idOf)
            .as("nothing ranks under a chunk the document no longer has")
            .noneMatch(id -> id.startsWith(SOURCE + ":notes.md:chunk:")
                && !id.equals(SOURCE + ":notes.md:chunk:0"));
        results.stream()
            .filter(result -> idOf(result).equals(SOURCE + ":notes.md:chunk:0"))
            .forEach(result -> assertThat((String) result.get("text"))
                .as("chunk 0 is answered as it is now")
                .isEqualTo(TIERING));
    }

    @Test
    @DisplayName("an erased document leaves no vector in any graph")
    void anErasureLeavesNoVector() throws Exception {
        node = new Node();
        String secret = longDocument("quarantined", 900);
        post(Map.of("secret.md", secret, "graphs.md", GRAPHS));
        awaitIntegrated();
        assertThat(node.liveAttributions()).anyMatch(id -> id.startsWith(SOURCE + ":secret.md:"));

        Reply erased = send("POST", "/v1/erasure",
            "{\"source_id\":\"" + SOURCE + "\",\"document_id\":\"secret.md\"}");
        assertThat(erased.status()).as(erased.body()).isBetween(200, 202);
        awaitIntegrated();

        assertThat(node.liveAttributions())
            .as("every chunk of the erased document has lost its vector")
            .containsExactly(SOURCE + ":graphs.md:chunk:0");
        assertThat(query(secret.substring(0, 200)).results()).extracting(ChunkAttributionNodeTest::idOf)
            .noneMatch(id -> ChunkIdentifiers.documentKeyOfChunk(id)
                .equals(SOURCE + ":secret.md"));
    }

    @Test
    @DisplayName("a graph an older build attributed by document is re-keyed to chunks")
    void aLegacyMappingIsUpgraded() throws Exception {
        node = new Node();
        post(Map.of("tiering.md", TIERING, "graphs.md", GRAPHS));
        awaitIntegrated();
        node.close();
        node = null;

        List<Path> mappings = mappingsUnder(dataDir);
        assertThat(mappings).isNotEmpty();
        for (Path mapping : mappings) {
            rewriteAsDocumentKeyed(mapping);
        }

        node = new Node();
        await(() -> node.graphs().stream().allMatch(entry -> entry.getValue()
                .attributionKeying() == NodeDocumentMapping.Keying.CHUNK),
            "the node re-keyed every graph an older build attributed by document");
        assertThat(node.liveAttributions()).containsExactlyInAnyOrder(
            SOURCE + ":tiering.md:chunk:0", SOURCE + ":graphs.md:chunk:0");

        Answer answer =
            queryBothSignals("which tiering policy moves cold segments onto cheaper disks");
        Map<String, Object> top = answer.top();
        assertThat(idOf(top)).as(answer.body()).isEqualTo(SOURCE + ":tiering.md:chunk:0");
        assertThat(signalsOf(top)).as(answer.body()).contains((Object) "vector", "bm25");

        node.close();
        node = null;
        for (Path mapping : mappingsUnder(dataDir)) {
            var report = NodeDocumentMapping.verifyIntegrity(mapping);
            assertThat(report.status()).as(report.detail()).isEqualTo(IntegrityStatus.INTACT);
            assertThat(report.detail())
                .as("the upgrade was written back, so it is not repeated on every start")
                .doesNotContain("name documents");
        }
    }

    /** Every live node-to-chunk mapping the data directory holds. */
    private static List<Path> mappingsUnder(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(path -> path.getFileName().toString()
                    .endsWith(".hnsw" + HnswGraph.DOCUMENT_MAPPING_SUFFIX))
                .toList();
        }
    }

    /**
     * Rewrites a mapping as an older build wrote it: each node named by its document, in the
     * sealed layout at the version whose entries name documents.
     */
    private static void rewriteAsDocumentKeyed(Path file) throws IOException {
        NodeDocumentMapping current = NodeDocumentMapping.createOrLoad(file, true);
        Map<Long, String> byChunk = current.getAllMappings();
        Files.delete(file);
        NodeDocumentMapping legacy = new NodeDocumentMapping(file);
        byChunk.forEach((nodeId, chunkId) ->
            legacy.register(nodeId, ChunkIdentifiers.documentKeyOfChunk(chunkId)));
        legacy.persist();

        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(Long.BYTES, NodeDocumentMapping.VERSION_DOCUMENT_KEYED);
        header.putLong(40, IndexChecksum.of(bytes, 0, 40));
        Files.write(file, bytes);
    }
}
