package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.core.hash.XxHash64;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.rank.Ranker;

import java.io.IOException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
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
     * rewritten anyway.</p>
     *
     * @param textOf Supplies a surviving chunk's text by its identifier
     */
    CompactionResult compactIfDue(Function<String, String> textOf) throws IOException;

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

    /** This index's retrieval signal, using {@code expander} for the semantic work. */
    Ranker ranker(QueryExpander expander);

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

    @Override
    void close();
}
