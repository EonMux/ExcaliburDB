package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A checkpoint above its own log's head is the quietest way a segment stops being searchable.
 *
 * <p>The integration worker only looks for work while the checkpoint is below the write log's
 * head. Above it, the worker never looks again: every subsequent write is stored,
 * acknowledged, and never indexed. Nothing throws and nothing repairs it, so the only evidence
 * is a segment that answers key lookups and not vector queries.</p>
 */
@DisplayName("HNSW integration checkpoint rebasing")
class IntegrationCheckpointTest {

    private static final int DIMENSION = 16;

    @TempDir
    Path tempDir;

    private Arena arena;
    private Segment segment;
    private HnswGraph graph;
    private TailOffsetTracker tracker;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        segment = new Segment(tempDir.resolve("segment_0.dat"), 1024 * 1024, arena);
        graph = new HnswGraph(tempDir.resolve("segment_0.hnsw"),
            HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 100);
        tracker = new TailOffsetTracker(graph);
    }

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.close();
        }
        if (segment != null) {
            segment.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    @DisplayName("a checkpoint that outran its log is pulled back to the head")
    void aStaleCheckpointIsRebasedOntoTheHead() {
        tracker.resetTo(40_000_000L);
        assertThat(segment.getWriteLogHead()).isLessThan(40_000_000L);

        IntegrationCheckpoint.rebaseIfStale(segment, tracker, "a split");

        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(segment.getWriteLogHead());
    }

    @Test
    @DisplayName("a checkpoint that fits its log is real progress and is left alone")
    void aValidCheckpointIsUntouched() {
        // Rewinding a checkpoint that fits would re-integrate everything below it, inserting
        // every one of those vectors into the graph a second time.
        assertThat(tracker.getLastIntegratedOffset()).isZero();

        IntegrationCheckpoint.rebaseIfStale(segment, tracker, "compaction");

        assertThat(tracker.getLastIntegratedOffset()).isZero();
    }

    @Test
    @DisplayName("a segment with no checkpoint at all is not a failure")
    void aMissingTrackerIsIgnored() {
        // A segment without an HNSW graph has no tracker, and the split path reaches here
        // before it knows whether there is one.
        assertThatCode(() -> IntegrationCheckpoint.rebaseIfStale(segment, null, "a split"))
            .doesNotThrowAnyException();
    }
}
