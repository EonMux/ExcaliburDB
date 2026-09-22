package com.eonmux.excalibur.retrieval.rerank;

import java.util.ArrayList;
import java.util.List;

import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;
import com.eonmux.excalibur.vector.distance.DistanceFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-scores an approximate ranker's output against uncompressed vectors.
 *
 * <p>Product quantisation makes a vector index fit in memory by storing an approximation, and that
 * approximation is good enough to decide which few hundred documents are worth considering. It is
 * not good enough to decide which of those is best: quantisation error is on the order of the
 * differences between the top candidates, so the ordering within the top-k is close to arbitrary.
 * Reading the real vectors for a few hundred documents costs a few hundred sequential reads and
 * fixes exactly that.
 *
 * <p>Which is why it over-fetches. Asking the approximate index for {@code k} and reranking those
 * cannot recover a document the approximation ranked at {@code k+1} — the error that reranking
 * exists to correct is the same error that would have excluded it. Fetching {@code k * factor} and
 * keeping the best {@code k} gives the correction something to work with.
 *
 * <p>A decorator over another {@link Ranker}, so it composes: the thing it wraps can be a dense
 * index today and a fused hybrid tomorrow without this class changing.
 *
 * <h2>One scale</h2>
 * Every score in the output is on one scale, so a threshold on the top score or on the gap below it
 * reads the same evidence whichever candidates happened to be in the list:
 * <ul>
 *   <li>A candidate with a stored vector scores its <em>similarity</em> to the query, higher
 *       meaning closer: the metric's own value for a metric that is already a similarity (cosine,
 *       in {@code [-1, 1]}; inner product, which is cosine on the unit vectors the embedding
 *       endpoint returns), and {@code 1 / (1 + d)} for a distance {@code d} (L2, in
 *       {@code (0, 1]}, one for an exact match). Negating a distance would order the same way and
 *       give a score that is negative for every candidate, which no threshold written as "at least
 *       this relevant" can be set against.</li>
 *   <li>A candidate with no stored vector was reached by a signal that does not use vector space,
 *       and has no similarity to report. Its approximate score is on the delegate's scale - BM25,
 *       say - and ranking it against similarities would compare two different quantities as though
 *       they were one: a lexical score of 7 outranks every cosine there is. So it ranks beneath
 *       every candidate that was measured, in the order the delegate gave it - kept, because
 *       dropping it would quietly make a hybrid ranker vector-only, but never promoted over
 *       evidence it was not compared with. Its score is its own, capped just below the lowest
 *       measured similarity: evidence the delegate found stays positive, so a threshold reads it
 *       as weak rather than as none, and one the delegate scored at nothing stays at nothing.</li>
 *   <li>When no candidate has a vector there is nothing to rescore and the delegate's list is the
 *       answer, on the delegate's own scale and in its order.</li>
 * </ul>
 */
