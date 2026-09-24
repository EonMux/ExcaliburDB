package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.core.hash.XxHash64;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.rank.Ranker;

import java.io.IOException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * A lexical index over the chunks this node stores.
 *
 * <h2>Why an interface</h2>
 * <p>There are two of these: one over Lucene, and one over the engine's own inverted index. They
 * are alternatives rather than layers - keeping both populated would put a second copy of every
 * chunk's text on disk, which is the opposite of what the native one exists to achieve. So the
 * choice is made once at startup and everything downstream is written against this.</p>
 *
 * <h2>Identifiers</h2>
 * <p>Fusion ranks by a {@code long}, while a chunk's real name is {@code source:document:chunk:n}.
 * {@link #idOf} is the bridge, and {@link #chunkIdOf} is the way back, so a hit is reported under
 * the name a caller can use rather than a number that means nothing outside this process.</p>
 */
public interface ChunkIndex extends AutoCloseable {

    /** The id a chunk is ranked under. Stable across restarts without a counter or a dictionary. */
    static long idOf(String chunkId) {
        return XxHash64.hash(chunkId);
    }

    /**
     * Indexes one chunk's text, replacing whatever was indexed under that identifier.
     *
     * <p>Not necessarily searchable when this returns - {@link #refresh()} is what publishes, and
     * replacement takes effect at the same moment. A chunk identifier names one chunk, so
     * indexing it again is a new version of that chunk rather than a second one: a re-crawl that
     * added instead of replacing would return the chunk twice and go on matching the words it
     * used to hold, while its stored text says otherwise.</p>
     *
     * <p>An identifier must not be added twice between two refreshes. That is not a replacement
     * of one version by another - it is one name given to two chunks in a single publication, and
     * neither engine has a version order to resolve it by.</p>
     */
    void add(String chunkId, String text) throws IOException;

    /**
     * Indexes every chunk of one document, as one unit.
     *
     * <p>A {@link #refresh()} running concurrently publishes all of them or none of them. Runs
     * over different sources share one index and refresh it on their own schedules, so a chunk
     * added one at a time can be published by another run's refresh while the rest of its
     * document is still arriving - and half a document is a wrong answer rather than a partial
     * one. The same rules as {@link #add} apply to each chunk.</p>
     *
     * @param chunks Chunk identifier to text, for one document; empty texts are skipped
     */
    void addDocument(Map<String, String> chunks) throws IOException;

    /** Makes everything added so far searchable. */
    void refresh() throws IOException;

    /**
     * Makes durable and searchable everything added so far, as the half of closing that must
     * always happen.
     *
     * <h3>Why closing is two steps</h3>
     * <p>Every chunk reaches storage before it reaches this index, and nothing reindexes a chunk
     * that storage holds and this index lacks: the document registry already lists its document,
     * so a later crawl reads it as unchanged. Whatever this index still buffers when the node
     * stops is therefore lost to lexical search unless it is published first - which is the
     * invariant a shutdown keeps, and the reason publishing cannot be tied to releasing the
     * index's files. A shutdown that finds a compaction still running must leave the files open
     * underneath it, and if publishing went with them the buffered chunks would go unindexed for
     * good. So a shutdown calls this unconditionally and {@link #close()} only when nothing is
     * still using the index. A crash, as opposed to a shutdown, still loses the buffer; reindexing
     * the source is what recovers it.</p>
     *
     * <p>Safe alongside a compaction in progress: it takes the same exclusion an ingestion run's
     * {@link #refresh()} takes, and a compaction is built to have runs publish around it.</p>
     *
     * @throws IOException if what is buffered cannot be published
     */
    default void publishBuffered() throws IOException {
        refresh();
    }

    /**
     * Whether this index's compactions read chunk text back out of the storage engine.
     *
     * <p>What a shutdown asks about a compaction that did not stop when drained: one that reads
     * storage makes closing the storage engine and its arena unsafe, and one that does not only
     * makes closing this index unsafe. Answering true when it is not so leaves storage open at
     * exit for nothing; answering false when it is so closes storage under a running read, so
     * true is the default.</p>
     *
     * @return true if a compaction of this index reads the text supplier it is given
     */
    default boolean compactionReadsStorage() {
        return true;
    }

    /**
     * Removes chunks from the index.
     *
     * @param chunkIds The chunks to remove
     * @return How many were found and removed
     */
    int erase(Set<String> chunkIds) throws IOException;

    /** The chunk name behind a ranked id, or null when this index cannot name it. */
    String chunkIdOf(long id) throws IOException;

    /** How many chunks this instance has indexed. */
    long indexedCount();

    /**
     * Physically removes erased chunks, if this index's policy says it is due.
     *
     * <p>Takes a text supplier because the native index stores postings and not text: the surviving
     * chunks' text is in the storage engine, and asking for it back is what keeps there from being
     * a second copy of the corpus. An index that manages its own merges has nothing to do here and
     * says so rather than pretending to have compacted.</p>
     *
     * <p>A supplier returning null means storage no longer holds that chunk, and the rewrite drops
     * it. That is opportunistic rather than a sweep: finding every such chunk would mean asking
     * storage about all of them, so what gets dropped is what happened to be in a segment being
     * rewritten anyway. A supplier that could not read the chunk throws
     * {@link java.io.UncheckedIOException} rather than answering null, and the rewrite leaves the
     * segments it was reading as they are: dropping postings is permanent, and a read that
     * failed is not evidence the text is gone. A supplier that knows the text can never be read -
     * the stored entry is damaged - throws
     * {@link com.eonmux.excalibur.retrieval.lexical.native_.UnrecoverableTextException}, and the
     * rewrite drops the chunk, logs it and counts it in
     * {@link ChunkIndexStats#unreadableChunksDropped()} rather than abandoning the segments on
     * every attempt.</p>
     *
     * @param textOf Supplies a surviving chunk's text by its identifier
     */
    CompactionResult compactIfDue(Function<String, String> textOf) throws IOException;

    /**
     * As {@link #compactIfDue(Function)}, ending early and publishing nothing once asked to.
     *
     * <p>For a caller that may have to take away what {@code textOf} reads while the compaction
     * runs - a shutdown about to close the storage engine - or that lets an operator cancel. The
     * signal is cooperative, never an interrupt: an interrupt reaching a thread in the middle of
     * file I/O closes the channel it was using, and an index shares its channels with every
     * query. An index honours it where it can stop without leaving anything half-done; by
     * default that is only before the compaction starts.</p>
     *
     * @param textOf        Supplies a surviving chunk's text by its identifier
     * @param stopRequested Answers true once the compaction should end without publishing
     * @return What the compaction did, or why it did nothing
     */
    default CompactionResult compactIfDue(Function<String, String> textOf,
                                          BooleanSupplier stopRequested) throws IOException {
        if (stopRequested.getAsBoolean()) {
            return CompactionResult.stopped("asked to stop before the compaction started");
        }
        return compactIfDue(textOf);
    }

    /**
     * Physically removes erased chunks now, whatever the policy would have decided.
     *
     * <p>What an erasure request made under a deadline needs: the difference between "removal has
     * been scheduled" and "the data is gone" is the whole of what such a caller is asking about.
     * The policy exists because rewriting the index per request would make erasure a denial of
     * service, so this is a caller accepting that cost deliberately.</p>
     *
     * @param textOf Supplies a surviving chunk's text by its identifier
     */
    CompactionResult compactNow(Function<String, String> textOf) throws IOException;

    /**
     * As {@link #compactNow(Function)}, ending early and publishing nothing once asked to.
     *
     * <p>The same cooperative signal as {@link #compactIfDue(Function, BooleanSupplier)}. A
     * forced compaction is what an erasure request made under a deadline runs on the request's
     * own thread, and a shutdown has to be able to end it before closing the storage it reads -
     * the erasure stays owed and is discharged by a later compaction.</p>
     *
     * @param textOf        Supplies a surviving chunk's text by its identifier
     * @param stopRequested Answers true once the compaction should end without publishing
     * @return What the compaction did, or why it did nothing
     */
    default CompactionResult compactNow(Function<String, String> textOf,
                                        BooleanSupplier stopRequested) throws IOException {
        if (stopRequested.getAsBoolean()) {
            return CompactionResult.stopped("asked to stop before the compaction started");
        }
        return compactNow(textOf);
    }

    /**
     * Rewrites every segment of the index now, dropping every chunk whose text is gone.
     *
     * <p>What an operator's forced compaction asks for. {@link #compactNow} discharges erasures
     * and leaves alone a segment with nothing erased in it; the chunks such a segment holds whose
     * text storage no longer has - a document re-crawled or erased in storage without this index
     * being told - are found only by rewriting it, since a rewrite is given the surviving text by
     * {@code textOf} and drops what that no longer supplies.</p>
     *
     * <p>An index that keeps its own copy of each chunk's text has nothing to learn from
     * {@code textOf} and no layout of its own to impose, so by default this is
     * {@link #compactNow}.</p>
     *
     * @param textOf Supplies a surviving chunk's text by its identifier
     */
    default CompactionResult rewriteAll(Function<String, String> textOf) throws IOException {
        return compactNow(textOf);
    }

    /**
     * As {@link #rewriteAll(Function)}, ending early and publishing nothing once asked to.
     *
     * <p>The same cooperative signal as {@link #compactIfDue(Function, BooleanSupplier)}, and
     * the call it matters most for: a rewrite of every segment is the longest thing an index
     * does, and without a way to stop it a shutdown could only wait or close storage under
     * it.</p>
     *
     * @param textOf        Supplies a surviving chunk's text by its identifier
     * @param stopRequested Answers true once the rewrite should end without publishing
     * @return What the rewrite did, or why it did nothing
     */
    default CompactionResult rewriteAll(Function<String, String> textOf,
                                        BooleanSupplier stopRequested) throws IOException {
        if (stopRequested.getAsBoolean()) {
            return CompactionResult.stopped("asked to stop before the rewrite started");
        }
        return rewriteAll(textOf);
    }

    /** This index's retrieval signal, using {@code expander} for the semantic work. */
    default Ranker ranker(QueryExpander expander) {
        return ranker(expander, null);
    }

    /**
     * This index's retrieval signal over the chunks a filter admits.
     *
     * <p>The filter is applied inside the search, where its page is decided, so the page is filled
     * from admitted chunks ranked as an unfiltered search would rank them. Filtering a page after
     * the search comes back short whenever the best matches are refused ones.</p>
     *
     * @param expander The expander doing the semantic work
     * @param admits   Whether a chunk, by its id, may be returned; null admits every chunk
     * @return The ranker
     */
    Ranker ranker(QueryExpander expander, java.util.function.Predicate<String> admits);

    /**
     * One search's fixed view of this index.
     *
     * <p>A search can rank more than once - deeper, past hits it withholds - and it is fused with
     * the vector half of a hybrid answer and stored under a fingerprint taken before it ran. All
     * of that describes one answer only if every ranking reads the same published state: an
     * epoch crossing a publication between two rankings would fill the deeper page from a corpus
     * the first page was not ranked against. The view is what holds that state still.</p>
     *
     * <p>Close it when the search is done; what it holds - a Lucene reader, for one - is
     * released then.</p>
     */
    interface View extends AutoCloseable {

        /**
         * This view's retrieval signal over the chunks a filter admits.
         *
         * @param expander The expander doing the semantic work
         * @param admits   Whether a chunk, by its id, may be returned; null admits every chunk
         * @return A ranker reading only the state this view holds
         */
        Ranker ranker(QueryExpander expander, java.util.function.Predicate<String> admits);

        @Override
        void close();
    }

    /**
     * A view of the state a search started now would read.
     *
     * <p>By default a view of the live index, for an implementation that holds no state a search
     * could read inconsistently.</p>
     *
     * @return The view; close it when the search is done
     */
    default View view() {
        return new View() {
            @Override
            public Ranker ranker(QueryExpander expander,
                                 java.util.function.Predicate<String> admits) {
                return ChunkIndex.this.ranker(expander, admits);
            }

            @Override
            public void close() {
                // Holds nothing.
            }
        };
    }

    /**
     * A view of the state a search reading at a visibility epoch reads.
     *
     * <p>For a search that has to read the same moment of the corpus as something else - the
     * vector half of a hybrid answer, the fingerprint its cache entry is stored under. An index
     * whose publications are not tied to epochs has no state at an epoch to offer, and gives
     * {@link #view()}: the reader it holds for the whole search is its equivalent.</p>
     *
     * @param epoch The visibility epoch the search reads at
     * @return The view; close it when the search is done
     */
    default View view(long epoch) {
        return view();
    }

    /** Which engine backs this index, for startup logging and diagnostics. */
    String engine();

    /**
     * What this index will say about itself, for {@code GET /v1/stats}.
     *
     * <p>Read from state the index already holds, so it is safe to call on a request path.</p>
     */
    ChunkIndexStats stats();

    /**
     * Which published state of the index a search started now would read.
     *
     * <p>For a cache of answers, which may replay an earlier answer only to a query that would
     * read the same state. The version has to be read through what a search reads rather than
     * from anything that merely describes writes: a publication here becomes visible at a
     * boundary - a refresh, or the query epoch crossing the epoch it was published at - that
     * no write is observed making at the moment it takes effect.</p>
     *
     * <p>Empty means this index cannot say, and nothing answered from it may be cached; that
     * is the default, so an index that has not worked out what its searches read is never
     * assumed to be unchanged.</p>
     *
     * @return A value that changes whenever a search's answer can, or empty when this index
     *         cannot tell
     */
    default OptionalLong searchVersion() {
        return OptionalLong.empty();
    }

    /**
     * Which published state of the index a search reading at a visibility epoch would read.
     *
     * <p>As {@link #searchVersion()}, for a search whose view was fixed at that epoch - see
     * {@link #view(long)} - so the fingerprint and the search describe the same state. An index
     * whose publications are not tied to epochs answers {@link #searchVersion()}.</p>
     *
     * @param epoch The visibility epoch the search reads at
     * @return A value that changes whenever such a search's answer can, or empty when this index
     *         cannot tell
     */
    default OptionalLong searchVersion(long epoch) {
        return searchVersion();
    }

    @Override
    void close();
}
