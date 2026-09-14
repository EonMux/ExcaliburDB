package excalibur.api.retrieval;

import excalibur.ingest.lexical.ChunkIndex;
import io.excalibur.retrieval.HybridRetriever;
import io.excalibur.retrieval.expand.QueryExpander;
import io.excalibur.retrieval.fusion.FusedDocument;
import io.excalibur.retrieval.fusion.ReciprocalRankFusion;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rank.RetrievalQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 */
public final class LexicalQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalQueryService.class);

    /** One retrieved chunk. */
    public record Hit(String chunkId, double score) { }

    private final ChunkIndex chunks;
    private final HybridRetriever retriever;
    private final int rrfK;

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
        List<Ranker> rankers = new ArrayList<>();
        // The index supplies its own signal: the two engines search differently enough that
        // asking either to expose the other's query type would mean writing a translation layer
        // whose only job is to be wrong somewhere.
        rankers.add(chunks.ranker(expander == null ? QueryExpander.identity() : expander));
        if (extra != null) {
            rankers.addAll(extra);
        }
        // Reciprocal rank fusion: signals score on scales that are not comparable, so their
        // ranks are fused rather than their scores.
        this.retriever = new HybridRetriever(rankers, new ReciprocalRankFusion(rrfK),
            perSignalOverFetch);
        LOGGER.info("Lexical query service ready with {} signal(s) over the {} engine "
            + "(rrf k={}, per-signal over-fetch={})",
            rankers.size(), chunks.engine(), rrfK, perSignalOverFetch);
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
     * The index's own account of itself.
     *
     * <p>What a query covered is derived from this rather than assumed, so an answer from an
     * empty or partial index is not reported as an answer from the whole corpus.</p>
     *
     * @return The stats the index reports
     */
    public excalibur.ingest.lexical.ChunkIndexStats stats() {
        return chunks.stats();
    }

    /**
     * Retrieves chunk identifiers for a query.
     *
     * @param queryText The user's query
     * @param limit     Most hits to return
     * @return Hits, best first
     */
    public List<Hit> search(String queryText, int limit) {
        if (queryText == null || queryText.isBlank() || limit <= 0) {
            return List.of();
        }
        List<FusedDocument> fused = retriever.retrieve(RetrievalQuery.ofText(queryText), limit);
        List<Hit> hits = new ArrayList<>(fused.size());
        for (FusedDocument document : fused) {
            String chunkId = resolve(document.documentId());
            if (chunkId != null) {
                hits.add(new Hit(chunkId, document.score()));
            }
        }
        return hits;
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
