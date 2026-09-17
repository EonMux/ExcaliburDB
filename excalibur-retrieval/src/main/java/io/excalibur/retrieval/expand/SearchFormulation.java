package io.excalibur.retrieval.expand;

import java.util.Objects;

/**
 * One way of searching for what the user asked, and how much it should count.
 *
 * <p>A formulation is a search term, phrase or identifier - not a question and not prose. The
 * weight is how strongly a match on it should be believed relative to the user's own words, which
 * always carry {@link #ORIGINAL_WEIGHT}. An expansion is a guess about vocabulary: it is there to
 * find documents the user's phrasing would miss, and it must never outrank the phrasing itself.
 *
 * @param text   The search text
 * @param weight Relative importance in (0, 1]
 */
public record SearchFormulation(String text, double weight) {

    /** The weight carried by the user's own query. Expansions are scored beneath it. */
    public static final double ORIGINAL_WEIGHT = 1.0;

    /**
     * The most weight an expansion may carry, however many formulations agree on the term.
     *
     * <p>Two bounds are needed, not one. This record's own check bounds a single formulation,
     * which is not the weight that reaches the index: every consumer sums the weights of the
     * formulations sharing a term, because a term several of them agreed on is stronger
     * evidence than one only the best of them suggested. The sum is what has to stay beneath
     * the user's own words, and {@link ExpansionWeights} is what holds it there.</p>
     *
     * <p>Strictly below {@link #ORIGINAL_WEIGHT} rather than equal to it: an expansion that
     * ties with the typed word has displaced it in every ranking where something else breaks
     * the tie, and "cannot be outranked" has to be a statement about the search rather than
     * about arithmetic.</p>
     */
    public static final double MAX_EXPANSION_WEIGHT = 0.9;

    public SearchFormulation {
        Objects.requireNonNull(text, "text must not be null");
        text = text.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("formulation text must not be blank");
        }
        if (!(weight > 0.0) || weight > ORIGINAL_WEIGHT || Double.isNaN(weight)) {
            throw new IllegalArgumentException(
                "weight must be in (0, " + ORIGINAL_WEIGHT + "]: " + weight);
        }
    }

    /** The user's own query, at full weight. */
    public static SearchFormulation original(String text) {
        return new SearchFormulation(text, ORIGINAL_WEIGHT);
    }
}
