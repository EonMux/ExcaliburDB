package excalibur.api.endpoint;

import java.util.List;

/**
 * Why one result is where it is.
 *
 * <h2>What a fused score does not say</h2>
 * <p>On a hybrid node a result's score is a sum of reciprocal ranks. It orders results and
 * means nothing in absolute terms, so a caller looking at {@code 0.032} cannot tell a document
 * both signals ranked third from one the vector half ranked first and BM25 never saw. The
 * signals list names which of them contributed; it does not say how much, and "how much" is
 * the whole of what someone tuning retrieval needs.</p>
 *
 * <p>So each contributing signal reports its own rank and its own score, on whatever scale
 * that signal reports on - a similarity for the dense half, and for the lexical half whatever
 * the lexical service hands back, which is a BM25 score when it runs one ranker and its own
 * fused score when it runs several. Those are not comparable with each other and are not meant
 * to be: that incomparability is why the fusion works on ranks, and hiding it behind a
 * normalised number would be inventing a calibration that goes stale as the corpus changes.
 * The rank is the part that is always comparable, which is why it is there.</p>
 *
 * @param signals      Each signal that returned this result, with its own rank and score
 * @param matchedTerms Which of the query's terms the returned passage actually contains
 */
public record ResultExplanation(List<Signal> signals, List<String> matchedTerms) {

    /**
     * Copies the lists so an explanation cannot change after the answer claims it.
     */
    public ResultExplanation {
        signals = List.copyOf(signals);
        matchedTerms = List.copyOf(matchedTerms);
    }

    /**
     * One signal's own account of a result.
     *
     * @param name  The signal, as {@code signals} on the result names it
     * @param rank  Where this result placed in that signal's own ranking, counting from one
     *              and counting from the start of the whole ranking rather than of the page
     * @param score What that signal scored it, on whatever scale that signal reports on
     */
    public record Signal(String name, int rank, double score) {

        /**
         * Checks the contribution names a signal and a place in its ranking.
         */
        public Signal {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("signal name must not be empty");
            }
            if (rank < 1) {
                throw new IllegalArgumentException("rank counts from one: " + rank);
            }
        }
    }
}
