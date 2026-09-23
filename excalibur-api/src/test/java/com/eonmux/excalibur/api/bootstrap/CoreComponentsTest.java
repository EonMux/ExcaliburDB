package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.ShardConfig;
import com.eonmux.excalibur.retrieval.lexical.native_.SegmentManifest;
import com.eonmux.excalibur.store.CompactionWorker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentLifecycleManager;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the storage stack the whole server is built on.
 *
 * <p>The one ordering constraint these tests exist for: the HNSW initializer must be
 * installed before the storage engine starts. Installing it afterwards leaves every
 * segment without a graph, which silently turns every vector query into an empty
 * result rather than into an error anyone would notice.</p>
 */
@DisplayName("CoreComponents")
class CoreComponentsTest {

    private CoreComponents components;

    @AfterEach
    void tearDown() {
        if (components != null) {
            try {
                components.indexOrchestrator().close();
            } catch (RuntimeException ignored) {
                // best effort
            }
            components.storageEngine().close();
            if (components.arena() != null) {
                components.arena().close();
            }
            components = null;
        }
    }

    @Test
    @DisplayName("gives every segment an HNSW graph")
    void everySegmentHasAnHnswGraph(@TempDir Path dataDir) {
        components = CoreComponents.create(config(), dataDir, HnswConfigFactory.withDefaults());

        Map<Long, Segment> segments = components.storageEngine().getSegments();

        assertThat(segments).isNotEmpty();
        assertThat(segments.values())
            .allSatisfy(segment -> assertThat(segment.getHnswGraph())
                .as("segment created without an HNSW graph is invisible to vector search")
                .isNotNull());
    }

    @Test
    @DisplayName("wires the volume manager and the catalog")
    void wiresSupportingComponents(@TempDir Path dataDir) {
        components = CoreComponents.create(config(), dataDir, HnswConfigFactory.withDefaults());

        assertThat(components.volumeManager().getVolumeCount()).isGreaterThan(0);
        assertThat(components.storageEngine().getCatalog()).isNotNull();
    }

    @Test
    @DisplayName("registers its segments with the index orchestrator")
    void registersSegments(@TempDir Path dataDir) {
        components = CoreComponents.create(config(), dataDir, HnswConfigFactory.withDefaults());

        assertThat(components.indexOrchestrator().getRegisteredSegmentCount())
            .isEqualTo(components.storageEngine().getSegments().size());
    }

    @Test
    @DisplayName("starts on a file that still sizes the removed term filter")
    void startsWithRetiredFilterSettings(@TempDir Path dataDir) {
        // A properties file written for an earlier build still carries the excalibur.filter.*
        // keys. Nothing reads them any more, and a node refusing to start on one would turn an
        // upgrade into an outage.
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        props.put("excalibur.filter.corpus-capacity", "not-a-number");
        props.put("excalibur.filter.fingerprint-bits", "99");

        components = CoreComponents.create(
            new Config(props), dataDir, HnswConfigFactory.withDefaults());

        assertThat(components.storageEngine().getSegments()).isNotEmpty();
    }

    @Nested
    @DisplayName("the visibility clock")
    class VisibilityClock {

        // Epochs are stamped into durable records and compared against a counter held in memory.
        // Two ways of getting that wrong were both live here, and neither shows up as an error:
        // a counter restarted below the stamps on disk hides the whole corpus, and a second
        // counter running beside the first puts two different scales into one catalog field.

        @Test
        @DisplayName("is one clock, shared by the storage engine and the index orchestrator")
        void theEngineAndTheOrchestratorAgree(@TempDir Path dataDir) {
            components = CoreComponents.create(config(), dataDir, HnswConfigFactory.withDefaults());

            assertThat(components.storageEngine().getVisibilityTracker())
                .as("a second epoch counter stamps tombstones on a scale nothing else reads")
                .isSameAs(components.indexOrchestrator().getVisibilityTracker());
        }

