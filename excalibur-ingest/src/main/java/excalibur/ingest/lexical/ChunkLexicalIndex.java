package excalibur.ingest.lexical;

import io.excalibur.core.hash.XxHash64;
import io.excalibur.retrieval.lexical.Bm25Parameters;
import io.excalibur.retrieval.lexical.LexicalIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent {@link #add}: Lucene's {@code IndexWriter} is, and the counters here are
 * atomic. {@link #refresh()} is what makes writes visible to search.</p>
 */
public final class ChunkLexicalIndex implements ChunkIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkLexicalIndex.class);

    private final LexicalIndex index;
    private final AtomicLong indexed = new AtomicLong();
    private final AtomicLong collisions = new AtomicLong();

    /** Documents the index already held when this instance opened it. */
    private final long documentsAtOpen;

    /**
     * Whether a chunk identifier written by this instance could have been written before.
     *
     * <p>Set by the first {@link #refresh()} and by any {@link #erase}. See
     * {@link #countChunksLostToCollisions()} for what turns on it.</p>
     */
    private final AtomicBoolean identifiersMayRepeat = new AtomicBoolean();

    private ChunkLexicalIndex(LexicalIndex index, long documentsAtOpen) {
        this.index = index;
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
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(bm25, "bm25 must not be null");
        LexicalIndex index = LexicalIndex.open(directory, ramBufferSizeMB, bm25);
        return new ChunkLexicalIndex(index, index.documentCount());
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
        index.put(idOf(chunkId), text, chunkId);
        indexed.incrementAndGet();
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
        index.refresh();
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
        index.commit();
    }

    /** The chunk identifier behind an engine id, or null. */
    @Override
    public String chunkIdOf(long id) throws IOException {
        return index.externalKeyOf(id);
    }

    @Override
    public int erase(java.util.Set<String> chunkIds) throws IOException {
        java.util.Objects.requireNonNull(chunkIds, "chunkIds must not be null");
        // A removed document leaves the index holding fewer than were written, which is what a
        // lost chunk looks like to the count.
        identifiersMayRepeat.set(true);
        for (String chunkId : chunkIds) {
            index.delete(idOf(chunkId));
        }
        // Lucene reports no count for a delete by term, and reading one back would cost a search
        // per chunk on a path that runs while a document is being rolled back.
        return chunkIds.size();
    }

    @Override
    public io.excalibur.retrieval.rank.Ranker ranker(
            io.excalibur.retrieval.expand.QueryExpander expander) {
        // Ungated deliberately. A term the corpus lacks matches nothing, so refusing such a
        // formulation cannot change what this ranker answers, only the work it does getting
        // there - and whether that trade pays against Lucene's own term dictionary is what the
        // bench's expansion and expansion+vocab strategies exist to measure. The native engine,
        // whose gate is the dictionary its search already reads, takes the trade.
        return new io.excalibur.retrieval.expand.ExpandedLexicalRanker(index, expander);
    }

    @Override
    public io.excalibur.retrieval.lexical.native_.CompactionResult compactIfDue(
            java.util.function.Function<String, String> textOf) {
        // Lucene merges its own segments and reclaims deleted documents on its own schedule.
        // There is no second thing to do here, and reporting a compaction that did not happen
        // would make the caller believe an erasure had been applied to disk.
        return io.excalibur.retrieval.lexical.native_.CompactionResult.declined(
            "lucene manages its own merges");
    }

    /**
     * Rewrites the segments holding erased chunks, so their text leaves disk.
     *
     * <p>Lucene keeps a copy of each chunk's text, so unlike the native index there is content
     * here to reclaim and not only postings. A caller under an erasure deadline needs this to
     * have happened rather than to be scheduled.</p>
     */
    @Override
    public io.excalibur.retrieval.lexical.native_.CompactionResult compactNow(
            java.util.function.Function<String, String> textOf) throws IOException {
        ChunkIndexStats before = stats();
        int reclaimed = index.forceMergeDeletes();
        index.refresh();
        LOGGER.info("Force-merged {} deleted chunk(s) out of the lexical index", reclaimed);
        // Performed, even when it reclaimed nothing. The caller forced this rather than asking
        // whether it was due, and "it ran and found nothing left to remove" is the answer they
        // need - Lucene often applies a deletion while flushing, so a zero here usually means the
        // text had already gone rather than that nothing happened.
        //
        // Lucene does not report which segments it chose, so the counts are what the index held
        // before and holds afterwards rather than an invented breakdown of its merge.
        return new io.excalibur.retrieval.lexical.native_.CompactionResult(true,
            before.segments(), stats().segments(), reclaimed,
            reclaimed == 0
                ? "lucene had no deletions left to reclaim"
                : "lucene force-merged its deletions");
    }

    @Override
    public String engine() {
        return "lucene";
    }

    /**
     * What Lucene will say about itself.
     *
     * <p>No erasure requests and never a compaction due: Lucene reclaims deleted documents on its
     * own merge schedule, so there is no obligation this node is holding and no moment at which it
     * would decide to act. Reporting a deadline it does not enforce would be the more misleading
     * answer.</p>
     */
    @Override
    public ChunkIndexStats stats() {
        int segments = 0;
        int deleted = 0;
        try {
            org.apache.lucene.index.IndexReader reader = index.searcher().getIndexReader();
            segments = reader.leaves().size();
            deleted = reader.numDeletedDocs();
        } catch (IllegalStateException e) {
            // Nothing has been refreshed yet, so there is no reader to count leaves in. An index
            // with no searcher has no segments and no deletions, which is what is reported.
            LOGGER.debug("Lexical stats requested before the first refresh");
        }
        return new ChunkIndexStats(engine(), indexed.get(), segments, index.documentCount(),
            deleted, 0, java.util.Optional.empty(), false);
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
        index.close();
    }
}
