package excalibur.shard;

import excalibur.filter.CuckooFilter;
import io.excalibur.store.CompactionListener;
import io.excalibur.store.CompactionOutcome;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The half of a compaction the storage engine cannot see.
 *
 * <p>{@code excalibur-store} rewrites the data file and installs it at the canonical path;
 * everything attached to the segment is invisible to it. The HNSW graph, its integration
 * checkpoint, the term frequency sketch and the LSH pre-filter all describe entries that
 * survived the rewrite unchanged, and three workers hold registrations keyed by the
 * {@link Segment} instance that is about to be retired. Moving all of that across, in an order
 * that leaves nothing pointing at a closed mapping, is this class.</p>
 *
 * <p>Two things make the ordering unforgiving, and both are why this is one object rather than
 * a handful of steps in the orchestrator:</p>
 * <ul>
 *   <li>The old segment is closed by the store as soon as the listener returns, and closing it
 *       closes whatever is still attached to it - so an index carried by reference alone is
 *       handed to the replacement already unmapped.</li>
 *   <li>The returned {@link CompactionOutcome} is what the store retires a segment on. A
 *       refusal reported as an installation closes a segment the directory is still routing
 *       to, and every read and write to it fails for the life of the process.</li>
 * </ul>
 *
 * <p>Every failure path therefore has to put back exactly what it quiesced and report what the
 * registry actually holds now.</p>
 */
