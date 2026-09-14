package io.excalibur.retrieval.rerank;

import java.util.ArrayList;
import java.util.List;

import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.retrieval.rank.ScoredDocument;
import io.excalibur.vector.distance.DistanceFunction;

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
 */
public final class FullPrecisionReranker implements Ranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(FullPrecisionReranker.class);

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

        List<ScoredDocument> rescored = new ArrayList<>(coarse.size());
        int missing = 0;
        for (ScoredDocument candidate : coarse.documents()) {
            float[] vector = vectors.vectorOf(candidate.documentId());
            if (vector == null) {
                // No stored vector: the candidate came from a signal that does not use vector
                // space. Dropping it would silently make this a vector-only ranker. Keep the
                // approximate score, which is the best evidence available for it.
                rescored.add(candidate);
                missing++;
                continue;
            }
            if (vector.length != queryVector.length) {
                throw new IllegalStateException("document " + candidate.documentId()
                    + " has a " + vector.length + "-dimension vector but the query has "
                    + queryVector.length);
            }
            // distance() is a monotone dissimilarity for every metric, so negating it yields a
            // higher-is-better score without needing to know which metric this is. The SIMD loop
            // lives inside the distance function, with its own scalar fallback.
            rescored.add(new ScoredDocument(candidate.documentId(),
                -distanceFunction.distance(queryVector, vector)));
        }

        if (missing > 0) {
            LOGGER.debug("{}: {} of {} candidates had no stored vector and kept their approximate "
                + "score", name, missing, coarse.size());
        }
        return RankedList.of(name, rescored, limit);
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
