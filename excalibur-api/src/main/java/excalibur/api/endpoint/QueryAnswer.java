package excalibur.api.endpoint;

/**
 * Everything one answer carries that the search itself did not produce.
 *
 * <h2>Why these travel together</h2>
 * <p>The page, the total, the passages and the explanation are four answers to one question -
 * what this response says beyond the ranking - and every retrieval path has to answer all four
 * the same way. Passing them as one value is what makes that hard to get wrong: a renderer
 * cannot be given a page and forgotten a total, and a path cannot quietly grow a fifth
 * behaviour that only one of the three exits has.</p>
 *
 * @param passages    Reads each result's text, or null when the request asked for none and
 *                    wanted no explanation
 * @param page        Which slice of the ranking this answer returned
 * @param total       How many results there are, and how much that number is worth
 * @param explanation Why the results are what they are, or null when none was asked for
 */
public record QueryAnswer(ResultPassages passages, ResultPage page, ResultTotal total,
                          QueryExplanation explanation) {

    /**
     * Checks the answer carries the two blocks every response has.
     */
    public QueryAnswer {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        if (total == null) {
            throw new IllegalArgumentException("total must not be null");
        }
    }

    /**
     * The answer of a search that produced nothing and cannot claim it looked everywhere.
     *
     * <p>For the timed-out and cancelled exits. They return no results and must still return
     * the same envelope, because a client that has to test for the page block on some answers
     * cannot use it on any.</p>
     *
     * @param limit The page size the request asked for
     * @return The answer
     */
    public static QueryAnswer nothing(int limit) {
        return new QueryAnswer(null, ResultPage.of(0, limit, 0),
            new ResultTotal(0, ResultTotal.AT_LEAST), null);
    }
}
