package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.query.IndexCoverage;

/**
 * How many results there are, and how much that number is worth.
 *
 * <h2>Why the relation is not optional</h2>
 * <p>Neither engine behind this route can count matches. Approximate nearest neighbour search
 * walks a graph until it has the depth it was asked for and stops; there is no {@code n} it
 * declined to mention, only a search it did not continue. Counting the rest would mean
 * scanning the corpus, which is the work the index exists to avoid. So the honest number in
 * the common case is a floor, and a precise-looking integer standing where a floor belongs is
 * a number every client will render as a count.</p>
 *
 * <h2>When it can be exact</h2>
 * <p>When the engine came back shallower than it was asked for and had no reason to stop
 * early. Then there is no eleventh result being withheld: it ran out of matches, and the count
 * is a fact. That case matters more than it looks, because it is the one that separates
 * "nothing matched" from "nothing was searched" - the same distinction {@code coverage}
 * already draws for the answer as a whole, carried into the number a caller actually shows.
 * A degraded, timed-out or cancelled answer is therefore never exact, whatever it returned.</p>
 *
 * @param value    The count, or the floor beneath the count
 * @param relation Whether {@link #value} is the number of results or a lower bound on it
 */
public record ResultTotal(long value, String relation) {

    /** The engine ran out of matches: {@code value} is how many there were. */
    public static final String EXACT = "exact";

    /** The engine stopped before the end: there are at least {@code value}, and maybe more. */
    public static final String AT_LEAST = "at_least";

    /**
     * Checks the total says what kind of number it is.
     */
    public ResultTotal {
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative: " + value);
        }
        if (!EXACT.equals(relation) && !AT_LEAST.equals(relation)) {
            throw new IllegalArgumentException("relation must be '" + EXACT + "' or '"
                + AT_LEAST + "': " + relation);
        }
    }

    /**
     * The total one search can stand behind.
     *
     * @param produced How many results the engine ranked, before any page was cut from them
     * @param depth    How many it was asked for
     * @param complete Whether the search finished over everything it was meant to search -
     *                 false for a degraded, timed-out or cancelled answer, which can never
     *                 establish that there was nothing more to find
     * @return The total
     */
    public static ResultTotal of(int produced, int depth, boolean complete) {
        return new ResultTotal(produced, complete && produced < depth ? EXACT : AT_LEAST);
    }

    /**
     * Whether a search reached everything it set out to reach.
     *
     * <p>Deliberately not {@code coverage.degraded()}. That flag answers a different question
     * on each path: on the vector path it means the query could not see enough of the segment
     * catalog, and on the lexical path it is always true and means the answer came from fewer
     * signals than a fully configured node would use. Fewer signals is a weaker ranking; it
     * does not make the ranking that did run any less exhausted. What matters to a total is
     * whether the corpus was reached, so that is what is asked.</p>
     *
     * @param coverage  What the query covered
     * @param timedOut  Whether the request's budget ran out
     * @param cancelled Whether the caller withdrew the request
     * @return true when the search can stand behind having run out of matches
     */
    public static boolean searchWasComplete(IndexCoverage coverage, boolean timedOut,
                                            boolean cancelled) {
        return !timedOut && !cancelled
            && coverage.estimatedCorpusCoverage() >= 1.0
            && coverage.segmentsExcluded() == 0
            && coverage.segmentsTimedOut() == 0
            && coverage.segmentsWithErrors() == 0;
    }
}
