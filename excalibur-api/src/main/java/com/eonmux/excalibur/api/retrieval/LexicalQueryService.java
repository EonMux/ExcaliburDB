package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.core.text.ChunkSources;
import com.eonmux.excalibur.core.text.SourceScope;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.query.visibility.DocumentAtomicityFilter;
import com.eonmux.excalibur.retrieval.HybridRetriever;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;
import com.eonmux.excalibur.retrieval.fusion.ReciprocalRankFusion;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Retrieval over the document corpus that needs no embedding of it.
 *
 * <h2>What this is for</h2>
 * <p>{@code POST /v1/query} was vector search and only vector search, which made a corpus-wide
 * embedding pass the price of any retrieval at all. For a corpus of titles, identifiers, DOIs and
 * error codes that is both the most expensive way to index it and the least accurate way to search
 * it - exact terms are what BM25 answers well and what a dense vector blurs.</p>
 *
 * <p>The semantic work moves to the query: an expander turns one sentence into the vocabulary a
 * matching document would actually contain, and the inverted index does the retrieval. That is a
 * few dozen generated tokens per query against billions of embedded tokens per corpus.</p>
 *
 * <h2>Degradation</h2>
 * <p>Every stage is optional beneath the one above it. No expander is plain BM25. No vector signal
 * is lexical-only fusion. The service answers as well as its available signals allow rather than
 * refusing when one is missing.</p>
 *
 * <h2>Documents being written</h2>
 * <p>The index publishes a version's postings at a refresh and erases a retired chunk at once,
 * while storage is rewritten in place one chunk at a time. Its postings alone therefore name
 * chunks of a document storage holds part of each version of - the previous version's while the
 * next is written over it, and a shrunk version's survivors once its tail is erased and before it
 * is published. Given the node's document visibility checker ({@link #withVisibility}), a search
 * withholds every hit of a document the checker withholds, by the rule the vector pipeline's
 * atomicity filter applies: in flight, or published after the search began, is withheld whole;
 * never heard of passes.</p>
 *
 * <p>Withholding does not shorten an answer while visible matches remain below it. Callers read a
 * short answer as the end of the matches - the query route reports an exact total from it and a
 * caller paging by offset stops there - so a search that withheld hits ranks deeper and fills the
 * answer from below them, and comes back short only when the index itself ran out. How much
 * deeper grows with the hits withheld, at least doubling each time, so the extra work is bounded by
 * how much of the corpus is being written at that moment and never by the size of the corpus.</p>
 *
 * <h2>Searching some sources</h2>
 * <p>A search scoped to some sources ({@link #search(String, int, SourceScope)}) hands the index a
 * filter on chunk ids, which it applies where its ranking is decided, so the scoped page is
 * filled from the sources' own chunks ranked as an unscoped search ranks them. Which source a
 * chunk is from is the node's document registry's to say ({@link #withChunkSources}), because a
 * source id may contain a colon and the chunk id alone cannot. The same check is applied again to
 * the fused ranking, which carries any additional signal the index's filter does not reach, and a
 * hit it refuses is withheld like one of a document being written.</p>
 */
public final class LexicalQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalQueryService.class);

    /** One retrieved chunk. */
    public record Hit(String chunkId, double score) { }

    private final ChunkIndex chunks;
    private final HybridRetriever retriever;
    private final int rrfK;

    /** The expander the index's ranker widens queries with, for a scoped ranker over it. */
    private final QueryExpander widening;

    /** The signals fused beside the index's own, possibly none. */
    private final List<Ranker> extra;

    /** How many times the limit each signal contributes, for a scoped fusion of them. */
    private final int perSignalOverFetch;

    /** Withholds the documents it withholds, or null to answer from the postings alone. */
    private final DocumentVisibilityChecker visibility;

    /** Names each chunk's source for a scoped search, or null to refuse one. */
    private final ChunkSources sources;


    /**
     * Creates the service.
     *
     * @param chunks   The lexical index over ingested chunks
     * @param expander Query expander, or null for plain BM25
     * @param extra    Additional signals to fuse (vector, graph), possibly empty
     */
    public LexicalQueryService(ChunkIndex chunks, QueryExpander expander,
                               List<Ranker> extra) {
        this(chunks, expander, extra, ReciprocalRankFusion.DEFAULT_K,
            HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH);
    }

    /**
     * Creates the service with the fusion settings this node is configured for.
     *
     * @param chunks             The lexical index over ingested chunks
     * @param expander           Query expander, or null for plain BM25
     * @param extra              Additional signals to fuse (vector, graph), possibly empty
     * @param rrfK               Reciprocal rank fusion's rank offset,
     *                           {@code excalibur.query.rrf-k}
     * @param perSignalOverFetch How many times the limit each signal contributes,
     *                           {@code excalibur.query.per-signal-over-fetch}
     */
    public LexicalQueryService(ChunkIndex chunks, QueryExpander expander,
                               List<Ranker> extra, int rrfK, int perSignalOverFetch) {
        this.chunks = Objects.requireNonNull(chunks, "chunks must not be null");
        this.rrfK = rrfK;
        this.widening = expander == null ? QueryExpander.identity() : expander;
        this.extra = extra == null ? List.of() : List.copyOf(extra);
        this.perSignalOverFetch = perSignalOverFetch;
        this.retriever = retrieverOver(null);
        this.visibility = null;
        this.sources = null;
        LOGGER.info("Lexical query service ready with {} signal(s) over the {} engine "
            + "(rrf k={}, per-signal over-fetch={})",
            retriever.rankers().size(), chunks.engine(), rrfK, perSignalOverFetch);
    }

    private LexicalQueryService(LexicalQueryService base, DocumentVisibilityChecker visibility,
                                ChunkSources sources) {
        this.chunks = base.chunks;
        this.retriever = base.retriever;
        this.rrfK = base.rrfK;
        this.widening = base.widening;
        this.extra = base.extra;
        this.perSignalOverFetch = base.perSignalOverFetch;
        this.visibility = visibility;
        this.sources = sources;
    }

    /**
     * The fusion of the index's own signal, over the chunks a filter admits, with the others.
     *
     * @param admits Whether a chunk may be returned, or null for every chunk
     * @return The retriever
     */
    private HybridRetriever retrieverOver(java.util.function.Predicate<String> admits) {
        List<Ranker> rankers = new ArrayList<>(1 + extra.size());
        // The index supplies its own signal: the two engines search differently enough that
        // asking either to expose the other's query type would mean writing a translation layer
        // whose only job is to be wrong somewhere.
        rankers.add(chunks.ranker(widening, admits));
        rankers.addAll(extra);
        // Reciprocal rank fusion: signals score on scales that are not comparable, so their
        // ranks are fused rather than their scores.
        return new HybridRetriever(rankers, new ReciprocalRankFusion(rrfK), perSignalOverFetch);
    }

    /**
     * This service, withholding what a document visibility checker withholds.
     *
     * <p>A copy sharing this one's index and signals, so the node can build the service when it
     * opens the index and hand it the checker once the query pipeline has one - the same checker,
     * so the two halves of a fused answer withhold the same documents.</p>
     *
     * @param checker The node's document visibility checker, or null to withhold nothing
     * @return The service to search through
     */
    public LexicalQueryService withVisibility(DocumentVisibilityChecker checker) {
        return checker == visibility ? this : new LexicalQueryService(this, checker, sources);
    }

    /**
     * This service, able to search some sources rather than all of them.
     *
     * <p>A copy sharing this one's index and signals, handed the attribution the query pipeline
     * was given - so the two halves of a fused answer place each chunk in the same source.</p>
     *
     * @param chunkSources Names each chunk's source, or null to refuse a scoped search
     * @return The service to search through
     */
    public LexicalQueryService withChunkSources(ChunkSources chunkSources) {
        return chunkSources == sources ? this
            : new LexicalQueryService(this, visibility, chunkSources);
    }

    /**
     * Reciprocal rank fusion's rank offset, as this node is configured.
     *
     * <p>Read back so that the two-signal fusion on the query route uses the same offset as
     * the fusion inside this service; two different offsets would rank the same pair of
     * signals two different ways depending on which path answered.</p>
     *
     * @return the configured offset
     */
    public int rrfK() {
        return rrfK;
    }

    /**
     * Which state of the index a search started now would read.
     *
     * <p>What lets a lexical answer be cached at all. The index publishes new chunks, applies
     * erasures and swaps compacted segments without a write this service sees, so there is no
     * mutation to invalidate on; what there is instead is the index's own account of what a
     * search would read. Two equal values mean a search would read the same postings, and an
     * answer computed at one is an answer at the other.</p>
     *
     * @return The version, or empty when this index cannot say - in which case nothing that
     *         read it may be cached
     */
    public OptionalLong searchVersion() {
        return chunks.searchVersion();
    }

    /**
     * The index's own account of itself.
     *
     * <p>What a query covered is derived from this rather than assumed, so an answer from an
     * empty or partial index is not reported as an answer from the whole corpus.</p>
     *
     * @return The stats the index reports
     */
    public com.eonmux.excalibur.ingest.lexical.ChunkIndexStats stats() {
        return chunks.stats();
    }

    /**
     * Retrieves chunk identifiers for a query.
     *
     * <p>With a visibility checker, hits of a document it withholds are left out and the answer
     * is filled from below them; see the class documentation. The epoch they are judged at is
     * read once, before the first ranking, so a search that ranks deeper judges every hit
     * against the same moment.</p>
     *
     * @param queryText The user's query
     * @param limit     Most hits to return
     * @return Hits, best first; fewer than {@code limit} only when no more visible ones match
     */
    public List<Hit> search(String queryText, int limit) {
        return search(queryText, limit, SourceScope.ALL);
    }

    /**
     * Retrieves chunk identifiers for a query, from the chunks of some sources.
     *
     * @param queryText The user's query
     * @param limit     Most hits to return
     * @param scope     The sources to answer from, {@link SourceScope#ALL} for every source
     * @return Hits, best first, every one from a source the scope names; fewer than
     *         {@code limit} only when no more visible ones from those sources match
     * @throws IllegalStateException if the search is scoped and this service has nothing to
     *                               attribute chunks with - see {@link #withChunkSources}
     */
    public List<Hit> search(String queryText, int limit, SourceScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        if (queryText == null || queryText.isBlank() || limit <= 0) {
            return List.of();
        }
        if (!scope.isUnrestricted() && sources == null) {
            throw new IllegalStateException("this node cannot attribute chunks to sources, so it "
                + "cannot answer a search scoped to " + scope.sourceIds());
        }
        RetrievalQuery query = RetrievalQuery.ofText(queryText);
        java.util.function.Predicate<String> admits = scope.admitting(sources);
        HybridRetriever scoped = admits == null ? retriever : retrieverOver(admits);
        DocumentVisibilityChecker checker = visibility;
        boolean atomic = DocumentAtomicityFilter.appliesTo(checker);
        DocumentAtomicityFilter filter = atomic ? new DocumentAtomicityFilter(checker) : null;
        long bound = atomic
            ? DocumentAtomicityFilter.boundFor(checker, checker.currentEpoch()) : 0L;
        if (filter == null && admits == null) {
            return visible(scoped.retrieve(query, limit), limit, null, 0L, null, new HashMap<>())
                .hits();
        }
        Map<Long, String> names = new HashMap<>();
        int depth = limit;
        while (true) {
            List<FusedDocument> fused = scoped.retrieve(query, depth);
            Ranked ranked = visible(fused, limit, filter, bound, admits, names);
            // Short of the limit with hits withheld, and the index not yet run dry: rank
            // deeper. Hits that could not be named do not count - an index failing to resolve
            // would otherwise be walked to its end.
            if (ranked.hits().size() == limit || ranked.withheld() == 0
                    || fused.size() < depth || depth == Integer.MAX_VALUE) {
                if (ranked.withheld() > 0) {
                    LOGGER.debug("Withheld {} hit(s) of documents being written or outside {}; "
                        + "ranked {} deep for {} visible", ranked.withheld(), scope, depth,
                        ranked.hits().size());
                }
                return ranked.hits();
            }
            depth = deeper(depth, ranked.withheld());
        }
    }

    /**
     * One ranking, named and filtered, cut at the limit.
     *
     * @param hits     The visible hits, best first, at most the limit
     * @param withheld How many hits above the cut were of documents the checker withholds
     */
    private record Ranked(List<Hit> hits, int withheld) {
    }

    /**
     * Names a ranking's hits, drops the ones the filter withholds, and cuts it at the limit.
     *
     * @param fused  The ranking
     * @param limit  Most hits to keep
     * @param filter The filter, or null to keep every named hit
     * @param bound  The epoch the filter judges at
     * @param admits Whether a hit is from a source the search is scoped to, or null for every
     *               source
     * @param names  Engine ids already named during this search, so a deeper ranking does not
     *               resolve the same hit twice
     * @return The hits kept and how many were withheld
     */
    private Ranked visible(List<FusedDocument> fused, int limit, DocumentAtomicityFilter filter,
                           long bound, java.util.function.Predicate<String> admits,
                           Map<Long, String> names) {
        List<Hit> hits = new ArrayList<>(Math.min(fused.size(), limit));
        int withheld = 0;
        for (FusedDocument document : fused) {
            long id = document.documentId();
            String chunkId = names.containsKey(id) ? names.get(id) : resolve(id);
            names.put(id, chunkId);
            if (chunkId == null) {
                continue;
            }
            if (filter != null && !filter.isChunkVisible(chunkId, bound)
                    || admits != null && !admits.test(chunkId)) {
                withheld++;
                continue;
            }
            hits.add(new Hit(chunkId, document.score()));
            if (hits.size() == limit) {
                break;
            }
        }
        return new Ranked(hits, withheld);
    }

    /**
     * How deep to rank next: at least twice as deep, and past every hit withheld so far.
     *
     * @param depth    How deep the last ranking went
     * @param withheld How many of its hits were withheld
     * @return The next depth, saturated at {@link Integer#MAX_VALUE}
     */
    private static int deeper(int depth, int withheld) {
        long next = (long) depth + Math.max(depth, withheld);
        return next > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) next;
    }

    /**
     * Maps an engine id back to the chunk identifier a caller can use.
     *
     * <p>A hit that cannot be resolved is dropped rather than reported under its internal id: the
     * numeric id is a hash and means nothing outside this process.</p>
     */
    private String resolve(long documentId) {
        try {
            return chunks.chunkIdOf(documentId);
        } catch (IOException e) {
            LOGGER.debug("Could not resolve document {}: {}", documentId, e.getMessage());
            return null;
        }
    }

    /**
     * How many chunks this node has handed to the index since it opened.
     *
     * <p>Not the size of the corpus: an index reopened over a populated store has been handed
     * nothing yet and answers zero. {@link #stats()} reports the searchable count.</p>
     */
    public long indexedChunks() {
        return chunks.indexedCount();
    }
}
