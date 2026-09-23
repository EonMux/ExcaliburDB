package com.eonmux.excalibur.ingest.lexical;

import java.time.Instant;
import java.util.List;
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
 * <h2>What the index holds and cannot search</h2>
 * <p>A segment the index could not open is kept out of every query, so its chunks are neither
 * {@link #liveDocuments() live} - nothing can match them - nor gone: they are on disk, and usually
 * a compaction away from being searchable again. {@link #unreadableSegments()} names each such
 * segment with its chunk count, and {@link #unsearchableDocuments()} is their total, so a node
 * missing part of its keyword corpus says so instead of reporting a smaller one.</p>
 *
 * @param engine               Which implementation is answering
 * @param indexed              Chunks this instance has been handed since it was opened
 * @param segments             Segments a query has to visit
 * @param liveDocuments        Chunks a query can currently match
 * @param erasedDocuments      Chunks hidden from queries but still on disk
 * @param erasureRequests      Erasure requests accepted and not yet discharged
 * @param oldestErasureRequest When the oldest of them was accepted, if any
 * @param compactionDue        Whether the index would compact if asked right now: because its
 *                             policy says so, or because a segment has to be rebuilt or upgraded
 * @param unreadableChunksDropped Chunks dropped since the index opened because nothing on disk
 *                             can bring them back: a compaction found their stored text
 *                             permanently unreadable, or an owed erasure removed a segment whose
 *                             postings and key table were both damaged. Each stopped matching any
 *                             query and needs reindexing. Always zero for an index that keeps its
 *                             own copy of the text
 * @param unreadableSegments   Segments the index holds and could not open, whose chunks no query
 *                             can match; empty for a healthy index
 * @param outdatedSegments     Segments written in an older format and not yet rewritten into the
 *                             current one; they answer exactly, without the checksums that would
 *                             reveal damage to them
 */
public record ChunkIndexStats(String engine,
                              long indexed,
                              int segments,
                              long liveDocuments,
                              long erasedDocuments,
                              int erasureRequests,
                              Optional<Instant> oldestErasureRequest,
                              boolean compactionDue,
                              long unreadableChunksDropped,
                              List<UnreadableSegment> unreadableSegments,
                              int outdatedSegments) {

    /**
     * One segment the index holds and could not open.
     *
     * @param name        The segment's name in the index directory
     * @param chunks      Chunks in it that are not erased, none of which any query can match
     * @param rebuildable Whether the next compaction can rebuild it from stored text; false means
     *                    only restoring its files, or reindexing its documents, brings them back
     * @param reason      What is wrong with it
     */
    public record UnreadableSegment(String name, long chunks, boolean rebuildable,
                                    String reason) {
        public UnreadableSegment {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            if (chunks < 0) {
                throw new IllegalArgumentException("chunks must not be negative: " + name);
            }
        }
    }

    public ChunkIndexStats {
        Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(oldestErasureRequest, "oldestErasureRequest must not be null");
        unreadableSegments = List.copyOf(
            Objects.requireNonNull(unreadableSegments, "unreadableSegments must not be null"));
        if (indexed < 0 || segments < 0 || liveDocuments < 0 || erasedDocuments < 0
                || erasureRequests < 0 || unreadableChunksDropped < 0 || outdatedSegments < 0) {
            throw new IllegalArgumentException("Counts must not be negative: " + engine);
        }
    }

    /**
     * What an index with every segment readable and current says about itself.
     *
     * @param engine                  Which implementation is answering
     * @param indexed                 Chunks this instance has been handed since it was opened
     * @param segments                Segments a query has to visit
     * @param liveDocuments           Chunks a query can currently match
     * @param erasedDocuments         Chunks hidden from queries but still on disk
     * @param erasureRequests         Erasure requests accepted and not yet discharged
     * @param oldestErasureRequest    When the oldest of them was accepted, if any
     * @param compactionDue           Whether the index would compact if asked right now
     * @param unreadableChunksDropped Chunks dropped for unreadable stored text
     */
    public ChunkIndexStats(String engine, long indexed, int segments, long liveDocuments,
                           long erasedDocuments, int erasureRequests,
                           Optional<Instant> oldestErasureRequest, boolean compactionDue,
                           long unreadableChunksDropped) {
        this(engine, indexed, segments, liveDocuments, erasedDocuments, erasureRequests,
            oldestErasureRequest, compactionDue, unreadableChunksDropped, List.of(), 0);
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
     * Chunks the index holds that no query can match, because their segment could not be opened.
     *
     * @return The total over {@link #unreadableSegments()}
     */
    public long unsearchableDocuments() {
        long total = 0;
        for (UnreadableSegment segment : unreadableSegments) {
            total += segment.chunks();
        }
        return total;
    }

    /**
     * The share of the index that is hidden but still occupying disk.
     *
     * <p>Measured against every chunk on disk, the unsearchable ones included, since they occupy
     * it as much as any other. Zero when there is nothing indexed, rather than undefined: an empty
     * index is not proportionally anything, and a caller charting this wants a number.</p>
     */
    public double erasedRatio() {
        long total = liveDocuments + erasedDocuments + unsearchableDocuments();
        return total == 0 ? 0.0 : erasedDocuments / (double) total;
    }
}
