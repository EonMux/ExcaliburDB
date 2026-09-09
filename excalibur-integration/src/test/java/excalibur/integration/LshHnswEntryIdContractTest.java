package excalibur.integration;

import excalibur.filter.lsh.LshIndex;
import io.excalibur.vector.distance.L2Distance;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LSH pre-filter and the HNSW graph share one id space, and it is the graph's.
 *
 * <p>Stage 2 of the query pipeline hands the ids an LSH index returns straight to
 * {@link HnswGraph#search(float[], int, Set)}, which keeps only results whose node id is in that
 * set. This test holds both halves of that arrangement against each other, because nothing else
 * does: the two components live in different modules, neither validates the ids it is given, and
 * a disagreement between them produces no error at all - only a segment that quietly stops
 * returning results.</p>
 */
@DisplayName("LSH pre-filter and HNSW graph id space")
class LshHnswEntryIdContractTest {

    private static final int DIMENSIONS = 16;
    private static final int VECTORS = 50;

    @Test
    @DisplayName("candidates keyed by node id narrow the search; foreign ids empty it")
    void theIdSpaceIsTheGraphs(@TempDir Path tempDir) throws Exception {
        List<float[]> vectors = deterministicVectors();

        try (HnswGraph graph = new HnswGraph(tempDir.resolve("graph.hnsw"),
                HnswConfig.create(DIMENSIONS), L2Distance.getInstance(), VECTORS * 2L);
             LshIndex byNodeId = LshIndex.create(DIMENSIONS);
             LshIndex byChunkHash = LshIndex.create(DIMENSIONS)) {

            for (int i = 0; i < VECTORS; i++) {
                float[] vector = vectors.get(i);
                long nodeId = graph.insert(vector);

                // How the integration worker keys it: the id the graph just assigned.
                byNodeId.insert(vector, nodeId);

                // How ingestion used to key it, before node ids were available to it.
                byChunkHash.insert(vector, ("source:doc:chunk:" + i).hashCode() & 0xFFFFFFFFL);
            }

            float[] query = vectors.get(0);

            assertThat(graph.search(query, 10))
                .as("the graph holds the query vector, so an unrestricted search finds it")
                .isNotEmpty();

            Set<Long> nodeIdCandidates = byNodeId.query(query);
            assertThat(nodeIdCandidates)
                .as("the pre-filter holds the query vector, so it offers candidates for it")
                .isNotEmpty();

            List<HnswSearchResult> narrowed = graph.search(query, 10, nodeIdCandidates);
            assertThat(narrowed)
                .as("candidates keyed by node id name nodes the graph can search")
                .isNotEmpty();
            assertThat(narrowed).allSatisfy(result ->
                assertThat(nodeIdCandidates).contains(result.nodeId()));

            // The failure this arrangement is exposed to, kept visible on purpose: ids from any
            // other space are not merely less useful, they exclude every result. That is why the
            // pre-filter is written where node ids are assigned rather than during ingestion.
            Set<Long> foreignCandidates = byChunkHash.query(query);
            assertThat(foreignCandidates)
                .as("the foreign-keyed index holds the vector too, so it also offers candidates")
                .isNotEmpty();
            assertThat(graph.search(query, 10, foreignCandidates))
                .as("but they name no node, so the search returns nothing at all")
                .isEmpty();
        }
    }

    /**
     * Well-separated vectors, so that nearest-neighbour answers do not depend on the seed.
     */
    private static List<float[]> deterministicVectors() {
        Random random = new Random(20260907L);
        List<float[]> vectors = new ArrayList<>(VECTORS);
        for (int i = 0; i < VECTORS; i++) {
            float[] vector = new float[DIMENSIONS];
            for (int d = 0; d < DIMENSIONS; d++) {
                vector[d] = (float) random.nextGaussian();
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
