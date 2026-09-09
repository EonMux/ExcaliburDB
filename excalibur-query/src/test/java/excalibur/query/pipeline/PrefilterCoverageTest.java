package excalibur.query.pipeline;

import excalibur.query.pipeline.PrefilterCoverage.Standing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How far a segment's pre-filter may be believed.
 *
 * <p>Every {@code UNKNOWN} here costs a full search of one segment. Every standing granted too
 * freely drops that segment's matches out of the result set with nothing logged as an error -
 * {@code COVERS_SEGMENT} all of them, {@code COVERS_GRAPH} the ones the index never saw - so the
 * two directions are not symmetric and the tests are written from that side.</p>
 */
@DisplayName("Pre-filter coverage")
class PrefilterCoverageTest {

    @Nested
    @DisplayName("It speaks for the whole segment")
    class CoversSegment {

        @Test
        @DisplayName("when it holds every node and the graph has absorbed the whole log")
        void fullyCoveredAndFullyIntegrated() {
            assertThat(PrefilterCoverage.standing(100, 100, 4096, 4096))
                .isEqualTo(Standing.COVERS_SEGMENT);
        }

        @Test
        @DisplayName("when it holds more entries than the graph has live nodes")
        void tombstonedNodesLeaveTheIndexAhead() {
            // Deleting an entry does not remove it from the pre-filter: the graph re-reads every
            // candidate and skips tombstoned ones, and the entry is reclaimed at compaction. An
            // index running ahead of the graph has still seen every node in it.
            assertThat(PrefilterCoverage.standing(140, 100, 4096, 4096))
                .isEqualTo(Standing.COVERS_SEGMENT);
        }
    }

    @Nested
    @DisplayName("It speaks for the graph but not the segment")
    class CoversGraph {

        @Test
        @DisplayName("when the write log has entries the graph has not absorbed")
        void anUnintegratedTailIsUnseen() {
            // The nodes it named still narrow stage 3's answer, because the graph holds only
            // absorbed nodes. What it may not do is rule the segment out: stage 3 would have
            // scanned that tail straight from the log, no pre-filter has ever seen it, and it
            // holds the most recently written entries - the ones a query is most likely to be
            // looking for.
            assertThat(PrefilterCoverage.standing(100, 100, 4096, 8192))
                .isEqualTo(Standing.COVERS_GRAPH);
        }

        @Test
        @DisplayName("when an unreadable offset is reported as a tail")
        void unknownOffsetsAreTreatedAsATail() {
            assertThat(PrefilterCoverage.standing(100, 100, -1, 4096))
                .isEqualTo(Standing.COVERS_GRAPH);
        }
    }

    @Nested
    @DisplayName("It speaks for nothing")
    class Unknown {

        @Test
        @DisplayName("when it holds fewer entries than the graph has nodes")
        void partialCoverageIsNoInformation() {
            // Half the graph indexed is not half an answer. The nodes it has not seen are the
            // ones it cannot report a match for, so narrowing a search to what it names drops
            // them - which is the same failure as excluding the segment, in a milder form.
            assertThat(PrefilterCoverage.standing(50, 100, 4096, 4096))
                .isEqualTo(Standing.UNKNOWN);
        }

        @Test
        @DisplayName("when it is empty")
        void anEmptyIndexKnowsNothing() {
            assertThat(PrefilterCoverage.standing(0, 100, 4096, 4096))
                .isEqualTo(Standing.UNKNOWN);
        }

        @Test
        @DisplayName("when it is empty and so is the graph")
        void anEmptySegmentIsNotExcludedOnTheIndexsSay() {
            // A segment with nothing in it is excluded by finding nothing, not by an index
            // claiming to have covered nothing.
            assertThat(PrefilterCoverage.standing(0, 0, 0, 0))
                .isEqualTo(Standing.UNKNOWN);
        }
    }
}
