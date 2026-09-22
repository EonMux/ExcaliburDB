package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a graph's node-to-document mapping is durable.
 *
 * <p>The mapping is what turns a search hit into a document: without it a re-attached
 * graph still returns vectors, but nothing downstream can attribute them, so document
 * visibility, atomicity filtering and payload resolution all see an unknown document.
 * The mapping is recorded by the integration worker as it inserts, so it is only
 * rebuilt by re-ingesting - it has to survive a restart on its own.</p>
 */
class NodeDocumentMappingPersistenceTest {

    private static final int DIMENSION = 16;

    @TempDir
    Path tempDir;

    private Path graphPath;
    private HnswConfig config;
    private DistanceFunction distanceFunction;

    @BeforeEach
    void setUp() {
        graphPath = tempDir.resolve("mapping.hnsw");
        config = HnswConfig.create(DIMENSION);
        distanceFunction = CosineSimilarity.getInstance();
    }

    @Test
    void mappingSurvivesCloseAndReopen() throws IOException {
        Map<Long, String> registered = new HashMap<>();

        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 8; i++) {
                long nodeId = graph.insert(unitVector(i));
                String documentId = "doc-" + i;
                graph.registerDocumentId(nodeId, documentId);
                registered.put(nodeId, documentId);
            }
        }

        assertThat(Files.exists(mappingFile(graphPath)))
            .as("the mapping is written beside the graph on close")
            .isTrue();

        try (HnswGraph reopened = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (Map.Entry<Long, String> entry : registered.entrySet()) {
                assertThat(reopened.getDocumentId(entry.getKey()))
                    .as("node %s keeps its document across a restart", entry.getKey())
                    .isEqualTo(entry.getValue());
            }
        }
    }

    @Test
    void mappingSurvivesReopenThroughOpenFactory() throws IOException {
        long nodeId;
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            nodeId = graph.insert(unitVector(3));
            graph.registerDocumentId(nodeId, "doc-open-factory");
        }

        try (HnswGraph reopened = HnswGraph.open(graphPath, distanceFunction)) {
            assertThat(reopened.getDocumentId(nodeId)).isEqualTo("doc-open-factory");
        }
    }

    @Test
    void flushMakesTheMappingReadableWithoutClosing() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            long nodeId = graph.insert(unitVector(1));
            graph.registerDocumentId(nodeId, "doc-flushed");
            graph.flush();

            NodeDocumentMapping onDisk =
                NodeDocumentMapping.createOrLoad(mappingFile(graphPath), true);
            assertThat(onDisk.getDocumentId(nodeId)).isEqualTo("doc-flushed");
        }
    }

    @Test
    void compactionRewritesTheMappingInTheNewNumbering() throws IOException {
        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 10; i++) {
                long nodeId = graph.insert(unitVector(i));
                graph.registerDocumentId(nodeId, "doc-" + i);
            }

            // Retire the first five. Compaction renumbers what is left to 0..4.
            for (int i = 0; i < 5; i++) {
                graph.delete(i);
            }

            assertThat(graph.compact(tempDir)).isEqualTo(5);
            assertThat(graph.nodeCount()).isEqualTo(5);

            // The surviving documents are exactly the ones that were not deleted, and no
            // node ID beyond the compacted count carries a mapping: an entry left over
            // from the old numbering would be inherited by the next node to take that ID.
            NodeDocumentMapping mapping = graph.getNodeDocumentMapping();
            assertThat(mapping.getAllMappings().values())
                .containsExactlyInAnyOrder("doc-5", "doc-6", "doc-7", "doc-8", "doc-9");
            assertThat(mapping.getAllMappings().keySet())
                .allSatisfy(nodeId -> assertThat(nodeId).isLessThan(graph.nodeCount()));
        }

        try (HnswGraph reopened = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            assertThat(reopened.getNodeDocumentMapping().getAllMappings().values())
                .as("the compacted numbering is what a restart reads back")
                .containsExactlyInAnyOrder("doc-5", "doc-6", "doc-7", "doc-8", "doc-9");
        }
    }

    @Test
    void compactedCopyCarriesDocumentIdentityAndNotAStaleMapping() throws IOException {
        Path copyPath = tempDir.resolve("copy.hnsw");

        // A mapping from an earlier copy at the same path. The copy renumbers nodes, so
        // adopting it would attribute the new nodes to whatever held those IDs before.
        NodeDocumentMapping stale = NodeDocumentMapping.createOrLoad(mappingFile(copyPath), false);
        stale.register(0L, "doc-from-a-previous-copy");
        stale.persist();

        try (HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 1000)) {
            for (int i = 0; i < 6; i++) {
                long nodeId = graph.insert(unitVector(i));
                graph.registerDocumentId(nodeId, "doc-" + i);
            }
            graph.delete(0);
            graph.delete(1);

            assertThat(graph.compactTo(copyPath)).isEqualTo(2);
        }

        try (HnswGraph copy = HnswGraph.open(copyPath, distanceFunction)) {
            assertThat(copy.getNodeDocumentMapping().getAllMappings().values())
                .containsExactlyInAnyOrder("doc-2", "doc-3", "doc-4", "doc-5");
        }
    }

    private static Path mappingFile(Path graphPath) {
        return Path.of(graphPath.toString() + HnswGraph.DOCUMENT_MAPPING_SUFFIX);
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
