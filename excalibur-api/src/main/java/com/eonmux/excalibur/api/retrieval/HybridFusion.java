package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;
import com.eonmux.excalibur.retrieval.fusion.ReciprocalRankFusion;
import com.eonmux.excalibur.retrieval.fusion.SignalContribution;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fuses the two answers {@code /v1/query} can produce for the same question.
 *
 * <h2>Why both</h2>
 * <p>A node with an embedding model indexes its chunks twice: into the HNSW graphs vector search
 * reads, and into the inverted index BM25 reads. The second one is written, compacted and
 * erasure-swept whether or not a query consults it. Answering from vectors alone therefore pays
 * the full price of the lexical index and takes none of its recall - and what it misses is the
 * part a dense vector is worst at: an identifier, a version string, an error code, a name that
 * appears nowhere in the model's training distribution.</p>
 *
 * <h2>What it fuses on</h2>
 * <p>Rank, not score, through {@link ReciprocalRankFusion}. A BM25 score of 31.7 and a cosine
 * similarity of 0.91 have no common scale, and giving them one - normalisation, weights - is a
 * calibration that goes stale as the corpus changes. A document both signals return therefore
 * outranks one a single signal loves, which is the behaviour hybrid retrieval exists to get.</p>
 *
 * <h2>The one thing both paths must agree on</h2>
 * <p>The identifier. Vector search reports the {@code source:document:chunk:N} string ingestion
 * assigned and the lexical index reports the same one, which is what makes a document returned
 * by both fusible rather than counted twice. Nothing here reconciles two id spaces, because
 * there is only one.</p>
 *
 * <p>The exception is a vector result the pipeline could not name - a graph with no
 * node-document mapping, or a write-log entry with an empty key. Such a result has no external
 * identifier, only a node id, and node ids are per-segment: two of them can be the same number
 * and different documents. Those are keyed by segment and node instead, so that turning fusion
 * on never merges two answers vector search alone would have returned separately. They can
 * never match a lexical hit, which is correct - nothing knows what they are.</p>
 */
public final class HybridFusion {

    /** The signal name vector search reports under. */
    public static final String DENSE_SIGNAL = "vector";

    /** The signal name the inverted index reports under. */
    public static final String LEXICAL_SIGNAL = "bm25";

    private HybridFusion() {
    }

    /**
     * One result after fusion.
     *
     * @param documentId The chunk identifier, as both signals report it
     * @param score      The fused score: a sum of reciprocal ranks, not a similarity
     * @param segmentId  The vector segment that held it, or null when only BM25 returned it -
     *                   a BM25 hit was never in a vector segment, and naming one would invent it
     * @param signals    The signals that returned it, best rank first
     */
    public record Result(String documentId, double score, Long segmentId, List<String> signals) {

        public Result {
            if (documentId == null || documentId.isEmpty()) {
                throw new IllegalArgumentException("documentId must not be empty");
            }
            signals = List.copyOf(signals);
        }
    }

    /**
     * Fuses a vector ranking and a lexical ranking into one.
     *
     * <p>Either signal may be absent or empty: a missing signal is one that found nothing, and
     * fusing one list with nothing returns that list's own order. That is what makes a lexical
     * index that failed to open, or a query no term matched, a weaker answer rather than no
     * answer.</p>
     *
     * @param dense   Vector search results, best first, or null if that signal did not run
     * @param lexical BM25 results, best first, or null if that signal did not run
     * @param limit   Most results to return
     * @return The fused ranking, best first, never null
     * @throws IllegalArgumentException if limit is negative
     */
    public static List<Result> fuse(List<QueryResultItem> dense,
                                    List<LexicalQueryService.Hit> lexical,
                                    int limit) {
        return fuse(dense, lexical, limit, ReciprocalRankFusion.DEFAULT_K);
    }

