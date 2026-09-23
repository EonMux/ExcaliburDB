package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.text.TermExtractor;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.store.CorruptSegmentEntryException;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.format.SegmentEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Refills a segment's level in the membership filter from the entries the segment holds.
 *
 * <h2>Why this exists</h2>
 * <p>Ingestion - a first crawl and a reindex alike - files a chunk's terms under the segment the
 * storage engine reports its write landed in, and that is the only way terms ever reach a level. Entries also reach segments without being written there: a split
 * hands each child a share of its parent's entries, a restart reopens segments whose levels a
 * fresh process has never filled, and a compaction rewrites a segment whose level still counts
 * the entries it dropped. Stage 1 of the query pipeline searches a segment last when its level
 * is trusted and names none of the query's terms, so the first write after any of those makes
 * the level look authoritative while it misses everything the segment held before - and every
 * query for those entries puts the segment that holds them behind every other. The Count-Min term sketch has
 * the same problem and solves it the same way: it is rebuilt from the live entries.</p>
 *
 * <h2>How a rebuild stays correct under writes</h2>
 * <p>The caller marks the level stale first, synchronously, so from that moment it says nothing
 * about the segment. The rebuild then empties the level in place and reads the text of every live
 * entry under the segment's read lock - one consistent snapshot, taken after the emptying. A
 * write recorded before the level was emptied was stored before the snapshot, so the snapshot
 * holds it; a write stored after the snapshot is recorded after it too, into the emptied level
 * itself. A value deleted or overwritten after the snapshot leaves its terms behind, which only
 * costs the segment a place ahead of others it did not need; nothing the segment holds is left
 * out. Only the
 * rebuild that began after the latest mark may clear it, so a split or compaction that changes
 * the segment meanwhile leaves it stale for the rebuild scheduled on its behalf.</p>
 *
 * <h2>Why the lock covers the copy and not the tokenising</h2>
 * <p>The argument above needs the snapshot to be consistent, and nothing more: it says what the
 * level must hold, not when. So the read lock - which holds every writer to the segment off -
 * is kept only while the entries' text bytes are copied out, a memory copy bounded by the
 * segment's data region. Decoding, tokenising and inserting the terms, which cost far more per
 * byte, happen after it is released, while the level is still stale and so still says nothing about
 * the segment. The walk cannot drop and retake the lock part-way instead: a write in between
 * can move entries across the slot order, and the walk would miss them.</p>
 *
 * <h2>A damaged entry keeps the level stale</h2>
 * <p>An entry that fails verification cannot be read, so its terms cannot be filed, and a level
 * missing them would send its segment to the back of queries the entry answers. The rebuild
 * still files every entry it can read - there is no reason to throw that work away - but does
 * not vouch for the level, which stays silent until a later rebuild reads the segment whole. The
 * damage itself is reported where the entry is read and by an integrity check of the segment.</p>
 *
 * <h2>Why one thread, off the caller's</h2>
 * <p>A rebuild reads the whole segment, and its callers cannot wait for that: a compaction swap
 * runs holding the old segment's write lock, a split's bookkeeping runs after publication with
 * writes already arriving, and bootstrap would otherwise read every byte on the node before
 * serving. While it waits the filter says nothing about the segment, which costs the query only
 * the ordering the level would have given.
 * Rebuilds run one at a time, in the order they were asked for, and each resolves the segment
 * registered under the id when it starts - so two rebuilds of one id never interleave, and the
 * last one reads the segment that is actually serving.</p>
 *
 * <p>A rebuild that fails leaves the level stale, which keeps the filter silent about the segment
 * until the next event that schedules one. A segment in the catalog as tombstoned is not rebuilt: no
 * query reads it, and the maintenance worker is removing its terms.</p>
 *
 * <h2>A segment being split is not read</h2>
 * <p>The left child of a split takes over its parent's id, and the engine routes writes to it
 * from the moment the split is published - while the parent is still registered under that id,
 * until the split's bookkeeping replaces the registration. A rebuild that resolved the id in
 * that window would snapshot the frozen parent, which holds none of the writes the child has
 * taken since; completing it would vouch for a level missing their terms whenever the mark the
 * bookkeeping makes on the child's behalf never comes, because the bookkeeping failed before
 * registering it. So a segment that is splitting, or has split, is never read.</p>
 *
 * <p>What happens to the rebuild depends on which. A split still in progress may yet be given
 * up, and a split given up before it was published tells nothing that would schedule a rebuild
 * of the parent, so that rebuild is put back on the queue after {@link #SPLIT_RETRY} and runs
 * once the parent is back in service - or once the id resolves to the child. A split that has
 * completed never puts its parent back, so a rebuild that finds one is dropped with the level
 * left stale: registering the child under the id schedules the rebuild that reads it, and until
 * then - for good, if the split's bookkeeping failed before it - the filter says nothing about
 * the segment. Retrying there instead would keep a rebuild pending for as long as the parent stayed
 * registered, and {@link #awaitIdle} waiting on it. Deferred rebuilds of one id are coalesced
 * into one retry: it resolves the id afresh and begins after every mark made meanwhile, which is
 * all the later ones would have done.</p>
 *
 * <p>The id is resolved again just before the rebuild vouches for the level: a rebuild whose
 * segment has since been replaced under the id, or has begun splitting, leaves the level stale
 * for the rebuild that follows the change.</p>
 *
 * <h2>Closing</h2>
 * <p>Rebuilds still queued when the shard closes are dropped - each would read a whole segment
 * for a filter about to be discarded - but each is still counted off the queue, so a caller of
 * {@link #awaitIdle} is not left waiting on work that will never run. The rebuild already
 * running is asked to stop and awaited for a bounded time, so the segments it reads are not
 * closed under it.</p>
 */
final class TermFilterRebuilds implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TermFilterRebuilds.class);

    /**
     * How long {@link #close()} waits for the rebuild already running. It is asked to stop and
     * does so between entries, so this bounds one entry's tokenising or the lock-held copy of
     * one segment's text, not a whole rebuild.
     */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    /**
     * How long a rebuild that found its segment splitting waits before it is queued again. A
     * split holds a segment for as long as it takes to copy it into two children, and its
     * outcome is what the retry waits for; asking much more often only spins the queue.
     */
    static final Duration SPLIT_RETRY = Duration.ofMillis(250);

    private final Supplier<HierarchicalCuckooFilter> filter;
    private final LongFunction<Segment> registered;
    private final SegmentCatalog catalog;
    private final Function<String, ? extends Collection<String>> tokeniser;
    private final ExecutorService executor;

    private final Object idleMonitor = new Object();
    private int pending;

    /** Ids with a rebuild waiting out a split, each counted once in {@link #pending}. */
    private final Set<Long> deferred = ConcurrentHashMap.newKeySet();

    /** Set by {@link #close()}; a running rebuild stops at its next entry. */
    private volatile boolean closing;

    /**
     * Creates the rebuild queue for one shard.
     *
     * @param filter     The membership filter currently configured, which may be null
     * @param registered The segment registered under an id, or null when none is
     * @param catalog    The catalog lifecycle states are read from, or null to read none
     */
    TermFilterRebuilds(Supplier<HierarchicalCuckooFilter> filter, LongFunction<Segment> registered,
                       SegmentCatalog catalog) {
        this(filter, registered, catalog, TermExtractor::extractUniqueTerms);
    }

    /**
     * Creates the rebuild queue with the tokeniser it files terms through.
     *
     * <p>Ingestion files {@link TermExtractor#extractUniqueTerms} of a chunk's text, and a
     * rebuild must file the same terms or it would vouch for a level ingestion would not have
     * written; the parameter exists so a test can observe the rebuild while it tokenises.</p>
     *
     * @param filter     The membership filter currently configured, which may be null
     * @param registered The segment registered under an id, or null when none is
     * @param catalog    The catalog lifecycle states are read from, or null to read none
     * @param tokeniser  The distinct terms of one chunk's text
     */
    TermFilterRebuilds(Supplier<HierarchicalCuckooFilter> filter, LongFunction<Segment> registered,
                       SegmentCatalog catalog,
                       Function<String, ? extends Collection<String>> tokeniser) {
        this.filter = filter;
        this.registered = registered;
        this.catalog = catalog;
        this.tokeniser = tokeniser;
        this.executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("excalibur-term-filter-rebuild").factory());
    }

    /**
     * Declares a segment's level stale without scheduling its rebuild.
     *
     * <p>For advance notice, when the segment is about to change and whatever reports the change
     * will schedule the rebuild. Lock-free.</p>
     *
     * @param segmentId The segment's id
     */
    void markStale(long segmentId) {
        HierarchicalCuckooFilter current = filter.get();
        if (current != null) {
            current.markSegmentStale(String.valueOf(segmentId));
        }
    }

    /**
     * Declares a segment's level stale and queues its refill from the segment.
     *
     * <p>Returns at once. The mark is made before this returns, so the filter says nothing about
     * the segment from now until the refill completes.</p>
     *
     * @param segmentId The segment's id
     */
    void schedule(long segmentId) {
        HierarchicalCuckooFilter current = filter.get();
        if (current == null) {
            return;
        }
        current.markSegmentStale(String.valueOf(segmentId));
        synchronized (idleMonitor) {
            pending++;
        }
        try {
            executor.execute(new Rebuild(segmentId));
        } catch (RejectedExecutionException e) {
            finished();
            LOGGER.debug("Term filter rebuild for segment {} not queued: the shard is closing; "
                + "the level stays stale", segmentId);
        }
    }

    /**
     * Waits until every rebuild queued so far has finished.
     *
     * @param timeout How long to wait
     * @param unit    The unit of the timeout
     * @return true if the queue drained, false if the timeout passed first
     * @throws InterruptedException if the wait is interrupted
     */
    boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (idleMonitor) {
            while (pending > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(idleMonitor, remaining);
            }
            return true;
        }
    }

    /**
     * Stops taking rebuilds, drops the queued ones and waits a bounded time for the running one.
     *
     * <p>See "Closing". Every level a dropped or stopped rebuild would have vouched for stays
     * stale, which is what a filter being discarded needs of it anyway.</p>
     */
    @Override
    public void close() {
        drain();
    }

    /**
     * As {@link #close()}, saying whether the running rebuild actually ended.
     *
     * <p>The one interrupt this delivers, through {@code shutdownNow}, is safe to deliver: a
     * rebuild copies text out of the segment's memory mapping and files terms into the in-memory
     * filter, and touches no file channel an interrupt could close. It is the {@code closing}
     * flag, read between entries, that actually stops it. The answer matters because the
     * mapping it copies from lives in the arena the caller closes next.</p>
     *
     * @return true if no rebuild is still running
     */
    boolean drain() {
        closing = true;
        List<Runnable> dropped = executor.shutdownNow();
        for (int i = 0; i < dropped.size(); i++) {
            finished();
        }
        try {
            if (executor.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            LOGGER.warn("A term filter rebuild was still running {} after the shard began "
                + "closing; it is left to finish on its own", CLOSE_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return executor.isTerminated();
    }

    /**
     * How many rebuilds are queued, running or waiting out a split.
     *
     * @return The count {@link #awaitIdle} waits to reach zero
     */
    int pending() {
        synchronized (idleMonitor) {
            return pending;
        }
    }

    private void finished() {
        synchronized (idleMonitor) {
            pending--;
            if (pending == 0) {
                idleMonitor.notifyAll();
            }
        }
    }

    /**
     * One queued rebuild; a named type so {@link #close()} can count the ones it drops.
     */
    private final class Rebuild implements Runnable {

        private final long segmentId;

        Rebuild(long segmentId) {
            this.segmentId = segmentId;
        }

        @Override
        public void run() {
            try {
                rebuild(segmentId);
            } finally {
                finished();
            }
        }
    }

    /**
     * The text of a segment's live entries, copied out under its read lock.
     *
     * @param texts   The UTF-8 text of every readable entry that carries any
     * @param entries The live entries read, with or without text
     * @param damaged The live entries that failed verification and were left out
     */
    private record Snapshot(List<byte[]> texts, long entries, long damaged) {
    }

    /**
     * Refills one segment's level from the segment registered under its id now.
     *
     * @param segmentId The segment's id
     */
    private void rebuild(long segmentId) {
        HierarchicalCuckooFilter current = filter.get();
        Segment segment = registered.apply(segmentId);
        if (current == null || segment == null || segment.isClosed()) {
            // Nothing serves this id now. Whatever registers a segment under it next schedules
            // the rebuild that describes that segment; until then the mark stands.
            return;
        }
        if (isTombstoned(segmentId) || closing) {
            return;
        }
        if (segment.isSplitComplete()) {
            // See "A segment being split is not read": the parent never serves the id again.
            LOGGER.debug("Segment {} has split and is still registered under its id; its level "
                + "stays stale for the rebuild registering the child schedules", segmentId);
            return;
        }
        if (segment.isSplitting()) {
            retryAfterSplit(segmentId);
            return;
        }

        String key = String.valueOf(segmentId);
        long generation = current.beginSegmentRebuild(key);
        Snapshot snapshot;
        try {
            snapshot = snapshot(segment);
        } catch (RuntimeException e) {
            LOGGER.warn("Rebuilding the term filter of segment {} could not read the segment; its "
                + "level stays stale until its next rebuild: {}", segmentId, e.toString());
            return;
        }
        if (snapshot == null) {
            return;
        }

        long filed = 0;
        try {
            for (byte[] text : snapshot.texts()) {
                if (closing) {
                    return;
                }
                file(current, key, text);
                filed++;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Rebuilding the term filter of segment {} stopped after {} of {} "
                + "entries; its level stays stale until its next rebuild: {}",
                segmentId, filed, snapshot.entries(), e.toString());
            return;
        }

        if (snapshot.damaged() > 0) {
            LOGGER.warn("Term filter of segment {} was refilled from {} live entries, leaving out "
                + "{} that fail verification; the level stays stale rather than vouch for a "
                + "segment while missing their terms", segmentId, snapshot.entries(),
                snapshot.damaged());
        } else if (registered.apply(segmentId) != segment) {
            LOGGER.debug("Segment {} was replaced under its id during its term filter rebuild; "
                + "the level stays stale for the rebuild of the segment that replaced it",
                segmentId);
        } else if (segment.isSplitComplete()) {
            LOGGER.debug("Segment {} split during its term filter rebuild; the level stays stale "
                + "for the rebuild registering the child schedules", segmentId);
        } else if (segment.isSplitting()) {
            LOGGER.debug("Segment {} began splitting during its term filter rebuild; the level "
                + "stays stale until the split is settled", segmentId);
            retryAfterSplit(segmentId);
        } else if (current.completeSegmentRebuild(key, generation)) {
            LOGGER.debug("Term filter of segment {} rebuilt from {} live entries", segmentId,
                snapshot.entries());
        } else {
            LOGGER.debug("Term filter of segment {} was marked again during its rebuild; the "
                + "rebuild queued after the mark will vouch for it", segmentId);
        }
    }

    /**
     * Copies the text of every live entry out of a segment, under its read lock for the walk.
     *
     * <p>A damaged entry is counted and stepped over; the walk resumes after it, see
     * {@link Segment.EntryIterator}.</p>
     *
     * @param segment The segment
     * @return The snapshot, or null if the queue began closing during the walk
     */
    private Snapshot snapshot(Segment segment) {
        List<byte[]> texts = new ArrayList<>();
        long entries = 0;
        long damaged = 0;
        try (Segment.EntryIterator live = segment.liveEntries()) {
            while (true) {
                if (closing) {
                    return null;
                }
                SegmentEntry entry;
                try {
                    if (!live.hasNext()) {
                        break;
                    }
                    entry = live.next();
                } catch (CorruptSegmentEntryException e) {
                    damaged++;
                    continue;
                }
                entries++;
                byte[] text = entry.value() == null
                    ? null
                    : ChunkPayloadCodec.textBytesOf(entry.value());
                if (text != null && text.length > 0) {
                    texts.add(text);
                }
            }
        }
        return new Snapshot(texts, entries, damaged);
    }

    /**
     * Files the distinct terms of one chunk's text, as ingestion does for a chunk it writes.
     *
     * @param target The filter
     * @param key    The segment's key in it
     * @param text   The chunk's UTF-8 text
     */
    private void file(HierarchicalCuckooFilter target, String key, byte[] text) {
        for (String term : tokeniser.apply(new String(text, StandardCharsets.UTF_8))) {
            target.insert(term.getBytes(StandardCharsets.UTF_8), key);
        }
    }

    /**
     * Queues a rebuild again once {@link #SPLIT_RETRY} has passed, counted as pending meanwhile.
     *
     * <p>The level is not marked again: the rebuild being put back was scheduled by a mark that
     * still stands, and a mark here would only cost a rebuild that began since its vouching.</p>
     *
     * <p>Coalesced with a retry of the same id already waiting, which is left to stand for both;
     * see "A segment being split is not read".</p>
     *
     * @param segmentId The segment's id
     */
    private void retryAfterSplit(long segmentId) {
        if (!deferred.add(segmentId)) {
            return;
        }
        synchronized (idleMonitor) {
            pending++;
        }
        CompletableFuture.delayedExecutor(SPLIT_RETRY.toMillis(), TimeUnit.MILLISECONDS)
            .execute(() -> requeue(segmentId));
    }

    /**
     * Puts a deferred rebuild on the queue, or counts it off when the queue has closed.
     *
     * @param segmentId The segment's id
     */
    private void requeue(long segmentId) {
        // Released before the rebuild is queued, so one that finds the split still undecided
        // defers again rather than being coalesced into the retry that is ending here.
        deferred.remove(segmentId);
        if (closing) {
            finished();
            return;
        }
        try {
            executor.execute(new Rebuild(segmentId));
        } catch (RejectedExecutionException e) {
            finished();
        }
    }

    private boolean isTombstoned(long segmentId) {
        if (catalog == null) {
            return false;
        }
        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
        return metadata != null && metadata.getLifecycleState() == LifecycleState.TOMBSTONED;
    }
}
