package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compaction replaces two files, and a crash can land between them.
 *
 * <p>A rebuilt graph renumbers every node, so the {@code .docmap} beside it is the only thing
 * that says which document a node holds. A new graph sitting beside the previous mapping is
 * not a broken index - it is a working index that answers with the wrong documents, which no
 * query, no checksum and no later open would ever notice. These tests pin the order the two
 * files change in and what an open makes of every state a crash can leave behind.</p>
 */
@DisplayName("Compaction's graph and document-mapping swap")
class CompactionMappingSwapTest {

    private static final int DIMENSION = 8;
    private static final int DOCUMENTS = 6;

    @TempDir
    Path tempDir;

    private final DistanceFunction distanceFunction = CosineSimilarity.getInstance();

    /**
     * A vector that is nearest to itself and to nothing else, so a search for it identifies
     * exactly one node and therefore exactly one document mapping.
     */
    private static float[] axis(int index) {
        float[] vector = new float[DIMENSION];
        vector[index] = 1.0f;
        return vector;
    }

    private Path graphPath() {
        return tempDir.resolve("index.hnsw");
    }

    private static Path mappingOf(Path graph) {
        return Path.of(graph + HnswGraph.DOCUMENT_MAPPING_SUFFIX);
    }

    private static Path stagedGraphOf(Path graph) {
        return Path.of(graph + ".compact");
    }

    /**
     * Builds a graph of {@link #DOCUMENTS} one-hot vectors, tombstoning the even ones so a
     * compaction has something to reclaim and every survivor is renumbered.
     */
    private HnswGraph buildGraphWithTombstones() throws IOException {
        HnswGraph graph = new HnswGraph(graphPath(), HnswConfig.create(DIMENSION),
            distanceFunction, 100);
        for (int i = 0; i < DOCUMENTS; i++) {
            long nodeId = graph.insert(axis(i));
            graph.registerDocumentId(nodeId, "doc-" + i);
        }
        for (int i = 0; i < DOCUMENTS; i += 2) {
            graph.delete(i);
        }
        graph.flush();
        return graph;
    }

    /**
     * Asserts that every surviving document is still reachable under its own name.
     */
    private void assertSurvivorsKeepTheirDocuments(HnswGraph graph) {
        for (int i = 1; i < DOCUMENTS; i += 2) {
            List<HnswSearchResult> results = graph.search(axis(i), 1);
            assertThat(results).as("document doc-%d is still in the graph", i).hasSize(1);
            assertThat(results.get(0).documentId())
                .as("the node holding doc-%d's vector must resolve to doc-%d", i, i)
                .isEqualTo("doc-" + i);
        }
    }

    @Test
    @DisplayName("a crash after the graph swap finishes the mapping swap on the next open")
    void crashAfterTheGraphSwapIsFinishedOnOpen() throws IOException {
        Path graph = graphPath();
        Path staged = stagedGraphOf(graph);

        try (HnswGraph live = buildGraphWithTombstones()) {
            live.compactTo(staged);
        }

        // The exact state a crash between the two renames leaves: the compacted graph is
        // live, and the mapping beside it is still the pre-compaction one.
        Files.move(staged, graph, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);

        try (HnswGraph reopened = HnswGraph.open(graph, distanceFunction)) {
            assertSurvivorsKeepTheirDocuments(reopened);
        }
    }

    @Test
    @DisplayName("a crash before the graph swap discards the staged pair and keeps the old one")
    void crashBeforeTheGraphSwapDiscardsTheStagedPair() throws IOException {
        Path graph = graphPath();
        Path staged = stagedGraphOf(graph);

        try (HnswGraph live = buildGraphWithTombstones()) {
            live.compactTo(staged);
        }

        try (HnswGraph reopened = HnswGraph.open(graph, distanceFunction)) {
            assertSurvivorsKeepTheirDocuments(reopened);
        }

        assertThat(staged)
            .as("the staged graph belongs to a compaction that never happened")
            .doesNotExist();
        assertThat(mappingOf(staged))
            .as("a staged mapping left beside no staged graph would be replayed as a "
                + "completed swap by the next open")
            .doesNotExist();
    }