final class CompactionSwap implements CompactionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompactionSwap.class);

    private final SegmentCatalog catalog;
    private final StorageEngine engine;
    private final ShardWorkers workers;
    private final SegmentRegistry registry;

    /**
     * Creates the swap handler for one shard.
     *
     * @param catalog  The catalog whose compaction transition is completed at the end
     * @param engine   The storage engine holding the directory the swap is applied to
     * @param workers  The workers whose registrations are keyed by segment identity
     * @param registry This shard's segment registrations
     */
    CompactionSwap(SegmentCatalog catalog, StorageEngine engine, ShardWorkers workers,
                   SegmentRegistry registry) {
        this.catalog = catalog;
        this.engine = engine;
        this.workers = workers;
        this.registry = registry;
    }

    /**
     * Called when compaction completes for a segment.
     *
     * <p>The store's compaction worker owns the file swap: by the time this runs the
     * compacted file already sits at the segment's canonical {@code segment_N.dat} name
     * and {@code newSegment} points at it. This method owns everything the store cannot
     * see - the index structures attached to the segment and the worker registrations
     * that are keyed by segment identity.</p>
     *
     * <ol>
     *   <li>Carries the HNSW graph, its tail tracker and the term frequency sketch onto
     *       the replacement</li>
     *   <li>Swaps the StorageEngine's registry entry</li>
     *   <li>Re-points every worker that held the old {@link Segment} instance</li>
     *   <li>Completes the catalog's compaction transition</li>
     * </ol>
     *
     * <h3>Thread Safety</h3>
     * <p>This runs on the compaction worker's thread while the old segment is still open
     * and its write lock is held by that thread. Nothing here may close the old segment,
     * take its write lock, or block: every call below is a lock-free registry update or
     * touches only the replacement segment.</p>
     *
     * @param oldSegment The segment that was compacted (still open, write lock held)
     * @param newSegment The compacted replacement, already installed at the canonical path
     * @return Which of the two segments the worker may retire
     */
    @Override
    public CompactionOutcome onCompactionComplete(Segment oldSegment, Segment newSegment) {
        try {
            return applyCompactionSwap(oldSegment, newSegment);
        } catch (RuntimeException e) {
            // The swap did not happen: everything quiesced on the way to it has been put back,
            // and the directory still routes to the old segment. Refusing is what keeps that
            // segment open - reporting the swap as installed would retire a segment the
            // directory is still handing reads and writes to.
            LOGGER.error("Compaction bookkeeping failed for {}: {}. The old segment stays in "
                + "service and the replacement is discarded; the compacted file is installed at "
                + "the canonical name and the next sweep reconciles the registry.",
                newSegment.path(), e.getMessage(), e);
            return CompactionOutcome.REFUSED;
        }
    }

    /**
     * Performs the registry and worker half of a completed compaction.
     *
     * @param oldSegment The segment that was compacted
     * @param newSegment The compacted replacement
     * @return Which of the two segments the worker may retire
     */
    private CompactionOutcome applyCompactionSwap(Segment oldSegment, Segment newSegment) {
        // The directory decides, because the directory is what the swap is validated against:
        // replaceSegment compares the segment it holds at this index with the one being replaced.
        // Asking it to swap at an index this orchestrator's own map happens to remember - a map
        // that drifts when a segment moves between indices - names an index holding somebody
        // else, and the swap is refused. The compaction worker retires the old segment on the
        // strength of the outcome returned here, so a refusal reported as an installation
        // retires a segment the directory is still routing to, and every read and write to it
        // fails for the life of the process.
        long directoryId = registry.directoryIndexOf(oldSegment);
        if (directoryId < 0) {
            return abandonSwap(oldSegment);
        }

        CompactionSwapContext context = swapContext(oldSegment, directoryId);
        LOGGER.info("Compaction complete for segment {}: {} -> {}",
            context.segmentId(), oldSegment.path(), newSegment.path());

        if (!installReplacement(oldSegment, newSegment, context)) {
            LOGGER.error("Compacted segment {} could not be installed in the storage registry; "
                + "leaving the existing registration in place", context.segmentId());
            return CompactionOutcome.REFUSED;
        }

        completeSwapBookkeeping(oldSegment, newSegment, context);
        return CompactionOutcome.INSTALLED;
    }

    /**
     * Retires a compacted segment the storage directory no longer holds.
     *
     * <p>Whoever won the race for it - a split publishing its children, or a reconciliation
     * sweep - has already routed its keys elsewhere, so there is no index to swap at and
     * nothing reads either segment. Both are retired: the registrations that outlived the
     * directory entry are dropped here, and the worker closes the two segments rather than
     * leaving their mappings and write log descriptors open with nothing pointing at them.</p>
     *
     * @param oldSegment The compacted segment, no longer in the directory
     * @return Always {@link CompactionOutcome#ABANDONED}
     */
    private CompactionOutcome abandonSwap(Segment oldSegment) {
        LOGGER.warn("Compacted segment {} is no longer in the storage directory; retiring it "
            + "along with its replacement", oldSegment.path());
        workers.hnsw().unregisterSegment(oldSegment);
        workers.unregisterFromCompaction(oldSegment);
        return CompactionOutcome.ABANDONED;
    }

    /**
     * Gathers what a compacted segment's registrations hold, before any of it is moved.
     *
     * @param oldSegment  The segment that was compacted
     * @param directoryId The directory index the storage engine holds it at
     * @return The index structures and identifiers the swap has to carry over
     */
    private CompactionSwapContext swapContext(Segment oldSegment, long directoryId) {
        long registeredId = registry.registeredIdOf(oldSegment);
        if (registeredId >= 0 && registeredId != directoryId) {
            LOGGER.warn("Compacted segment {} is registered here as {} but held by the directory "
                + "as {}; swapping at the directory's index", oldSegment.path(), registeredId,
                directoryId);
        }

        SegmentIndexInfo oldInfo = registry.get(registeredId >= 0 ? registeredId : directoryId);
        HnswGraph graph = oldInfo != null && oldInfo.graph() != null
            ? oldInfo.graph() : oldSegment.getHnswGraph();
        TailOffsetTracker tracker = oldInfo != null && oldInfo.tracker() != null
            ? oldInfo.tracker() : oldSegment.getTailOffsetTracker();
        CuckooFilter filter = oldInfo != null ? oldInfo.filter() : null;
        return new CompactionSwapContext(directoryId, registeredId, oldInfo, graph, tracker, filter);
    }

    /**
     * Moves the storage directory from the compacted segment to its replacement.
     *
     * @param oldSegment The segment that was compacted
     * @param newSegment The compacted replacement
     * @param context    What the old segment's registrations hold
     * @return true if the directory now routes to the replacement
     */
    private boolean installReplacement(Segment oldSegment, Segment newSegment,
                                       CompactionSwapContext context) {
        // Quiesce the old registration before touching anything it shares. The integration
        // worker holds this very tracker and the old segment's still-populated write log, and it
        // takes no lock: a cycle that starts after the rebase re-reads the old log from zero and
        // inserts every vector a second time, and a batch already in flight completes and pushes
        // the offset back up to one measured against a log the replacement does not have.
        workers.hnsw().unregisterSegment(oldSegment);
        workers.unregisterFromCompaction(oldSegment);

        boolean installed = false;
        try {
            carryIndexAttachments(oldSegment, newSegment, context.graph(), context.tracker());
            installed = engine.replaceSegment(context.segmentId(), oldSegment, newSegment);
            return installed;
        } finally {
            if (!installed) {
                // Put back what was quiesced, so a failed swap leaves the old segment served
                // and indexed exactly as it was. The attachments come back first: carrying them
                // over moved ownership to a replacement that is about to be discarded and closed,
                // and closing it releases the mappings of whatever is still attached to it.
                returnIndexAttachments(oldSegment, newSegment);
                if (context.graph() != null && context.tracker() != null) {
                    workers.hnsw().registerSegment(oldSegment, context.graph(), context.tracker());
                }
                workers.registerForCompaction(oldSegment);
            }
        }
    }

    /**
     * Finishes the bookkeeping behind a swap the storage directory has already taken.
     *
     * <p>A failure here is logged rather than raised. The directory routes to the replacement
     * by the time this runs, which makes the compacted segment unreachable whatever else goes
     * wrong; reporting a refusal now would keep that segment open for the life of the process
     * with nothing to serve, and the reconciliation sweep repairs the registrations that did
     * not complete.</p>
     *
     * @param oldSegment The segment that was compacted
     * @param newSegment The compacted replacement, now serving
     * @param context    What the old segment's registrations held
     */
    private void completeSwapBookkeeping(Segment oldSegment, Segment newSegment,
                                         CompactionSwapContext context) {
        long segmentId = context.segmentId();
        try {
            repointWorkers(segmentId, oldSegment, newSegment,
                context.graph(), context.filter(), context.tracker());

            if (context.hasDriftedRegistration()) {
                // The drifted entry names the segment the worker is about to close. Left in
                // place it would answer every later lookup of that index with a closed segment,
                // and the reconciliation sweep would keep finding an index it believes is
                // already registered.
                registry.removeIfPresent(context.registeredId(), context.oldInfo());
                workers.warming().unregisterSegment(context.registeredId());
            }

            catalog.completeCompaction(segmentId);

            LOGGER.info("Compaction swap complete for segment {}: {} entries, registry updated",
                segmentId, newSegment.entryCount());
        } catch (RuntimeException e) {
            LOGGER.error("Compaction of segment {} is installed but its bookkeeping did not "
                + "complete: {}. The replacement is serving; the next reconciliation sweep "
                + "repairs the registrations.", segmentId, e.getMessage(), e);
        }
    }

    /**
     * What a compacted segment's registrations held, gathered before the swap disturbs them.
     *
     * @param segmentId    The directory index the swap is applied at
     * @param registeredId The index this orchestrator has the segment under, or -1
     * @param oldInfo      The old registration, or null if there is none
     * @param graph        The HNSW graph to carry, or null
     * @param tracker      The tail offset tracker to carry, or null
     * @param filter       The Cuckoo filter to carry, or null
     */
    private record CompactionSwapContext(long segmentId, long registeredId,
                                         SegmentIndexInfo oldInfo, HnswGraph graph,
                                         TailOffsetTracker tracker, CuckooFilter filter) {

        /**
         * Whether this orchestrator holds the segment at an index the directory disagrees with.
         *
         * @return true if a registration under a different index has to be dropped
         */
        boolean hasDriftedRegistration() {
            return registeredId >= 0 && registeredId != segmentId && oldInfo != null;
        }
    }

    /**
     * Moves the owned index structures back off a replacement that was not installed.
     *
     * <p>The mirror of {@link #carryIndexAttachments}, for the swap that did not happen. Only the
     * two structures a segment closes are moved: the tail offset tracker and the term frequency
     * sketch were attached to the replacement without being taken from the original, which still
     * has them. Leaving the graph and the pre-filter on the replacement would hand them to
     * {@code discardCompacted}, which closes it - releasing the mappings under the original
     * segment, which is about to go on serving queries.</p>
     *
     * @param oldSegment The segment that stays in service
     * @param newSegment The replacement that will be discarded
     */
    private void returnIndexAttachments(Segment oldSegment, Segment newSegment) {
        if (newSegment == null || oldSegment.isClosed()) {
            return;
        }
        io.excalibur.vector.hnsw.HnswGraph graph = newSegment.detachHnswGraph();
        if (graph != null) {
            oldSegment.attachHnswGraph(graph);
        }
        excalibur.filter.lsh.LshIndex prefilter = newSegment.detachLshIndex();
        if (prefilter != null) {
            oldSegment.attachLshIndex(prefilter);
        }
    }

    /**
     * Moves the index structures a segment carries onto its compacted replacement.
     *
     * <p>Compaction rewrites the segment's data only. The HNSW graph, its tail offset
     * tracker and the term frequency sketch describe exactly the same entries and belong
     * to the replacement - the query path reads them off the segment
     * ({@code segment.getHnswGraph()}), so a replacement without them drops the whole
     * segment out of vector search with no error.</p>
     *
     * <p>The LSH index is deliberately <strong>not</strong> carried: it is owned by the
     * old segment, whose {@code close()} closes it as soon as this listener returns, and
     * the ingestion pipeline re-opens it from its persisted directory - named after the
     * unchanged segment path - on the next write.</p>
     *
     * @param oldSegment The compacted segment
     * @param newSegment The replacement
     * @param graph      The HNSW graph to carry, or null if the segment has none
     * @param tracker    The tail offset tracker to carry, or null
     */
    private void carryIndexAttachments(Segment oldSegment, Segment newSegment,
                                       HnswGraph graph, TailOffsetTracker tracker) {
        if (graph != null) {
            // Take ownership away from the old segment before attaching. The store closes the old
            // segment as soon as this listener returns, and Segment.close() now closes whatever
            // graph is still attached — which would release the mappings out from under the
            // replacement that is about to serve queries from the same graph.
            oldSegment.detachHnswGraph();
            newSegment.attachHnswGraph(graph);
        }
        if (tracker != null) {
            newSegment.attachTailOffsetTracker(tracker);
            // Entries between the checkpoint and the old log's head were copied into the
            // replacement's data file and were never indexed: the integration worker reads only
            // from the write log, and compaction has just discarded the one they were in.
            // Rebasing alone would leave them retrievable by key and absent from vector search
            // for good. Under sustained ingest the worker is always behind, so this is the
            // ordinary case, not an unlucky one.
            if (graph != null && hasUnintegratedBacklog(oldSegment, tracker)) {
                LOGGER.info("Compaction of {} left {} bytes of write log unintegrated; draining "
                    + "them before the log is discarded",
                    oldSegment.path(), oldSegment.getWriteLogHead()
                        - tracker.getLastIntegratedOffset());
                // The old log is unlinked but still mapped by the WriteLog object the old
                // segment holds, so its entries are readable right up until that segment closes.
                workers.hnsw().drainPendingIntegration(oldSegment, graph, tracker);
            }
            rebaseCheckpointIfStale(newSegment, tracker);
        }

        excalibur.filter.sketch.CountMinSketch sketch = oldSegment.getTermFrequencySketch();
        if (sketch != null) {
            newSegment.attachTermFrequencySketch(sketch);
        }

        // The pre-filter is keyed by the node ids of the graph carried over just above, and
        // compaction does not renumber them, so it describes the replacement exactly as well as
        // it described the original. Detach it for the same reason the graph is detached: the old
        // segment closes as soon as this listener returns, and Segment.close() closes whatever is
        // still attached - one mapped file per hash table, released underneath a live query path.
        // Left behind instead, a segment loses its pre-filter permanently on its first compaction
        // and falls back to a full graph search for good.
        excalibur.filter.lsh.LshIndex prefilter = oldSegment.detachLshIndex();
        if (prefilter != null) {
            newSegment.attachLshIndex(prefilter);
        }
    }

    /**
     * Whether a segment's write log holds entries the graph never saw.
     *
     * @param oldSegment The segment being replaced, whose log is still readable
     * @param tracker    Its integration checkpoint
     * @return true when the checkpoint is behind the log's head
     */
    private boolean hasUnintegratedBacklog(Segment oldSegment, TailOffsetTracker tracker) {
        return oldSegment.getWriteLogHead() > tracker.getLastIntegratedOffset();
    }

    /**
     * Reports an integration checkpoint that no longer indexes into the segment's log.
     *
     * <p>The compacted segment starts from an empty write log, while the HNSW integration
     * checkpoint is a byte offset into the log that compaction discarded. Left as it is, every
     * entry appended after the compaction sits below the checkpoint and is never integrated, so
     * the segment silently accepts vectors that no query can find.</p>
     *
     * @param newSegment The compacted replacement
     * @param tracker    The carried tail offset tracker
     */
    private void rebaseCheckpointIfStale(Segment newSegment, TailOffsetTracker tracker) {
        IntegrationCheckpoint.rebaseIfStale(newSegment, tracker, "compaction");
    }

    /**
     * Re-points every worker registration from the old segment instance to its replacement.
     *
     * <p>The HNSW integration worker and the store's compaction worker key registrations
     * by {@link Segment} identity, so a swap that updates only the maps leaves both of
     * them polling a segment whose write log no longer receives appends, and leaves the
     * replacement registered with nobody.</p>
     *
     * @param segmentId  The segment ID
     * @param oldSegment The compacted segment
     * @param newSegment The replacement
     * @param graph      The carried HNSW graph, or null
     * @param filter     The carried Cuckoo filter, or null
     * @param tracker    The carried tail offset tracker, or null
     */
    private void repointWorkers(long segmentId, Segment oldSegment, Segment newSegment,
                                HnswGraph graph, CuckooFilter filter, TailOffsetTracker tracker) {
        workers.hnsw().unregisterSegment(oldSegment);
        workers.unregisterFromCompaction(oldSegment);

        if (graph != null && tracker != null) {
            workers.hnsw().registerSegment(newSegment, graph, tracker);
            workers.warming().unregisterSegment(segmentId);
            workers.warming().registerSegment(segmentId, newSegment, graph, tracker);
            // The graph itself is unchanged, so its HNSW compaction registration stands.
        }

        workers.registerForCompaction(newSegment);

        registry.put(segmentId, new SegmentIndexInfo(newSegment, graph, filter, tracker));
    }
}
