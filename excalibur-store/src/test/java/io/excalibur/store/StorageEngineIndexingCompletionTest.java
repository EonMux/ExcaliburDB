package io.excalibur.store;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.hnsw.HnswConfigFactory;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a live-ingested segment leaves {@link LifecycleState#INDEXING}.
 *
 * <p>The engine creates every segment in {@code INDEXING} and, until this was wired, nothing
 * took it out again. Everything downstream selects on a later state - compaction on
 * {@code ACTIVE}, PQ training on {@code WARMING}, scheduled reindexing on {@code ACTIVE} - so a
 * node that had only ever ingested reported sixty-four indexing segments and no active ones,
 * and none of those three ever ran. A query asking to be spared partially-indexed segments was
 * spared all of them.</p>
 */
@DisplayName("Indexing completion")
class StorageEngineIndexingCompletionTest {

    private static final byte[] KEY = "completion-key".getBytes();
    private static final byte[] VALUE = "completion-value".getBytes();

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        engine = new StorageEngine(tempDir, arena);
        engine.setHnswInitializer(new HnswInitializer(
            HnswConfigFactory.withDefaults(), CosineSimilarity.getInstance(),
            HnswInitializer.DEFAULT_VECTOR_DIMENSION, 1024, arena));
        engine.start();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    /** The index the engine's routing sends {@link #KEY} to. */
    private static long writtenSegment() {
        return io.excalibur.store.hash.XxHash64.hash(KEY) & 63;
    }

    /** Stands in for the HNSW integration worker draining the segment's write log. */
    private void absorbTheWriteLog(long segmentIndex) {
        Segment segment = engine.getSegment(segmentIndex);
        TailOffsetTracker tracker = segment.getTailOffsetTracker();
        assertThat(tracker).as("segment %d should have an integration checkpoint", segmentIndex)
            .isNotNull();
        tracker.setLastIntegratedOffset(segment.getWriteLogHead());
    }

    @Nested
    @DisplayName("The engine reports a segment's index as caught up")
    class Complete {

        @Test
        @DisplayName("once the checkpoint has reached the head of its write log")
        void anAbsorbedWriteLogIsComplete() {
            engine.put(KEY, VALUE);
            long segmentIndex = writtenSegment();
            absorbTheWriteLog(segmentIndex);

            assertThat(engine.isIndexingComplete(segmentIndex)).isTrue();
        }
    }

    @Nested
    @DisplayName("The engine does not report a segment's index as caught up")
    class Incomplete {

        @Test
        @DisplayName("while its write log holds entries the checkpoint has not reached")
        void anUnabsorbedWriteLogIsIncomplete() {
            engine.put(KEY, VALUE);

            assertThat(engine.isIndexingComplete(writtenSegment())).isFalse();
        }

        @Test
        @DisplayName("for a pre-allocated segment nothing has been written to")
        void anEmptySegmentIsIncomplete() {
            long empty = (writtenSegment() + 1) & 63;

            assertThat(engine.isIndexingComplete(empty)).isFalse();
        }

        @Test
        @DisplayName("for an index that names no segment")
        void anUnknownSegmentIsIncomplete() {
            assertThat(engine.isIndexingComplete(9999L)).isFalse();
        }
    }

    @Nested
    @DisplayName("The lifecycle manager")
    class LifecycleSweep {

        @Test
        @DisplayName("advances a live-ingested segment out of INDEXING once its index catches up")
        void aCaughtUpSegmentIsAdvanced() {
            engine.put(KEY, VALUE);
            long segmentIndex = writtenSegment();
            absorbTheWriteLog(segmentIndex);

            engine.getLifecycleManager().checkForIndexingCompletion();

            assertThat(engine.getSegmentLifecycleState(segmentIndex))
                .isEqualTo(LifecycleState.WARMING);
        }

        @Test
        @DisplayName("leaves a segment whose index is still behind where it is")
        void aLaggingSegmentIsLeftAlone() {
            engine.put(KEY, VALUE);
            long segmentIndex = writtenSegment();

            engine.getLifecycleManager().checkForIndexingCompletion();

            assertThat(engine.getSegmentLifecycleState(segmentIndex))
                .isEqualTo(LifecycleState.INDEXING);
        }

        @Test
        @DisplayName("leaves the sixty-three segments nothing was written to where they are")
        void emptySegmentsAreLeftAlone() {
            engine.put(KEY, VALUE);
            long written = writtenSegment();
            absorbTheWriteLog(written);

            engine.getLifecycleManager().checkForIndexingCompletion();

            for (long segmentIndex = 0; segmentIndex < 64; segmentIndex++) {
                if (segmentIndex == written) {
                    continue;
                }
                assertThat(engine.getSegmentLifecycleState(segmentIndex))
                    .as("empty segment %d", segmentIndex)
                    .isEqualTo(LifecycleState.INDEXING);
            }
        }
    }
}
