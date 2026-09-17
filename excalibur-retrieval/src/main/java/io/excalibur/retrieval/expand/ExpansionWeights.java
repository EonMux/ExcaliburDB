package io.excalibur.retrieval.expand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the summed weight of the expansions beneath the weight of the user's own words.
 *
 * <h2>Why a per-formulation bound is not enough</h2>
 * <p>An expander already produces every formulation beneath {@link
 * SearchFormulation#ORIGINAL_WEIGHT}. That bound is on one formulation, and one formulation is
 * not what reaches the index: a term is scored once, with the weights of every formulation
 * containing it added together. Summing is the right thing to do - a term several formulations
 * agreed on is stronger evidence than one only the best of them suggested - but it means two
 * formulations at 0.9 sharing a term arrive carrying 1.8 against the 1.0 of a word the user
 * actually typed, and the guess wins the ranking. The claim that an expansion cannot outrank
 * the user's own words is a claim about that sum, so it has to be enforced on that sum.</p>
 *
 * <h2>Scaling rather than clipping</h2>
 * <p>One factor is applied to every expansion, chosen so the heaviest summed term lands exactly
 * on {@link SearchFormulation#MAX_EXPANSION_WEIGHT}. Clipping each term at the ceiling instead
 * would make every term that reached it equal, which throws away both the ordering the model
 * was asked to produce and the agreement the summation exists to record. One factor leaves both
 * intact: what changes is the scale the expansions share, never their order.</p>
 *
 * <h2>Where it is applied</h2>
 * <p>By each consumer, with its own tokenisation, because what counts as one term is a property
 * of the engine that will look it up - the native store folds and splits on non-alphanumerics,
 * Lucene asks its analyzer - and a ceiling computed against the wrong notion of a term bounds
 * nothing. The rule is here; only the splitting is theirs.</p>
 */
public final class ExpansionWeights {

    /** How a consumer splits a formulation into the terms its own engine will score. */
    @FunctionalInterface
    public interface Terms {

        /**
         * @param text One formulation's text
         * @return Its terms, in order and including repeats, as the engine will look them up
         */
        List<String> of(String text);
    }

    private ExpansionWeights() {
        throw new AssertionError("No instances");
    }

    /**
     * Returns the formulations with every expansion scaled beneath the user's own words.
     *
     * <p>The first formulation is the user's query and is returned untouched: that is the
     * order {@link QueryExpander#expand} promises, and the weight it carries is the definition
     * the others are measured against rather than a value to adjust.</p>
     *
     * @param formulations What an expander produced, the original first, already filtered down
     *                     to the ones the caller will actually search
     * @param terms        How the calling engine splits a formulation
     * @return The same formulations in the same order, scaled only if some term summed past
     *         {@link SearchFormulation#MAX_EXPANSION_WEIGHT}
     * @throws NullPointerException if either argument is null
     */
    public static List<SearchFormulation> bounded(List<SearchFormulation> formulations,
                                                  Terms terms) {
        Objects.requireNonNull(formulations, "formulations must not be null");
        Objects.requireNonNull(terms, "terms must not be null");
        double heaviest = heaviestTerm(formulations, terms);
        if (heaviest <= SearchFormulation.MAX_EXPANSION_WEIGHT) {
            return List.copyOf(formulations);
        }
        double scale = SearchFormulation.MAX_EXPANSION_WEIGHT / heaviest;
        List<SearchFormulation> scaled = new ArrayList<>(formulations.size());
        scaled.add(formulations.get(0));
        for (int i = 1; i < formulations.size(); i++) {
            SearchFormulation formulation = formulations.get(i);
            scaled.add(new SearchFormulation(formulation.text(), formulation.weight() * scale));
        }
        return List.copyOf(scaled);
    }

    /**
     * The most weight any one term collects from the expansions alone.
     *
     * <p>The original is skipped rather than counted: the weight a typed word carries is the
     * thing being protected, and a term the user also typed is that word reinforced by an
     * expansion agreeing with it, not a guess overtaking it.</p>
     *
     * @param formulations The formulations, original first
     * @param terms        How the calling engine splits a formulation
     * @return The heaviest summed expansion weight, or zero when there are no expansions
     */
    private static double heaviestTerm(List<SearchFormulation> formulations, Terms terms) {
        Map<String, Double> summed = new HashMap<>();
        double heaviest = 0.0;
        for (int i = 1; i < formulations.size(); i++) {
            SearchFormulation formulation = formulations.get(i);
            for (String term : terms.of(formulation.text())) {
                heaviest = Math.max(heaviest, summed.merge(term, formulation.weight(),
                    Double::sum));
            }
        }
        return heaviest;
    }
}
