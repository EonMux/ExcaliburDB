package com.eonmux.excalibur.retrieval.expand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Remembering what a query was widened into.
 */
@DisplayName("CachingQueryExpander")
class CachingQueryExpanderTest {

    /** An expander that counts how often it is actually asked to generate. */
    private static QueryExpander counting(AtomicInteger calls) {
        return query -> {
            calls.incrementAndGet();
            return List.of(SearchFormulation.original(query),
                new SearchFormulation(query + " expanded", 0.8));
        };
    }

    /**
     * The whole reason it exists. The ranker expands to search and the response expands to
     * report; a second generation is a second bill and a different answer from the one that
     * was searched.
     */
    @Test
    @DisplayName("the same query is generated once however often it is asked for")
    void generatesOnce() {
        AtomicInteger calls = new AtomicInteger();
        QueryExpander expander = CachingQueryExpander.over(counting(calls));

        List<SearchFormulation> first = expander.expand("why did my process die");
        List<SearchFormulation> second = expander.expand("why did my process die");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("different queries are generated separately")
    void differentQueriesAreSeparate() {
        AtomicInteger calls = new AtomicInteger();
        QueryExpander expander = CachingQueryExpander.over(counting(calls));

        expander.expand("one");
        expander.expand("two");

        assertThat(calls.get()).isEqualTo(2);
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

        expander.expand("a");
        expander.expand("b");
        expander.expand("c");
        expander.expand("a");

        assertThat(calls.get())
            .as("'a' fell out when 'c' arrived and had to be generated again")
            .isEqualTo(4);
    }

    @Test
    @DisplayName("a null delegate widens nothing rather than failing")
    void aNullDelegateWidensNothing() {
        assertThat(CachingQueryExpander.over(null).expand("anything"))
            .containsExactly(SearchFormulation.original("anything"));
    }

    @Test
    @DisplayName("a cache of nothing is refused")
    void refusesAnEmptyCache() {
        assertThatThrownBy(() -> CachingQueryExpander.over(QueryExpander.identity(), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capacity");
    }
}
