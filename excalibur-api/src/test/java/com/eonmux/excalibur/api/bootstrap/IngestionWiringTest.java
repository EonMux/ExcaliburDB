package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.server.ExcaliburDBServer;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * What a node ends up able to ingest with, given what it was configured to reach.
 *
 * <p>These combinations are the reason the wiring is a class of its own. Three deployments are
 * legitimate - no model at all, a model used to embed the corpus, and a model used to expand
 * queries instead - and each produces a different pipeline from the same code. The failure mode
 * they share is silence: a node that wired the wrong half still starts, still accepts
 * registrations, and answers queries from whichever signal it happens to have.</p>
 *
 * <p>The chunk index is checked separately from the rest because it is the one component the
 * caller has to close. Handing it back even when the attachment later failed is what stops a
 * node holding an index directory open for the life of the process with nothing referencing
 * it.</p>
 */
@DisplayName("Ingestion wiring")
class IngestionWiringTest {

    private CoreComponents core;
    private GraphTenants tenants;
    private IngestionWiring.Attachment attachment;

    @AfterEach
    void tearDown() {
        if (attachment != null) {
            if (attachment.lexicalErasureSweep() != null) {
                attachment.lexicalErasureSweep().close();
            }
            if (attachment.chunkIndex() != null) {
                attachment.chunkIndex().close();
            }
            attachment = null;
        }
        if (tenants != null) {
            tenants.close();
            tenants = null;
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
            core = null;
        }
    }

    /**
     * Builds a configuration for a node, with the keys the storage stack needs already set.
     *
     * @param extra The keys this particular deployment is being described by
     * @return The configuration
     */
    private static Config config(Map<String, String> extra) {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        props.put("excalibur.graph.ram-buffer-mb", "4.0");
        props.putAll(extra);
        return new Config(props);
    }

    /**
     * Builds a whole node's worth of collaborators and attaches the ingestion half.
     *
     * @param config  The deployment being described
     * @param dataDir Where the node keeps everything
     * @return The server it attached to, so a test can read back what was wired onto it
     * @throws IOException if the graph tenants cannot be opened
     */
    private ExcaliburDBServer attach(Config config, Path dataDir) throws IOException {
        core = CoreComponents.create(config, dataDir, HnswConfigFactory.withDefaults());
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(
            DocumentRegistryFactory.open(dataDir),
            core.indexOrchestrator().getVisibilityTracker());
        tenants = new GraphTenants(dataDir.resolve("graph"), config, MappingBudget.detect(), 4);
        ExcaliburDBServer server = new ExcaliburDBServer(0, ExcaliburDBServer.DEFAULT_BACKLOG,
            core.indexOrchestrator(), core.storageEngine(), core.volumeManager(), config,
            mock(QueryPipeline.class));

        attachment = new IngestionWiring(config, dataDir, core.storageEngine(),
            core.indexOrchestrator(), core.hierarchicalFilter(), tracker, tenants)
            .attachTo(server);
        return server;
    }

    @Test
    @DisplayName("a node with no model still chunks, stores and term-indexes")
    void lexicalOnlyNodeStillIngests(@TempDir Path dataDir) throws IOException {
        ExcaliburDBServer server = attach(config(Map.of()), dataDir);

        assertThat(server.getIngestionOrchestrator())
            .as("no embedding endpoint is lexical-only ingestion, not no ingestion")
            .isNotNull();
        assertThat(attachment.chunkIndex()).isNotNull();
        assertThat(attachment.lexicalErasureSweep())
            .as("an index with no sweep keeps erased chunks on disk until the next run")
            .isNotNull();
    }

    @Test
    @DisplayName("a node that embeds its corpus gets the pipeline and the index")
    void embeddingNodeGetsBothSignals(@TempDir Path dataDir) throws IOException {
        ExcaliburDBServer server = attach(config(Map.of(
            "excalibur.llm.api-key", "test-key")), dataDir);

        assertThat(server.getIngestionOrchestrator()).isNotNull();
        assertThat(attachment.chunkIndex())
            .as("hybrid retrieval needs the lexical half maintained even when vectors exist")
            .isNotNull();
    }

    @Test
    @DisplayName("a node that declines the embedding pass still ingests and expands")
    void expandingNodeIngestsLexically(@TempDir Path dataDir) throws IOException {
        ExcaliburDBServer server = attach(config(Map.of(
            "excalibur.llm.api-key", "test-key",
            "excalibur.llm.embeddings-enabled", "false")), dataDir);

        assertThat(server.getIngestionOrchestrator()).isNotNull();
        assertThat(attachment.chunkIndex())
            .as("the expansion path has nothing to retrieve from without the chunk index")
            .isNotNull();
    }

    @Test
    @DisplayName("a node holding both signals but reading one still maintains the index")
    void fusionCanBeDeclinedWithoutLosingTheIndex(@TempDir Path dataDir) throws IOException {
        attach(config(Map.of(
            "excalibur.llm.api-key", "test-key",
            "excalibur.query.hybrid-retrieval", "false")), dataDir);

        assertThat(attachment.chunkIndex())
            .as("turning fusion off must not stop the index being written, or turning it "
                + "back on would answer from a corpus that stopped at the restart")
            .isNotNull();
    }

    @Test
    @DisplayName("a chunk index that will not open costs one signal, not the pipeline")
    void anUnopenableIndexIsNotFatal(@TempDir Path dataDir) throws IOException {
        // A regular file where the native engine expects its directory. Whatever the cause on a
        // real node - a permission, a half-deleted directory - the node has to keep ingesting.
        Files.writeString(dataDir.resolve(ChunkIndexFactory.NATIVE_DIRECTORY), "not a directory");

        ExcaliburDBServer server = attach(config(Map.of()), dataDir);

        assertThat(server.getIngestionOrchestrator())
            .as("losing BM25 must not cost the node its ability to ingest")
            .isNotNull();
        assertThat(attachment.chunkIndex()).isNull();
        assertThat(attachment.lexicalErasureSweep())
            .as("there is no index for a sweep to compact")
            .isNull();
    }

    @Test
    @DisplayName("refuses a collaborator it cannot do without")
    void refusesMissingCollaborators(@TempDir Path dataDir) {
        Config config = config(Map.of());

        assertThatThrownBy(() -> new IngestionWiring(null, dataDir, null, null, null, null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("config");
        assertThatThrownBy(() -> new IngestionWiring(config, null, null, null, null, null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("dataDirectory");
        assertThatThrownBy(() -> new IngestionWiring(config, dataDir, null, null, null, null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("storageEngine");
    }
}
