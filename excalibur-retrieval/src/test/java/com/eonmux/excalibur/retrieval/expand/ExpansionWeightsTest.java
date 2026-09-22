package com.eonmux.excalibur.retrieval.expand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ceiling that makes "the user's own words always win" true after summation.
 *
 * <p>Every consumer of an expansion sums the weights of the formulations that share a term,
 * because a term several of them agreed on is stronger evidence than one only the best of them
 * suggested. Each formulation being bounded below the user's own weight says nothing about what
 * that sum comes to, which is what these tests are about.</p>
 */
@DisplayName("Expansion weights")
class ExpansionWeightsTest {

    /** Whitespace splitting, which is enough to make two formulations share a term. */
    private static final ExpansionWeights.Terms WORDS =
        text -> Arrays.asList(text.toLowerCase().split("\\s+"));

    private static double weightOf(List<SearchFormulation> out, String text) {
        return out.stream().filter(f -> f.text().equals(text)).findFirst().orElseThrow().weight();
    }

    @Test
    @DisplayName("a term two formulations agree on is held beneath the user's own words")
    void agreementIsHeldBeneathTheOriginal() {
        List<SearchFormulation> out = ExpansionWeights.bounded(List.of(
            SearchFormulation.original("alpha"),
            new SearchFormulation("bravo charlie", 0.9),
            new SearchFormulation("bravo delta", 0.9)), WORDS);

        assertThat(weightOf(out, "bravo charlie") + weightOf(out, "bravo delta"))
            .isLessThanOrEqualTo(SearchFormulation.MAX_EXPANSION_WEIGHT);
        assertThat(out.get(0).weight()).isEqualTo(SearchFormulation.ORIGINAL_WEIGHT);
    }

    @Test
    @DisplayName("scaling keeps the order the model asked for")
    void orderSurvivesTheScaling() {
        List<SearchFormulation> out = ExpansionWeights.bounded(List.of(
            SearchFormulation.original("alpha"),
            new SearchFormulation("bravo charlie", 0.9),
            new SearchFormulation("bravo delta", 0.8),
            new SearchFormulation("echo", 0.6)), WORDS);

        assertThat(weightOf(out, "bravo charlie")).isGreaterThan(weightOf(out, "bravo delta"));
        assertThat(weightOf(out, "bravo delta")).isGreaterThan(weightOf(out, "echo"));
    }

    @Test
    @DisplayName("formulations that share nothing are left exactly as they were")
    void nothingIsScaledWhenNothingIsShared() {
        List<SearchFormulation> given = List.of(
            SearchFormulation.original("alpha"),
            new SearchFormulation("bravo", 0.9),
            new SearchFormulation("charlie", 0.8));

        assertThat(ExpansionWeights.bounded(given, WORDS))
            .as("the ceiling is on the sum, and nothing here sums")
            .isEqualTo(given);
    }

    @Test
    @DisplayName("the user's own query is never scaled, however much the expansions agree")
    void theOriginalIsNeverScaled() {
        List<SearchFormulation> out = ExpansionWeights.bounded(List.of(
            SearchFormulation.original("bravo"),
            new SearchFormulation("bravo charlie", 0.9),
            new SearchFormulation("bravo delta", 0.9),
            new SearchFormulation("bravo echo", 0.9)), WORDS);

        assertThat(out.get(0).weight()).isEqualTo(SearchFormulation.ORIGINAL_WEIGHT);
    }

    @Test
    @DisplayName("an empty expansion is nothing to bound")
    void theOriginalAloneIsUnchanged() {
        List<SearchFormulation> given = List.of(SearchFormulation.original("alpha"));

        assertThat(ExpansionWeights.bounded(given, WORDS)).isEqualTo(given);
        assertThat(ExpansionWeights.bounded(List.of(), WORDS)).isEmpty();
    }

    @Test
    @DisplayName("neither argument may be missing")
    void argumentsAreRequired() {
        assertThatThrownBy(() -> ExpansionWeights.bounded(null, WORDS))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExpansionWeights.bounded(List.of(), null))
            .isInstanceOf(NullPointerException.class);
    }
}
