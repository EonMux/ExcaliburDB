package io.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A segment tombstoned without an epoch stays visible to every query.
 *
 * <p>{@code transitionToTombstoned(long)} writes the lifecycle state under the metadata's
 * state lock and the tombstone epoch as a separate volatile store, so there is a window in
 * which a reader sees {@code TOMBSTONED} with the epoch still at its {@code -1} sentinel. The
 * same shape arises permanently for a segment tombstoned with no epoch source wired, and for
 * a row recovered from a catalog written before epochs were tracked.</p>
 *
 * <p>All three must read as visible. The segment was visible an instant earlier, so answering
 * "visible" can only delay a tombstone taking effect; answering "invisible" would drop a
 * segment out from under a query that started before the tombstone and is entitled to see it.
 * The test exists to stop the sentinel being tidied into a default of 0, which reads as
 * "tombstoned at the very first epoch" and makes the segment invisible to everything.</p>
 */
@DisplayName("Tombstoned with no epoch recorded")
class TombstoneEpochSentinelTest {

    @ParameterizedTest(name = "visible to a query at epoch {0}")
    @ValueSource(longs = {0L, 1L, 42L, Long.MAX_VALUE})
    @DisplayName("a tombstone with no epoch is visible at every query epoch")
    void tombstonedWithoutEpochIsVisibleEverywhere(long queryEpoch) {
        SegmentCatalog.SegmentMetadata metadata = tombstonedWithoutEpoch();

        assertThat(metadata.getLifecycleState()).isEqualTo(LifecycleState.TOMBSTONED);
        assertThat(metadata.getTombstoneEpoch()).isEqualTo(-1L);
        assertThat(metadata.isVisibleAtEpoch(queryEpoch))
            .as("a tombstone whose epoch was never recorded must not hide the segment")
            .isTrue();
    }

    @Test
    @DisplayName("the missing tombstone epoch does not disturb the creation bound")
    void theCreationBoundStillApplies() {
        SegmentCatalog.SegmentMetadata metadata = tombstonedWithoutEpoch();
        metadata.setCreationEpoch(5L);

        assertThat(metadata.isVisibleAtEpoch(4L)).isFalse();
        assertThat(metadata.isVisibleAtEpoch(5L)).isTrue();
        assertThat(metadata.isVisibleAtEpoch(Long.MAX_VALUE)).isTrue();
    }

    /**
     * Drives a fresh segment to TOMBSTONED without recording a visibility epoch.
     *
     * @return Metadata in the state a reader sees inside the tombstone transition window
     */
    private SegmentCatalog.SegmentMetadata tombstonedWithoutEpoch() {
        SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(1L);
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToTombstoned();
        return metadata;
    }
}
