package excalibur.api.bootstrap;

import io.excalibur.config.Config;
import io.excalibur.store.Segment;
import io.excalibur.vector.hnsw.HnswConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        // every key that does. Sizing it too small is not a degradation: the corpus level cannot
        // record a term it has no room for, and a term it has no record of prunes every segment,
        // so queries return nothing for documents that are sitting in storage. These tests pin
        // the channel, because the failure it caused is invisible from the outside.

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
        @DisplayName("a value that is not a positive number fails the start")
        void refusesNonsense(@TempDir Path dataDir) {
            // Falling back to the default would leave a node sized for a tenth of the corpus its
            // operator asked for, answering nothing and reporting itself healthy.
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

    private static Config config() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        return new Config(props);
    }
}
