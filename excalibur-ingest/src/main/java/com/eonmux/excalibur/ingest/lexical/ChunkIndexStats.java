package com.eonmux.excalibur.ingest.lexical;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What a lexical index will say about itself.
 *
 * <h2>Why an operator needs this</h2>
 * <p>The index makes two promises that nothing else in the node can be asked about. One is that an
 * erased chunk leaves disk within a bounded time; the other is that the number of segments a query
 * has to visit stays bounded. Both are kept by a compaction that runs at the end of an ingestion
 * or reindex run, so a node that has stopped ingesting is a node where neither promise is being
 * advanced - and from the outside that is indistinguishable from one where nothing is owed.</p>
 *
 * <p>{@link #oldestErasureRequest()} is the field that separates them. Present and old means the
 * node accepted an erasure it has not yet discharged; absent means it owes nothing.</p>
 *
 * @param engine               Which implementation is answering
 * @param indexed              Chunks this instance has been handed since it was opened
 * @param segments             Segments a query has to visit
 * @param liveDocuments        Chunks a query can currently match
 * @param erasedDocuments      Chunks hidden from queries but still on disk
 * @param erasureRequests      Erasure requests accepted and not yet discharged
 * @param oldestErasureRequest When the oldest of them was accepted, if any
 * @param compactionDue        Whether the index's policy would compact if asked right now
 * @param unreadableChunksDropped Chunks a compaction dropped since the index opened because
 *                             their stored text is permanently unreadable; each stopped matching
 *                             any query and needs restoring and reindexing. Always zero for an
 *                             index that keeps its own copy of the text
 */
public record ChunkIndexStats(String engine,
                              long indexed,
                              int segments,
                              long liveDocuments,
                              long erasedDocuments,
                              int erasureRequests,
                              Optional<Instant> oldestErasureRequest,
                              boolean compactionDue,
                              long unreadableChunksDropped) {

    public ChunkIndexStats {
        Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(oldestErasureRequest, "oldestErasureRequest must not be null");
        if (indexed < 0 || segments < 0 || liveDocuments < 0 || erasedDocuments < 0
                || erasureRequests < 0 || unreadableChunksDropped < 0) {
            throw new IllegalArgumentException("Counts must not be negative: " + engine);
        }
    }

    /**
     * What an index that has dropped nothing for unreadable text says about itself.
     *
     * @param engine               Which implementation is answering
     * @param indexed              Chunks this instance has been handed since it was opened
     * @param segments             Segments a query has to visit
     * @param liveDocuments        Chunks a query can currently match
     * @param erasedDocuments      Chunks hidden from queries but still on disk
     * @param erasureRequests      Erasure requests accepted and not yet discharged
     * @param oldestErasureRequest When the oldest of them was accepted, if any
     * @param compactionDue        Whether the index's policy would compact if asked right now
     */
    public ChunkIndexStats(String engine, long indexed, int segments, long liveDocuments,
                           long erasedDocuments, int erasureRequests,
                           Optional<Instant> oldestErasureRequest, boolean compactionDue) {
        this(engine, indexed, segments, liveDocuments, erasedDocuments, erasureRequests,
            oldestErasureRequest, compactionDue, 0L);
    }

    /**
     * The share of the index that is hidden but still occupying disk.
     *
     * <p>Zero when there is nothing indexed, rather than undefined: an empty index is not
     * proportionally anything, and a caller charting this wants a number.</p>
     */
    public double erasedRatio() {
        long total = liveDocuments + erasedDocuments;
        return total == 0 ? 0.0 : erasedDocuments / (double) total;
    }
}