        @Test
        @DisplayName("resumes above every epoch the durable stores already stamped")
        void resumesAboveTheDurableStamps(@TempDir Path dataDir) throws IOException {
            // What an earlier run left behind: lexical segments published at epoch 9100.
            Path lexical = Files.createDirectories(
                dataDir.resolve(ChunkIndexFactory.NATIVE_DIRECTORY));
            SegmentManifest manifest = SegmentManifest.empty();
            manifest.add(new SegmentManifest.Entry("seg-0", 9_100, 3, 0));
            manifest.write(lexical);

            components = CoreComponents.create(config(), dataDir, HnswConfigFactory.withDefaults());

            assertThat(components.indexOrchestrator().getVisibilityTracker().currentEpoch())
                .as("below 9100 every published segment reads as written in the future")
                .isGreaterThan(9_100L);
        }

        @Test
        @DisplayName("starts at zero when nothing has ever been stamped")
        void aFreshNodeStartsAtZero(@TempDir Path dataDir) {
            components = CoreComponents.create(config(), dataDir, HnswConfigFactory.withDefaults());

            assertThat(components.indexOrchestrator().getVisibilityTracker().currentEpoch())
                .isZero();
        }
    }

    @Nested
    @DisplayName("compaction callback")
    class CompactionCallbackTests {

        /**
         * The lifecycle manager marks a segment handled on what this callback returns, and
         * stops asking. Reporting a refusal as a dispatch strands the tombstoned space.
         */
        @Test
        @DisplayName("reports scheduled while the worker is live and not-scheduled once it has stopped")
        void reportsWhatTheWorkerActuallyDid(@TempDir Path dataDir) {
            components = CoreComponents.create(config(), dataDir, HnswConfigFactory.withDefaults());
            CompactionWorker worker = components.indexOrchestrator().getCompactionWorker();
            assertThat(worker).as("the orchestrator wires a compaction worker").isNotNull();

            SegmentLifecycleManager.CompactionCallback callback =
                CoreComponents.compactionCallback(components.storageEngine(), worker);
            long segmentId = components.storageEngine().getSegments().keySet().iterator().next();

            assertThat(callback.onCompactionNeeded(segmentId))
                .as("a live worker takes the rewrite, so the segment really is handled")
                .isTrue();

            worker.stop(java.time.Duration.ofSeconds(10));

            assertThat(callback.onCompactionNeeded(segmentId))
                .as("a stopped worker rewrites nothing, and the manager must not record it as handled")
                .isFalse();
        }

        @Test
        @DisplayName("reports not-scheduled for a segment the engine does not know")
        void reportsNotScheduledForAnUnknownSegment(@TempDir Path dataDir) {
            components = CoreComponents.create(config(), dataDir, HnswConfigFactory.withDefaults());
            CompactionWorker worker = components.indexOrchestrator().getCompactionWorker();

            SegmentLifecycleManager.CompactionCallback callback =
                CoreComponents.compactionCallback(components.storageEngine(), worker);

            assertThat(callback.onCompactionNeeded(Long.MAX_VALUE)).isFalse();
        }
    }

    @Test
    @DisplayName("hands the storage engine the configured routing directory limit")
    void appliesTheRoutingWidthLimit(@TempDir Path dataDir) {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        props.put("excalibur.shard.max-routing-width", "4096");

        components = CoreComponents.create(
            new Config(props), dataDir, HnswConfigFactory.withDefaults());

        assertThat(components.storageEngine().maxRoutingWidth()).isEqualTo(4096);
    }

    @Test
    @DisplayName("bounds the routing directory limit exactly as the engine does")
    void routingWidthBoundsAreTheEngines() {
        // The config module cannot see the store, so its bounds are written out twice. A value
        // one side accepts and the other refuses would turn a startup check into a refusal from
        // inside the engine, or let through a width the engine then throws on.
        assertThat(ShardConfig.DEFAULT_MAX_ROUTING_WIDTH)
            .isEqualTo(StorageEngine.DEFAULT_MAX_ROUTING_WIDTH);
        assertThat(ShardConfig.MIN_MAX_ROUTING_WIDTH)
            .isEqualTo(StorageEngine.DEFAULT_INITIAL_SEGMENTS);
        assertThat(ShardConfig.MAX_MAX_ROUTING_WIDTH)
            .isEqualTo(StorageEngine.MAX_ROUTING_WIDTH_LIMIT);
    }

    private static Config config() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        return new Config(props);
    }
}
