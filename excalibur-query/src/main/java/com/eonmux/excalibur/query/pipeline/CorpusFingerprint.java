package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.cache.CorpusVersion;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Reads, cheaply, everything that decides what a vector query returns.
 *
 * <h2>The rule this file follows</h2>
 * <p>Every quantity below is read through the <em>same accessor the pipeline itself reads it
 * through</em>. That is not a stylistic preference, it is the whole correctness argument: a
 * fingerprint taken from a different source than the query reads can be stable while the
 * query's own inputs move, and the cache then serves a stale answer. Two traps in particular
 * are avoided here, because both look like the obvious choice:</p>
 * <ul>
 *   <li>{@code SegmentCatalog.SegmentMetadata.getWriteLogHead()} and
 *       {@code getLastIntegratedOffset()} are never written by anything in this system - no
 *       production code calls their setters - so they read zero for ever. The live values are
 *       {@link Segment#getWriteLogHead()} and {@link HnswGraph#getLastIntegratedOffset()},
 *       which is exactly the pair {@code SegmentQueryTask.captureTailRange()} uses to bound
 *       the brute-force tail scan.</li>
 *   <li>{@code SegmentMetadata.getCreationEpoch()} is likewise unset outside catalog
 *       recovery. The creation and tombstone epochs that decide visibility live in the
 *       {@link VisibilityTracker}'s own maps, which is where
 *       {@code VisibilityTracker.visibleSegments} reads them.</li>
 * </ul>
 *
 * <h2>What each component catches</h2>
 * <table border="1">
 *   <caption>Mutation paths and the component that records each</caption>
 *   <tr><td>Ingest into an existing segment</td>
 *       <td>{@code Segment.currentEpoch()}, {@code getWriteLogHead()}, entry counts</td></tr>
 *   <tr><td>Erasure and reindex deletions</td>
 *       <td>the same, plus the catalog's tombstone count</td></tr>
 *   <tr><td>HNSW integration, which advances no epoch at all</td>
 *       <td>{@code HnswGraph.getLastIntegratedOffset()} and {@code nodeCount()}</td></tr>
 *   <tr><td>Product quantizer training, which changes how the graph is searched</td>
 *       <td>{@code HnswGraph.encodedVectorCount()}</td></tr>
 *   <tr><td>A vector tombstoned directly in a graph, which touches no segment, no catalog
 *       entry and no epoch</td>
 *       <td>{@code HnswGraph.tombstoneGeneration()}</td></tr>
 *   <tr><td>Segment compaction, which advances no epoch and resets the tail</td>
 *       <td>{@code Segment.segmentId()} (a fresh instance) and the last compaction time</td></tr>
 *   <tr><td>Splits, and the sweep that registers a new segment up to a second late</td>
 *       <td>the visible segment set and its size</td></tr>
 *   <tr><td>Tombstoning a segment, lifecycle transitions</td>
 *       <td>tombstone epoch and lifecycle state</td></tr>
 * </table>
 *
 * <p>The visibility epoch is <em>not</em> folded in. It is read here, because
 * {@code visibleSegments} is asked as of an epoch, but it does not reach the digest. It used
 * to, as the backstop for the last row of that table: a graph tombstone's only exact witness
 * was {@code HnswGraph.tombstoneCount()}, an O(number of nodes) scan no query can pay for,
 * so the epoch stood in for it and bounded the staleness to one tick. That cost more than it
 * bought. {@code EpochTickScheduler.performTick()} advances the epoch every
 * {@code excalibur.visibility.epoch-tick-ms} whether or not a byte was written, so an epoch
 * in the digest expires every entry within one tick - a cache that cannot outlive its own
 * clock. {@code HnswGraph.tombstoneGeneration()} is the exact O(1) witness the backstop was
 * approximating, so the approximation is gone and every row above is covered directly.</p>
 *
 * <p>One row is covered by a quantity that could in principle return to where it started:
 * retraining a product quantizer empties the code map and re-encodes into it, so
 * {@code encodedVectorCount()} ends where it began while the codebook - and therefore the
 * ranking - has changed. The epoch did not really cover that either, only bounding it to a
 * tick. It is not reachable: {@code WarmingPhaseWorker} trains once per segment, during
 * WARMING, and a segment is not visible to a query until it reaches ACTIVE, a transition the
 * lifecycle state above records. Anything that makes retraining reachable on a live segment
 * needs a generation counter on the quantizer, in the shape of the one on tombstones.</p>
 *
 * <h2>Cost</h2>
 * <p>One set of visible segment ids, then a fixed number of field reads per segment. No
 * locks are taken and no file is touched. Paid once per query, and only on a node where the
 * cache is enabled.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: every collaborator is read through a thread-safe accessor, and nothing here
 * holds state.</p>
 */
final class CorpusFingerprint {

    private static final Logger LOGGER = LoggerFactory.getLogger(CorpusFingerprint.class);

    /** An arbitrary odd seed, so that an empty corpus does not digest to zero. */
    private static final long SEED = 0x9E3779B97F4A7C15L;

    /** Folded in place of a segment the engine no longer holds, or that has been closed. */
    private static final long ABSENT_SEGMENT = 0xA55E_47ABL;

    /** Folded in place of a graph a segment is serving without. */
    private static final long ABSENT_GRAPH = 0x6_4A5EL;

    private final StorageEngine storageEngine;
    private final VisibilityTracker visibilityTracker;
    private final QueryPipeline.HnswGraphProvider graphProvider;

    CorpusFingerprint(StorageEngine storageEngine,
                      VisibilityTracker visibilityTracker,
                      QueryPipeline.HnswGraphProvider graphProvider) {
        this.storageEngine = storageEngine;
        this.visibilityTracker = visibilityTracker;
        this.graphProvider = graphProvider;
    }

    /**
     * Fingerprints the corpus as a query starting now would read it.
     *
     * <p>Any failure yields {@link CorpusVersion#UNREADABLE} rather than a partial digest.
     * A digest missing one segment is indistinguishable from a digest of a corpus without it,
     * which would let the cache serve an answer across the very change it failed to read.</p>
     *
     * @return The fingerprint, or {@link CorpusVersion#UNREADABLE} if it could not be taken
     */
    CorpusVersion current() {
        try {
            long epoch = visibilityTracker.currentEpoch();
            Set<Long> visible = visibilityTracker.visibleSegments(epoch);
            SegmentCatalog catalog = storageEngine.getCatalog();

            long digest = SEED;
            for (Long segmentId : visible) {
                // Combined by exclusive-or so the digest does not depend on the iteration
                // order of a hash set, which is not stable across calls. Every term mixes in
                // the segment id, so no two segments can produce the same term and cancel.
                digest ^= mix(segmentId, catalog);
            }
            // The epoch bounded which segments were asked for; it is deliberately not part
            // of the fingerprint, because a timer advances it under a corpus nobody wrote to.
            return new CorpusVersion(0L, digest, visible.size());
        } catch (RuntimeException e) {
            // Not swallowed: an unreadable corpus disables the cache for this request, and an
            // operator seeing hit rates collapse needs the reason in the log.
            LOGGER.warn("Corpus fingerprint unavailable, so no query may be answered from "
                + "cache until it can be read: {}", e.toString());
            return CorpusVersion.UNREADABLE;
        }
    }

    /**
     * Digests everything about one visible segment that a query's answer depends on.
     *
     * @param segmentId The directory index the engine routes by, which is the id the catalog,
     *                  the visibility tracker and the graph provider are all keyed on
     * @param catalog   The catalog holding this segment's lifecycle and tombstone counts
     * @return A 64-bit term, distinct per segment
     */
    private long mix(long segmentId, SegmentCatalog catalog) {
        long digest = fold(SEED, segmentId);
        digest = fold(digest, visibilityTracker.getCreationEpoch(segmentId));
        digest = fold(digest, visibilityTracker.getTombstoneEpoch(segmentId));
        digest = foldCatalog(digest, catalog.get(segmentId));
        return foldSegment(digest, storageEngine.getSegment(segmentId), segmentId);
    }

    /**
     * Folds in what the catalog says about a segment's admission and its deletions.
     *
     * @param digest   The digest so far
     * @param metadata The catalog entry, or null for a segment the catalog has lost
     * @return The digest
     */
    private static long foldCatalog(long digest, SegmentCatalog.SegmentMetadata metadata) {
        if (metadata == null) {
            return fold(digest, ABSENT_SEGMENT);
        }
        long folded = fold(digest, metadata.getLifecycleStateValue());
        folded = fold(folded, metadata.getTombstoneCount());
        // The only observable a compaction moves: it advances no epoch, keeps the directory
        // index, and resets the very watermarks that would otherwise have recorded it.
        return fold(folded, metadata.getLastCompactionTime());
    }

    /**
     * Folds in the live state of a segment and of the graph attached to it.
     *
     * @param digest    The digest so far
     * @param segment   The segment, or null when the engine no longer holds one at this index
     * @param segmentId The directory index, for looking the graph up
     * @return The digest
     */
    private long foldSegment(long digest, Segment segment, long segmentId) {
        if (segment == null || segment.isClosed()) {
            return fold(digest, ABSENT_SEGMENT);
        }
        // The process-local instance counter, not the directory index: a compaction swaps a
        // new Segment object into the same slot, and this is what says so.
        long folded = fold(digest, segment.segmentId());
        folded = fold(folded, segment.currentEpoch());
        folded = fold(folded, segment.getWriteLogHead());
        folded = fold(folded, segment.entryCount());
        folded = fold(folded, segment.liveEntryCount());
        return foldGraph(folded, graphProvider.getGraph(segmentId));
    }

    /**
     * Folds in the vector index a segment is searched through.
     *
     * @param digest The digest so far
     * @param graph  The graph, or null for a segment serving without one
     * @return The digest
     */
    private static long foldGraph(long digest, HnswGraph graph) {
        if (graph == null) {
            return fold(digest, ABSENT_GRAPH);
        }
        long folded = fold(digest, graph.getLastIntegratedOffset());
        folded = fold(folded, graph.nodeCount());
        folded = fold(folded, graph.encodedVectorCount());
        // A tombstone set by HnswGraph.delete leaves nodeCount() and every storage-side
        // counter exactly where they were, and an erasure that failed to reach storage
        // leaves nothing else at all. This is the only term that moves for it.
        return fold(folded, graph.tombstoneGeneration());
    }

    /**
     * Mixes one value into a digest.
     *
     * <p>A 64-bit avalanche step, so that two segments differing in a single low bit of a
     * single counter produce digests that differ everywhere rather than in that bit alone -
     * which is what stops the exclusive-or above cancelling two similar segments out.</p>
     *
     * @param digest The digest so far
     * @param value  The value to mix in
     * @return The digest
     */
    private static long fold(long digest, long value) {
        long mixed = digest * 0x100000001B3L ^ value;
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        mixed *= 0xC4CEB9FE1A85EC53L;
        return mixed ^ (mixed >>> 33);
    }
}
