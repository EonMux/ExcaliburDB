package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.erasure.SegmentVectorEraser;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code POST /v1/documents} with a document id the node already holds, on a real node.
 *
 * <p>The documented contract is that posting an id again replaces what the node holds under it.
 * A shorter replacement writes fewer chunk ids than the version it replaces; the ones past its
 * end must leave storage and the lexical index with it, or they stay searchable under a registry
 * entry that no longer names them - which is also what {@code /v1/erasure} reads to find a
 * document's chunks, so nothing could ever remove them.</p>
 */
@DisplayName("Re-posting a document replaces it")
class DocumentRepostEndToEndTest {

    private static final String TOKEN = "repost-e2e-token";
    private static final String SOURCE = "repost-e2e";

    private CoreComponents core;
    private GraphTenants tenants;
    private IngestionWiring.Attachment attachment;
    private ExcaliburDBServer server;
    private DocumentVisibilityTracker tracker;

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

    @Test
    @DisplayName("a shorter version leaves nothing of the longer one, and erasure then takes all")
    void shorterRepostRetiresTheTail(@TempDir Path dataDir) throws Exception {
        startNode(dataDir);
        List<String> oldWords = words("old", 80);

        post("/v1/documents", documentBody(String.join(" ", oldWords)));
        List<String> oldChunkIds = registered().getChunkIds();
        assertThat(oldChunkIds).as("precondition: the first version spans chunks")
            .hasSizeGreaterThan(2);
        awaitSearchable(oldWords.get(oldWords.size() - 1));

        post("/v1/documents", documentBody("a short replacement"));
        awaitSearchable("replacement");
        List<String> newChunkIds = registered().getChunkIds();
        assertThat(newChunkIds).hasSizeLessThan(oldChunkIds.size());
        for (String chunkId : oldChunkIds.subList(newChunkIds.size(), oldChunkIds.size())) {
            assertThat(stored(chunkId)).as("%s belongs to the replaced version", chunkId)
                .isFalse();
        }
        for (String word : oldWords) {
            assertThat(hits(word)).as("'%s' is only in the replaced version", word).isZero();
        }

        post("/v1/erasure", "{\"source_id\": \"" + SOURCE + "\", \"document_ids\": [\"a.md\"]}");
        assertThat(tracker.getRegistry().get(SOURCE, "a.md")).isNull();
        for (String chunkId : oldChunkIds) {
            assertThat(stored(chunkId)).as("%s after erasure", chunkId).isFalse();
        }
        assertThat(hits("replacement")).isZero();
        assertThat(hits("short")).isZero();
    }

    private void startNode(Path dataDir) throws IOException {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        props.put("excalibur.graph.ram-buffer-mb", "4.0");
        // Small chunks, so a modest document spans several of them.
        props.put("excalibur.ingest.chunk-target-tokens", "8");
        props.put("excalibur.ingest.chunk-overlap-tokens", "0");
        Config config = new Config(props);
        core = CoreComponents.create(config, dataDir, HnswConfigFactory.withDefaults());
        tracker = new DocumentVisibilityTracker(DocumentRegistryFactory.open(dataDir),
            core.indexOrchestrator().getVisibilityTracker());
        tenants = new GraphTenants(dataDir.resolve("graph"), config, MappingBudget.detect(), 4);
        server = new ExcaliburDBServer(0, ExcaliburDBServer.DEFAULT_BACKLOG,
            core.indexOrchestrator(), core.storageEngine(), core.volumeManager(), config,
            mock(QueryPipeline.class, Answers.RETURNS_SELF));
        attachment = new IngestionWiring(config, dataDir, core.storageEngine(),
            core.indexOrchestrator(), tracker, tenants)
            .attachTo(server);
        server.withDocumentErasure(new DocumentErasureService(tracker.getRegistry(),
            StorageChunkWriter.tombstoneAdapter(core.storageEngine()), attachment.chunkIndex(),
            StoredChunkText.of(core.storageEngine()), tracker,
            SegmentVectorEraser.of(core.storageEngine())));
        server.withSecurity(ApiSecurityConfig.builder().authToken(TOKEN).build());
        server.withGraphSupport(tenants);
        server.start();
    }

    private DocumentMetadata registered() {
        DocumentMetadata metadata = tracker.getRegistry().get(SOURCE, "a.md");
        assertThat(metadata).isNotNull();
        return metadata;
    }

    private boolean stored(String chunkId) {
        MemorySegment value = core.storageEngine().get(chunkId.getBytes(StandardCharsets.UTF_8));
        return value != null && value.byteSize() > 0;
    }

    /**
     * Waits for a term to be found once.
     *
     * <p>The native engine publishes a refreshed segment at the epoch after the current one, so
     * what a run indexed becomes searchable on the node's next epoch tick rather than the moment
     * the request returns.</p>
     */
    private void awaitSearchable(String term) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (hits(term) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("'" + term + "' never became searchable");
            }
            Thread.sleep(10);
        }
        assertThat(hits(term)).as("'%s' is in exactly one chunk", term).isOne();
    }

    private int hits(String term) {
        return attachment.chunkIndex().ranker(QueryExpander.identity())
            .rank(RetrievalQuery.ofText(term), 100).documents().size();
    }

    private static String documentBody(String text) {
        return "{\"source_id\": \"" + SOURCE + "\", \"documents\": [{\"document_id\": \"a.md\", "
            + "\"text\": \"" + text + "\", \"content_type\": \"text/plain\"}]}";
    }

    /** Distinct alphabetic words, so each is found in exactly the chunk that holds it. */
    private static List<String> words(String prefix, int count) {
        List<String> words = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            words.add(prefix + (char) ('a' + i / 26) + (char) ('a' + i % 26));
        }
        return words;
    }

    private void post(String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
            .create("http://127.0.0.1:" + server.getPort() + path).toURL().openConnection();
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try {
            assertThat(connection.getResponseCode()).as("POST %s", path).isBetween(200, 299);
        } finally {
            connection.disconnect();
        }
    }
}
