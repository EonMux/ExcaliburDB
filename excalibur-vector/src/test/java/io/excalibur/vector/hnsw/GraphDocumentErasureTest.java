package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retiring every node a named document contributed.
 *
 * <p>A graph node carries no text, so an erased document's vectors are easy to mistake for
 * harmless residue. They are not: a node is attributed to a document, and a search that ranks it
 * says that document matched the query. The graph is the only component that can name a document's
 * nodes, so retiring them is its operation - the tombstone it writes is what every traversal, the
 * on-disk file and the compaction that reclaims the space all agree on.</p>
 */
@DisplayName("Erasing a document from a graph")
class GraphDocumentErasureTest {

    private static final int DIMENSION = 16;

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("erasure.hnsw");
        config = HnswConfig.create(DIMENSION);
        distanceFunction = CosineSimilarity.getInstance();
    }

    @Test
    @DisplayName("every node of the document is tombstoned, and no other")
    void tombstonesExactlyTheDocumentsNodes() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            List<Long> erased = insert(graph, "source:secret.md", 0, 3);
            List<Long> kept = insert(graph, "source:public.md", 3, 2);

            assertThat(graph.deleteDocument("source:secret.md")).isEqualTo(3);

            assertThat(erased).allSatisfy(nodeId ->
                assertThat(graph.isTombstoned(nodeId))
                    .as("node %s belonged to the erased document", nodeId)
                    .isTrue());
            assertThat(kept).allSatisfy(nodeId ->
                assertThat(graph.isTombstoned(nodeId))
                    .as("node %s belongs to a document nobody asked to erase", nodeId)
                    .isFalse());
        }
    }

    @Test
    @DisplayName("a search stops ranking the erased document")
    void searchStopsRankingTheErasedDocument() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            List<Long> erased = insert(graph, "source:secret.md", 0, 2);
            insert(graph, "source:public.md", 2, 2);

            assertThat(nodeIds(graph.search(unitVector(0), 10)))
                .as("the document is rankable before the erasure, or the test proves nothing")
                .containsAnyElementsOf(erased);

            graph.deleteDocument("source:secret.md");

            assertThat(nodeIds(graph.search(unitVector(0), 10)))
                .as("a hit still names its document even with no text left to show")
                .doesNotContainAnyElementsOf(erased);
        }
    }

    @Test
    @DisplayName("the tombstones are what a restart reads back")
    void tombstonesSurviveARestart() throws IOException {
        List<Long> erased;
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            erased = insert(graph, "source:secret.md", 0, 3);
            insert(graph, "source:public.md", 3, 1);
            graph.deleteDocument("source:secret.md");
        }

        try (HnswGraph reopened = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            assertThat(erased).allSatisfy(nodeId ->
                assertThat(reopened.isTombstoned(nodeId))
                    .as("an erasure that a restart undoes is not an erasure")
                    .isTrue());
        }
    }

    @Test
    @DisplayName("erasing again finds nothing left to retire")
    void erasingTwiceIsIdempotent() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            insert(graph, "source:secret.md", 0, 2);

            assertThat(graph.deleteDocument("source:secret.md")).isEqualTo(2);
            assertThat(graph.deleteDocument("source:secret.md")).isZero();
        }
    }

    @Test
    @DisplayName("a document this graph never held retires nothing")
    void anUnknownDocumentRetiresNothing() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            insert(graph, "source:public.md", 0, 2);

            assertThat(graph.deleteDocument("source:never-ingested.md")).isZero();
            assertThat(graph.deleteDocument(null)).isZero();
        }
    }

    /** Inserts count nodes attributed to one document, and returns their node IDs. */
    private static List<Long> insert(HnswGraph graph, String documentId, int firstVector,
                                     int count) throws IOException {
        List<Long> nodeIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long nodeId = graph.insert(unitVector(firstVector + i));
            graph.registerDocumentId(nodeId, documentId);
            nodeIds.add(nodeId);
        }
        return nodeIds;
    }

    private static List<Long> nodeIds(List<HnswSearchResult> results) {
        return results.stream().map(HnswSearchResult::nodeId).toList();
    }

    /**
     * A deterministic unit vector that differs per index, so nodes stay distinguishable
     * without the test depending on any particular neighbour ordering.
     */
    private static float[] unitVector(int index) {
        float[] vector = new float[DIMENSION];
        vector[Math.floorMod(index, DIMENSION)] = 1.0f;
        return vector;
    }
}
