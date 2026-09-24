package com.eonmux.excalibur.retrieval.expand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Remembering what a query was widened into.
 */
@DisplayName("CachingQueryExpander")
class CachingQueryExpanderTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);

    /** An expander that counts how often it is actually asked to generate. */
    private static QueryExpander counting(AtomicInteger calls) {
        return QueryExpander.infallible(query -> {
            calls.incrementAndGet();
            return List.of(SearchFormulation.original(query),
                new SearchFormulation(query + " expanded", 0.8));
        });
    }

    /**
     * The whole reason it exists. A caller paging through an answer asks the same question
     * again for every page; a second generation is a second bill and different terms from the
     * ones the first page was ranked by.
     */
    @Test
    @DisplayName("the same query is generated once however often it is asked for")
    void generatesOnce() {
        AtomicInteger calls = new AtomicInteger();
        QueryExpander expander = CachingQueryExpander.over(counting(calls));

        Expansion first = expander.expand("why did my process die", BUDGET);
        Expansion second = expander.expand("why did my process die", BUDGET);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("different queries are generated separately")
    void differentQueriesAreSeparate() {
        AtomicInteger calls = new AtomicInteger();
        QueryExpander expander = CachingQueryExpander.over(counting(calls));

        expander.expand("one", BUDGET);
        expander.expand("two", BUDGET);

        assertThat(calls.get()).isEqualTo(2);
    }

    /**
     * A fallback is one moment's trouble. Remembered, it would be replayed to every later caller
     * asking the same question as plain BM25, long after the model recovered.
     */
    @Test
    @DisplayName("a fallback is not remembered, so the next caller asks the model again")
    void aFallbackIsNotRemembered() {
        List<Expansion> answers = new ArrayList<>(List.of(
            Expansion.fellBack("out of memory"),
            Expansion.of(List.of(SearchFormulation.original("out of memory"),
                new SearchFormulation("OOM killer", 0.9)))));
        AtomicInteger calls = new AtomicInteger();
        QueryExpander expander = CachingQueryExpander.over((query, budget) -> {
            calls.incrementAndGet();
            return answers.removeFirst();
        });

        Expansion first = expander.expand("out of memory", BUDGET);
        Expansion second = expander.expand("out of memory", BUDGET);
        Expansion third = expander.expand("out of memory", BUDGET);

        assertThat(first.fellBack()).isTrue();
        assertThat(second.fellBack()).isFalse();
        assertThat(third).isEqualTo(second);
        assertThat(calls.get())
            .as("the fallback was asked again; the recovered answer was remembered")
            .isEqualTo(2);
    }

    /** The budget belongs to the request asking, and has to reach the call it bounds. */
    @Test
    @DisplayName("a miss hands the caller's budget to the expander doing the work")
    void aMissPassesTheBudgetOn() {
        List<Duration> seen = new ArrayList<>();
        QueryExpander expander = CachingQueryExpander.over((query, budget) -> {
            seen.add(budget);
            return Expansion.of(List.of(SearchFormulation.original(query)));
        });

        expander.expand("q", Duration.ofMillis(1_234));

        assertThat(seen).containsExactly(Duration.ofMillis(1_234));
    }

    /**
     * Queries are caller-supplied strings, so an unbounded map of them is an unbounded
     * allocation. The least recently used entry goes first.
     */
    @Test
    @DisplayName("the cache evicts rather than growing without bound")
    void evictsTheLeastRecentlyUsed() {
        AtomicInteger calls = new AtomicInteger();
        QueryExpander expander = CachingQueryExpander.over(counting(calls), 2);

        expander.expand("a", BUDGET);
        expander.expand("b", BUDGET);
        expander.expand("c", BUDGET);
        expander.expand("a", BUDGET);

        assertThat(calls.get())
            .as("'a' fell out when 'c' arrived and had to be generated again")
            .isEqualTo(4);
    }

    @Test
    @DisplayName("a null delegate widens nothing rather than failing")
    void aNullDelegateWidensNothing() {
        Expansion expansion = CachingQueryExpander.over(null).expand("anything", BUDGET);

        assertThat(expansion.formulations())
            .containsExactly(SearchFormulation.original("anything"));
        assertThat(expansion.fellBack()).isFalse();
    }

    @Test
    @DisplayName("a cache of nothing is refused")
    void refusesAnEmptyCache() {
        assertThatThrownBy(() -> CachingQueryExpander.over(QueryExpander.identity(), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capacity");
    }
}
