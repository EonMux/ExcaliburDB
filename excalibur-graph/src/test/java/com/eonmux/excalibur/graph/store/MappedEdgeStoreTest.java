package com.eonmux.excalibur.graph.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MappedEdgeStoreTest {

    private static final int KNOWS = 0;
    private static final int WORKS_AT = 1;
    private static final int LOCATED_IN = 2;

    private static int day(int year, int month, int dayOfMonth) {
        return Math.toIntExact(LocalDate.of(year, month, dayOfMonth).toEpochDay());
    }

    private static MappedEdgeStore build(Path directory, long entityCount,
                                         java.util.function.Consumer<EdgeStoreWriter> edges)
            throws IOException {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        edges.accept(writer);
        writer.build(directory, entityCount);
        return MappedEdgeStore.open(directory);
    }

    private static long[] sorted(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    @Nested
    @DisplayName("adjacency")
    class Adjacency {

        @Test
        @DisplayName("neighbours round-trip through the CSR")
        void neighboursRoundTrip(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(0, KNOWS, 1)
                    .add(0, KNOWS, 2)
                    .add(0, WORKS_AT, 3)
                    .add(1, KNOWS, 2))) {

                assertThat(sorted(store.neighbors(0, null))).containsExactly(1, 2, 3);
                assertThat(sorted(store.neighbors(1, null))).containsExactly(0, 2);
                assertThat(sorted(store.neighbors(2, null))).containsExactly(0, 1);
            }
        }

        @Test
        @DisplayName("no relation filter means both directions, not forward alone")
        void absentFilterIsNotADirection(@TempDir Path dir) throws IOException {
            // Entity 2 asserts nothing and is asserted about twice. A store that reads an absent
            // relation filter as "forward" calls it isolated, and every question asked from its
            // side of an edge goes unanswered - which is the direction half of any real corpus
            // happens to have been written in.
            try (MappedEdgeStore store = build(dir, 4, w -> w
                    .add(0, KNOWS, 2)
                    .add(1, WORKS_AT, 2))) {

                assertThat(sorted(store.neighbors(2, null)))
                    .as("direction is not a relation, so omitting a relation filter cannot pick one")
                    .containsExactly(0, 1);
                assertThat(sorted(store.expand(new long[] { 2 }, 1, null, TemporalFilter.ALL)))
                    .containsExactly(0, 1, 2);
            }
        }

        @Test
        @DisplayName("one direction is still available, by saying so")
        void directionIsStillExpressible(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 4, w -> w
                    .add(0, KNOWS, 2)
                    .add(1, WORKS_AT, 2))) {

                assertThat(store.neighbors(2, new int[] { RelationSelector.forward(KNOWS) }))
                    .as("2 asserts nothing, so it has no forward neighbour on any relation")
                    .isEmpty();
                assertThat(store.neighbors(2, new int[] { RelationSelector.reverse(KNOWS) }))
                    .containsExactly(0);
                assertThat(sorted(store.neighbors(2, RelationSelector.both(KNOWS))))
                    .containsExactly(0);
            }
        }

        @Test
        @DisplayName("an entity with no edges has an empty run, not a missing one")
        void isolatedEntity(@TempDir Path dir) throws IOException {
            // Entity 4 is addressable but never mentioned by an edge; entity 3 sits between two
            // that are. Both must resolve to empty rather than reading a neighbouring run.
            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(0, KNOWS, 1)
                    .add(2, KNOWS, 1))) {

                assertThat(store.neighbors(3, null)).isEmpty();
                assertThat(store.neighbors(4, null)).isEmpty();
                assertThat(store.neighbors(0, null)).containsExactly(1);
            }
        }

        @Test
        @DisplayName("relation filter selects exactly its run")
        void relationFilter(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 6, w -> w
                    .add(0, KNOWS, 1)
                    .add(0, KNOWS, 2)
                    .add(0, WORKS_AT, 3)
                    .add(0, LOCATED_IN, 4))) {

                assertThat(sorted(store.neighbors(0, new int[] { RelationSelector.forward(KNOWS) })))
                    .containsExactly(1, 2);
                assertThat(store.neighbors(0, new int[] { RelationSelector.forward(WORKS_AT) }))
                    .containsExactly(3);
                assertThat(store.neighbors(0, new int[] { RelationSelector.forward(LOCATED_IN) }))
                    .containsExactly(4);
            }
        }

        @Test
        @DisplayName("an empty filter selects nothing, which is a request rather than an error")
        void emptyFilter(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 3, w -> w.add(0, KNOWS, 1))) {
                assertThat(store.neighbors(0, new int[0])).isEmpty();
            }
        }

        @Test
        @DisplayName("a neighbour reachable by two relations appears once")
        void duplicateNeighbourDeduplicated(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 3, w -> w
                    .add(0, KNOWS, 1)
                    .add(0, WORKS_AT, 1))) {

                assertThat(store.neighbors(0, null)).containsExactly(1);
            }
        }

        @Test
        @DisplayName("an out-of-range entity is rejected rather than truncated")
        void entityOutOfRange(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 3, w -> w.add(0, KNOWS, 1))) {
                assertThatThrownBy(() -> store.neighbors(3, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of range");
                assertThatThrownBy(() -> store.neighbors(-1, null))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Nested
    @DisplayName("reverse index")
    class Reverse {

        @Test
        @DisplayName("reverse adjacency is the transpose of forward adjacency")
        void reverseIsTranspose(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(0, KNOWS, 3)
                    .add(1, KNOWS, 3)
                    .add(2, WORKS_AT, 3))) {

                int[] backKnows = { RelationSelector.reverse(KNOWS) };
                assertThat(sorted(store.neighbors(3, backKnows))).containsExactly(0, 1);

                int[] backAll = {
                    RelationSelector.reverse(KNOWS), RelationSelector.reverse(WORKS_AT) };
                assertThat(sorted(store.neighbors(3, backAll))).containsExactly(0, 1, 2);
            }
        }

        @Test
        @DisplayName("a selector set mixes directions in one lookup")
        void mixedDirections(@TempDir Path dir) throws IOException {
            // 0 -knows-> 1 and 2 -knows-> 1. From 1, following knows both ways reaches both.
            try (MappedEdgeStore store = build(dir, 4, w -> w
                    .add(0, KNOWS, 1)
                    .add(2, KNOWS, 1)
                    .add(1, KNOWS, 3))) {

                assertThat(sorted(store.neighbors(1, RelationSelector.both(KNOWS))))
                    .containsExactly(0, 2, 3);
            }
        }

        @Test
        @DisplayName("temporal columns read through the permutation match the original edge")
        void permutationPreservesIntervals(@TempDir Path dir) throws IOException {
            // The reverse index visits edges in a different order than the forward index, so the
            // permutation is the only thing keeping a reverse traversal reading the right edge's
            // interval. Give each edge a distinct interval so a mismatch cannot pass by luck.
            int e2020 = day(2020, 1, 1);
            int e2021 = day(2021, 1, 1);
            int e2022 = day(2022, 1, 1);

            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(0, KNOWS, 4, e2020, e2021, Provenance.UNKNOWN)
                    .add(1, KNOWS, 4, e2021, e2022, Provenance.UNKNOWN)
                    .add(2, KNOWS, 4, e2022, TemporalFilter.OPEN, Provenance.UNKNOWN))) {

                int[] backKnows = { RelationSelector.reverse(KNOWS) };

                // Each snapshot must see exactly the one subject whose interval contains it.
                assertThat(store.expand(new long[] { 4 }, 1, backKnows,
                    new TemporalFilter(day(2020, 6, 1)))).containsExactlyInAnyOrder(4, 0);
                assertThat(store.expand(new long[] { 4 }, 1, backKnows,
                    new TemporalFilter(day(2021, 6, 1)))).containsExactlyInAnyOrder(4, 1);
                assertThat(store.expand(new long[] { 4 }, 1, backKnows,
                    new TemporalFilter(day(2023, 6, 1)))).containsExactlyInAnyOrder(4, 2);
            }
        }
    }

    @Nested
    @DisplayName("degree")
    class Degree {

        @Test
        @DisplayName("count matches the edges a relation actually has")
        void countMatchesEdges(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 6, w -> w
                    .add(0, KNOWS, 1)
                    .add(0, KNOWS, 2)
                    .add(0, KNOWS, 3)
                    .add(0, WORKS_AT, 4))) {

                assertThat(store.count(0, RelationSelector.forward(KNOWS))).isEqualTo(3);
                assertThat(store.count(0, RelationSelector.forward(WORKS_AT))).isEqualTo(1);
                assertThat(store.count(0, RelationSelector.forward(LOCATED_IN))).isZero();
            }
        }

        @Test
        @DisplayName("count works in reverse")
        void countReverse(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 4, w -> w
                    .add(0, KNOWS, 3)
                    .add(1, KNOWS, 3)
                    .add(2, KNOWS, 3))) {

                assertThat(store.count(3, RelationSelector.reverse(KNOWS))).isEqualTo(3);
                assertThat(store.count(3, RelationSelector.forward(KNOWS))).isZero();
            }
        }

        @Test
        @DisplayName("count counts edges, so a repeated link counts twice")
        void countCountsEdgesNotNeighbours(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 3, w -> w
                    .add(0, KNOWS, 1, 0, 100, Provenance.UNKNOWN)
                    .add(0, KNOWS, 1, 100, TemporalFilter.OPEN, Provenance.UNKNOWN))) {

                assertThat(store.count(0, RelationSelector.forward(KNOWS))).isEqualTo(2);
                // Distinct neighbours, which is a different question.
                assertThat(store.neighbors(0, null)).containsExactly(1);
            }
        }
    }

    @Nested
    @DisplayName("expansion")
    class Expansion {

        @Test
        @DisplayName("zero hops returns the seeds alone")
        void zeroHops(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 4, w -> w.add(0, KNOWS, 1))) {
                assertThat(store.expand(new long[] { 0 }, 0, null, TemporalFilter.ALL))
                    .containsExactly(0);
            }
        }

        @Test
        @DisplayName("multi-hop expansion includes seeds and every depth reached")
        void multiHop(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(0, KNOWS, 1)
                    .add(1, KNOWS, 2)
                    .add(2, KNOWS, 3))) {

                assertThat(sorted(store.expand(new long[] { 0 }, 1, null, TemporalFilter.ALL)))
                    .containsExactly(0, 1);
                assertThat(sorted(store.expand(new long[] { 0 }, 2, null, TemporalFilter.ALL)))
                    .containsExactly(0, 1, 2);
                assertThat(sorted(store.expand(new long[] { 0 }, 3, null, TemporalFilter.ALL)))
                    .containsExactly(0, 1, 2, 3);
            }
        }

        @Test
        @DisplayName("a cycle terminates instead of revisiting")
        void cycleTerminates(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 3, w -> w
                    .add(0, KNOWS, 1)
                    .add(1, KNOWS, 2)
                    .add(2, KNOWS, 0))) {

                assertThat(sorted(store.expand(new long[] { 0 }, 10, null, TemporalFilter.ALL)))
                    .containsExactly(0, 1, 2);
            }
        }

        @Test
        @DisplayName("a wide frontier crossing the parallel threshold gives the same answer")
        void wideFrontierMatchesSerial(@TempDir Path dir) throws IOException {
            // Above FrontierExpander.PARALLEL_THRESHOLD the hop fans out across virtual threads
            // and merges private bitmaps. A star with 1000 leaves exercises that path; the answer
            // must not depend on how the work was divided.
            int leaves = 1000;
            try (MappedEdgeStore store = build(dir, leaves + 2, w -> {
                for (int leaf = 1; leaf <= leaves; leaf++) {
                    w.add(0, KNOWS, leaf);
                    w.add(leaf, WORKS_AT, leaves + 1);
                }
            })) {
                long[] result = store.expand(new long[] { 0 }, 2, null, TemporalFilter.ALL);
                assertThat(result).hasSize(leaves + 2);
                assertThat(sorted(result)[0]).isZero();
                assertThat(sorted(result)[leaves + 1]).isEqualTo(leaves + 1);
            }
        }

        @Test
        @DisplayName("a null temporal filter is rejected rather than defaulted")
        void nullFilterRejected(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 3, w -> w.add(0, KNOWS, 1))) {
                assertThatThrownBy(() -> store.expand(new long[] { 0 }, 1, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("asOf");
            }
        }
    }

    @Nested
    @DisplayName("valid time")
    class ValidTime {

        @Test
        @DisplayName("an edge outside the window does not contribute its neighbours to the next hop")
        void filteringHappensDuringExpansionNotAfter(@TempDir Path dir) throws IOException {
            // This is the difference between filtering during expansion and filtering the result.
            // A -> B held only during 2020. B -> C holds always. As of 2022 the A->B edge is closed,
            // so C is not reachable from A at all. A post-filter would have already let A->B pull B
            // into hop 1 and B->C pull C into hop 2, then removed only B — leaving C in the answer
            // with nothing connecting it.
            int start2020 = day(2020, 1, 1);
            int start2021 = day(2021, 1, 1);

            try (MappedEdgeStore store = build(dir, 3, w -> w
                    .add(0, KNOWS, 1, start2020, start2021, Provenance.UNKNOWN)
                    .add(1, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN, Provenance.UNKNOWN))) {

                long[] during = store.expand(new long[] { 0 }, 2, null,
                    new TemporalFilter(day(2020, 6, 1)));
                assertThat(sorted(during)).containsExactly(0, 1, 2);

                long[] after = store.expand(new long[] { 0 }, 2, null,
                    new TemporalFilter(day(2022, 6, 1)));
                assertThat(sorted(after))
                    .as("C must be unreachable once the only edge to B is closed")
                    .containsExactly(0);
            }
        }

        @Test
        @DisplayName("intervals are half-open: the start day is included, the end day is not")
        void halfOpenInterval(@TempDir Path dir) throws IOException {
            int from = day(2020, 1, 1);
            int to = day(2020, 12, 31);

            try (MappedEdgeStore store = build(dir, 2, w -> w
                    .add(0, KNOWS, 1, from, to, Provenance.UNKNOWN))) {

                assertThat(store.expand(new long[] { 0 }, 1, null, new TemporalFilter(from)))
                    .as("start day is inside the interval")
                    .containsExactlyInAnyOrder(0, 1);
                assertThat(store.expand(new long[] { 0 }, 1, null, new TemporalFilter(to)))
                    .as("end day is outside the interval")
                    .containsExactly(0);
                assertThat(store.expand(new long[] { 0 }, 1, null, new TemporalFilter(to - 1)))
                    .containsExactlyInAnyOrder(0, 1);
                assertThat(store.expand(new long[] { 0 }, 1, null, new TemporalFilter(from - 1)))
                    .containsExactly(0);
            }
        }

        @Test
        @DisplayName("an open interval is admitted by every later snapshot")
        void openInterval(@TempDir Path dir) throws IOException {
            int from = day(2020, 1, 1);
            try (MappedEdgeStore store = build(dir, 2, w -> w
                    .add(0, KNOWS, 1, from, TemporalFilter.OPEN, Provenance.UNKNOWN))) {

                assertThat(store.expand(new long[] { 0 }, 1, null, new TemporalFilter(from)))
                    .containsExactlyInAnyOrder(0, 1);
                assertThat(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2999, 1, 1)))).containsExactlyInAnyOrder(0, 1);
                assertThat(store.expand(new long[] { 0 }, 1, null, new TemporalFilter(from - 1)))
                    .containsExactly(0);
            }
        }

        @Test
        @DisplayName("ALL admits an edge that every snapshot would exclude")
        void allAdmitsClosedIntervals(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 2, w -> w
                    .add(0, KNOWS, 1, day(2020, 1, 1), day(2020, 2, 1), Provenance.UNKNOWN))) {

                assertThat(store.expand(new long[] { 0 }, 1, null, TemporalFilter.ALL))
                    .containsExactlyInAnyOrder(0, 1);
                assertThat(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2024, 1, 1)))).containsExactly(0);
            }
        }
    }

    @Nested
    @DisplayName("supersession")
    class Supersession {

        @Test
        @DisplayName("a later statement closes the prior interval on a single-valued relation")
        void laterStatementCloses(@TempDir Path dir) throws IOException {
            int start2020 = day(2020, 1, 1);
            int start2022 = day(2022, 1, 1);

            EdgeStoreWriter writer = new EdgeStoreWriter()
                .supersessionPolicy(SupersessionPolicy.forRelations(WORKS_AT));
            writer.add(0, WORKS_AT, 1, start2020, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.add(0, WORKS_AT, 2, start2022, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.build(dir, 3);

            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                assertThat(store.expand(new long[] { 0 }, 1, null, new TemporalFilter(day(2021, 1, 1))))
                    .as("only the first employer during 2021")
                    .containsExactlyInAnyOrder(0, 1);
                assertThat(store.expand(new long[] { 0 }, 1, null, new TemporalFilter(day(2023, 1, 1))))
                    .as("only the second employer after the change")
                    .containsExactlyInAnyOrder(0, 2);
            }
        }

        @Test
        @DisplayName("a multi-valued relation keeps every statement open")
        void multiValuedRelationUnaffected(@TempDir Path dir) throws IOException {
            int start2020 = day(2020, 1, 1);
            int start2022 = day(2022, 1, 1);

            EdgeStoreWriter writer = new EdgeStoreWriter()
                .supersessionPolicy(SupersessionPolicy.forRelations(WORKS_AT));
            writer.add(0, KNOWS, 1, start2020, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.add(0, KNOWS, 2, start2022, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.build(dir, 3);

            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                assertThat(sorted(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2023, 1, 1)))))
                    .as("knowing someone new does not stop knowing the first")
                    .containsExactly(0, 1, 2);
            }
        }

        @Test
        @DisplayName("an explicitly ended interval is left alone")
        void explicitEndPreserved(@TempDir Path dir) throws IOException {
            int start2020 = day(2020, 1, 1);
            int end2021 = day(2021, 1, 1);
            int start2022 = day(2022, 1, 1);

            EdgeStoreWriter writer = new EdgeStoreWriter()
                .supersessionPolicy(SupersessionPolicy.forRelations(WORKS_AT));
            writer.add(0, WORKS_AT, 1, start2020, end2021, Provenance.UNKNOWN);
            writer.add(0, WORKS_AT, 2, start2022, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.build(dir, 3);

            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                // The source said the first job ended at the start of 2021, so the gap before the
                // second job began is real and must survive.
                assertThat(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2021, 6, 1))))
                    .as("no employer during the gap")
                    .containsExactly(0);
            }
        }

        @Test
        @DisplayName("statements starting the same day are left ambiguous rather than guessed at")
        void sameDayStatementsNotSuperseded(@TempDir Path dir) throws IOException {
            int sameDay = day(2020, 1, 1);

            EdgeStoreWriter writer = new EdgeStoreWriter()
                .supersessionPolicy(SupersessionPolicy.forRelations(WORKS_AT));
            writer.add(0, WORKS_AT, 1, sameDay, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.add(0, WORKS_AT, 2, sameDay, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.build(dir, 3);

            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                assertThat(sorted(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2021, 1, 1)))))
                    .as("nothing orders these two, so both remain")
                    .containsExactly(0, 1, 2);
            }
        }

        @Test
        @DisplayName("a later statement closes both members of a same-day pair")
        void laterStatementClosesEveryEarlierStatement(@TempDir Path dir) throws IOException {
            // The common shape when two facts are extracted from one document and both take
            // that document's date. Comparing only adjacent statements closed the second of the
            // pair and left the first open forever, so a query years later returned a job the
            // subject had left, presented as current and sitting beside the correct answer.
            int sameDay = day(2020, 1, 1);
            int later = day(2022, 1, 1);

            EdgeStoreWriter writer = new EdgeStoreWriter()
                .supersessionPolicy(SupersessionPolicy.forRelations(WORKS_AT));
            writer.add(0, WORKS_AT, 1, sameDay, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.add(0, WORKS_AT, 2, sameDay, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.add(0, WORKS_AT, 3, later, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.build(dir, 4);

            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                assertThat(sorted(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2021, 1, 1)))))
                    .as("before the change, the ambiguous pair is still current")
                    .containsExactly(0, 1, 2);
                assertThat(sorted(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2023, 1, 1)))))
                    .as("after it, neither member of the pair survives")
                    .containsExactly(0, 3);
            }
        }

        @Test
        @DisplayName("a run of same-day statements is closed as a whole, however long")
        void wholeRunIsClosed(@TempDir Path dir) throws IOException {
            int sameDay = day(2020, 1, 1);
            int later = day(2022, 1, 1);

            EdgeStoreWriter writer = new EdgeStoreWriter()
                .supersessionPolicy(SupersessionPolicy.forRelations(WORKS_AT));
            for (int object = 1; object <= 5; object++) {
                writer.add(0, WORKS_AT, object, sameDay, TemporalFilter.OPEN, Provenance.UNKNOWN);
            }
            writer.add(0, WORKS_AT, 6, later, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.build(dir, 7);

            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                assertThat(sorted(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2023, 1, 1)))))
                    .as("every member of the run is superseded, not just the last")
                    .containsExactly(0, 6);
            }
        }

        @Test
        @DisplayName("successive same-day runs each close the ones before them")
        void successiveRunsClose(@TempDir Path dir) throws IOException {
            int first = day(2020, 1, 1);
            int second = day(2021, 1, 1);
            int third = day(2022, 1, 1);

            EdgeStoreWriter writer = new EdgeStoreWriter()
                .supersessionPolicy(SupersessionPolicy.forRelations(WORKS_AT));
            writer.add(0, WORKS_AT, 1, first, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.add(0, WORKS_AT, 2, first, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.add(0, WORKS_AT, 3, second, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.add(0, WORKS_AT, 4, second, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.add(0, WORKS_AT, 5, third, TemporalFilter.OPEN, Provenance.UNKNOWN);
            writer.build(dir, 6);

            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                assertThat(sorted(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2020, 6, 1))))).containsExactly(0, 1, 2);
                assertThat(sorted(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2021, 6, 1))))).containsExactly(0, 3, 4);
                assertThat(sorted(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2022, 6, 1))))).containsExactly(0, 5);
            }
        }

        @Test
        @DisplayName("supersession is off by default")
        void noSupersessionByDefault(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 3, w -> w
                    .add(0, WORKS_AT, 1, day(2020, 1, 1), TemporalFilter.OPEN, Provenance.UNKNOWN)
                    .add(0, WORKS_AT, 2, day(2022, 1, 1), TemporalFilter.OPEN, Provenance.UNKNOWN))) {

                assertThat(sorted(store.expand(new long[] { 0 }, 1, null,
                    new TemporalFilter(day(2023, 1, 1))))).containsExactly(0, 1, 2);
            }
        }
    }

    @Nested
    @DisplayName("provenance")
    class ProvenanceColumns {

        @Test
        @DisplayName("provenance survives the round-trip in forward edge order")
        void provenanceRoundTrip(@TempDir Path dir) throws IOException {
            Provenance kb = new Provenance(SourceType.KNOWLEDGE_BASE, 42L, 95, 7);
            Provenance chat = new Provenance(SourceType.CONVERSATION, 1234L, 30, 9);

            try (MappedEdgeStore store = build(dir, 3, w -> w
                    .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN, kb)
                    .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN, chat))) {

                // Forward order is (subject, relation, object), so edge 0 is 0-knows->1.
                assertThat(store.provenanceOf(0)).isEqualTo(kb);
                assertThat(store.provenanceOf(1)).isEqualTo(chat);
            }
        }

        @Test
        @DisplayName("confidence spans the full byte range without wrapping")
        void confidenceRange(@TempDir Path dir) throws IOException {
            Provenance certain = new Provenance(SourceType.KNOWLEDGE_BASE, 1L, 100, 1);
            Provenance doubtful = new Provenance(SourceType.INFERRED, 2L, 0, 1);

            try (MappedEdgeStore store = build(dir, 3, w -> w
                    .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN, certain)
                    .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN, doubtful))) {

                assertThat(store.provenanceOf(0).confidence()).isEqualTo(100);
                assertThat(store.provenanceOf(1).confidence()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("store lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("an empty store opens and answers")
        void emptyStore(@TempDir Path dir) throws IOException {
            new EdgeStoreWriter().build(dir, 3);
            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                assertThat(store.edgeCount()).isZero();
                assertThat(store.entityCount()).isEqualTo(3);
                assertThat(store.neighbors(0, null)).isEmpty();
                assertThat(store.count(0, RelationSelector.forward(KNOWS))).isZero();
                assertThat(store.expand(new long[] { 0 }, 3, null, TemporalFilter.ALL))
                    .containsExactly(0);
            }
        }

        @Test
        @DisplayName("a store with no entities at all opens")
        void zeroEntities(@TempDir Path dir) throws IOException {
            new EdgeStoreWriter().build(dir, 0);
            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                assertThat(store.entityCount()).isZero();
                assertThat(store.expand(new long[0], 1, null, TemporalFilter.ALL)).isEmpty();
            }
        }

        @Test
        @DisplayName("close is idempotent")
        void closeIsIdempotent(@TempDir Path dir) throws IOException {
            MappedEdgeStore store = build(dir, 2, w -> w.add(0, KNOWS, 1));
            store.close();
            store.close();
        }

        @Test
        @DisplayName("reading after close fails rather than reading unmapped memory")
        void readAfterClose(@TempDir Path dir) throws IOException {
            MappedEdgeStore store = build(dir, 2, w -> w.add(0, KNOWS, 1));
            store.close();
            assertThatThrownBy(() -> store.neighbors(0, null))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("the resource cost an open store reports matches what it actually holds")
        void resourceCostIsReal(@TempDir Path dir) throws IOException {
            // A namespace registry budgets against vm.max_map_count with these numbers, so a guess
            // here means either evicting too eagerly or finding the kernel limit by hitting it.
            try (MappedEdgeStore store = build(dir, 3, w -> w.add(0, KNOWS, 1))) {
                assertThat(MappedEdgeStore.mappingCount())
                    .as("one mapping per column file, whatever the store's size")
                    .isEqualTo(com.eonmux.excalibur.graph.format.EdgeColumn.values().length);
                assertThat(MappedEdgeStore.descriptorCount())
                    .as("a mapping outlives its channel, so the channels are already closed")
                    .isZero();
                assertThat(store.edgeCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("an entity id beyond entityCount is refused at build time")
        void entityCountMustCoverIds(@TempDir Path dir) {
            EdgeStoreWriter writer = new EdgeStoreWriter().add(0, KNOWS, 9);
            assertThatThrownBy(() -> writer.build(dir, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not cover entity id 9");
        }
    }

    @Nested
    @DisplayName("relation id boundaries")
    class RelationBoundaries {

        @Test
        @DisplayName("the largest relation id is visible to filtered lookups")
        void largestRelationIdIsUsable(@TempDir Path dir) throws IOException {
            // The run's upper bound used to be found by searching for relation + 1, which
            // overflows here: the bracket collapsed and every filtered lookup answered as if the
            // edge did not exist. The selector's ordering is total over this id and the writer
            // accepts it, so a deployment reserving it as a sentinel got silence.
            try (MappedEdgeStore store = build(dir, 8, w -> w
                    .add(0, Integer.MAX_VALUE, 5)
                    .add(0, KNOWS, 6))) {

                assertThat(sorted(store.neighbors(0, null))).containsExactly(5, 6);
                assertThat(store.neighbors(0,
                        new int[] { RelationSelector.forward(Integer.MAX_VALUE) }))
                    .containsExactly(5);
                assertThat(store.neighbors(5,
                        new int[] { RelationSelector.reverse(Integer.MAX_VALUE) }))
                    .containsExactly(0);
                assertThat(store.count(0, RelationSelector.forward(Integer.MAX_VALUE)))
                    .isEqualTo(1);
                assertThat(store.count(5, RelationSelector.reverse(Integer.MAX_VALUE)))
                    .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("an ordinary relation id is unaffected")
        void ordinaryRelationIdStillWorks(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 8, w -> w
                    .add(0, Integer.MAX_VALUE, 5)
                    .add(0, KNOWS, 6))) {

                assertThat(store.neighbors(0, new int[] { RelationSelector.forward(KNOWS) }))
                    .containsExactly(6);
                assertThat(store.count(0, RelationSelector.forward(KNOWS))).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("per-entity walk")
    class PerEntityWalk {

        @Test
        @DisplayName("visits exactly one entity's forward edges")
        void visitsOneEntitysEdges(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(0, KNOWS, 1)
                    .add(0, WORKS_AT, 3)
                    .add(1, KNOWS, 2))) {

                java.util.List<String> seen = new java.util.ArrayList<>();
                store.forEachEdgeOf(0, (subject, relation, object, index) ->
                    seen.add(subject + "-" + relation + "->" + object));

                assertThat(seen).containsExactlyInAnyOrder("0-0->1", "0-1->3");
            }
        }

        @Test
        @DisplayName("agrees with the full walk, entity by entity")
        void agreesWithTheFullWalk(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 6, w -> w
                    .add(0, KNOWS, 1)
                    .add(0, KNOWS, 2)
                    .add(2, WORKS_AT, 3)
                    .add(4, LOCATED_IN, 5))) {

                java.util.List<String> whole = new java.util.ArrayList<>();
                store.forEachEdge((subject, relation, object, index) ->
                    whole.add(subject + "-" + relation + "->" + object + "@" + index));

                java.util.List<String> perEntity = new java.util.ArrayList<>();
                for (long entity = 0; entity < 6; entity++) {
                    store.forEachEdgeOf(entity, (subject, relation, object, index) ->
                        perEntity.add(subject + "-" + relation + "->" + object + "@" + index));
                }

                assertThat(perEntity).isEqualTo(whole);
            }
        }

        @Test
        @DisplayName("an entity with no edges visits nothing")
        void anIsolatedEntityVisitsNothing(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 5, w -> w.add(0, KNOWS, 1))) {
                java.util.List<String> seen = new java.util.ArrayList<>();
                store.forEachEdgeOf(4, (s, r, o, i) -> seen.add("x"));
                assertThat(seen).isEmpty();
            }
        }
    }
}
