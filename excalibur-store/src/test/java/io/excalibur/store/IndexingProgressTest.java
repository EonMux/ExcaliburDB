package io.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a segment's index may be said to speak for the segment.
 *
 * <p>The two directions are not symmetric. Declaring completion too early publishes a segment
 * whose newest entries no index has seen, and the query path has no second chance to notice -
 * a query that asked to skip partially-indexed segments will have been told this one is not
 * partial. Declaring it too late is what the engine does today: nothing ever leaves
 * {@code INDEXING}, so background compaction, PQ training and scheduled reindexing never see a
 * candidate. The tests are written from the first side.</p>
 */
@DisplayName("Indexing progress")
class IndexingProgressTest {

    @Nested
    @DisplayName("The index speaks for the segment")
    class Complete {

        @Test
        @DisplayName("when it has absorbed the write log up to the head")
        void caughtUpWithTheHead() {
            assertThat(IndexingProgress.isComplete(120, 4096, 4096)).isTrue();
        }

        @Test
        @DisplayName("when it has absorbed past a head read a moment earlier")
        void aStaleHeadDoesNotHoldItBack() {
            // The head and the checkpoint are read one after the other, not atomically. A
            // checkpoint that has run past the head it was compared against has still seen
            // everything that head covered.
            assertThat(IndexingProgress.isComplete(120, 8192, 4096)).isTrue();
        }
    }

    @Nested
    @DisplayName("The index does not yet speak for the segment")
    class Incomplete {

        @Test
        @DisplayName("when the write log has entries it has not absorbed")
        void anUnabsorbedTailIsUnseen() {
            assertThat(IndexingProgress.isComplete(120, 4096, 8192)).isFalse();
        }

        @Test
        @DisplayName("when the segment holds nothing yet")
        void anEmptySegmentIsNotPublished() {
            // The engine pre-allocates and lazily creates segments a write may never reach.
            // Promoting them costs every later sweep - compaction, reindexing, warming - a scan
            // of segments that hold nothing, and the recovery path already refuses to publish an
            // empty segment for the same reason.
            assertThat(IndexingProgress.isComplete(0, 0, 0)).isFalse();
        }

        @Test
        @DisplayName("when the entry count is not readable")
        void aNegativeEntryCountClaimsNothing() {
            assertThat(IndexingProgress.isComplete(-1, 4096, 4096)).isFalse();
        }

        @Test
        @DisplayName("when no integration checkpoint could be read")
        void anUnknownCheckpointClaimsNothing() {
            assertThat(IndexingProgress.isComplete(120, -1, 4096)).isFalse();
        }

        @Test
        @DisplayName("when the write log head could not be read")
        void anUnknownHeadClaimsNothing() {
            // Without a head there is nothing to have caught up to, and a checkpoint compared
            // against a head of -1 would otherwise read as complete.
            assertThat(IndexingProgress.isComplete(120, 0, -1)).isFalse();
        }
    }
}