    /**
     * Fuses a vector ranking and a lexical ranking into one, with a given rank offset.
     *
     * @param dense   Vector search results, best first, or null if that signal did not run
     * @param lexical BM25 results, best first, or null if that signal did not run
     * @param limit   Most results to return
     * @param rrfK    Reciprocal rank fusion's rank offset, from
     *                {@code excalibur.query.rrf-k}
     * @return The fused ranking, best first, never null
     * @throws IllegalArgumentException if limit is negative or the offset is not positive
     */
    public static List<Result> fuse(List<QueryResultItem> dense,
                                    List<LexicalQueryService.Hit> lexical,
                                    int limit,
                                    int rrfK) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative, got " + limit);
        }
        if (limit == 0) {
            return List.of();
        }

        // Fusion works on integer ids, and both signals name documents differently. The
        // mapping is per-call and dense-first, so a tie in the fused score breaks towards the
        // vector signal's order rather than towards whatever a hash happened to produce.
        Map<Key, Long> idOf = new HashMap<>();
        List<String> byId = new ArrayList<>();
        Map<Long, Long> segmentOf = new HashMap<>();

        List<ScoredDocument> denseDocuments = new ArrayList<>();
        if (dense != null) {
            for (QueryResultItem item : dense) {
                long id = intern(keyOf(item), item.externalId(), idOf, byId);
                denseDocuments.add(new ScoredDocument(id, item.score()));
                // First occurrence wins: the list is ordered best first, so that is the segment
                // the strongest match for this document came from.
                segmentOf.putIfAbsent(id, item.segmentId());
            }
        }

        List<ScoredDocument> lexicalDocuments = new ArrayList<>();
        if (lexical != null) {
            for (LexicalQueryService.Hit hit : lexical) {
                lexicalDocuments.add(new ScoredDocument(
                    intern(new Key(hit.chunkId(), 0L, 0L), hit.chunkId(), idOf, byId),
                    hit.score()));
            }
        }

        if (denseDocuments.isEmpty() && lexicalDocuments.isEmpty()) {
            return List.of();
        }

        // The lists arrive already ordered by their own signal, so their positions are the ranks.
        // Re-sorting them on score would reorder the dense list by similarity after the pipeline
        // has already reranked it, which would discard the LLM scoring stage's work.
        List<RankedList> lists = new ArrayList<>(2);
        lists.add(new RankedList(DENSE_SIGNAL, denseDocuments));
        lists.add(new RankedList(LEXICAL_SIGNAL, lexicalDocuments));

        List<FusedDocument> fused = new ReciprocalRankFusion(rrfK).fuse(lists, limit);
        List<Result> results = new ArrayList<>(fused.size());
        for (FusedDocument document : fused) {
            String documentId = byId.get((int) document.documentId());
            List<String> signals = new ArrayList<>(document.contributions().size());
            for (SignalContribution contribution : document.contributions()) {
                signals.add(contribution.rankerName());
            }
            results.add(new Result(documentId, document.score(),
                segmentOf.get(document.documentId()), List.copyOf(signals)));
        }
        return List.copyOf(results);
    }

    /**
     * What makes two results the same result.
     *
     * <p>{@code documentId} alone when the pipeline named the document, because then the two
     * signals can agree about it and a chunk indexed into two segments is still one answer.
     * The segment and node when it did not, because a node id is only unique within its
     * segment and the number alone would merge unrelated documents.</p>
     *
     * @param documentId The external identifier, or null for a result that has none
     * @param segmentId  The segment, meaningful only when documentId is null
     * @param nodeId     The node, meaningful only when documentId is null
     */
    private record Key(String documentId, long segmentId, long nodeId) {
    }

    /**
     * The identity of one vector result.
     *
     * <p>A result the pipeline could not name carries its node id as its numeric id, so the
     * numeric id is not a document identifier and must not be used as one.</p>
     */
    private static Key keyOf(QueryResultItem item) {
        String documentIdStr = item.documentIdStr();
        return documentIdStr != null && !documentIdStr.isEmpty()
            ? new Key(documentIdStr, 0L, 0L)
            : new Key(null, item.segmentId(), item.nodeId());
    }

    /**
     * Assigns a document its integer id, keeping the order it was first seen in.
     *
     * @param key        What makes this result distinct from another
     * @param documentId How the result is named back to the caller
     * @param idOf       Keys already assigned an id
     * @param byId       Display identifiers, indexed by assigned id
     * @return The integer id fusion works on
     */
    private static long intern(Key key, String documentId, Map<Key, Long> idOf,
                               List<String> byId) {
        Long existing = idOf.get(key);
        if (existing != null) {
            return existing;
        }
        long id = byId.size();
        idOf.put(key, id);
        byId.add(documentId);
        return id;
    }
}
