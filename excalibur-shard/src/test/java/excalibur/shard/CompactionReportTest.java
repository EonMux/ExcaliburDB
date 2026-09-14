package excalibur.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a run reports, and what an operator is entitled to read into it.
 *
 * @author ExcaliburDB
 */
@DisplayName("Compaction report")
class CompactionReportTest {

    private static CompactionReport report(int segmentsConsidered, int segmentsCompacted,
                                           int segmentsFailed, int graphsConsidered,
                                           int graphsCompacted, int graphsFailed) {
        return new CompactionReport(segmentsConsidered, segmentsCompacted, segmentsFailed,
            graphsConsidered, graphsCompacted, graphsFailed, Duration.ofMillis(250));
    }

    @Test
    @DisplayName("graphs it looked at and left alone are counted as skipped")
    void graphsSkippedIsWhatWasLeftAlone() {
        // Forcing a run skips the tombstone-ratio policy, not the question of whether there is
        // anything to reclaim, so a forced run over a corpus with few deletions leaves most of
        // its graphs alone. Without this count, "4 of 64 compacted" looks like the force was
        // ignored rather than answered honestly.
        assertThat(report(0, 0, 0, 64, 4, 0).graphsSkipped()).isEqualTo(60);
    }

    @Test
    @DisplayName("a graph is skipped, compacted or failed, and never two of them")
    void theThreeGraphOutcomesAccountForEverythingConsidered() {
        CompactionReport mixed = report(0, 0, 0, 10, 3, 2);

        assertThat(mixed.graphsCompacted() + mixed.graphsFailed() + mixed.graphsSkipped())
            .isEqualTo(mixed.graphsConsidered());
    }

    @Test
    @DisplayName("a run that touched nothing skipped nothing either")
    void nothingConsideredSkipsNothing() {
        assertThat(report(0, 0, 0, 0, 0, 0).graphsSkipped()).isZero();
    }

    @Test
    @DisplayName("more graphs acted on than considered is refused rather than counted negative")
    void skippedCannotGoNegative() {
        assertThatThrownBy(() -> report(0, 0, 0, 2, 2, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("More graphs acted on than considered");
    }

    @Test
    @DisplayName("segments are accounted for the same way")
    void segmentsSkippedIsWhatWasLeftAlone() {
        assertThat(report(7, 3, 1, 0, 0, 0).segmentsSkipped()).isEqualTo(3);
    }

    @Test
    @DisplayName("a failure anywhere is a failure of the run")
    void failuresAreNotRoundedAway() {
        assertThat(report(1, 0, 1, 0, 0, 0).hasFailures()).isTrue();
        assertThat(report(0, 0, 0, 1, 0, 1).hasFailures()).isTrue();
        assertThat(report(1, 1, 0, 1, 1, 0).hasFailures()).isFalse();
    }
}
