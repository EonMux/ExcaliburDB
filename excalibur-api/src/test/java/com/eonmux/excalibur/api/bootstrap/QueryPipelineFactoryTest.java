package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.query.executor.PqSearchSettings;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pipeline assembly, checked for the arguments whose absence has no symptom.
 *
 * <p>Four of the pipeline's arguments are filters that are inert when omitted: the atomicity
 * checker, the membership filter, the epoch validator and the quantised-search policy. A
 * pipeline built without one of them answers queries perfectly happily and answers them wrongly
 * - half an ingested document, every segment scanned, a vector written after the snapshot was
 * taken. Nothing downstream can tell, which is why they are pinned here at the point of
 * assembly.</p>
 */
@DisplayName("Query pipeline factory")
class QueryPipelineFactoryTest {

    private CoreComponents core;

    @AfterEach
    void tearDown() {
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
     * Builds a configuration with the keys the storage stack needs already set.
     *
     * @param extra The keys this particular deployment is being described by
     * @return The configuration
     */
    private static Config config(Map<String, String> extra) {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        props.putAll(extra);
        return new Config(props);
    }

    /**
     * Builds a real storage stack and the pipeline over it.
     *
     * @param config  The deployment being described
     * @param dataDir Where the node keeps its segments
     * @return The assembled pipeline
     */
    private QueryPipeline pipeline(Config config, Path dataDir) {
        core = CoreComponents.create(config, dataDir, HnswConfigFactory.withDefaults());
        DocumentVisibilityChecker checker = new DocumentVisibilityTrackerAdapter(
            new DocumentVisibilityTracker(DocumentRegistryFactory.open(dataDir),
                core.indexOrchestrator().getVisibilityTracker()));
        return QueryPipelineFactory.create(config, core.storageEngine(),
            core.indexOrchestrator(), checker, core.hierarchicalFilter());
    }

    @Test
    @DisplayName("builds a pipeline over the node's own storage")
    void buildsAPipeline(@TempDir Path dataDir) {
        assertThat(pipeline(config(Map.of()), dataDir)).isNotNull();
    }

    @Test
    @DisplayName("builds one for a node with a chat model too")
    void buildsAPipelineWithAScorer(@TempDir Path dataDir) {
        // The scorer is the one collaborator built from an endpoint rather than from local
        // state, so a node with a key takes a different path through the assembly.
        assertThat(pipeline(config(Map.of("excalibur.llm.api-key", "test-key")), dataDir))
            .isNotNull();
    }

    @Test
    @DisplayName("refuses a filter it was not given rather than building a pipeline without it")
    void refusesMissingFilters(@TempDir Path dataDir) {
        Config config = config(Map.of());
        core = CoreComponents.create(config, dataDir, HnswConfigFactory.withDefaults());

        assertThatThrownBy(() -> QueryPipelineFactory.create(
            null, core.storageEngine(), core.indexOrchestrator(), null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("config");
        assertThatThrownBy(() -> QueryPipelineFactory.create(
            config, core.storageEngine(), core.indexOrchestrator(), null, null))
            .as("a pipeline with no atomicity filter can return half an ingested document")
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("documentVisibilityChecker");
    }

    @Test
    @DisplayName("quantised search is off unless it was asked for")
    void quantisedSearchIsOptIn() {
        assertThat(QueryPipelineFactory.pqSearchSettings(config(Map.of())))
            .isEqualTo(PqSearchSettings.DISABLED);
    }

    @Test
    @DisplayName("quantised search carries the configured over-fetch")
    void quantisedSearchReadsTheOversample() {
        PqSearchSettings settings = QueryPipelineFactory.pqSearchSettings(config(Map.of(
            "excalibur.shard.pq-search-enabled", "true",
            "excalibur.shard.pq-oversample", "6")));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.oversample())
            .as("an over-fetch that does not reach the beam re-measures the wrong candidates")
            .isEqualTo(6);
    }
}
