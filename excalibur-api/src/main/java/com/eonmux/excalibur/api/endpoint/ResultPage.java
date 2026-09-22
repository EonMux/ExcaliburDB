package com.eonmux.excalibur.api.endpoint;

import java.util.List;

/**
 * Which slice of one ranking an answer returned, and whether there is another.
 *
 * <h2>Why paging a ranked index is not paging a table</h2>
 * <p>There is no cursor to hold. Approximate nearest neighbour search and BM25 both answer
 * "the best n" and nothing else, so the second page is not a continuation of the first: it is
 * the tail of a deeper search of the same query. That is why {@code offset} deepens the search
 * rather than resuming one, why the depth is bounded like {@code top_k} is, and why a caller
 * paging far into a ranking is paying for the whole prefix again each time.</p>
 *
 * <h2>Why it is on every answer</h2>
 * <p>A field a caller has to test for is a field it cannot use for arithmetic. Returning the
 * block unconditionally - including on the empty answer of a query that timed out - is what
 * lets a client render "showing 11-20" without first working out whether the server felt like
 * saying so.</p>
 *
 * @param offset     How many results were skipped before this page
 * @param limit      Most results this page could hold, being the request's {@code top_k}
 * @param returned   How many it actually holds
 * @param nextOffset The offset of the next page, or null when the engine reached its end
 */
public record ResultPage(int offset, int limit, int returned, Integer nextOffset) {

    /**
     * Checks the page describes a slice that could exist.
     */
    public ResultPage {
        if (offset < 0 || limit <= 0 || returned < 0 || returned > limit) {
            throw new IllegalArgumentException("not a page: offset=" + offset + " limit=" + limit
                + " returned=" + returned);
        }
    }

    /**
     * The page cut from a ranking the engine produced.
     *
     * <p>There is a next page only when the engine filled the depth it was asked for. A search
     * that came back shallower has nothing more to give at this depth, and offering an offset
     * that would return nothing is worse than saying there is no next page.</p>
     *
     * @param offset   How many results the request asked to skip
     * @param limit    The request's page size
     * @param produced How many results the engine ranked, before the page was cut
     * @return The page
     */
    public static ResultPage of(int offset, int limit, int produced) {
        int returned = Math.max(0, Math.min(limit, produced - offset));
        Integer next = produced >= offset + limit && returned > 0 ? offset + returned : null;
        return new ResultPage(offset, limit, returned, next);
    }

    /**
     * The slice of a ranking this page names.
     *
     * @param <T>      The result type being paged
     * @param produced The whole ranking the engine produced, best first
     * @return The results this page holds, possibly empty
     */
    public <T> List<T> slice(List<T> produced) {
        if (offset >= produced.size()) {
            return List.of();
        }
        return List.copyOf(produced.subList(offset, Math.min(produced.size(), offset + limit)));
    }
}
