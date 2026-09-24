package com.eonmux.excalibur.graph.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MappedEdgeStore bounded expansion")
class BoundedExpansionTest {

    private static final int LINKS = 0;
    private static final int OTHER = 1;
    private static final BooleanSupplier NEVER = () -> false;

    private static int day(int year) {
        return Math.toIntExact(LocalDate.of(year, 1, 1).toEpochDay());
    }

    private static Provenance confidence(int percent) {
        return new Provenance(SourceType.DOCUMENT, 1, percent, 1);
    }

    private static MappedEdgeStore build(Path directory, long entityCount,
                                         Consumer<EdgeStoreWriter> edges) throws IOException {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        edges.accept(writer);
        writer.build(directory, entityCount);
        return MappedEdgeStore.open(directory);
    }

    /**
     * A hub, entity 0, linked to entities 1..50. Entity {@code i}'s edge carries confidence
     * {@code i}, so the strongest neighbours are the highest ids.
     */
    private static MappedEdgeStore hub(Path directory) throws IOException {
        return build(directory, 51, w -> {
            for (int i = 1; i <= 50; i++) {
                w.add(0, LINKS, i, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(i));
            }
        });
    }

    @Nested
    @DisplayName("the per-hop cap")
    class Cap {

        @Test
        @DisplayName("keeps a hub's most confident neighbours and counts the rest as dropped")
        void keepsStrongestNeighbours(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = hub(dir)) {
                MappedEdgeStore.Expansion expansion =
                    store.expand(new long[] {0}, 1, null, TemporalFilter.ALL, 5, NEVER);

                assertThat(expansion.reached()).containsExactly(0, 46, 47, 48, 49, 50);
                assertThat(expansion.entitiesDropped()).isEqualTo(45);
                assertThat(expansion.truncated()).isTrue();
                assertThat(expansion.stopped()).isFalse();
            }
        }

