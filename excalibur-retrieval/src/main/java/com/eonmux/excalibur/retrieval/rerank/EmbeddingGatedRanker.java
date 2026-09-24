package com.eonmux.excalibur.retrieval.rerank;

import com.eonmux.excalibur.core.util.QueryBudget;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Reranks against vectors when the query has one, and does not when it has none.
 *
 * <h2>Why this exists</h2>
 * <p>{@link FullPrecisionReranker} requires the query to carry an embedding and throws when it does
 * not - correctly, because a reranker that silently did nothing would be indistinguishable from one
 * that was working. But whether a query carries an embedding is decided at request time by whether
 * the embedding endpoint answered, and that endpoint is behind a circuit breaker like every other
 * model call. Without this gate, a node whose embedder is briefly unavailable turns every graph
 * question into a failed retrieval signal - and when the reranked lexical ranker is the only
 * signal, into a failed request.</p>
 *
 * <p>So the choice is made once, per query, in one place: the exact rerank when there is something
 * to rerank against, and the ranking underneath it when there is not. Degrading to a BM25 ordering
 * is a worse answer; failing the question is not an answer at all.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable, and as thread-safe as the two rankers it chooses between.</p>
 *
 * @author ExcaliburDB
 */
public final class EmbeddingGatedRanker implements Ranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingGatedRanker.class);

    private final Ranker whenEmbedded;
    private final Ranker whenNotEmbedded;

    /**
     * Creates the gate.
     *
     * @param whenEmbedded    Used when the query carries an embedding; typically a reranker
     * @param whenNotEmbedded Used when it does not; typically the ranker the reranker wraps
     */
    public EmbeddingGatedRanker(Ranker whenEmbedded, Ranker whenNotEmbedded) {
        this.whenEmbedded = Objects.requireNonNull(whenEmbedded, "whenEmbedded must not be null");
        this.whenNotEmbedded =
            Objects.requireNonNull(whenNotEmbedded, "whenNotEmbedded must not be null");
    }

    @Override
    public String name() {
        return whenEmbedded.name();
    }

    @Override
    public RankedList rank(RetrievalQuery query, int limit) {
        return rank(query, limit, QueryBudget.unbounded());
    }

    @Override
    public RankedList rank(RetrievalQuery query, int limit, QueryBudget budget) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (query.hasEmbedding()) {
            return whenEmbedded.rank(query, limit, budget);
        }
        LOGGER.debug("query carries no embedding; answering from {} without an exact rerank",
            whenNotEmbedded.name());
        return whenNotEmbedded.rank(query, limit, budget);
    }
}
