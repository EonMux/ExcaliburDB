package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.retrieval.lexical.native_.SegmentManifest;
import com.eonmux.excalibur.store.CompactionWorker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentLifecycleManager;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("wires the hierarchical filter and the volume manager")
    void wiresSupportingComponents(@TempDir Path dataDir) {
        components = CoreComponents.create(config(), dataDir, HnswConfigFactory.withDefaults());

        assertThat(components.hierarchicalFilter()).isNotNull();
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

    @Nested
    @DisplayName("the membership filter's corpus capacity")
    class CorpusCapacity {

        // This value was read from System.getProperty rather than from the configuration, so it
        // answered to neither the properties file nor the environment while looking exactly like
        // every key that does. Sizing it too small costs every query a full scan, because a
        // level that has refused a term stops being trusted to rule anything out. These tests
        // pin the channel, because a capacity that does not reach the filter is invisible from
        // the outside.

        @Test
        @DisplayName("comes from the properties, not from a system property")
        void readsTheConfiguredValue(@TempDir Path dataDir) {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.query.vector-dimensions", "128");
            props.put(CoreComponents.KEY_CORPUS_CAPACITY, "40000000");

            components = CoreComponents.create(
                new Config(props), dataDir, HnswConfigFactory.withDefaults());

            assertThat(components.hierarchicalFilter().corpusCapacity())
                .as("a raised capacity that does not reach the filter is silently ignored")
                .isGreaterThanOrEqualTo(40_000_000L);
        }

        @Test
        @DisplayName("falls back to the default when nothing is configured")
        void defaultsWhenAbsent(@TempDir Path dataDir) {
            components = CoreComponents.create(
                config(), dataDir, HnswConfigFactory.withDefaults());

            assertThat(components.hierarchicalFilter().corpusCapacity())
                .isGreaterThanOrEqualTo(CoreComponents.DEFAULT_CORPUS_CAPACITY);
        }

        @Test
        @DisplayName("the per-segment level is sizeable too")
        void readsTheConfiguredSegmentCapacity(@TempDir Path dataDir) {
            // A segment level that runs out of room stops pruning that segment, which is safe
            // and slow. Without a knob an operator watching cuckoo.saturatedSegments climb has
            // nothing to turn: the corpus capacity does not size this level.
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.query.vector-dimensions", "128");
            props.put(CoreComponents.KEY_SEGMENT_CAPACITY, "250000");

            components = CoreComponents.create(
                new Config(props), dataDir, HnswConfigFactory.withDefaults());

            assertThat(components.hierarchicalFilter().segmentCapacity())
                .isGreaterThanOrEqualTo(250_000);
        }

        @Test
        @DisplayName("the per-segment level falls back to its default")
        void defaultsTheSegmentCapacity(@TempDir Path dataDir) {
            components = CoreComponents.create(
                config(), dataDir, HnswConfigFactory.withDefaults());

            assertThat(components.hierarchicalFilter().segmentCapacity())
                .isGreaterThanOrEqualTo(CoreComponents.DEFAULT_SEGMENT_CAPACITY);
        }

        @Test
        @DisplayName("a per-segment value that is not a positive number fails the start")
        void refusesNonsenseSegmentCapacity(@TempDir Path dataDir) {
            for (String bad : new String[] {"not-a-number", "0", "-1"}) {
                Map<String, String> props = new HashMap<>();
                props.put("excalibur.query.vector-dimensions", "128");
                props.put(CoreComponents.KEY_SEGMENT_CAPACITY, bad);

                assertThatThrownBy(() -> CoreComponents.create(
                    new Config(props), dataDir, HnswConfigFactory.withDefaults()))
                    .as("rejected value: %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CoreComponents.KEY_SEGMENT_CAPACITY);
            }

            assertThat(dataDir).isEmptyDirectory();
        }

        @Test
        @DisplayName("a value that is not a positive number fails the start")
        void refusesNonsense(@TempDir Path dataDir) {
            // Falling back to the default would leave a node sized for a tenth of the corpus its
            // operator asked for, scanning every segment on every query and reporting nothing
            // about why.
            for (String bad : new String[] {"not-a-number", "0", "-1"}) {
                Map<String, String> props = new HashMap<>();
                props.put("excalibur.query.vector-dimensions", "128");
                props.put(CoreComponents.KEY_CORPUS_CAPACITY, bad);

                assertThatThrownBy(() -> CoreComponents.create(
                    new Config(props), dataDir, HnswConfigFactory.withDefaults()))
                    .as("rejected value: %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(CoreComponents.KEY_CORPUS_CAPACITY);
            }

            // Nothing was opened before the value was rejected, so the directory the engine
            // would have used is still untouched and a retry with a good value can proceed.
            assertThat(dataDir).isEmptyDirectory();
        }
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

            worker.stop();

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

    private static Config config() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        return new Config(props);
    }
}
