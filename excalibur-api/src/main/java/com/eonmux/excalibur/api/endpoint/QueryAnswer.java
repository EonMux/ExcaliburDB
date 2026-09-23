package com.eonmux.excalibur.api.endpoint;

import java.util.List;

/**
 * Everything one answer carries that the search itself did not produce.
 *
 * <h2>Why these travel together</h2>
 * <p>The page, the total, the passages, the explanation and the fields left unacted on are five
 * answers to one question - what this response says beyond the ranking - and every retrieval
 * path has to answer all five the same way. Passing them as one value is what makes that hard
 * to get wrong: a renderer cannot be given a page and forgotten a total, and a path cannot
 * quietly grow a sixth behaviour that only one of the three exits has.</p>
 *
 * <p>None of it is cached. A cached answer is the ranking and what the search said about
 * itself; the passages are read, the page is cut and the unacted-on fields are decided again
 * for the request that is being answered.</p>
 *
 * @param passages    Reads each result's text, or null when the request asked for none and
 *                    wanted no explanation
 * @param page        Which slice of the ranking this answer returned
 * @param total       How many results there are, and how much that number is worth
 * @param explanation Why the results are what they are, or null when none was asked for
 * @param ignored     The request fields this answer accepted and did not act on, in a stable
 *                    order; empty when every field it was sent was honoured
 */
public record QueryAnswer(ResultPassages passages, ResultPage page, ResultTotal total,
                          QueryExplanation explanation, List<String> ignored) {

    /**
     * Checks the answer carries the blocks every response has.
     */
    public QueryAnswer {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        if (total == null) {
            throw new IllegalArgumentException("total must not be null");
        }
        ignored = ignored == null ? List.of() : List.copyOf(ignored);
    }

    /**
     * An answer that left no field unacted on.
     *
     * @param passages    Reads each result's text, or null
     * @param page        Which slice of the ranking this answer returned
     * @param total       How many results there are
     * @param explanation Why the results are what they are, or null
     */
    public QueryAnswer(ResultPassages passages, ResultPage page, ResultTotal total,
                       QueryExplanation explanation) {
        this(passages, page, total, explanation, List.of());
    }

    /**
     * This answer, naming the fields it did not act on.
     *
     * @param fields The fields, as {@link IgnoredFields} decided them for this request
     * @return A copy carrying them
     */
    public QueryAnswer ignoring(List<String> fields) {
        return new QueryAnswer(passages, page, total, explanation, fields);
    }
}
