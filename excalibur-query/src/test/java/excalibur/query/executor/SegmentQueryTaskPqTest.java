package excalibur.query.executor;

import io.excalibur.store.Segment;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which of the graph's searches a segment task runs, and on whose say-so.
 *
 * <p>Quantised search is off unless a deployment turns it on, so the branch has to be chosen
 * from configuration and not from whatever the segment happens to hold: a node that has warmed
 * its segments would otherwise change its query path the moment a quantiser appeared, with
 * nothing asked for and nothing said. With the flag off the task must take the same search it
 * always took.</p>
 *
 * <p>When it is on, two things still rule the quantised pass out. A segment with no trained
 * quantiser has no codes to look up, and a pre-filter's candidate set has no quantised
 * overload to be honoured by - narrowing to it matters more than quantising, because dropping
 * it would search nodes the filter excluded.</p>
 */
@DisplayName("Segment query task, quantised path")
class SegmentQueryTaskPqTest {

    private static final int DIMENSION = 8;
    private static final long SEGMENT_ID = 7L;
    private static final int CORPUS_SIZE = 40;

    @TempDir
    Path tempDir;

    private Arena arena;
    private Segment segment;
    private RecordingGraph graph;
    private DistanceFunction distance;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        distance = CosineSimilarity.getInstance();
        segment = new Segment(tempDir.resolve("segment.dat"), 4L * 1024 * 1024, arena);
        graph = new RecordingGraph(tempDir.resolve("graph.hnsw"),
            HnswConfig.create(DIMENSION), distance, 10_000);

        Random random = new Random(8675309L);
        for (int i = 0; i < CORPUS_SIZE; i++) {
            graph.insert(randomVector(random));
        }
    }

    @AfterEach
    void tearDown() {
        if (graph != null && !graph.isClosed()) {
            graph.close();
        }
        if (segment != null) {
            segment.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Nested
    @DisplayName("settings")
    class Settings {

        @Test
        @DisplayName("the disabled settings are what today's constructors imply")
        void disabledIsTheDefault() {
            assertThat(PqSearchSettings.DISABLED.enabled()).isFalse();
        }

        @Test
        @DisplayName("an oversample below one is refused")
        void oversampleBelowOneIsRefused() {
            assertThatThrownBy(() -> new PqSearchSettings(true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oversample");
        }

        @Test
        @DisplayName("settings are required rather than defaulted from null")
        void nullSettingsAreRefused() {
            assertThatThrownBy(() -> new SegmentQueryTask(
                SEGMENT_ID, segment, graph, query(), 5, 1, distance, null, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    @DisplayName("with the flag off a quantised segment is still searched exactly")
    void flagOffKeepsTodaysPath() throws Exception {
        quantise();

        SegmentQueryTask.SegmentQueryResult result =
            new SegmentQueryTask(SEGMENT_ID, segment, graph, query(), 5, 1, distance).call();

        assertThat(result.success()).isTrue();
        assertThat(graph.calls)
            .as("the flag is what turns quantised search on, not the presence of a quantiser")
            .containsExactly("search");
    }

    @Test
    @DisplayName("with the flag on a quantised segment takes the rescored path")
    void flagOnUsesTheQuantisedPath() throws Exception {
        quantise();
        List<HnswSearchResult> exact =
            new SegmentQueryTask(SEGMENT_ID, segment, graph, query(), 5, 1, distance)
                .call().results();
        graph.calls.clear();

        SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask(
            SEGMENT_ID, segment, graph, query(), 5, 1, distance, null,
            new PqSearchSettings(true, 6)).call();

        assertThat(graph.calls)
            .as("the configured oversample has to reach the graph")
            .containsExactly("rerank:6");
        assertThat(result.results())
            .extracting(HnswSearchResult::nodeId, HnswSearchResult::distance)
            .as("rescored results are exact, so they rank as the exact path's do")
            .containsExactlyElementsOf(exact.stream()
                .map(hit -> org.assertj.core.groups.Tuple.tuple(hit.nodeId(), hit.distance()))
                .toList());
    }

    @Test
    @DisplayName("with the flag on and no quantiser the exact search answers")
    void flagOnWithoutQuantiserFallsBack() throws Exception {
        assertThat(graph.isPQEnabled()).isFalse();

        SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask(
            SEGMENT_ID, segment, graph, query(), 5, 1, distance, null,
            new PqSearchSettings(true, 4)).call();

        assertThat(result.success()).isTrue();
        assertThat(result.results()).isNotEmpty();
        assertThat(graph.calls).containsExactly("search");
    }

    @Test
    @DisplayName("a pre-filter's candidates outrank the quantised path")
    void candidatesTakePrecedence() throws Exception {
        quantise();

        SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask(
            SEGMENT_ID, segment, graph, query(), 5, 1, distance, Set.of(1L, 2L, 3L),
            new PqSearchSettings(true, 4)).call();

        assertThat(result.success()).isTrue();
        assertThat(graph.calls)
            .as("there is no candidate-restricted quantised search; narrowing wins")
            .containsExactly("searchCandidates");
        assertThat(result.results()).extracting(HnswSearchResult::nodeId)
            .isSubsetOf(1L, 2L, 3L);
    }

    private void quantise() {
        graph.trainProductQuantizer(CORPUS_SIZE, 4, 16);
        graph.encodeAllVectors();
        graph.calls.clear();
    }

    private static float[] query() {
        return randomVector(new Random(4242L));
    }

    private static float[] randomVector(Random random) {
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return vector;
    }

    /**
     * A graph that records which of its searches the task chose.
     *
     * <p>Comparing results alone cannot tell the paths apart - rescored results are exact, so
     * on a corpus this size both paths return the same list. The call is the observation.</p>
     */
    private static final class RecordingGraph extends HnswGraph {

        private final List<String> calls = new ArrayList<>();

        RecordingGraph(Path path, HnswConfig config, DistanceFunction distanceFunction,
                       long maxNodes) throws IOException {
            super(path, config, distanceFunction, maxNodes);
        }

        @Override
        public List<HnswSearchResult> search(float[] query, int k) {
            calls.add("search");
            return super.search(query, k);
        }

        @Override
        public List<HnswSearchResult> search(float[] query, int k, Set<Long> candidateIds) {
            calls.add("searchCandidates");
            return super.search(query, k, candidateIds);
        }

        @Override
        public List<HnswSearchResult> searchWithPQThenRerank(float[] query, int k, int oversample) {
            calls.add("rerank:" + oversample);
            return super.searchWithPQThenRerank(query, k, oversample);
        }
    }
}
