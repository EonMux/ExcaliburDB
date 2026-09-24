package com.eonmux.excalibur.query.visibility;

import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one rule every filtering path applies: whether to filter, by which epoch, and per chunk id.
 */
@DisplayName("Atomicity rule shared by every filtering path")
class AtomicityRuleTest {

    @Test
    @DisplayName("filters whenever something is tracked, in flight alone included")
    void appliesWheneverSomethingIsTracked() {
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        DocumentVisibilityTrackerAdapter checker = new DocumentVisibilityTrackerAdapter(tracker);

        assertThat(DocumentAtomicityFilter.appliesTo(null)).isFalse();
        assertThat(DocumentAtomicityFilter.appliesTo(checker)).isFalse();
        tracker.beginIngestion("src:torn", 1);
        assertThat(DocumentAtomicityFilter.appliesTo(checker)).isTrue();
    }

    @Test
    @DisplayName("bounds by the query's epoch only on the node clock")
    void boundsOnlyOnTheNodeClock() {
        DocumentVisibilityTrackerAdapter privateClock = new DocumentVisibilityTrackerAdapter(
            new DocumentVisibilityTracker(new DocumentRegistry()));
        DocumentVisibilityTrackerAdapter nodeClock = new DocumentVisibilityTrackerAdapter(
            new DocumentVisibilityTracker(new DocumentRegistry(),
                new VisibilityTracker(new SegmentCatalog())));

        assertThat(DocumentAtomicityFilter.boundFor(null, 7)).isEqualTo(DocumentAtomicityFilter.UNBOUNDED);
        assertThat(DocumentAtomicityFilter.boundFor(privateClock, 7))
            .isEqualTo(DocumentAtomicityFilter.UNBOUNDED);
        assertThat(DocumentAtomicityFilter.boundFor(nodeClock, 7)).isEqualTo(7);
        assertThat(DocumentAtomicityFilter.boundFor(nodeClock, -1))
            .isEqualTo(DocumentAtomicityFilter.UNBOUNDED);
    }

    @Test
    @DisplayName("judges a chunk id exactly as it judges a result carrying it")
    void chunkIdsAreJudgedLikeResults() {
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        tracker.registerDocument("src:done", Set.of("src:done:chunk:0"), tracker.advanceEpoch());
        tracker.registerDocument("src:rewriting", Set.of("src:rewriting:chunk:0"),
            tracker.advanceEpoch());
        tracker.beginIngestion("src:rewriting", 1);
        tracker.beginIngestion("src:new", 1);
        DocumentAtomicityFilter filter = new DocumentAtomicityFilter(tracker);
        long bound = DocumentAtomicityFilter.UNBOUNDED;

        for (String chunkId : List.of("src:done:chunk:0", "src:rewriting:chunk:0",
                "src:new:chunk:3", "src:unknown:chunk:0")) {
            assertThat(filter.isChunkVisible(chunkId, bound)).as(chunkId)
                .isEqualTo(filter.isResultVisible(
                    new QueryResultItem(chunkId, 0.5f, null, 1L, 1L), bound));
        }
        assertThat(filter.isChunkVisible("src:done:chunk:0", bound)).isTrue();
        assertThat(filter.isChunkVisible("src:unknown:chunk:0", bound))
            .as("a document the tracker never saw passes, through the legacy constructor too")
            .isTrue();
        assertThat(filter.isChunkVisible("src:rewriting:chunk:0", bound)).isFalse();
        assertThat(filter.isChunkVisible("src:new:chunk:3", bound)).isFalse();
    }

    @Test
    @DisplayName("refuses a null chunk id and a negative bound")
    void refusesBadArguments() {
        DocumentAtomicityFilter filter = new DocumentAtomicityFilter(
            new DocumentVisibilityTracker(new DocumentRegistry()));

        assertThatThrownBy(() -> filter.isChunkVisible(null, 1))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> filter.isChunkVisible("src:a:chunk:0", -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
