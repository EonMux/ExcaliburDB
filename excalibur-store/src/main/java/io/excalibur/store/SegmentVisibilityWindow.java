package io.excalibur.store;

/**
 * The span of visibility epochs over which one segment answers queries.
 *
 * <p>A query fixes an epoch when it starts and reads the corpus as it was then, so a
 * segment cannot simply appear and disappear: it has to be invisible to queries that began
 * before it existed, and to stay visible to queries that began before it was retired. Two
 * epochs bound that span - the one at which the segment was registered, and the one at
 * which it was tombstoned - and the whole of the rule is the comparison between them and
 * the query's own epoch.</p>
 *
 * <p>Both default to -1, meaning "not set". For the creation epoch that means a segment
 * registered before anything was tracking epochs, which must be visible to everything
 * rather than to nothing; for the tombstone epoch it means the segment is still live. A
 * boundary encoded as a sentinel rather than as an absence is the reason both comparisons
 * check for it before comparing, and the reason 0 is a legitimate epoch rather than a
 * stand-in for unset.</p>
 *
 * <p>Retirement waits on this. A tombstoned segment's file cannot be freed while a query
 * that started before the tombstone might still read it, so reclamation asks whether every
 * in-flight query has moved past {@link #tombstoneEpoch()}.</p>
 *
 * <p><strong>Thread safety:</strong> both epochs are volatile. They are written once each,
 * by whichever thread registered or tombstoned the segment, and read lock-free by every
 * query that evaluates the segment.</p>
 */
final class SegmentVisibilityWindow {

    /**
     * Epoch at which the segment was created, or -1 when it has not been set.
     *
     * <p>Queries whose epoch precedes it cannot see the segment.</p>
     */
    private volatile long creationEpoch = -1;

    /**
     * Epoch at which the segment was tombstoned, or -1 when it has not been.
     *
     * <p>Queries that started before it can still see the segment; queries that started at
     * or after it cannot.</p>
     *
     * <p>-1 also stands for "tombstoned, epoch not yet recorded": the lifecycle state and
     * this field are written in that order and not under one lock. See
     * {@link #visibleAt(long)}.</p>
     */
    private volatile long tombstoneEpoch = -1;

    /**
     * Returns the epoch at which the segment was created.
     *
     * @return The creation epoch, or -1 if not set
     */
    long creationEpoch() {
        return creationEpoch;
    }

    /**
     * Records the epoch at which the segment was created.
     *
     * @param epoch The visibility epoch at which the segment was created
     */
    void creationEpoch(long epoch) {
        this.creationEpoch = epoch;
    }

    /**
     * Returns the epoch at which the segment was tombstoned.
     *
     * @return The tombstone epoch, or -1 if the segment has not been tombstoned
     */
    long tombstoneEpoch() {
        return tombstoneEpoch;
    }

    /**
     * Records the epoch at which the segment was tombstoned.
     *
     * @param epoch The visibility epoch at which the segment was tombstoned
     */
    void tombstoneEpoch(long epoch) {
        this.tombstoneEpoch = epoch;
    }

    /**
     * Whether a query that started at the given epoch may read this segment.
     *
     * @param queryEpoch The epoch at which a query started
     * @return true if the segment is within the query's window
     */
    boolean visibleAt(long queryEpoch) {
        // If creation epoch is set and query epoch is before creation, not visible
        if (creationEpoch >= 0 && queryEpoch < creationEpoch) {
            return false;
        }
        // The sentinel covers two cases, and both are visible. Either the segment has not
        // been tombstoned, or it has been tombstoned by a thread that has already written
        // TOMBSTONED and not yet reached the epoch store - see
        // SegmentCatalog.SegmentMetadata#transitionToTombstoned(long). Reading the sentinel as
        // visible is the safe half of that ambiguity: the segment was visible an instant
        // earlier, so it can only delay a tombstone, never retire a segment out from under a
        // query that started before it. A default of 0 would invert this, reading as
        // "tombstoned at the first epoch" and hiding the segment from everything.
        if (tombstoneEpoch < 0) {
            return true;
        }
        // If tombstoned after query started, visible to that query
        return tombstoneEpoch > queryEpoch;
    }
}
