package com.eonmux.excalibur.ingest.lexical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an index says about the part of itself no query can reach.
 */
@DisplayName("Chunk index stats")
class ChunkIndexStatsTest {

    private static ChunkIndexStats withUnreadable(long live, long erased,
                                                  List<ChunkIndexStats.UnreadableSegment> bad) {
        return new ChunkIndexStats("native", 0, 3, live, erased, 0, Optional.empty(), false, 0,
            bad, 0);
    }

    @Test
    @DisplayName("unsearchable chunks are the sum over the segments that could not be opened")
    void unsearchableIsSummed() {
        ChunkIndexStats stats = withUnreadable(10, 0, List.of(
            new ChunkIndexStats.UnreadableSegment("seg-1", 4, true, "damaged"),
            new ChunkIndexStats.UnreadableSegment("seg-2", 6, false, "damaged")));

        assertThat(stats.unsearchableDocuments()).isEqualTo(10);
    }

    @Test
    @DisplayName("the erased share is measured against everything on disk")
    void erasedRatioCountsUnsearchable() {
        ChunkIndexStats stats = withUnreadable(5, 5, List.of(
            new ChunkIndexStats.UnreadableSegment("seg-1", 10, true, "damaged")));

        assertThat(stats.erasedRatio()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("an index that cannot have damaged segments reports none")
    void shorterFormsReportNone() {
        ChunkIndexStats stats = new ChunkIndexStats("lucene", 1, 1, 1, 0, 0, Optional.empty(),
            false);

        assertThat(stats.unreadableSegments()).isEmpty();
        assertThat(stats.unsearchableDocuments()).isZero();
        assertThat(stats.outdatedSegments()).isZero();
    }

    @Test
    @DisplayName("refuses a count that cannot be true")
    void refusesImpossibleCounts() {
        assertThatThrownBy(() -> new ChunkIndexStats("native", 0, 0, 0, 0, 0, Optional.empty(),
            false, 0, List.of(), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkIndexStats.UnreadableSegment("seg-1", -1, true, "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkIndexStats.UnreadableSegment(null, 1, true, "x"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> withUnreadable(0, 0, null))
            .isInstanceOf(NullPointerException.class);
    }
}