public final class FullPrecisionReranker implements Ranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(FullPrecisionReranker.class);

    /** Fraction each unmeasured candidate is shaved by per place, to keep the delegate's order. */
    private static final double UNMEASURED_SLIVER = 1e-6;

    /**
     * Over-fetch multiplier, per the retrieval design: ask for four times what is wanted.
     *
     * <p>Four is a deliberate middle. Below it, too few of the documents that quantisation
     * mis-ranked are in the candidate set for reranking to reach them; well above it, the extra
     * candidates are increasingly documents the approximation was right to reject, and each one
     * still costs a full-precision vector read.
     *
     * <p>A deployment that has measured the trade for its own corpus sets it with
     * {@code excalibur.graph.rerank-over-fetch} and passes the value to the four-argument
     * constructor.
     */
    public static final int DEFAULT_OVER_FETCH_FACTOR = 4;

    private final Ranker delegate;
    private final VectorSource vectors;
    private final DistanceFunction distanceFunction;
    private final int overFetchFactor;
    private final String name;

    /** A reranker over {@code delegate} at the default over-fetch. */
    public FullPrecisionReranker(Ranker delegate, VectorSource vectors,
                                 DistanceFunction distanceFunction) {
        this(delegate, vectors, distanceFunction, DEFAULT_OVER_FETCH_FACTOR);
    }

    /**
     * @param delegate         The approximate ranker whose ordering is being corrected
     * @param vectors          Where the uncompressed vectors are read from
     * @param distanceFunction The metric the vectors are compared under
     * @param overFetchFactor  Multiplies the limit to give the candidate set, from
     *                         {@code excalibur.graph.rerank-over-fetch}
     */
    public FullPrecisionReranker(Ranker delegate, VectorSource vectors,
                                 DistanceFunction distanceFunction, int overFetchFactor) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (vectors == null) {
            throw new IllegalArgumentException("vectors must not be null");
        }
        if (distanceFunction == null) {
            throw new IllegalArgumentException("distanceFunction must not be null");
        }
        if (overFetchFactor < 1) {
            throw new IllegalArgumentException(
                "overFetchFactor must be at least 1, got " + overFetchFactor
                    + "; a factor below 1 would fetch fewer candidates than are wanted");
        }
        this.delegate = delegate;
        this.vectors = vectors;
        this.distanceFunction = distanceFunction;
        this.overFetchFactor = overFetchFactor;
        this.name = delegate.name() + "+exact";
    }

    @Override
    public String name() {
        return name;
    }

    /** The over-fetch multiplier in use. */
    public int overFetchFactor() {
        return overFetchFactor;
    }

    @Override
    public RankedList rank(RetrievalQuery query, int limit) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (limit <= 0) {
            return RankedList.empty(name);
        }
        float[] queryVector = query.requireEmbedding(name);

        int overFetch = overFetch(limit);
        RankedList coarse = delegate.rank(query, overFetch);
        if (coarse.isEmpty()) {
            return RankedList.empty(name);
        }

        List<ScoredDocument> measured = new ArrayList<>(coarse.size());
        List<ScoredDocument> unmeasured = new ArrayList<>();
        for (ScoredDocument candidate : coarse.documents()) {
            float[] vector = vectors.vectorOf(candidate.documentId());
            if (vector == null) {
                // No stored vector: the candidate came from a signal that does not use vector
                // space. Kept, beneath the measured ones - see the class documentation.
                unmeasured.add(candidate);
                continue;
            }
            if (vector.length != queryVector.length) {
                throw new IllegalStateException("document " + candidate.documentId()
                    + " has a " + vector.length + "-dimension vector but the query has "
                    + queryVector.length);
            }
            // The SIMD loop lives inside the distance function, with its own scalar fallback.
            measured.add(
                new ScoredDocument(candidate.documentId(), similarity(queryVector, vector)));
        }

        if (measured.isEmpty()) {
            return RankedList.of(name, coarse.documents(), limit);
        }
        if (!unmeasured.isEmpty()) {
            LOGGER.debug("{}: {} of {} candidates had no stored vector and rank beneath the {} "
                + "that did", name, unmeasured.size(), coarse.size(), measured.size());
        }
        return RankedList.of(name, beneath(measured, unmeasured), limit);
    }

    /**
     * How similar a document's vector is to the query's, higher meaning closer.
     *
     * <p>A similarity metric's value is already that. A distance maps through
     * {@code 1 / (1 + d)}, which keeps its order, is one for an exact match and falls towards zero
     * with distance - the same reading a similarity has, without assuming a bound on how far apart
     * two vectors can be.
     */
    private double similarity(float[] query, float[] document) {
        float value = distanceFunction.compute(query, document);
        return distanceFunction.ordering() == DistanceFunction.Ordering.LARGER_IS_CLOSER
            ? value
            : 1.0 / (1.0 + value);
    }

    /**
     * The measured candidates, then the unmeasured ones scored beneath them.
     *
     * <p>Each unmeasured candidate keeps its own score, capped below the lowest similarity and
     * shaved by a sliver per place so the delegate's order among them survives the sort
     * {@link RankedList} applies. The delegate's list is best first, so capping keeps it in order.
     * When no measured candidate is above zero there is no room beneath it on the positive side,
     * and the unmeasured ones go one below it: nothing measured cleared zero, so nothing
     * unmeasured is presented as clearing it.
     */
    private static List<ScoredDocument> beneath(List<ScoredDocument> measured,
                                                 List<ScoredDocument> unmeasured) {
        double lowest = Double.POSITIVE_INFINITY;
        for (ScoredDocument document : measured) {
            lowest = Math.min(lowest, document.score());
        }
        List<ScoredDocument> ordered = new ArrayList<>(measured.size() + unmeasured.size());
        ordered.addAll(measured);
        int place = 0;
        for (ScoredDocument document : unmeasured) {
            place++;
            double score = lowest > 0
                ? Math.min(document.score(), lowest) * (1.0 - place * UNMEASURED_SLIVER)
                : lowest - 1.0 - place * UNMEASURED_SLIVER;
            ordered.add(new ScoredDocument(document.documentId(), score));
        }
        return ordered;
    }

    /**
     * How many candidates to ask the delegate for.
     *
     * <p>Saturates rather than overflowing: a caller asking for a very large limit would otherwise
     * multiply into a negative number and request nothing at all.
     */
    private int overFetch(int limit) {
        long requested = (long) limit * overFetchFactor;
        return requested > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) requested;
    }
}
