package io.excalibur.store;

/**
 * Whether a segment's index has caught up with the data written to it.
 *
 * <p>A segment is created in {@link LifecycleState#INDEXING} and its write log is appended to
 * immediately, while the HNSW graph and the pre-filter absorb that log on a background sweep.
 * Until the sweep reaches the head of the log the segment holds entries no index has seen, which
 * is exactly what {@link LifecycleState#isPartiallyIndexed()} reports and what a query asking to
 * be spared partial answers is asking to avoid.</p>
 *
 * <p>This is the same pair of numbers the query path already reasons about per request, kept
 * here as a predicate over longs so the lifecycle decision can be tested without a segment, a
 * graph or a write log.</p>
 */
public final class IndexingProgress {

    private IndexingProgress() {
    }

    /**
     * Decides whether a segment's index has absorbed everything written to the segment.
     *
     * @param entryCount       Entries the segment holds
     * @param integratedOffset The write log offset the index has absorbed up to, or negative
     *                         if no checkpoint could be read
     * @param writeLogHead     The write log's current head, or negative if it could not be read
     * @return true if the index speaks for the whole segment
     */
    public static boolean isComplete(int entryCount, long integratedOffset, long writeLogHead) {
        if (entryCount <= 0) {
            return false;
        }
        if (writeLogHead < 0) {
            // Without a head there is nothing to have caught up to. A checkpoint compared
            // against an unreadable head would otherwise read as complete.
            return false;
        }
        return integratedOffset >= writeLogHead;
    }
}
