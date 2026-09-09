package excalibur.query.pipeline;

import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.executor.CancellationToken;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ID a query uses to look a segment up has to be the ID everything else files it under.
 *
 * <p>Two numbers identify a segment. {@link Segment#segmentId()} counts every segment instance a
 * process has constructed. The directory index is the routing slot the storage engine holds it
 * at, and it is what the catalog, the visibility tracker, the term filter hierarchy and the graph
 * provider are all keyed by. They agree while segments are created in directory order, and part
 * company as soon as one is created out of it - a compacted replacement, or a split child.</p>
 *
 * <p>Nothing detects the disagreement. A catalog lookup on the wrong number returns no metadata,
 * stage 0 skips the segment, and it is missing from every result with no error raised anywhere -
 * so a segment quietly leaves the searchable set at its first compaction and never comes back.</p>
 */
@DisplayName("Segment ID space")
class SegmentIdSpaceTest {

    private static final int DIMENSION = 8;

    @Test
    @DisplayName("a segment is queryable when its instance counter has run ahead of its index")
    void aSegmentIsFoundByItsDirectoryIndex(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        try (Arena arena = Arena.ofShared()) {
            // Push the instance counter past the directory indices, which is the state any engine
            // is in after a compaction: the replacement is constructed last and lands at an index
            // that was allocated long before it.
            try (Segment throwaway = new Segment(tempDir.resolve("throwaway.dat"), 1024 * 1024, arena)) {
                assertThat(throwaway.segmentId()).isNotNegative();
            }

            try (StorageEngine engine = new StorageEngine(dataDir, arena)) {
                engine.start();

                Segment segment = engine.getSegment(0L);
                assertThat(segment).isNotNull();
                assertThat(segment.segmentId())
                    .as("this test is only meaningful while the two numbers differ")
                    .isNotEqualTo(0L);

                HnswGraph graph = new HnswGraph(dataDir.resolve("probe.hnsw"),
                    HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 64);
                float[] stored = unit(1.0f);
                graph.insert(stored);
                segment.attachHnswGraph(graph);

                engine.getCatalog().get(0L).setLifecycleState(LifecycleState.ACTIVE);

                VisibilityTracker visibility = new VisibilityTracker(engine.getCatalog());
                visibility.registerSegment(0L, visibility.currentEpoch());

                // Keyed the way the running system keys it: the index the engine routes by.
                QueryPipeline pipeline = new QueryPipeline(
                    engine,
                    visibility,
                    id -> {
                        Segment routed = engine.getSegment(id);
                        return routed == null ? null : routed.getHnswGraph();
                    },
                    CosineSimilarity.getInstance());

                QueryResponse response = pipeline.executeSync(
                    QueryRequest.builder().queryVector(unit(1.0f)).topK(5).build(),
                    new CancellationToken());

                assertThat(response.coverage().segmentsSearched())
                    .as("the segment holding the vector must be searched, not skipped for want "
                        + "of a catalog entry under a number nothing files it under")
                    .isEqualTo(1);
                assertThat(response.results())
                    .as("a vector that is in the graph must come back")
                    .isNotEmpty();

                graph.close();
            }
        }
    }

    private static float[] unit(float first) {
        float[] vector = new float[DIMENSION];
        vector[0] = first;
        return vector;
    }
}
