package io.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entry-state update records when the segment was last touched.
 *
 * <p>The stamp is not decoration. When a segment is tombstoned without a visibility epoch -
 * an older catalog row, or a tombstone taken while no epoch source is wired -
 * {@code SegmentLifecycleManager} falls back to a wall-clock grace period, and
 * {@code lastActiveTime} is the reference that period is measured from. Losing the stamp on
 * either path does not fail anything visibly: it silently moves the reference back to the
 * segment's creation time, and a segment created long ago becomes reclaimable the instant it
 * is tombstoned.</p>
 *
 * <p>Both paths are pinned because the delete path is the one that matters and the one that
 * looks most redundant: "last active time" on a segment that has just been deleted reads like
 * a mistake until the grace period is followed through.</p>
 */
@DisplayName("Entry-state updates stamp the last-active time")
class CatalogActivityStampTest {

    @Test
    @DisplayName("an ACTIVE update stamps the last-active time")
    void activeUpdateStampsLastActiveTime() {
        assertStampedByUpdate(SegmentCatalog.ENTRY_STATE_ACTIVE);
    }

    @Test
    @DisplayName("a DELETED update stamps the last-active time the grace period runs from")
    void deletedUpdateStampsLastActiveTime() {
        assertStampedByUpdate(SegmentCatalog.ENTRY_STATE_DELETED);
    }

    /**
     * Asserts that an update in the given entry state moves the last-active time to now.
     *
     * @param entryState The entry state to update the segment into
     */
    private void assertStampedByUpdate(int entryState) {
        SegmentCatalog catalog = new SegmentCatalog();

        long before = System.currentTimeMillis();
        catalog.update(1L, entryState, 1);
        long after = System.currentTimeMillis();

        assertThat(catalog.get(1L).getLastActiveTime())
            .as("entry state %d must record when the segment was touched", entryState)
            .isBetween(before, after);
    }
}
