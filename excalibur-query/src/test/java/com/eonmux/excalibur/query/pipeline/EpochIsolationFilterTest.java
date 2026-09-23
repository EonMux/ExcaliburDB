package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.executor.SegmentQueryTask;
import com.eonmux.excalibur.shard.visibility.EpochIsolationValidator;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dropping the nodes a query may not see yet, without rewriting anything else a segment said.
 *
 * <p>The filter rebuilds each segment's result around the surviving nodes. Whatever it does not
 * copy across is silently reset to the constructor's default, and for completeness that default
 * is "searched in full" - so a segment whose tail scan stopped early would come out of the filter
 * reporting a whole answer, and the response's coverage with it.</p>
 */
@DisplayName("Epoch isolation filter")
class EpochIsolationFilterTest {

    private static final long SEGMENT_ID = 4L;
    private static final long QUERY_EPOCH = 10L;

    @Test
    @DisplayName("a segment that answered from part of its log still says so after filtering")
    void partialSegmentsStayPartial() {
        EpochIsolationValidator validator = new EpochIsolationValidator();
        // Nodes from 5 upward were inserted after the query began.
        validator.registerNodeRange(SEGMENT_ID, 0L, 1L);
        validator.registerNodeRange(SEGMENT_ID, 5L, QUERY_EPOCH + 1);

        SegmentQueryTask.SegmentQueryResult partial = new SegmentQueryTask.SegmentQueryResult(
            SEGMENT_ID,
            List.of(new HnswSearchResult(1L, 0.1f), new HnswSearchResult(7L, 0.2f)),
            64L, 3L, true, null, Map.of(1L, new byte[] {1}), false);

        List<SegmentQueryTask.SegmentQueryResult> filtered =
            EpochIsolationFilter.retainVisible(validator, List.of(partial), QUERY_EPOCH);

        assertThat(filtered).singleElement().satisfies(result -> {
            assertThat(result.results()).extracting(HnswSearchResult::nodeId)
                .as("the node inserted after the query's epoch is dropped")
                .containsExactly(1L);
            assertThat(result.complete())
                .as("dropping invisible nodes does not turn a truncated tail scan into a whole one")
                .isFalse();
            assertThat(result.tailScanEntries()).isEqualTo(64L);
            assertThat(result.payload(1L)).containsExactly(1);
        });
    }

    @Test
    @DisplayName("a segment searched in full stays complete")
    void completeSegmentsStayComplete() {
        EpochIsolationValidator validator = new EpochIsolationValidator();
        validator.registerNodeRange(SEGMENT_ID, 0L, 1L);

        SegmentQueryTask.SegmentQueryResult whole = new SegmentQueryTask.SegmentQueryResult(
            SEGMENT_ID, List.of(new HnswSearchResult(1L, 0.1f)), 0L, 3L, true, null,
            Map.of(), true);

        assertThat(EpochIsolationFilter.retainVisible(validator, List.of(whole), QUERY_EPOCH))
            .singleElement()
            .satisfies(result -> assertThat(result.complete()).isTrue());
    }
}
