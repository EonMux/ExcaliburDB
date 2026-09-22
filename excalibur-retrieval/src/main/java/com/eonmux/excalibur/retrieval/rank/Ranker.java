package com.eonmux.excalibur.retrieval.rank;

/**
 * One retrieval signal: something that can order candidates for a query.
 *
 * <p>BM25 over an inverted index, nearest neighbours in vector space, a graph-proximity score and a
 * model asked to judge relevance are all the same shape from the fusion layer's point of view — a
 * name and an ordering. Keeping them behind one interface is what makes adding a signal a matter of
 * writing a class and registering it, rather than editing a scoring function that every other
 * signal also passes through.
 *
 * <p>The scores a ranker reports are its own. They are not required to be normalised, comparable
 * with another ranker's, or even positive, because
 * {@link com.eonmux.excalibur.retrieval.fusion.ReciprocalRankFusion} reads position rather than
 * magnitude. A ranker that tried to make its scores comparable with the others would be doing
 * the fusion layer's job, and doing it with less information.
 *
 * <p>Implementations must be safe for concurrent use: one instance serves many queries.
 */
public interface Ranker {

    /**
     * Stable identifier for this signal, used in fusion diagnostics and per-stage timings.
     *
     * <p>Distinct per configured instance rather than per class — two BM25 rankers over different
     * fields are different signals, and a trace that calls them both "bm25" cannot be read.
     */
    String name();

    /**
     * Ranks candidates for {@code query}, best first.
     *
     * <p>May return fewer than {@code limit} results, including none. Returning nothing is a valid
     * answer meaning this signal found nothing, and is different from the signal being unavailable
     * — for which an implementation throws.
     *
     * @param query the query to rank for
     * @param limit maximum results to return; implementations must not exceed it
     * @return results ordered best first, never null
     */
    RankedList rank(RetrievalQuery query, int limit);
}
