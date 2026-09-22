package com.eonmux.excalibur.store;

import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.distance.L2Distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ownership of an attached HNSW graph.
 *
 * <p>A graph holds a FileChannel, an Arena and a whole-file mapping. Nothing in production released
 * them, so every retired segment leaked one of each until the process exited — which is what
 * eventually exhausts the descriptor and mapping budgets on a long-running node.
 *
 * <p>The complication is compaction: it carries the graph onto a replacement segment and then lets
 * the old one close. Closing on the old segment unconditionally would release the mappings out
 * from under the index that is about to serve queries.
 */
@DisplayName("Segment HNSW graph ownership")
class SegmentGraphOwnershipTest {

    private static HnswGraph graph(Path dir, String name) throws IOException {
        return new HnswGraph(dir.resolve(name), HnswConfig.create(8), L2Distance.getInstance(), 100);
    }

    private static Segment segment(Path dir, String name, Arena arena) throws IOException {
        return new Segment(dir.resolve(name), 1024 * 1024, arena);
    }

    @Test
    @DisplayName("closing a segment closes the graph it owns")
    void closeReleasesTheGraph(@TempDir Path dir) throws IOException {
        try (Arena arena = Arena.ofShared()) {
            HnswGraph graph = graph(dir, "owned.hnsw");
            Segment segment = segment(dir, "owned.dat", arena);
            segment.attachHnswGraph(graph);

            segment.close();

            assertThat(graph.isClosed())
                .as("the mappings and descriptor must go back when the segment is retired")
                .isTrue();
        }
    }

    @Test
    @DisplayName("a detached graph survives its former segment being closed")
    void detachedGraphSurvives(@TempDir Path dir) throws IOException {
        // The compaction path: the replacement is about to serve queries from this same graph.
        try (Arena arena = Arena.ofShared()) {
            HnswGraph graph = graph(dir, "carried.hnsw");
            Segment oldSegment = segment(dir, "old.dat", arena);
            Segment replacement = segment(dir, "new.dat", arena);

            oldSegment.attachHnswGraph(graph);
            HnswGraph detached = oldSegment.detachHnswGraph();
            replacement.attachHnswGraph(detached);

            oldSegment.close();

            assertThat(detached).isSameAs(graph);
            assertThat(graph.isClosed())
                .as("closing the retired segment must not pull the mappings from the replacement")
                .isFalse();
            assertThat(replacement.getHnswGraph()).isSameAs(graph);

            replacement.close();
            assertThat(graph.isClosed()).isTrue();
        }
    }

    @Test
    @DisplayName("detaching leaves the segment holding nothing")
    void detachClearsTheField(@TempDir Path dir) throws IOException {
        try (Arena arena = Arena.ofShared()) {
            Segment segment = segment(dir, "detach.dat", arena);
            segment.attachHnswGraph(graph(dir, "detach.hnsw"));

            assertThat(segment.detachHnswGraph()).isNotNull();
            assertThat(segment.getHnswGraph()).isNull();
            assertThat(segment.detachHnswGraph()).isNull();

            segment.close();
        }
    }

    @Test
    @DisplayName("a segment with no graph closes cleanly")
    void noGraphIsFine(@TempDir Path dir) throws IOException {
        try (Arena arena = Arena.ofShared()) {
            Segment segment = segment(dir, "bare.dat", arena);
            segment.close();
            assertThat(segment.isClosed()).isTrue();
        }
    }
}
