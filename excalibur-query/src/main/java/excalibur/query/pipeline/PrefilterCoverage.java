package excalibur.query.pipeline;

/**
 * Decides how far a segment's vector pre-filter may be believed.
 *
 * <p>Stage 2 of the query pipeline asks each segment's pre-filter which entries could match, and
 * both of the answers it can give are only actionable if the index has seen what it is speaking
 * about. A non-empty answer narrows stage 3's answer to the nodes it names; an empty one rules the
 * segment out altogether. An index that has missed entries is wrong in the same direction either
 * way - it drops matches, silently and with no error anywhere - and differs only in how many.</p>
 *
 * <h2>What a segment is searched for</h2>
 * <p>Stage 3 covers a segment in two parts: an HNSW walk over the nodes the integration worker has
 * absorbed, and a brute-force scan of the write log beyond the integration checkpoint. The
 * pre-filter is written one entry per node, as those nodes are assigned, so it can speak for the
 * first part and never for the second - the tail is, by construction, everything no index has seen
 * yet.</p>
 *
 * <p>That is why the two answers need different amounts of evidence. Narrowing only touches the
 * graph search, and the tail is scanned whatever the pre-filter says, so it needs the index to
 * cover the graph. Excluding the segment skips the tail scan too, so it needs the log to be fully
 * integrated as well - the condition that is easy to forget, because a segment under active ingest
 * never satisfies it and its unindexed entries are the most recently written ones.</p>
 */
final class PrefilterCoverage {

    private PrefilterCoverage() {
    }

    /**
     * How much of a segment a pre-filter's answer speaks for.
     */
    enum Standing {

        /**
         * The index has missed nodes the graph holds, so its answer speaks for nothing: the nodes
         * it missed are exactly the ones it cannot mention.
         */
        UNKNOWN,

        /**
         * The index has seen every node in the graph, but the graph is behind the write log. Its
         * answer narrows the graph search; the unindexed tail is still scanned.
         */
        COVERS_GRAPH,

        /**
         * The index has seen everything a search of this segment would reach, so an empty answer
         * means the segment holds no match rather than that nothing is known about it.
         */
        COVERS_SEGMENT
    }

    /**
     * Returns how much of a segment its pre-filter's answer speaks for.
     *
     * <p>Counts are compared rather than contents because the pre-filter mirrors the graph
     * exactly: the integration worker records one entry per node it inserts, and neither deletion
     * nor compaction removes entries from the index. An index holding fewer entries than the graph
     * has nodes has missed some of them, whatever the reason.</p>
     *
     * @param indexedEntries       Entries the pre-filter holds
     * @param graphNodes           Nodes in the segment's HNSW graph
     * @param lastIntegratedOffset Write log offset the graph has absorbed up to
     * @param writeLogHead         Write log offset entries are being appended at
     * @return The standing of the pre-filter's answer for this segment
     */
    static Standing standing(long indexedEntries, long graphNodes,
                             long lastIntegratedOffset, long writeLogHead) {
        if (indexedEntries <= 0) {
            // Nothing has been indexed, so the index has no basis for an opinion. This is also
            // the state of a segment whose pre-filter was created moments ago, which is every
            // segment for as long as the first integration cycle takes.
            return Standing.UNKNOWN;
        }
        if (indexedEntries < graphNodes) {
            return Standing.UNKNOWN;
        }
        return lastIntegratedOffset >= writeLogHead ? Standing.COVERS_SEGMENT : Standing.COVERS_GRAPH;
    }
}
