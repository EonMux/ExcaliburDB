package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.erasure.SegmentVectorEraser;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code POST /v1/compact} reclaims the chunk index's dead weight on a real node.
 *
 * <p>Erasing a document tombstones its chunks in the lexical index, and a handful of erasures in
 * a larger corpus stays below the index's own tombstone ratio. An ordinary run leaves them to
 * that policy and says why; a forced run has to rewrite them away however few there are, and
 * the status route has to say so.</p>
 */
@DisplayName("On-demand compaction reaches the chunk index")
class CompactionChunkIndexEndToEndTest {

    private static final String TOKEN = "compaction-e2e-token";

    private CoreComponents core;
    private GraphTenants tenants;
    private IngestionWiring.Attachment attachment;
    private ExcaliburDBServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (attachment != null) {
            if (attachment.lexicalErasureSweep() != null) {
                attachment.lexicalErasureSweep().close();
            }
            if (attachment.chunkIndex() != null) {
                attachment.chunkIndex().close();
            }
        }
        if (tenants != null) {
            tenants.close();
        }
        if (core != null) {
            try {
                core.indexOrchestrator().close();
            } catch (RuntimeException ignored) {
                // best effort
            }
            core.storageEngine().close();
            if (core.arena() != null) {
                core.arena().close();
            }
        }
    }

    private void startNode(Path dataDir) throws IOException {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        props.put("excalibur.graph.ram-buffer-mb", "4.0");
        Config config = new Config(props);
        core = CoreComponents.create(config, dataDir, HnswConfigFactory.withDefaults());
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(
            DocumentRegistryFactory.open(dataDir),
            core.indexOrchestrator().getVisibilityTracker());
        tenants = new GraphTenants(dataDir.resolve("graph"), config, MappingBudget.detect(), 4);
        server = new ExcaliburDBServer(0, ExcaliburDBServer.DEFAULT_BACKLOG,
            core.indexOrchestrator(), core.storageEngine(), core.volumeManager(), config,
            mock(QueryPipeline.class, Answers.RETURNS_SELF));
        attachment = new IngestionWiring(config, dataDir, core.storageEngine(),
            core.indexOrchestrator(), core.hierarchicalFilter(), tracker, tenants)
            .attachTo(server);
        server.withDocumentErasure(new DocumentErasureService(tracker.getRegistry(),
            StorageChunkWriter.tombstoneAdapter(core.storageEngine()), attachment.chunkIndex(),
            StoredChunkText.of(core.storageEngine()), tracker,
            SegmentVectorEraser.of(core.storageEngine())));
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.withGraphSupport(tenants);
        server.start();
    }

    @Test
    @DisplayName("a forced run rewrites erased chunks away and reports it")
    void forcedRunReclaimsErasedChunks(@TempDir Path dataDir) throws Exception {
        startNode(dataDir);
        ingest();
        assertThat(post("/v1/erasure", "{\"source_id\": \"compaction-e2e\", "
            + "\"document_ids\": [\"doc-3.md\", \"doc-11.md\"]}")).isBetween(200, 299);
        ChunkIndexStats before = attachment.chunkIndex().stats();
        assertThat(before.erasedDocuments())
            .as("the erasure tombstoned the documents' chunks")
            .isPositive();

        JsonNode ordinary = compact(false).path("lexical");
        assertThat(ordinary.path("performed").asBoolean())
            .as("too few are dead for the index's own policy")
            .isFalse();
        assertThat(ordinary.path("reason").asText()).isNotBlank();
        assertThat(attachment.chunkIndex().stats().erasedDocuments())
            .isEqualTo(before.erasedDocuments());

        JsonNode forced = compact(true).path("lexical");
        assertThat(forced.path("performed").asBoolean()).isTrue();
        assertThat(forced.path("segments_rewritten").asInt()).isPositive();
        assertThat(forced.path("chunks_removed").asLong()).isEqualTo(before.erasedDocuments());
        assertThat(forced.path("reason").asText()).isEqualTo("full rewrite");

        ChunkIndexStats after = attachment.chunkIndex().stats();
        assertThat(after.erasedDocuments()).isZero();
        assertThat(after.liveDocuments()).isEqualTo(before.liveDocuments());
    }

    private void ingest() throws IOException {
        StringBuilder documents = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                documents.append(',');
            }
            documents.append("{\"document_id\": \"doc-").append(i).append(".md\", \"text\": \"")
                .append("document ").append(i).append(" about topic")
                .append(i).append("\", \"content_type\": \"text/plain\"}");
        }
        int status = post("/v1/documents",
            "{\"source_id\": \"compaction-e2e\", \"documents\": [" + documents + "]}");
        assertThat(status).isBetween(200, 299);
    }

    private JsonNode compact(boolean force) throws Exception {
        assertThat(post("/v1/compact", "{\"force\": " + force + "}")).isEqualTo(202);
        for (int i = 0; i < 2000; i++) {
            JsonNode status = get("/v1/compact/status");
            String state = status.path("state").asText();
            if (!"running".equals(state) && !"idle".equals(state)) {
                assertThat(state).isEqualTo("completed");
                return status;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("compaction did not finish");
    }

    private JsonNode get(String path) throws IOException {
        HttpURLConnection connection = open(path);
        try {
            assertThat(connection.getResponseCode()).isEqualTo(200);
            return new ObjectMapper().readTree(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private int post(String path, String body) throws IOException {
        HttpURLConnection connection = open(path);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path).toURL().openConnection();
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        return connection;
    }
}
