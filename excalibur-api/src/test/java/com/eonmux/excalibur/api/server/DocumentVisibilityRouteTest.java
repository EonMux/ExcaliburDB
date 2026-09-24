package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every route that reads the corpus withholds a document while its new version is written.
 *
 * <p>The filter and the text gate are only as good as the routes they are handed to, so these
 * run a server: a BM25 query with its text, a chunk read by id, and a document read through the
 * listing, each asked about a document whose rewrite is in flight and about one nobody is
 * touching.</p>
 */
@DisplayName("Routes over a document being rewritten")
class DocumentVisibilityRouteTest {

    private static final String TOKEN = "test-bearer-token";

    @TempDir
    Path dataDir;

    private final Map<String, String> storage = new ConcurrentHashMap<>();
    private ChunkLexicalIndex index;
    private ExcaliburDBServer server;

    @BeforeEach
    void setUp() throws Exception {
        SegmentCatalog catalog = new SegmentCatalog();
        VisibilityTracker clock = new VisibilityTracker(catalog);
        IndexOrchestrator indexOrchestrator = mock(IndexOrchestrator.class);
        StorageEngine storageEngine = mock(StorageEngine.class);
        QueryPipeline queryPipeline = mock(QueryPipeline.class);
        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker()).thenReturn(clock);
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);
        when(storageEngine.get(any(byte[].class))).thenAnswer(invocation -> {
            String text = storage.get(
                new String((byte[]) invocation.getArgument(0), StandardCharsets.UTF_8));
            return text == null ? null
                : MemorySegment.ofArray(ChunkPayloadCodec.encodeTextOnly(text));
        });
        AtomicReference<DocumentVisibilityChecker> installed = new AtomicReference<>();
        when(queryPipeline.getVisibilityChecker()).thenAnswer(invocation -> installed.get());
        when(queryPipeline.withVisibilityChecker(any())).thenAnswer(invocation -> {
            installed.set(invocation.getArgument(0));
            return queryPipeline;
        });

        DocumentRegistry registry = new DocumentRegistry(dataDir.resolve("document-registry"));
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(registry, clock);
        index = ChunkLexicalIndex.open(dataDir.resolve("lexical"), 8.0);
        for (String documentId : List.of("a.txt", "b.txt")) {
            String chunkId = chunkOf(documentId);
            String text = "shared words of " + documentId.charAt(0);
            storage.put(chunkId, text);
            index.add(chunkId, text);
            DocumentMetadata metadata = DocumentMetadata.builder().sourceId("docs")
                .documentId(documentId).contentLength(text.length()).addChunkId(chunkId).build();
            registry.register(metadata);
            tracker.registerDocument(ChunkIdentifiers.documentKey("docs", documentId),
                Set.of(chunkId), tracker.advanceEpoch());
        }
        index.refresh();
        tracker.beginIngestion(ChunkIdentifiers.documentKey("docs", "a.txt"), 1);

        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.withDocumentRegistry(registry);
        server.withIngestionPipeline(mock(TextChunker.class), null, null, tracker);
        server.withLexicalQueryService(new LexicalQueryService(index, null, List.of()));
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        index.close();
    }

    @Test
    @DisplayName("a BM25 query answers the untouched document and not the one in flight")
    void query() throws Exception {
        JsonNode body = request("POST", "/v1/query",
            "{\"query\": \"shared words\", \"top_k\": 10, \"include_text\": true}");

        assertThat(body.path("retrieval").asText()).isEqualTo("lexical");
        assertThat(body.get("results")).extracting(result -> result.get("document_id").asText())
            .containsExactly(chunkOf("b.txt"));
        assertThat(body.get("results").get(0).get("text").asText()).isEqualTo("shared words of b");
    }

    @Test
    @DisplayName("a chunk read by id has no text while its document is in flight")
    void fetch() throws Exception {
        JsonNode rewriting = request("GET", "/v1/documents?document_id="
            + URLEncoder.encode(chunkOf("a.txt"), StandardCharsets.UTF_8), null);
        JsonNode untouched = request("GET", "/v1/documents?document_id="
            + URLEncoder.encode(chunkOf("b.txt"), StandardCharsets.UTF_8), null);

        assertThat(rewriting.get("documents").get(0).get("text").isNull()).isTrue();
        assertThat(rewriting.get("documents_not_found").asInt()).isEqualTo(1);
        assertThat(untouched.get("documents").get(0).get("text").asText())
            .isEqualTo("shared words of b");
    }

    @Test
    @DisplayName("a document read through the listing has no text while it is in flight")
    void list() throws Exception {
        JsonNode rewriting = request("GET",
            "/v1/documents/list?source_id=docs&document_id=a.txt&include_text=true", null);
        JsonNode untouched = request("GET",
            "/v1/documents/list?source_id=docs&document_id=b.txt&include_text=true", null);

        assertThat(rewriting.get("chunks").get(0).get("text").isNull()).isTrue();
        assertThat(untouched.get("chunks").get(0).get("text").asText())
            .isEqualTo("shared words of b");
    }

    private JsonNode request(String method, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        assertThat(status).as("%s %s answered %s", method, path, response).isEqualTo(200);
        return new ObjectMapper().readTree(response);
    }

    private static String chunkOf(String documentId) {
        return ChunkIdentifiers.chunkId("docs", documentId, 0);
    }
}
