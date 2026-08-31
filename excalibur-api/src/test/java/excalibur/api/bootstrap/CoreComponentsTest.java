package excalibur.api.bootstrap;

import io.excalibur.config.Config;
import io.excalibur.store.Segment;
import io.excalibur.vector.hnsw.HnswConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static Config config() {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.vector-dimensions", "128");
        return new Config(props);
    }
}
