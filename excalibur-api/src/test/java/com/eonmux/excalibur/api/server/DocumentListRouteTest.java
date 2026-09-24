package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that {@code GET /v1/documents/list} is reachable on a running server.
 *
 * <p>A route is registered by one line in one method, and every unit test of the endpoint
 * passes whether or not that line exists. This one is registered conditionally - only on a node
 * that keeps a document registry - so both halves of that condition are worth pinning: the
 * listing answers where there is a registry, and 404s rather than lying about an empty corpus
 * where there is not.</p>
 */
@DisplayName("GET /v1/documents/list over HTTP")
class DocumentListRouteTest {

    private static final String TOKEN = "test-bearer-token";

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private DocumentRegistry registry;
    private ExcaliburDBServer server;

    @BeforeEach
    void setUp() {
        SegmentCatalog catalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        queryPipeline = mock(QueryPipeline.class, Answers.RETURNS_SELF);

        EpochTickScheduler scheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(catalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(scheduler);
        when(scheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(catalog);

        registry = new DocumentRegistry();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void ingest(String sourceId, String documentId, int chunks) {
        DocumentMetadata.Builder metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .contentLength(100);
        for (int i = 0; i < chunks; i++) {
            metadata.addChunkId(sourceId + ":" + documentId + ":chunk:" + i);
        }
        registry.register(metadata.build());
    }

    private void startWithRegistry() throws IOException {
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.withDocumentRegistry(registry);
        server.start();
    }

    private void startWithoutRegistry() throws IOException {
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.start();
    }

    private record Response(int status, String body) {
    }

    private Response get(String query, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + "/v1/documents/list" + query)
            .toURL().openConnection();
        connection.setRequestMethod("GET");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        int status = connection.getResponseCode();
        var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null
            ? ""
            : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, body);
    }

    @Test
    @DisplayName("lists what the node holds")
    void listsOverHttp() throws Exception {
        startWithRegistry();
        ingest("docs", "handbook.md", 2);
        ingest("docs", "appendix.md", 1);

        Response response = get("", TOKEN);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.get("total").asInt()).isEqualTo(2);
        assertThat(body.get("documents")).hasSize(2);
        assertThat(body.get("documents").get(0).get("document_id").asText())
            .isEqualTo("appendix.md");
        assertThat(body.get("documents").get(0).get("chunk_ids")).hasSize(1);
    }

    @Test
    @DisplayName("reads one document's chunks in order, text and all")
    void readsOneDocumentOverHttp() throws Exception {
        startWithRegistry();
        ingest("docs", "handbook.md", 3);
        ingest("docs", "appendix.md", 1);

        Response response = get("?source_id=docs&document_id=handbook.md&include_text=true",
            TOKEN);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.get("document_id").asText()).isEqualTo("handbook.md");
        assertThat(body.get("chunk_count").asInt()).isEqualTo(3);
        assertThat(body.get("chunks")).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(body.get("chunks").get(i).get("position").asInt()).isEqualTo(i);
            assertThat(body.get("chunks").get(i).get("chunk_id").asText())
                .isEqualTo("docs:handbook.md:chunk:" + i);
            assertThat(body.get("chunks").get(i).has("text")).isTrue();
        }
        assertThat(get("?source_id=docs&document_id=missing.md", TOKEN).status())
            .isEqualTo(404);
    }

    @Test
    @DisplayName("requires a bearer token like every other read")
    void requiresAToken() throws Exception {
        startWithRegistry();
        ingest("docs", "handbook.md", 1);

        assertThat(get("", null).status()).isEqualTo(401);
        assertThat(get("", "wrong").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("a node keeping no registry does not serve the route at all")
    void absentWithoutARegistry() throws Exception {
        startWithoutRegistry();

        assertThat(get("", TOKEN).status())
            .as("an empty listing would read as an empty corpus; 404 says the node cannot "
                + "answer the question")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("the path is exact: a child path is not the listing")
    void childPathIsNotTheListing() throws Exception {
        startWithRegistry();

        assertThat(get("/extra", TOKEN).status()).isEqualTo(404);
    }
}
