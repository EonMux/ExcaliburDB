package com.eonmux.excalibur.retrieval.pipeline;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.planner.QueryPlan.AggregateFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregationTest {

    private static final Provenance CURATED = new Provenance(SourceType.KNOWLEDGE_BASE, 1, 90, 1);

    private static int day(int year) {
        return Math.toIntExact(LocalDate.of(year, 1, 1).toEpochDay());
    }

    private static SubgraphEdge fact(int subject, int relation, int object, int validFrom) {
        return new SubgraphEdge(subject, relation, object, relation, validFrom,
            TemporalFilter.OPEN, CURATED, 1);
    }

    private static Subgraph around(long seed, SubgraphEdge... facts) {
        Subgraph.Builder builder = new Subgraph.Builder().seed(seed);
        for (SubgraphEdge fact : facts) {
            builder.edge(fact);
        }
        return builder.build();
    }

    @Test
    @DisplayName("a count is of entities, not facts: two facts joining the same pair count once")
    void countsEntitiesNotFacts() {
        Subgraph subgraph = around(1,
            fact(1, 0, 2, day(1900)),
            fact(2, 1, 1, day(1910)),
            fact(1, 2, 3, day(1920)),
            fact(1, 3, 1, day(1930)));

        Aggregation count = Aggregation.over(AggregateFunction.COUNT, subgraph, id -> "E" + id);

        assertThat(count.count()).as("the self-loop relates the seed to nothing new").isEqualTo(2);
        assertThat(count.factsConsidered()).isEqualTo(4);
        assertThat(count.entities()).containsExactly("E2", "E3");
        assertThat(count.describe(List.of("E1")))
            .isEqualTo("count: 2 distinct entities related to E1 by 4 facts");
    }

    @Test
    @DisplayName("only facts with a named entity at one end are read, however far the walk went")
    void readsOnlyFactsAboutTheNamedEntities() {
        // A two-hop walk from Ada collects Acme -> Bob too. Bob's 1980 fact is older than any of
        // Ada's, and answering with it would report someone else's history as hers.
        Subgraph subgraph = around(1,
            fact(1, 0, 2, day(1990)),
            fact(2, 1, 3, day(1980)));

        Aggregation earliest =
            Aggregation.over(AggregateFunction.EARLIEST, subgraph, id -> "E" + id);
        Aggregation count = Aggregation.over(AggregateFunction.COUNT, subgraph, id -> "E" + id);

        assertThat(earliest.validFrom()).hasValue(day(1990));
        assertThat(earliest.entities()).containsExactly("E2");
        assertThat(earliest.factsConsidered()).isEqualTo(1);
        assertThat(count.count()).isEqualTo(1);
        assertThat(count.factsConsidered()).isEqualTo(1);
    }

    @Test
    @DisplayName("facts sharing the extreme start are all the answer, and undated facts none of it")
    void extremeKeepsTiesAndSkipsUndated() {
        List<SubgraphEdge> facts = List.of(
            fact(1, 0, 2, Integer.MIN_VALUE),
            fact(1, 1, 3, day(1900)),
            fact(1, 2, 4, day(1900)),
            fact(1, 3, 5, day(1950)));

        assertThat(Aggregation.extremeFacts(AggregateFunction.EARLIEST, facts))
            .extracting(SubgraphEdge::object).containsExactly(3, 4);
        assertThat(Aggregation.extremeFacts(AggregateFunction.LATEST, facts))
            .extracting(SubgraphEdge::object).containsExactly(5);
        assertThat(Aggregation.extremeFacts(AggregateFunction.EARLIEST,
            List.of(fact(1, 0, 2, Integer.MIN_VALUE)))).isEmpty();
    }

    @Test
    @DisplayName("an extremum shared by two facts names both, and says how many")
    void extremumDescribesTies() {
        Subgraph subgraph = around(1, fact(1, 1, 3, day(1900)), fact(4, 2, 1, day(1900)));

        Aggregation earliest =
            Aggregation.over(AggregateFunction.EARLIEST, subgraph, id -> "E" + id);

        assertThat(earliest.validFrom()).hasValue(day(1900));
        assertThat(earliest.entities()).containsExactly("E3", "E4");
        assertThat(earliest.describe(List.of("E1")))
            .isEqualTo("earliest about E1: began 1900-01-01, with E3, E4 (2 facts)");
    }

    @Test
    @DisplayName("an extremum over nothing says there was nothing, rather than a date")
    void extremumOverNothing() {
        Aggregation latest = Aggregation.over(AggregateFunction.LATEST, around(1), id -> "E" + id);

        assertThat(latest.count()).isZero();
        assertThat(latest.validFrom()).isEmpty();
        assertThat(latest.describe(List.of("E1")))
            .isEqualTo("latest about E1: no fact with a recorded start");
    }

    @Test
    @DisplayName("a malformed aggregate is refused")
    void validates() {
        assertThatThrownBy(() -> new Aggregation(null, 0, 0, OptionalInt.empty(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Aggregation(AggregateFunction.COUNT, -1, 0,
                OptionalInt.empty(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Aggregation(AggregateFunction.COUNT, 0, -1,
                OptionalInt.empty(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
