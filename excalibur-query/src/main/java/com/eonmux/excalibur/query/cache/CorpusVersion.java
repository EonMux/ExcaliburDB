package com.eonmux.excalibur.query.cache;

/**
 * A fingerprint of everything a query reads, at one instant.
 *
 * <h2>What it is for</h2>
 * <p>This is the evidence a cached answer is still an answer. Two queries that observe the
 * same {@code CorpusVersion} read the same segments, the same graphs, and the same stretch of
 * each segment's write log, so they compute the same ranking.</p>
 *
 * <h2>Why the visibility epoch is no part of it</h2>
 * <p>Stage 0 of the pipeline captures a visibility epoch, and the tempting reading is that an
 * answer computed at epoch N is valid for any query at epoch N. It is not, in either
 * direction, and both failures matter.</p>
 * <ul>
 *   <li>The epoch moves when nothing has changed. {@code EpochTickScheduler.performTick()}
 *       advances it every {@code excalibur.visibility.epoch-tick-ms} on a timer, whether or
 *       not a byte was written. Anything keyed on it - even partly - expires within one tick
 *       and essentially never hits.</li>
 *   <li>The epoch does not move when things have changed. It fixes which segments a query may
 *       read, not what is inside them - {@code QuerySnapshot} says exactly that in its own
 *       javadoc - and {@code SegmentQueryTask} reads the tail bounds
 *       ({@code HnswGraph.getLastIntegratedOffset()} and {@code Segment.getWriteLogHead()})
 *       live at execution time and brute-force scans between them. Graph integration and
 *       segment compaction likewise advance no epoch at all.</li>
 * </ul>
 *
 * <p>The epoch was once folded in anyway, as a backstop for the one mutation no cheap counter
 * recorded: a vector tombstoned directly in a graph by {@code HnswGraph.delete}, whose exact
 * witness {@code tombstoneCount()} costs a scan of every node. Bounding that staleness to one
 * tick also capped every entry's life at one tick, which is the first bullet above charged
 * against the whole cache. {@code HnswGraph.tombstoneGeneration()} is that mutation's exact
 * O(1) witness, and {@code CorpusFingerprint} folds it in, so the backstop has been retired.
 * {@code contentDigest} now carries the fingerprint alone, and an untouched corpus
 * fingerprints the same however long it is left alone.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable, and compared by value.</p>
 *
 * @param epoch        Always zero for a readable fingerprint. Retained so the shape of the
 *                     record does not change under callers, but a timer-driven counter is
 *                     not evidence of a mutation and is no longer digested
 * @param contentDigest A digest over every visible segment's identity, lifecycle, entry
 *                     counts, write-log head, integration watermark, graph size and graph
 *                     tombstone generation
 * @param segmentCount How many segments were visible, or a negative number for a corpus that
 *                     could not be read
 * @author ExcaliburDB
 */
public record CorpusVersion(long epoch, long contentDigest, int segmentCount) {

    /**
     * The version of a corpus that could not be read.
     *
     * <p>Never equal to a readable version, and refused by {@link QueryResultCache} on both
     * reads and writes. A fingerprint that could not be taken is not evidence that nothing
     * changed, and the safe reading of "I could not check" is "do not serve".</p>
     */
    public static final CorpusVersion UNREADABLE = new CorpusVersion(-1L, 0L, -1);

    /**
     * Whether this fingerprint is evidence of anything.
     *
     * @return true when the corpus was read successfully
     */
    public boolean readable() {
        return segmentCount >= 0;
    }

    /**
     * This fingerprint extended with a second index's own account of itself.
     *
     * <p>For a node whose answers read another index besides the vector segments - the
     * lexical one BM25 searches. That index publishes on its own schedule: its segments become
     * visible when the query epoch crosses the epoch they were published at, and erasures and
     * merges change what it returns without touching a vector segment. So a fingerprint of the
     * vector segments alone can stand still while a BM25 answer moves, and an entry that read
     * both is valid only while both are unchanged. {@code otherDigest} is that index's version,
     * read through the same state its searches read.</p>
     *
     * <p>Mixed rather than added or exclusive-ored, so that a change in one digest cannot be
     * cancelled by an equal and opposite change in the other.</p>
     *
     * @param otherDigest The other index's version
     * @return The combined fingerprint, or {@link #UNREADABLE} when this one is unreadable
     */
    public CorpusVersion alongside(long otherDigest) {
        if (!readable()) {
            return UNREADABLE;
        }
        return new CorpusVersion(epoch, mix(contentDigest, otherDigest), segmentCount);
    }

    /**
     * Mixes a second value into a digest with a 64-bit avalanche step.
     *
     * @param digest The digest so far
     * @param value  The value to mix in
     * @return The mixed digest
     */
    private static long mix(long digest, long value) {
        long mixed = digest * 0x100000001B3L ^ value;
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        mixed *= 0xC4CEB9FE1A85EC53L;
        return mixed ^ (mixed >>> 33);
    }

    @Override
    public String toString() {
        if (!readable()) {
            return "CorpusVersion{unreadable}";
        }
        return String.format("CorpusVersion{digest=%016x, segments=%d}",
            contentDigest, segmentCount);
    }
}
