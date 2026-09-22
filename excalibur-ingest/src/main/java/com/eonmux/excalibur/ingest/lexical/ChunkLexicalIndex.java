package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.core.hash.XxHash64;
import com.eonmux.excalibur.graph.erasure.CompactionPolicy;
import com.eonmux.excalibur.graph.erasure.CompactionPolicy.CompactionStats;
import com.eonmux.excalibur.retrieval.lexical.Bm25Parameters;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.retrieval.lexical.native_.DocumentErasureLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * A BM25 index over the corpus's own chunks, written as they are ingested.
 *
 * <h2>Why this exists</h2>
 * <p>Lucene was already in the build, but only over the graph's entities, reachable through
 * {@code POST /v1/graph/query} and only after a graph had been extracted and built. The document
 * corpus itself had no lexical index at all: {@code POST /v1/query} was vector search and nothing
 * else. That made a corpus-wide embedding pass the price of admission for any retrieval, which for
 * a corpus of identifiers, titles, DOIs and error codes is both the most expensive option and the
 * worst one - exact terms are what BM25 is good at and what a dense vector blurs.</p>
 *
 * <h2>Identifiers</h2>
 * <p>Ranking and fusion are built on a {@code long} document id, while a chunk's real name is
 * {@code source:document:chunk:n}. The long is {@link XxHash64} of that string: stable across
 * restarts and reingestion without a counter or a dictionary to keep in step. The original string
 * is stored beside it, so a hit is reported under the name a caller can use. Two names sharing an
 * id costs one of the chunks, which {@link #refresh()} reports where it can prove that is what
 * happened.</p>
 *
 * <h2>Erasure is an obligation here too</h2>
 * <p>Lucene's {@code deleteDocuments} hides a chunk at once but leaves its text in the segment
 * until a merge happens to rewrite it, and Lucene chooses when that is. That is the right default
 * for reclaiming space and the wrong answer for a caller under a deadline, so the promise is
 * recorded the way the native engine records it: a {@link DocumentErasureLog} entry forced to disk
 * before {@link #erase} returns, a {@link CompactionPolicy} that decides when the cost of a
 * force-merge is owed, and a {@link #stats()} that reports what is outstanding so the node's sweep
 * can see it. Without the log there is no record of <em>when</em> a request arrived, and the one
 * condition that catches a single erased chunk in a large corpus is the only one driven by age.</p>
 *
 * <p>The log is also the authority on open. A delete is committed to Lucene separately from the
 * log entry that promised it, and a crash between the two would leave a chunk readable with
 * nothing left to notice, so anything still outstanding is deleted again as the index opens.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent {@link #add}: Lucene's {@code IndexWriter} is, and the counters here are
 * atomic. {@link #refresh()} is what makes writes visible to search. The erasure log is guarded by
 * a monitor of its own and compaction by a lock of its own, so an erase arriving during a
 * force-merge waits for neither.</p>
 */
public final class ChunkLexicalIndex implements ChunkIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkLexicalIndex.class);

    /**
     * The policy used when a caller does not name one.
     *
     * <p>The native engine's defaults, deliberately: an operator who has set
     * {@code excalibur.ingest.lexical-max-latency-seconds} has stated a bound on this node's
     * erasures, not on one engine's, and a Lucene index that answered to a different number would
     * make the configured one true only by accident of which engine was selected.</p>
     */
    private static final CompactionPolicy DEFAULT_POLICY = CompactionPolicy.standard(
        CompactionPlanner.DEFAULT_TOMBSTONE_RATIO,
        CompactionPlanner.DEFAULT_MAX_ERASURE_LATENCY,
        CompactionPlanner.DEFAULT_MAX_SEGMENTS);

    private final LexicalIndex index;
    private final DocumentErasureLog erasureLog;
    private final CompactionPolicy policy;
    private final Clock clock;
    private final AtomicLong indexed = new AtomicLong();
    private final AtomicLong collisions = new AtomicLong();

    /** Guards the erasure log, which is a mutable file and not thread-safe on its own. */
    private final Object logLock = new Object();

    /**
     * Chunks whose deletes have been handed to Lucene and not yet committed.
     *
     * <p>A delete is applied, as far as a reopen is concerned, only once a commit has made it
     * durable, so this is what the next commit confirms on the erasure log. Guarded by
     * {@link #logLock}.</p>
     */
    private final Set<String> deletesAwaitingCommit = new LinkedHashSet<>();

    /**
     * Keeps a commit from landing inside a document.
     *
     * <p>Additions share the read half, so any number of runs index concurrently; a commit takes
     * the write half, so it waits for every document in progress to be wholly added. Lucene's
     * own commit captures whatever its writer holds at that instant, which for a document added
     * chunk by chunk can be half of it.</p>
     */
    private final ReentrantReadWriteLock commitGate = new ReentrantReadWriteLock();

    /**
     * Held for the length of a force-merge.
     *
     * <p>Tried rather than waited on: a second compaction queued behind the first would run
     * against a state the first has already changed, and the answer it would give - that there
     * was nothing left to reclaim - is one the first has already given.</p>
     */
    private final ReentrantLock compacting = new ReentrantLock();

    /** Documents the index already held when this instance opened it. */
    private final long documentsAtOpen;

    /**
     * Whether a chunk identifier written by this instance could have been written before.
     *
     * <p>Set by the first {@link #refresh()} and by any {@link #erase}. See
     * {@link #countChunksLostToCollisions()} for what turns on it.</p>
     */
    private final AtomicBoolean identifiersMayRepeat = new AtomicBoolean();

    private ChunkLexicalIndex(LexicalIndex index, DocumentErasureLog erasureLog,
                              CompactionPolicy policy, Clock clock, long documentsAtOpen) {
        this.index = index;
        this.erasureLog = erasureLog;
        this.policy = policy;
        this.clock = clock;
        this.documentsAtOpen = documentsAtOpen;
    }

    /**
     * Opens (or creates) the chunk index under a directory.
     *
     * @param directory       Where the Lucene index lives
     * @param ramBufferSizeMB Indexing buffer before a flush
     * @return The opened index
     * @throws IOException if the directory cannot be opened
     */
    public static ChunkLexicalIndex open(Path directory, double ramBufferSizeMB) throws IOException {
        return open(directory, ramBufferSizeMB, Bm25Parameters.DEFAULT);
    }

    /**
     * Opens (or creates) the chunk index under a directory, scoring it with a given BM25 pair.
     *
     * @param directory       Where the Lucene index lives
     * @param ramBufferSizeMB Indexing buffer before a flush
     * @param bm25            The scoring parameters this node is configured for
     * @return The opened index
     * @throws IOException if the directory cannot be opened
     */
    public static ChunkLexicalIndex open(Path directory, double ramBufferSizeMB,
                                         Bm25Parameters bm25) throws IOException {
        return open(directory, ramBufferSizeMB, bm25, DEFAULT_POLICY, Clock.systemUTC());
    }

    /**
     * Opens (or creates) the chunk index, under a given erasure policy.
     *
     * <p>The policy is what turns an accepted erasure into a bounded one. Lucene would otherwise
     * reclaim the text whenever its own merges happened to rewrite the segment holding it, which
     * for a single erased chunk in a large index may be never.</p>
     *
     * @param directory       Where the Lucene index lives
     * @param ramBufferSizeMB Indexing buffer before a flush
     * @param bm25            The scoring parameters this node is configured for
     * @param policy          Decides when an outstanding erasure is worth a force-merge
     * @param clock           Stamps erasure requests, and judges their age
     * @return The opened index
     * @throws IOException if the directory cannot be opened
     */
    public static ChunkLexicalIndex open(Path directory, double ramBufferSizeMB,
                                         Bm25Parameters bm25, CompactionPolicy policy,
                                         Clock clock) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(bm25, "bm25 must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        LexicalIndex index = LexicalIndex.open(directory, ramBufferSizeMB, bm25);
        try {
            DocumentErasureLog erasureLog = DocumentErasureLog.open(directory);
            ChunkLexicalIndex chunks =
                new ChunkLexicalIndex(index, erasureLog, policy, clock, index.documentCount());
            chunks.reapplyErasures();
            return chunks;
        } catch (IOException | RuntimeException e) {
            // The index is open and holds a writer lock. Leaving it open after failing to return
            // it would keep the directory locked for the life of the process, so the next attempt
            // to open it would fail for a reason that has nothing to do with what went wrong here.
            index.close();
            throw e;
        }
    }

    /**
     * Re-applies whatever the log still owes.
     *
     * <p>A delete is committed to Lucene separately from the log entry promising it, and the log
     * entry is forced first. A crash in that window leaves the index holding a promise it has no
     * record of having applied, and nothing afterwards would notice: the chunk is readable, the
     * index is intact, and only the answer is wrong. So the log is the authority on open, and
     * any promise it has no confirmation of having committed is deleted again. Only those: a
     * committed delete stays on the log until a force-merge reclaims it, and deleting again
     * would remove whatever has been indexed under the same identifier since. Free when nothing
     * is unconfirmed, which is the usual case.</p>
     */
    private void reapplyErasures() throws IOException {
        Set<String> unapplied;
        synchronized (logLock) {
            unapplied = erasureLog.unappliedKeys();
        }
        if (unapplied.isEmpty()) {
            return;
        }
        identifiersMayRepeat.set(true);
        for (String chunkId : unapplied) {
            index.delete(idOf(chunkId));
        }
        index.commit();
        synchronized (logLock) {
            erasureLog.confirmApplied(unapplied);
        }
        LOGGER.warn("Re-applied {} erasure(s) the log still owed; an interrupted write may have "
            + "made them readable again", unapplied.size());
    }

    /**
     * Runs a Lucene commit and confirms the deletes it made durable.
     *
     * <p>The set is taken before the commit, so a delete issued while it runs is left for the
     * next one to confirm. Confirming it here could record as applied a delete that missed this
     * commit, and a crash before the next would lose it with nothing left to replay.</p>
     *
     * @param commit The operation that commits: a plain commit, or a refresh, which commits
     * @throws IOException if the commit or the confirmation fails
     */
    private void commitAndConfirm(IoAction commit) throws IOException {
        Set<String> issued;
        synchronized (logLock) {
            issued = Set.copyOf(deletesAwaitingCommit);
        }
        commitGate.writeLock().lock();
        try {
            commit.run();
        } finally {
            commitGate.writeLock().unlock();
        }
        if (issued.isEmpty()) {
            return;
        }
        synchronized (logLock) {
            erasureLog.confirmApplied(issued);
            deletesAwaitingCommit.removeAll(issued);
        }
    }

    /** An index operation that can fail with an I/O error. */
    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** The engine id a chunk is indexed under. */
    public static long idOf(String chunkId) {
        return XxHash64.hash(chunkId);
    }

    /**
     * Indexes one chunk's text.
     *
     * @param chunkId The chunk's identifier, {@code source:document:chunk:n}
     * @param text    The chunk text
     * @throws IOException if the write fails
     */
    @Override
    public void add(String chunkId, String text) throws IOException {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        if (text == null || text.isEmpty()) {
            return;
        }
        // No per-chunk collision lookup. Reading the index back for every chunk added turned
        // indexing into one Lucene search per chunk, which dominated the write path completely:
        // it is a query on the hot path of ingestion, run millions of times, to check for an
        // event whose probability across a hundred million chunks is under one in a thousand.
        // Collisions are detected in aggregate at refresh instead, which costs one read per run.
        commitGate.readLock().lock();
        try {
            index.put(idOf(chunkId), text, chunkId);
        } finally {
            commitGate.readLock().unlock();
        }
        indexed.incrementAndGet();
    }

    @Override
    public void addDocument(Map<String, String> chunks) throws IOException {
        Objects.requireNonNull(chunks, "chunks must not be null");
        int added = 0;
        commitGate.readLock().lock();
        try {
            for (Map.Entry<String, String> chunk : chunks.entrySet()) {
                Objects.requireNonNull(chunk.getKey(), "chunkId must not be null");
                String text = chunk.getValue();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                index.put(idOf(chunk.getKey()), text, chunk.getKey());
                added++;
            }
        } finally {
            commitGate.readLock().unlock();
        }
        indexed.addAndGet(added);
    }

    /**
     * Makes everything written so far searchable, and checks what was actually stored.
     *
     * <p>Every chunk is written under a 64-bit hash of its identifier, and Lucene replaces rather
     * than appends on a repeated id. So two chunk names hashing alike is not an error at write
     * time - it is one document where there should have been two, and the only evidence is a
     * document count lower than the number of chunks added. Checking it here costs one read per
     * run rather than one per chunk, and turns a silently short corpus into a logged number.</p>
     */
    @Override
    public void refresh() throws IOException {
        commitAndConfirm(index::refresh);
        countChunksLostToCollisions();
    }

    /**
     * Reports chunks the index does not hold, where that can only mean it lost them.
     *
     * <p>A document count below the number of chunks written is what a collision looks like, and
     * it is also what a reindex, a reopened index and an erasure look like: all three write or
     * remove an identifier the index already held, and none of them lose anything. The counter
     * cannot tell them apart, so the alarm is confined to the case where nothing else can
     * explain the shortfall - an index this instance opened empty, on its first pass over it,
     * before any identifier could have been written twice.</p>
     *
     * <p>Within that pass every chunk name is minted once, so a document missing from the index
     * is a chunk missing from the corpus, whether two names collided under the hash or ingestion
     * minted one name twice. Both are worth the same alarm. Outside it the check is silent
     * rather than wrong: a false alarm on every reindex would be read as one, and the real
     * event - under one in a thousand across a hundred million chunks - would be read as one
     * too.</p>
     */
    private void countChunksLostToCollisions() throws IOException {
        if (identifiersMayRepeat.getAndSet(true) || documentsAtOpen != 0) {
            return;
        }
        long added = indexed.get();
        long stored = index.documentCount();
        if (stored < added) {
            long missing = added - stored;
            collisions.set(missing);
            LOGGER.error("{} chunk(s) of {} are missing from the lexical index: their identifiers "
                + "collided under a 64-bit hash and replaced one another", missing, added);
        }
    }

    /** Flushes to disk without making writes searchable. */
    public void commit() throws IOException {
        commitAndConfirm(index::commit);
    }

    /** The chunk identifier behind an engine id, or null. */
    @Override
    public String chunkIdOf(long id) throws IOException {
        return index.externalKeyOf(id);
    }

    /**
     * Records the promise to erase these chunks, and hides them immediately.
     *
     * <p>The log entry is forced to disk before the delete is issued. A caller told its request
     * was accepted and then losing it to a power failure is the one outcome erasure must not
     * have, and the order matters: a promise written after the delete would be lost by a crash
     * that kept the delete, and the text would sit in the segment with nothing left owing it.</p>
     *
     * @param chunkIds The chunks to remove
     * @return How many were asked for, since Lucene reports no count for a delete by term
     */
    @Override
    public int erase(Set<String> chunkIds) throws IOException {
        Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        if (chunkIds.isEmpty()) {
            return 0;
        }
        synchronized (logLock) {
            erasureLog.append(chunkIds, clock.instant());
        }
        // A removed document leaves the index holding fewer than were written, which is what a
        // lost chunk looks like to the count.
        identifiersMayRepeat.set(true);
        for (String chunkId : chunkIds) {
            index.delete(idOf(chunkId));
        }
        synchronized (logLock) {
            deletesAwaitingCommit.addAll(chunkIds);
        }
        // Lucene reports no count for a delete by term, and reading one back would cost a search
        // per chunk on a path that runs while a document is being rolled back.
        return chunkIds.size();
    }

    @Override
    public com.eonmux.excalibur.retrieval.rank.Ranker ranker(
            com.eonmux.excalibur.retrieval.expand.QueryExpander expander) {
        // Ungated deliberately. A term the corpus lacks matches nothing, so refusing such a
        // formulation cannot change what this ranker answers, only the work it does getting
        // there - and whether that trade pays against Lucene's own term dictionary is what the
        // bench's expansion and expansion+vocab strategies exist to measure. The native engine,
        // whose gate is the dictionary its search already reads, takes the trade.
        return new com.eonmux.excalibur.retrieval.expand.ExpandedLexicalRanker(index, expander);
    }

    /**
     * Force-merges the segments holding erased chunks, if the policy says it is due.
     *
     * <p>Lucene reclaims deletions on its own merge schedule, which is the right default for
     * space and no answer at all for a deadline: a single erased chunk in a large index may never
     * be in a segment Lucene chooses to rewrite. So the policy is consulted against the same
     * three numbers the native engine offers it, and the oldest outstanding request - the only
     * one of the three that a single erasure can move - comes from the log.</p>
     *
     * <p>{@code textOf} is unused. Lucene stores each chunk's text itself, so a rewrite has
     * everything it needs and never has to ask storage for it back.</p>
     */
    @Override
    public CompactionResult compactIfDue(Function<String, String> textOf) throws IOException {
        Objects.requireNonNull(textOf, "textOf must not be null");
        return compact(false);
    }

    /**
     * Rewrites the segments holding erased chunks now, whatever the policy would have decided.
     *
     * <p>Lucene keeps a copy of each chunk's text, so unlike the native index there is content
     * here to reclaim and not only postings. A caller under an erasure deadline needs this to
     * have happened rather than to be scheduled.</p>
     */
    @Override
    public CompactionResult compactNow(Function<String, String> textOf) throws IOException {
        Objects.requireNonNull(textOf, "textOf must not be null");
        return compact(true);
    }

    private CompactionResult compact(boolean forced) throws IOException {
        if (!compacting.tryLock()) {
            // One at a time. A second force-merge queued behind the first would run against an
            // index the first has already rewritten, and would answer what the first answered.
            return CompactionResult.declined("a compaction is already running");
        }
        try {
            ChunkIndexStats before = stats();
            if (!forced && !policy.shouldCompact(judged(before), clock.instant())) {
                return CompactionResult.declined("not due: " + before.erasedDocuments() + " of "
                    + (before.liveDocuments() + before.erasedDocuments())
                    + " chunk(s) erased across " + before.segments() + " segment(s)");
            }
            // Captured before the merge, not after. An erase arriving while the force-merge runs
            // is a promise this compaction has not kept, and discharging it here would leave the
            // chunk on disk with nothing left to say it was owed.
            Set<String> owed;
            synchronized (logLock) {
                owed = erasureLog.outstandingKeys();
            }
            int reclaimed = index.forceMergeDeletes();
            commitAndConfirm(index::refresh);
            synchronized (logLock) {
                Set<String> keep = new LinkedHashSet<>(erasureLog.outstandingKeys());
                keep.removeAll(owed);
                erasureLog.retain(keep);
            }
            LOGGER.info("Force-merged {} deleted chunk(s) out of the lexical index, discharging "
                + "{} erasure request(s)", reclaimed, owed.size());
            // Performed, even when it reclaimed nothing. The caller forced this rather than asking
            // whether it was due, and "it ran and found nothing left to remove" is the answer they
            // need - Lucene often applies a deletion while flushing, so a zero here usually means
            // the text had already gone rather than that nothing happened.
            //
            // Lucene does not report which segments it chose, so the counts are what the index
            // held before and holds afterwards rather than an invented breakdown of its merge.
            return new CompactionResult(true, before.segments(), stats().segments(), reclaimed,
                reclaimed == 0
                    ? "lucene had no deletions left to reclaim"
                    : "lucene force-merged its deletions");
        } finally {
            compacting.unlock();
        }
    }

    /**
     * The three numbers the policy judges, read off what the index is holding.
     *
     * <p>A chunk hidden by a delete is still in its segment, so it counts towards the total as
     * well as towards the tombstoned share - which is what makes the ratio the fraction of the
     * index that a rewrite would reclaim.</p>
     */
    private CompactionStats judged(ChunkIndexStats stats) {
        Optional<Instant> oldest;
        synchronized (logLock) {
            oldest = erasureLog.oldestOutstanding();
        }
        return new CompactionStats(stats.liveDocuments() + stats.erasedDocuments(),
            stats.erasedDocuments(), oldest, stats.segments());
    }

    @Override
    public String engine() {
        return "lucene";
    }

    /**
     * What Lucene will say about itself.
     *
     * <p>The erasure figures come from the log rather than from Lucene, because Lucene knows what
     * is deleted but not when its deletion was promised - and age is the only condition under
     * which a single erased chunk in a large index is ever worth a rewrite. An outstanding count
     * of zero here is what lets the node's sweep skip this index, so reporting zero while a
     * promise was owed would suspend the deadline rather than merely misreport it.</p>
     */
    @Override
    public ChunkIndexStats stats() {
        int segments = 0;
        int deleted = 0;
        Optional<LexicalIndex.Lease> leased = index.tryAcquire();
        if (leased.isPresent()) {
            try (LexicalIndex.Lease lease = leased.get()) {
                org.apache.lucene.index.IndexReader reader = lease.searcher().getIndexReader();
                segments = reader.leaves().size();
                deleted = reader.numDeletedDocs();
            }
        } else {
            // Nothing has been refreshed yet, so there is no reader to count leaves in. An index
            // with no searcher has no segments and no deletions, which is what is reported.
            LOGGER.debug("Lexical stats requested before the first refresh");
        }
        int outstanding;
        Optional<Instant> oldest;
        synchronized (logLock) {
            outstanding = erasureLog.outstanding().size();
            oldest = erasureLog.oldestOutstanding();
        }
        long live = index.documentCount();
        boolean due = policy.shouldCompact(
            new CompactionStats(live + deleted, deleted, oldest, segments), clock.instant());
        return new ChunkIndexStats(engine(), indexed.get(), segments, live, deleted, outstanding,
            oldest, due);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The version of the reader searches go through, which moves only when
     * {@link #refresh()} publishes; an erasure hides a chunk at that same boundary, not
     * before it.</p>
     */
    @Override
    public java.util.OptionalLong searchVersion() {
        return index.readerVersion();
    }

    /** Erasure promises still on the log, whether or not they matched a chunk. */
    public List<DocumentErasureLog.Record> outstandingErasureRequests() {
        synchronized (logLock) {
            return erasureLog.outstanding();
        }
    }

    /** The underlying index, for rankers. */
    public LexicalIndex index() {
        return index;
    }

    /** How many chunks have been indexed by this instance. */
    @Override
    public long indexedCount() {
        return indexed.get();
    }

    /**
     * How many chunks were dropped because their id collided.
     *
     * <p>Counted only where a shortfall can mean nothing else - see
     * {@link #countChunksLostToCollisions()} - so zero is "none seen", not "none happened".</p>
     */
    public long collisionCount() {
        return collisions.get();
    }

    @Override
    public void close() {
        // Committed here rather than left to the writer's own commit on close, which would make
        // the deletes durable without confirming them - so the next open would replay them over
        // anything indexed under the same identifiers since.
        try {
            commitAndConfirm(index::commit);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not commit the lexical index before closing it; erasures issued "
                + "since the last commit will be replayed when it next opens", e);
        }
        index.close();
    }
}
