package io.excalibur.store;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.hnsw.HnswConfigFactory;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An interrupted HNSW compaction has to be resolved on the path production actually opens by.
 *
 * <p>Compaction renumbers every surviving node and replaces two files - the graph and the
 * {@code .docmap} beside it - with two renames it cannot perform as one. A crash between them
 * leaves a compacted graph sitting next to a pre-compaction mapping, and that pair is not a
 * broken index: it is a working index whose every answer names the wrong document. Nothing
 * downstream can detect it, because both files are individually intact.</p>
 *
 * <p>The recovery for that state is exercised elsewhere through {@code HnswGraph.open}. This
 * test deliberately does not use it. Production attaches graphs through {@link HnswInitializer},
 * which reaches the four-argument constructor, and a recovery that only runs on a code path no
 * deployment takes is the same as no recovery at all.</p>
 */
@DisplayName("Interrupted HNSW compaction, recovered through the segment attach path")
class HnswInterruptedCompactionRecoveryTest {

    private static final int DIMENSION = 8;
    private static final int DOCUMENTS = 6;
    private static final long SEGMENT_CAPACITY = 1024 * 1024;

    @TempDir
    Path tempDir;

    private Arena arena;

    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
    }

    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    /** A vector nearest to itself and to nothing else, so one search identifies one document. */
    private static float[] axis(int index) {
        float[] vector = new float[DIMENSION];
        vector[index] = 1.0f;
        return vector;
    }

    private static HnswInitializer initializer() {
        return new HnswInitializer(HnswConfigFactory.withDefaults(),
            CosineSimilarity.getInstance(), DIMENSION, 100L, null);
    }

    private static void assertSurvivorsKeepTheirDocuments(HnswGraph graph) {
        for (int i = 1; i < DOCUMENTS; i += 2) {
            List<HnswSearchResult> results = graph.search(axis(i), 1);
            assertThat(results).as("document doc-%d is still in the graph", i).hasSize(1);
            assertThat(results.get(0).documentId())
                .as("the node holding doc-%d's vector must resolve to doc-%d", i, i)
                .isEqualTo("doc-" + i);
        }
    }

    @Test
    @DisplayName("a crash between the two renames is finished when the segment re-attaches")
    void aCrashBetweenTheRenamesIsFinishedOnSegmentAttach() throws IOException {
        HnswInitializer initializer = initializer();
        Path segmentPath = tempDir.resolve("segment_0.dat");
        Path graphPath = initializer.computeGraphPath(segmentPath);
        Path staged = Path.of(graphPath + ".compact");

        try (Segment segment = new Segment(segmentPath, SEGMENT_CAPACITY, arena)) {
            initializer.initializeForSegment(segment);
            HnswGraph graph = segment.getHnswGraph();
            assertThat(graph).isNotNull();

            for (int i = 0; i < DOCUMENTS; i++) {
                graph.registerDocumentId(graph.insert(axis(i)), "doc-" + i);
            }
            for (int i = 0; i < DOCUMENTS; i += 2) {
                graph.delete(i);
            }
            graph.flush();
            graph.compactTo(staged);
        }

        // Exactly what a crash between the graph rename and the mapping rename leaves behind:
        // the compacted graph is live, the mapping beside it still describes the old numbering,
        // and the mapping that matches is still under the staging name.
        Files.move(staged, graphPath,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        assertThat(Path.of(staged + HnswGraph.DOCUMENT_MAPPING_SUFFIX))
            .as("the staged mapping is the evidence the recovery works from")
            .exists();

        try (Segment reattached = Segment.openExisting(segmentPath, arena)) {
            initializer.initializeForSegment(reattached);
            HnswGraph graph = reattached.getHnswGraph();
            assertThat(graph).isNotNull();
            assertSurvivorsKeepTheirDocuments(graph);
        }
    }

    @Test
    @DisplayName("a crash before the graph rename discards the staged pair on segment attach")
    void aCrashBeforeTheGraphRenameDiscardsTheStagedPair() throws IOException {
        HnswInitializer initializer = initializer();
        Path segmentPath = tempDir.resolve("segment_1.dat");
        Path graphPath = initializer.computeGraphPath(segmentPath);
        Path staged = Path.of(graphPath + ".compact");

        try (Segment segment = new Segment(segmentPath, SEGMENT_CAPACITY, arena)) {
            initializer.initializeForSegment(segment);
            HnswGraph graph = segment.getHnswGraph();
            for (int i = 0; i < DOCUMENTS; i++) {
                graph.registerDocumentId(graph.insert(axis(i)), "doc-" + i);
            }
            for (int i = 0; i < DOCUMENTS; i += 2) {
                graph.delete(i);
            }
            graph.flush();
            graph.compactTo(staged);
        }

        try (Segment reattached = Segment.openExisting(segmentPath, arena)) {
            initializer.initializeForSegment(reattached);
            assertSurvivorsKeepTheirDocuments(reattached.getHnswGraph());
        }

        assertThat(staged)
            .as("the staged graph belongs to a compaction that never swapped in")
            .doesNotExist();
        assertThat(Path.of(staged + HnswGraph.DOCUMENT_MAPPING_SUFFIX))
            .as("a staged mapping left beside no staged graph reads as a completed swap")
            .doesNotExist();
    }
}
