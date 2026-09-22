package com.eonmux.excalibur.retrieval.fusion;

import java.util.List;

import com.eonmux.excalibur.retrieval.rank.RankedList;

/**
 * Combines several signals' orderings into one.
 *
 * <p>Separate from {@link com.eonmux.excalibur.retrieval.rank.Ranker} so that how signals are combined can
 * be changed, measured and A/B tested without touching any signal. A weighted-score fusion, a
 * learned combiner and reciprocal rank fusion are alternatives at this seam, not rewrites of the
 * retrieval path.
 */
public interface FusionStrategy {

    /** Name of this strategy, for diagnostics. */
    String name();

    /**
     * Fuses {@code lists} into a single ordering.
     *
     * @param lists one entry per signal that ran; an empty or absent signal simply contributes
     *              nothing rather than being an error
     * @param limit maximum documents to return
     * @return fused documents, best first
     */
    List<FusedDocument> fuse(List<RankedList> lists, int limit);
}
