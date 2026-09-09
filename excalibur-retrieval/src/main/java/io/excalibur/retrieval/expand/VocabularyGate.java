package io.excalibur.retrieval.expand;

/**
 * Decides whether a generated search term exists in the corpus at all.
 *
 * <h2>Why a gate</h2>
 * <p>An expander asks a language model to guess the vocabulary of documents it has never seen. Some
 * of those guesses are right, and some name things the corpus has no word for. Measured on a real
 * corpus, the wrong ones were not harmless: every one becomes a clause of the boolean query, and a
 * clause that matches nothing relevant still matches <em>something</em>, so a bad expansion does not
 * merely fail to help - it competes with the user's own words for the ranking.</p>
 *
 * <p>The corpus already knows which words it contains. Consulting it before searching turns a guess
 * into a guess that has been checked, which is far cheaper than discovering the same thing by
 * scoring documents.</p>
 */
@FunctionalInterface
public interface VocabularyGate {

    /**
     * Whether the corpus may contain this term.
     *
     * <p>May over-report: an implementation backed by a probabilistic filter answers "possibly" and
     * never "definitely", which is the right direction to be wrong in. A false positive costs one
     * clause that finds nothing; a false negative would silently discard a good expansion.</p>
     *
     * @param term A single lowercase token
     * @return false only when the corpus certainly does not contain it
     */
    boolean mayContain(String term);

    /** A gate that admits everything, for deployments with no vocabulary to check against. */
    static VocabularyGate permissive() {
        return term -> true;
    }
}
