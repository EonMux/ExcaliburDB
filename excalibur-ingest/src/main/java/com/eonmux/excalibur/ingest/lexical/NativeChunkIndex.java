package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.lexical.Bm25Parameters;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlan;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.retrieval.lexical.native_.LexicalStore;
import com.eonmux.excalibur.retrieval.rank.Ranker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The chunk index over the engine's own inverted index.
 *
 * <h2>One copy of the data</h2>
 * <p>The store holds postings, not text. A chunk's text is already in the storage engine under the
 * chunk's own key, so this index never writes a second copy of it - which is what
 * {@link #compactIfDue} needs a text supplier for rather than reading its own files.</p>
 *
 * <h2>Buffering and publication</h2>
 * <p>Chunks accumulate in memory and are written out as a segment whenever the buffer fills, but
 * a written segment is not a readable one: it is staged, and every segment a run produced is
 * published together in a single manifest replacement at {@link #refresh()}. That is what keeps a
 * run's chunks appearing at one instant however many times the buffer had to spill, which matters
 * because a spill lands mid-document and half a document is a wrong answer rather than a partial
 * one.</p>
 *
 * <h2>Epochs</h2>
 * <p>Segments are published at the epoch after the current one, so a query already running at the
 * current epoch does not acquire results halfway through. The compaction floor runs the other way:
 * a query cannot outlive its own timeout, so no reader is further behind than the timeout divided
 * by the epoch tick, and segments older than that can be merged without any reader noticing.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Safe for concurrent use. Queries hold the read half of a lock and writes the write half; the
 * store's segment handles are opened whenever the set of segments changes, so a query never has to
 * open one itself and readers never mutate what other readers are reading.</p>
 */
public final class NativeChunkIndex implements ChunkIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeChunkIndex.class);

    /** Chunks held before the buffer spills to a staged segment. */
    public static final int DEFAULT_BUFFER_CHUNKS = 50_000;

    /**
     * Names kept for reporting hits under.
     *
     * <p>Bounded because the corpus is not: a hundred million chunk names would be gigabytes of
     * heap held to answer a question only ever asked about the handful of chunks a query just
     * returned. Every name a hit could need is recorded as that hit is produced.</p>
     */
    public static final int DEFAULT_NAME_CACHE = 100_000;

    private final LexicalStore store;
    private final LongSupplier epochs;
    private final long readerLagEpochs;
    private final int bufferChunks;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Held for the whole of a compaction, including the part that holds no other lock.
     *
     * <p>Separate from the read-write lock because it guards a different thing: not the store's
     * state, but the segments a running compaction is still reading out of.</p>
     */
    private final java.util.concurrent.locks.ReentrantLock compacting =
        new java.util.concurrent.locks.ReentrantLock();
    private final Map<String, String> buffer = new LinkedHashMap<>();
    private final List<LexicalStore.Staged> staged = new ArrayList<>();
    private final Set<String> deferredErasures = new LinkedHashSet<>();
    private final Map<Long, String> names;
    private final AtomicLong indexed = new AtomicLong();
    private final AtomicLong collisions = new AtomicLong();

    private NativeChunkIndex(LexicalStore store, LongSupplier epochs, long readerLagEpochs,
                             int bufferChunks, int nameCache) {
        this.store = store;
        this.epochs = epochs;
        this.readerLagEpochs = readerLagEpochs;
        this.bufferChunks = bufferChunks;
        this.names = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
                return size() > nameCache;
            }
        });
    }

    /**
     * Opens the index under a directory.
     *
     * @param directory       Where the store lives
     * @param epochs          The node's current visibility epoch
     * @param readerLagEpochs How many epochs behind a live reader may be
     * @param planner         Decides when and what to compact
     */
    public static NativeChunkIndex open(Path directory, LongSupplier epochs, long readerLagEpochs,
                                        CompactionPlanner planner) throws IOException {
        return open(directory, epochs, readerLagEpochs, planner, Bm25Parameters.DEFAULT);
    }

    /**
     * Opens the index under a directory, scoring it with a given BM25 pair.
     *
     * <p>The pair is recorded in what the store writes and checked against what it reads, so an
     * index built under one pair refuses to open under another rather than being scored against
     * block bounds computed from the other.</p>
     *
     * @param directory       Where the store lives
     * @param epochs          The node's current visibility epoch
     * @param readerLagEpochs How many epochs behind a live reader may be
     * @param planner         Decides when and what to compact
     * @param bm25            The scoring parameters this node is configured for
     */
    public static NativeChunkIndex open(Path directory, LongSupplier epochs, long readerLagEpochs,
                                        CompactionPlanner planner, Bm25Parameters bm25)
            throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(epochs, "epochs must not be null");
        Objects.requireNonNull(planner, "planner must not be null");
        Objects.requireNonNull(bm25, "bm25 must not be null");
        if (readerLagEpochs < 0) {
            throw new IllegalArgumentException(
                "readerLagEpochs must not be negative: " + readerLagEpochs);
        }
        LexicalStore store = LexicalStore.open(directory, planner, Clock.systemUTC(), bm25);
        store.warm();
        return new NativeChunkIndex(store, epochs, readerLagEpochs, DEFAULT_BUFFER_CHUNKS,
            DEFAULT_NAME_CACHE);
    }

    /** For tests that need a buffer small enough to spill. */
    static NativeChunkIndex open(Path directory, LongSupplier epochs, long readerLagEpochs,
                                 CompactionPlanner planner, int bufferChunks) throws IOException {
        LexicalStore store = LexicalStore.open(directory, planner, Clock.systemUTC());
        store.warm();
        return new NativeChunkIndex(store, epochs, readerLagEpochs, bufferChunks,
            DEFAULT_NAME_CACHE);
    }

    @Override
    public void add(String chunkId, String text) throws IOException {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        if (text == null || text.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            buffer.put(chunkId, text);
            if (buffer.size() >= bufferChunks) {
                spill();
            }
        } finally {
            lock.writeLock().unlock();
        }
        indexed.incrementAndGet();
    }

    @Override
    public void addDocument(Map<String, String> chunks) throws IOException {
        Objects.requireNonNull(chunks, "chunks must not be null");
        int added = 0;
        // One hold of the write lock for the whole document, which refresh() also takes: a
        // refresh either publishes the buffer before any of these chunks arrive or after all of
        // them have. A spill in the middle is harmless, because a staged segment is published
        // only with the rest of what refresh() finds.
        lock.writeLock().lock();
        try {
            for (Map.Entry<String, String> chunk : chunks.entrySet()) {
                Objects.requireNonNull(chunk.getKey(), "chunkId must not be null");
                String text = chunk.getValue();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                buffer.put(chunk.getKey(), text);
                added++;
                if (buffer.size() >= bufferChunks) {
                    spill();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        indexed.addAndGet(added);
    }

    @Override
    public void refresh() throws IOException {
        lock.writeLock().lock();
        try {
            spill();
            if (!staged.isEmpty()) {
                // The epoch after this one: a query that captured the current epoch was reading a
                // corpus without these chunks in it, and must go on doing so until it finishes.
                store.publish(List.copyOf(staged), epochs.getAsLong() + 1);
                staged.clear();
            }
            if (!deferredErasures.isEmpty()) {
                // Chunks rolled back while they sat in a staged segment. They could not be
                // tombstoned then - the segment was not in the manifest to be tombstoned in.
                int erased = store.erase(Set.copyOf(deferredErasures));
                LOGGER.info("Applied {} deferred erasure(s), {} chunk(s) tombstoned",
                    deferredErasures.size(), erased);
                deferredErasures.clear();
            }
            store.warm();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Writes what the buffer holds as a staged segment. Caller holds the write lock. */
    private void spill() throws IOException {
        if (buffer.isEmpty()) {
            return;
        }
        LexicalStore.Staged segment = store.stage(buffer);
        if (segment != null) {
            staged.add(segment);
        }
        buffer.clear();
    }

    @Override
    public int erase(Set<String> chunkIds) throws IOException {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        if (chunkIds.isEmpty()) {
            return 0;
        }
        lock.writeLock().lock();
        try {
            int dropped = 0;
            for (String chunkId : chunkIds) {
                if (buffer.remove(chunkId) != null) {
                    dropped++;
                }
            }
            int tombstoned = store.erase(chunkIds);
            if (!staged.isEmpty()) {
                // Anything already written into a staged segment is beyond erase()'s reach until
                // that segment is in the manifest. Remembered rather than dropped, because a
                // rollback that quietly failed to remove a chunk is the same as not rolling back.
                deferredErasures.addAll(chunkIds);
            }
            store.warm();
            return dropped + tombstoned;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Compacts if the policy says it is due.
     *
     * @param textOf Supplies a surviving chunk's text, normally from the storage engine
     */
    public CompactionResult compactIfDue(Function<String, String> textOf) throws IOException {
        return compact(textOf, false);
    }

    /**
     * Compacts whether or not the policy says it is due.
     *
     * <p>For a caller that has to be able to say the data is gone rather than that its removal is
     * scheduled - an erasure request made under a deadline. The policy exists because rewriting
     * the store per request would make erasure a denial of service, so this is the caller
     * accepting that cost deliberately rather than the default.</p>
     *
     * @param textOf Supplies a surviving chunk's text, normally from the storage engine
     */
    @Override
    public CompactionResult compactNow(Function<String, String> textOf) throws IOException {
        return compact(textOf, true);
    }

    private CompactionResult compact(Function<String, String> textOf, boolean forced)
            throws IOException {
        Objects.requireNonNull(textOf, "textOf must not be null");
        if (!compacting.tryLock()) {
            // One at a time. Two compactions rewriting overlapping segments would have the second
            // reading files the first had already replaced, and queueing the second behind the
            // first only means running a plan built from a store that no longer looks like that.
            return CompactionResult.declined("a compaction is already running");
        }
        try {
            LexicalStore.CompactionSnapshot snapshot;
            lock.writeLock().lock();
            try {
                long floor = Math.max(0, epochs.getAsLong() - readerLagEpochs);
                java.util.Optional<CompactionPlan> plan = forced
                    ? java.util.Optional.of(store.forcedPlan(floor))
                    : store.planCompaction(floor);
                if (plan.isEmpty()) {
                    var stats = store.compactionStats();
                    return CompactionResult.declined("not due: " + stats.tombstonedRecords()
                        + " of " + stats.totalRecords() + " chunk(s) tombstoned across "
                        + stats.segmentCount() + " segment(s)");
                }
                snapshot = store.snapshot(plan.get());
            } finally {
                lock.writeLock().unlock();
            }

            // The long part, and the reason for the snapshot: it reads only what was copied out
            // and writes only files the manifest does not name, so queries and ingestion both run
            // throughout. Holding a read lock here instead would not do - a writer queueing behind
            // it makes every query that arrives afterwards queue too.
            LexicalStore.PreparedCompaction prepared = store.build(textOf, snapshot);

            lock.writeLock().lock();
            try {
                CompactionResult result = store.commit(prepared);
                store.warm();
                return result;
            } finally {
                lock.writeLock().unlock();
            }
        } finally {
            compacting.unlock();
        }
    }

    /** Searches the published corpus at the current epoch. */
    List<LexicalStore.Hit> search(String[] terms, double[] weights, int limit) throws IOException {
        lock.readLock().lock();
        try {
            return store.search(terms, weights, limit, epochs.getAsLong());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>A digest of the segments visible at the current epoch, read under the same lock and at
     * the same epoch a search reads them - see {@link LexicalStore#searchVersion(long)}.</p>
     */
    @Override
    public java.util.OptionalLong searchVersion() {
        lock.readLock().lock();
        try {
            return java.util.OptionalLong.of(store.searchVersion(epochs.getAsLong()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Whether any published segment holds this term. */
    boolean knowsTerm(String term) throws IOException {
        lock.readLock().lock();
        try {
            return store.knowsTerm(term, epochs.getAsLong());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Records the name a hit should be reported under, and returns its ranked id.
     *
     * <p>Two names sharing a 64-bit id is logged rather than ignored, but unlike an index keyed by
     * the hash it is not data loss: both chunks are stored and both are searchable, and what
     * collides is only which name one of them is reported under.</p>
     */
    long remember(String chunkId) {
        long id = ChunkIndex.idOf(chunkId);
        String previous = names.put(id, chunkId);
        if (previous != null && !previous.equals(chunkId)) {
            collisions.incrementAndGet();
            LOGGER.error("Chunk identifiers {} and {} share a 64-bit id; a hit for one may be "
                + "reported under the other's name", previous, chunkId);
        }
        return id;
    }

    @Override
    public String chunkIdOf(long id) {
        return names.get(id);
    }

    @Override
    public long indexedCount() {
        return indexed.get();
    }

    /** How many chunk names were found to share a ranked id. */
    public long collisionCount() {
        return collisions.get();
    }

    /** Chunks published and not erased, as of the current epoch. */
    public int liveChunks() {
        lock.readLock().lock();
        try {
            return store.liveDocuments(epochs.getAsLong());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Ranker ranker(QueryExpander expander) {
        return new NativeChunkRanker(this, expander);
    }

    @Override
    public String engine() {
        return "native";
    }

    @Override
    public ChunkIndexStats stats() {
        lock.readLock().lock();
        try {
            long epoch = epochs.getAsLong();
            long floor = Math.max(0, epoch - readerLagEpochs);
            var compaction = store.compactionStats();
            return new ChunkIndexStats(engine(), indexed.get(), compaction.segmentCount(),
                store.liveDocuments(epoch), compaction.tombstonedRecords(),
                store.outstandingErasureRequests().size(), compaction.oldestOutstanding(),
                store.planCompaction(floor).isPresent());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            // Whatever is buffered is already in the storage engine, written before it was
            // indexed. Closing without publishing it would leave those chunks present in storage
            // and findable by nothing, until something happened to reingest their document.
            refresh();
        } catch (IOException e) {
            LOGGER.warn("Buffered chunks were not published before close; they remain in storage "
                + "but are not searchable until their documents are reingested: {}",
                e.getMessage());
        } finally {
            try {
                store.close();
            } finally {
                lock.writeLock().unlock();
            }
        }
    }
}