    /**
     * Puts a non-empty directory where a staged pair's mapping belongs, so deleting it fails.
     *
     * <p>A failed delete and a crash between two deletes leave the same thing on disk: whatever
     * was removed first is gone and the rest is not. Making the mapping's removal fail is how
     * these tests reach that window deterministically.</p>
     */
    private static void makeStagedMappingUndeletable(Path staged) throws IOException {
        Path stagedMapping = mappingOf(staged);
        Files.deleteIfExists(stagedMapping);
        Files.createDirectory(stagedMapping);
        Files.createFile(stagedMapping.resolve("in-the-way"));
    }

    @Test
    @DisplayName("discarding an abandoned pair never leaves the staged mapping on its own")
    void discardingAnAbandonedPairNeverStrandsTheMapping() throws IOException {
        Path graph = graphPath();
        Path staged = stagedGraphOf(graph);

        try (HnswGraph live = buildGraphWithTombstones()) {
            live.compactTo(staged);
        }
        makeStagedMappingUndeletable(staged);

        try (HnswGraph reopened = HnswGraph.open(graph, distanceFunction)) {
            assertSurvivorsKeepTheirDocuments(reopened);
        }

        assertThat(staged)
            .as("with the staged mapping still present, removing the staged graph would turn a "
                + "compaction that never happened into one the next open completes")
            .exists();
    }

    @Test
    @DisplayName("clearing an abandoned pair before compacting never leaves the mapping alone")
    void clearingAnAbandonedPairBeforeCompactingNeverStrandsTheMapping() throws IOException {
        Path graph = graphPath();
        Path staged = stagedGraphOf(graph);

        try (HnswGraph live = buildGraphWithTombstones()) {
            live.compactTo(staged);
            makeStagedMappingUndeletable(staged);

            assertThatThrownBy(() -> live.compact(tempDir))
                .isInstanceOf(IOException.class);
        }

        assertThat(staged)
            .as("the staged graph must outlive a staged mapping that could not be removed")
            .exists();
    }

    @Test
    @DisplayName("a completed compaction leaves no staged files behind")
    void aCompletedCompactionLeavesNothingStaged() throws IOException {
        Path graph = graphPath();

        try (HnswGraph live = buildGraphWithTombstones()) {
            assertThat(live.compact(tempDir)).isEqualTo(DOCUMENTS / 2);
            assertSurvivorsKeepTheirDocuments(live);
        }

        assertThat(stagedGraphOf(graph)).doesNotExist();
        assertThat(mappingOf(stagedGraphOf(graph))).doesNotExist();

        try (HnswGraph reopened = HnswGraph.open(graph, distanceFunction)) {
            assertSurvivorsKeepTheirDocuments(reopened);
        }
    }

    @Test
    @DisplayName("a mapping the swap could not install is still recoverable from the staged copy")
    void anUninstallableMappingIsRecoveredFromTheStagedCopy() throws IOException {
        Path graph = graphPath();
        Path mapping = mappingOf(graph);

        try (HnswGraph live = buildGraphWithTombstones()) {
            // A non-empty directory where the mapping belongs makes the rename that installs
            // it fail, which is the same window a crash opens: the graph has been replaced
            // and the mapping beside it has not.
            Files.deleteIfExists(mapping);
            Files.createDirectory(mapping);
            Files.createFile(mapping.resolve("in-the-way"));

            live.compact(tempDir);
        }

        Files.delete(mapping.resolve("in-the-way"));
        Files.delete(mapping);

        try (HnswGraph reopened = HnswGraph.open(graph, distanceFunction)) {
            assertSurvivorsKeepTheirDocuments(reopened);
        }
    }
}