        @Test
        @DisplayName("chooses the same entities on every walk")
        void isDeterministic(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = hub(dir)) {
                long[] first = store.expand(new long[] {0}, 2, null, TemporalFilter.ALL, 7, NEVER)
                    .reached();
                for (int i = 0; i < 10; i++) {
                    assertThat(store.expand(new long[] {0}, 2, null, TemporalFilter.ALL, 7, NEVER)
                        .reached()).containsExactly(first);
                }
            }
        }

        @Test
        @DisplayName("breaks a confidence tie by the later start, then by the lower id")
        void breaksTiesByRecencyThenId(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 6, w -> w
                    .add(0, LINKS, 1, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(50))
                    .add(0, LINKS, 2, day(2001), TemporalFilter.OPEN, confidence(50))
                    .add(0, LINKS, 3, day(2020), TemporalFilter.OPEN, confidence(50))
                    .add(0, LINKS, 4, day(2010), TemporalFilter.OPEN, confidence(50))
                    .add(0, LINKS, 5, day(2010), TemporalFilter.OPEN, confidence(50)))) {

                assertThat(store.expand(new long[] {0}, 1, null, TemporalFilter.ALL, 2, NEVER)
                    .reached()).as("the latest start first").containsExactly(0, 3, 4);
                assertThat(store.expand(new long[] {0}, 1, null, TemporalFilter.ALL, 4, NEVER)
                    .reached()).as("an undated edge is the oldest").containsExactly(0, 2, 3, 4, 5);
            }
        }

        @Test
        @DisplayName("ranks a neighbour by the strongest of the edges that reach it")
        void ranksByStrongestEdge(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 4, w -> w
                    .add(0, LINKS, 1, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(10))
                    .add(0, OTHER, 1, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(90))
                    .add(0, LINKS, 2, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(50))
                    .add(3, LINKS, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(40)))) {

                assertThat(store.expand(new long[] {0}, 1, null, TemporalFilter.ALL, 1, NEVER)
                    .reached()).containsExactly(0, 1);
            }
        }

        @Test
        @DisplayName("ranks a reverse edge by the confidence of the fact it names")
        void ranksReverseEdges(@TempDir Path dir) throws IOException {
            // Entity 0 is the object of every fact, so each neighbour is reached through the
            // reverse index, whose positions differ from the forward order the columns are in.
            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(1, LINKS, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(80))
                    .add(2, LINKS, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(20))
                    .add(3, LINKS, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(90))
                    .add(4, LINKS, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(10)))) {

                int[] reverse = {RelationSelector.reverse(LINKS)};
                assertThat(store.expand(new long[] {0}, 1, reverse, TemporalFilter.ALL, 2, NEVER)
                    .reached()).containsExactly(0, 1, 3);
            }
        }

        @Test
        @DisplayName("ranks only the edges the relation filter and the instant admit")
        void ranksAdmittedEdgesOnly(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(0, OTHER, 1, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(99))
                    .add(0, LINKS, 1, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(5))
                    .add(0, LINKS, 2, day(2000), day(2005), confidence(95))
                    .add(0, LINKS, 3, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(30))
                    .add(0, LINKS, 4, TemporalFilter.UNDATED, TemporalFilter.OPEN, confidence(20)))) {

                int[] forward = {RelationSelector.forward(LINKS)};
                MappedEdgeStore.Expansion expansion = store.expand(new long[] {0}, 1, forward,
                    TemporalFilter.asOf(LocalDate.of(2020, 1, 1)), 2, NEVER);

                // Entity 2's strong fact had ended by 2020 and entity 1's strong one is on
                // another relation; neither lends its strength to the ranking.
                assertThat(expansion.reached()).containsExactly(0, 3, 4);
                assertThat(expansion.entitiesDropped()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("leaves a walk inside its cap exactly as the unbounded walk")
        void insideCapMatchesUnbounded(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 6, w -> w
                    .add(0, LINKS, 1).add(1, LINKS, 2).add(2, LINKS, 3).add(4, OTHER, 0))) {
                MappedEdgeStore.Expansion expansion =
                    store.expand(new long[] {0}, 3, null, TemporalFilter.ALL, 100, NEVER);

                assertThat(expansion.reached())
                    .containsExactly(store.expand(new long[] {0}, 3, null, TemporalFilter.ALL));
                assertThat(expansion.truncated()).isFalse();
                assertThat(expansion.stopped()).isFalse();
            }
        }

        @Test
        @DisplayName("always admits the seeds, however many the question named")
        void admitsEverySeed(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = hub(dir)) {
                MappedEdgeStore.Expansion expansion = store.expand(
                    new long[] {10, 20, 30, 40}, 1, null, TemporalFilter.ALL, 1, NEVER);

                assertThat(expansion.reached()).containsExactly(0, 10, 20, 30, 40);
                assertThat(expansion.entitiesDropped()).isZero();
            }
        }

        @Test
        @DisplayName("caps every hop, not only the first")
        void capsEveryHop(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = hub(dir)) {
                // From entity 50: one hop reaches the hub, the second the hub's 49 other
                // neighbours, of which three are kept.
                MappedEdgeStore.Expansion expansion =
                    store.expand(new long[] {50}, 2, null, TemporalFilter.ALL, 3, NEVER);

                assertThat(expansion.reached()).containsExactly(0, 47, 48, 49, 50);
                assertThat(expansion.entitiesDropped()).isEqualTo(46);
            }
        }

        @Test
        @DisplayName("a zero-hop walk returns the seeds")
        void zeroHops(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = hub(dir)) {
                assertThat(store.expand(new long[] {0}, 0, null, TemporalFilter.ALL, 1, NEVER)
                    .reached()).containsExactly(0);
            }
        }
    }

    @Nested
    @DisplayName("the stop signal")
    class Stop {

        @Test
        @DisplayName("before the first hop leaves the seeds and says the walk stopped")
        void beforeFirstHop(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = hub(dir)) {
                MappedEdgeStore.Expansion expansion =
                    store.expand(new long[] {0}, 2, null, TemporalFilter.ALL, 100, () -> true);

                assertThat(expansion.reached()).containsExactly(0);
                assertThat(expansion.stopped()).isTrue();
            }
        }

        @Test
        @DisplayName("keeps the hops a walk finished and discards the one it interrupted")
        void discardsInterruptedHop(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(0, LINKS, 1).add(1, LINKS, 2).add(1, LINKS, 3).add(2, LINKS, 4))) {
                // Read once before hop one, once for its one frontier entity, once before hop
                // two - and fires while hop two is expanding its first entity.
                AtomicInteger reads = new AtomicInteger();
                MappedEdgeStore.Expansion expansion = store.expand(new long[] {0}, 3, null,
                    TemporalFilter.ALL, 100, () -> reads.incrementAndGet() > 3);

                assertThat(expansion.reached()).containsExactly(0, 1);
                assertThat(expansion.stopped()).isTrue();
            }
        }

        @Test
        @DisplayName("stops while ranking an overflowing hop")
        void stopsWhileRanking(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = hub(dir)) {
                // Two reads for the hop itself; the third, as the ranking starts, fires.
                AtomicInteger reads = new AtomicInteger();
                MappedEdgeStore.Expansion expansion = store.expand(new long[] {0}, 1, null,
                    TemporalFilter.ALL, 5, () -> reads.incrementAndGet() > 2);

                assertThat(expansion.reached()).containsExactly(0);
                assertThat(expansion.stopped()).isTrue();
            }
        }

        @Test
        @DisplayName("stops a wide hop that expands in parallel")
        void stopsParallelHop(@TempDir Path dir) throws IOException {
            // A frontier of 300 is past the parallel threshold, so the hop is split into tasks.
            try (MappedEdgeStore store = build(dir, 302, w -> {
                for (int i = 1; i <= 300; i++) {
                    w.add(0, LINKS, i);
                    w.add(i, LINKS, 301);
                }
            })) {
                AtomicInteger reads = new AtomicInteger();
                MappedEdgeStore.Expansion stopped = store.expand(new long[] {0}, 2, null,
                    TemporalFilter.ALL, 1000, () -> reads.incrementAndGet() > 5);
                assertThat(stopped.stopped()).isTrue();
                assertThat(stopped.reached()).hasSize(301).doesNotContain(301L);

                MappedEdgeStore.Expansion whole = store.expand(new long[] {0}, 2, null,
                    TemporalFilter.ALL, 1000, NEVER);
                assertThat(whole.reached()).hasSize(302);
            }
        }
    }

    @Test
    @DisplayName("refuses arguments the unbounded walk refuses, and a cap below one")
    void validatesArguments(@TempDir Path dir) throws IOException {
        try (MappedEdgeStore store = hub(dir)) {
            long[] seeds = {0};
            assertThatThrownBy(() -> store.expand(null, 1, null, TemporalFilter.ALL, 1, NEVER))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.expand(seeds, -1, null, TemporalFilter.ALL, 1, NEVER))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.expand(seeds, 1, null, null, 1, NEVER))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.expand(seeds, 1, null, TemporalFilter.ALL, 0, NEVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxFrontier");
            assertThatThrownBy(() -> store.expand(seeds, 1, null, TemporalFilter.ALL, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.expand(new long[] {99}, 1, null, TemporalFilter.ALL, 1,
                NEVER)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("reports the reached entities in ascending order")
    void reachedIsSorted(@TempDir Path dir) throws IOException {
        try (MappedEdgeStore store = hub(dir)) {
            long[] reached = store.expand(new long[] {0}, 1, null, TemporalFilter.ALL, 10, NEVER)
                .reached();
            long[] sorted = reached.clone();
            Arrays.sort(sorted);
            assertThat(reached).containsExactly(sorted);
        }
    }
}
