package io.excalibur.vector.hnsw;

import io.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The O(1) witness for a vector retired directly in the graph.
 *
 * <p>A tombstone set by {@link HnswGraph#delete(long)} changes what a search returns and
 * flips one bit in a mapped file. Its exact witness is {@link HnswGraph#tombstoneCount()},
 * an O(node count) scan no query can pay for per call, so the graph also carries a counter
 * of state transitions that a reader of the graph can sample for free. These tests pin the
 * two properties that make it a witness of <em>state</em> rather than of calls:</p>
 * <ul>
 *   <li>it moves exactly when a node's tombstone flag actually transitions, and</li>
 *   <li>it stands still for everything else - a repeated delete of an already-retired node,
 *       a delete of a node that was never there, an insert, a search.</li>
 * </ul>
 *
 * <p>Were the second property to fail, a caller comparing two samples would see a change
 * where none happened, which costs a cache hit but is safe. Were the first to fail, it would
 * report no change across an erasure, which is not.</p>
 */
@DisplayName("HNSW tombstone generation")
class TombstoneGenerationTest {

    private static final int DIMENSION = 8;

    private static HnswGraph graph(Path dir) throws IOException {
        return new HnswGraph(dir.resolve("generation.hnsw"),
            HnswConfig.create(DIMENSION), L2Distance.getInstance(), 1024);
    }

    private static float[] vector(float first) {
        float[] value = new float[DIMENSION];
        value[0] = first;
        return value;
    }

    @Test
    @DisplayName("a fresh graph reports a generation, and an insert does not move it")
    void insertionDoesNotMoveTheGeneration(@TempDir Path tempDir) throws Exception {
        try (HnswGraph graph = graph(tempDir)) {
            long initial = graph.tombstoneGeneration();

            graph.insert(vector(1.0f));
            graph.insert(vector(2.0f));

            assertThat(graph.tombstoneGeneration())
                .as("an insert retires nothing, so it is not a tombstone transition")
                .isEqualTo(initial);
        }
    }

    @Test
    @DisplayName("tombstoning a node moves the generation")
    void aTombstoneMovesTheGeneration(@TempDir Path tempDir) throws Exception {
        try (HnswGraph graph = graph(tempDir)) {
            long nodeId = graph.insert(vector(1.0f));
            long before = graph.tombstoneGeneration();

            assertThat(graph.delete(nodeId)).isTrue();

            assertThat(graph.tombstoneGeneration())
                .as("a retired vector is a change to what every search returns")
                .isNotEqualTo(before);
        }
    }

    @Test
    @DisplayName("tombstoning the same node twice moves the generation once")
    void aRepeatedTombstoneDoesNotMoveTheGeneration(@TempDir Path tempDir) throws Exception {
        try (HnswGraph graph = graph(tempDir)) {
            long nodeId = graph.insert(vector(1.0f));
            graph.delete(nodeId);
            long afterFirst = graph.tombstoneGeneration();

            assertThat(graph.delete(nodeId))
                .as("delete reports that it found nothing left to retire")
                .isFalse();

            assertThat(graph.tombstoneGeneration())
                .as("a counter of calls rather than of state would invalidate a cache on "
                    + "every no-op erasure retry")
                .isEqualTo(afterFirst);
        }
    }

    @Test
    @DisplayName("deleting a node that was never inserted does not move the generation")
    void anAbsentNodeDoesNotMoveTheGeneration(@TempDir Path tempDir) throws Exception {
        try (HnswGraph graph = graph(tempDir)) {
            graph.insert(vector(1.0f));
            long before = graph.tombstoneGeneration();

            assertThat(graph.delete(4242L)).isFalse();
            assertThat(graph.delete(-1L)).isFalse();

            assertThat(graph.tombstoneGeneration())
                .as("no flag was flipped, so no state changed")
                .isEqualTo(before);
        }
    }

    @Test
    @DisplayName("searching does not move the generation")
    void aSearchDoesNotMoveTheGeneration(@TempDir Path tempDir) throws Exception {
        try (HnswGraph graph = graph(tempDir)) {
            graph.insert(vector(1.0f));
            graph.insert(vector(2.0f));
            long before = graph.tombstoneGeneration();

            graph.search(vector(1.5f), 2);
            graph.search(vector(1.5f), 2);

            assertThat(graph.tombstoneGeneration())
                .as("reading a graph is not a change to it, or nothing would ever cache")
                .isEqualTo(before);
        }
    }

    @Test
    @DisplayName("each distinct node retired moves the generation again")
    void everyDistinctTombstoneMovesTheGeneration(@TempDir Path tempDir) throws Exception {
        try (HnswGraph graph = graph(tempDir)) {
            long first = graph.insert(vector(1.0f));
            long second = graph.insert(vector(2.0f));
            long third = graph.insert(vector(3.0f));

            long start = graph.tombstoneGeneration();
            graph.delete(first);
            long afterFirst = graph.tombstoneGeneration();
            graph.delete(second);
            long afterSecond = graph.tombstoneGeneration();
            graph.delete(third);
            long afterThird = graph.tombstoneGeneration();

            assertThat(List.of(start, afterFirst, afterSecond, afterThird))
                .as("three retirements are three distinct states of the graph")
                .doesNotHaveDuplicates();
        }
    }
}
