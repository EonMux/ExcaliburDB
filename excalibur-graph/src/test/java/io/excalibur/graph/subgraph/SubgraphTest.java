package io.excalibur.graph.subgraph;

import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is actually handed to the model.
 *
 * <p>Two properties here are correctness rather than tidiness. Deduplication is the first: the
 * same entity is routinely reached by several paths, and serialising it once per path spends
 * the token budget twice over and presents the model with what looks like corroboration from
 * independent sources when it is one fact seen repeatedly. {@link Subgraph#meanConfidence()} is
 * the second: it is an input to abstention, so a subgraph assembled entirely from weak
 * extractions has to be distinguishable from a strong one however well either matched the
 * query.</p>
 */
@DisplayName("Subgraph")
class SubgraphTest {

    private static Provenance provenance(double confidence) {
        return Provenance.of(SourceType.DOCUMENT, 1L, confidence, 1);
    }

    private static SubgraphEdge edge(int subject, int object, long index, int depth) {
        return new SubgraphEdge(subject, 0, object, index, 0, TemporalFilter.OPEN,
            provenance(0.5), depth);
    }

    private static SubgraphEdge edge(int subject, int object, long index, int depth,
                                     double confidence) {
        return new SubgraphEdge(subject, 0, object, index, 0, TemporalFilter.OPEN,
            provenance(confidence), depth);
    }

    @Nested
    @DisplayName("empty")
    class Empty {

        @Test
        @DisplayName("an empty subgraph has nothing to serialise")
        void emptyIsEmpty() {
            Subgraph subgraph = Subgraph.empty();

            assertThat(subgraph.isEmpty()).isTrue();
            assertThat(subgraph.entityCount()).isZero();
            assertThat(subgraph.edgeCount()).isZero();
            assertThat(subgraph.entities()).isEmpty();
            assertThat(subgraph.seeds()).isEmpty();
            assertThat(subgraph.edges()).isEmpty();
            assertThat(subgraph.seedList()).isEmpty();
        }

        @Test
        @DisplayName("an empty subgraph reports zero depth and zero confidence")
        void emptyAggregatesAreZero() {
            // meanConfidence of nothing must not be NaN: it feeds a threshold comparison, and
            // every comparison against NaN is false, so an abstention policy would silently
            // stop firing rather than abstain.
            assertThat(Subgraph.empty().maxDepth()).isZero();
            assertThat(Subgraph.empty().meanConfidence()).isZero();
            assertThat(Double.isNaN(Subgraph.empty().meanConfidence())).isFalse();
        }

        @Test
        @DisplayName("a subgraph holding only seeds is not empty")
        void seedsAloneAreNotEmpty() {
            // There are entities to serialise even with no edge between them.
            Subgraph subgraph = new Subgraph.Builder().seed(7L).build();

            assertThat(subgraph.isEmpty()).isFalse();
            assertThat(subgraph.entityCount()).isEqualTo(1);
            assertThat(subgraph.edgeCount()).isZero();
        }
    }

    @Nested
    @DisplayName("deduplication")
    class Deduplication {

        @Test
        @DisplayName("an edge reached from both ends is kept once")
        void repeatedEdgesAreCollapsed() {
            SubgraphEdge first = edge(1, 2, 100L, 1);
            SubgraphEdge again = edge(1, 2, 100L, 3);

            Subgraph subgraph = new Subgraph.Builder().edge(first).edge(again).build();

            assertThat(subgraph.edgeCount()).isEqualTo(1);
            assertThat(subgraph.edges()).containsExactly(first);
            assertThat(subgraph.maxDepth())
                .as("the first arrival wins, so the shallower depth is the one kept")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("an entity reached by several paths appears once")
        void repeatedEntitiesAreCollapsed() {
            Subgraph subgraph = new Subgraph.Builder()
                .edge(edge(1, 2, 10L, 1))
                .edge(edge(2, 3, 11L, 2))
                .edge(edge(3, 1, 12L, 2))
                .build();

            assertThat(subgraph.entities()).containsExactly(1L, 2L, 3L);
            assertThat(subgraph.entityCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a seed that is also an edge endpoint is not counted twice")
        void seedsAndEndpointsShareOneEntitySet() {
            Subgraph subgraph = new Subgraph.Builder()
                .seed(1L)
                .edge(edge(1, 2, 10L, 1))
                .build();

            assertThat(subgraph.entities()).containsExactly(1L, 2L);
            assertThat(subgraph.seeds()).containsExactly(1L);
        }

        @Test
        @DisplayName("the same seed recorded twice is one entity")
        void repeatedSeedsAreCollapsed() {
            Subgraph subgraph = new Subgraph.Builder().seed(5L).seed(5L).build();

            assertThat(subgraph.seeds()).containsExactly(5L);
            assertThat(subgraph.entityCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("insertion order is preserved, so serialisation is deterministic")
        void orderIsPreserved() {
            Subgraph subgraph = new Subgraph.Builder()
                .seed(9L)
                .seed(3L)
                .edge(edge(4, 1, 10L, 1))
                .build();

            assertThat(subgraph.seeds()).containsExactly(9L, 3L);
            assertThat(subgraph.entities()).containsExactly(9L, 3L, 4L, 1L);
        }
    }

    @Nested
    @DisplayName("aggregates")
    class Aggregates {

        @Test
        @DisplayName("maxDepth is the greatest depth any edge was reached at")
        void maxDepthIsTheGreatest() {
            Subgraph subgraph = new Subgraph.Builder()
                .edge(edge(1, 2, 10L, 1))
                .edge(edge(2, 3, 11L, 4))
                .edge(edge(3, 4, 12L, 2))
                .build();

            assertThat(subgraph.maxDepth()).isEqualTo(4);
        }

        @Test
        @DisplayName("meanConfidence averages the edges")
        void meanConfidenceAverages() {
            Subgraph subgraph = new Subgraph.Builder()
                .edge(edge(1, 2, 10L, 1, 0.2))
                .edge(edge(2, 3, 11L, 1, 0.8))
                .build();

            assertThat(subgraph.meanConfidence()).isCloseTo(0.5, within());
        }

        @Test
        @DisplayName("a subgraph of weak extractions is distinguishable from a strong one")
        void weakAndStrongSubgraphsDiffer() {
            // The whole point of the value: abstention has to be able to tell these apart.
            Subgraph weak = new Subgraph.Builder()
                .edge(edge(1, 2, 10L, 1, 0.1)).edge(edge(2, 3, 11L, 1, 0.2)).build();
            Subgraph strong = new Subgraph.Builder()
                .edge(edge(1, 2, 10L, 1, 0.9)).edge(edge(2, 3, 11L, 1, 1.0)).build();

            assertThat(weak.meanConfidence()).isLessThan(strong.meanConfidence());
        }

        @Test
        @DisplayName("seeds contribute no confidence of their own")
        void seedsDoNotDiluteConfidence() {
            Subgraph subgraph = new Subgraph.Builder()
                .seed(1L).seed(2L).seed(3L)
                .edge(edge(1, 2, 10L, 1, 0.6))
                .build();

            assertThat(subgraph.meanConfidence()).isCloseTo(0.6, within());
        }

        private static org.assertj.core.data.Offset<Double> within() {
            return org.assertj.core.data.Offset.offset(1e-9);
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("mutating a returned array does not change the subgraph")
        void arraysAreDefensivelyCopied() {
            Subgraph subgraph = new Subgraph.Builder().seed(1L).edge(edge(1, 2, 10L, 1)).build();

            long[] seeds = subgraph.seeds();
            long[] entities = subgraph.entities();
            seeds[0] = 999L;
            entities[0] = 999L;

            assertThat(subgraph.seeds()).containsExactly(1L);
            assertThat(subgraph.entities()).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("the edge list cannot be added to")
        void edgesAreUnmodifiable() {
            Subgraph subgraph = new Subgraph.Builder().edge(edge(1, 2, 10L, 1)).build();

            assertThatThrownBy(() -> subgraph.edges().add(edge(3, 4, 11L, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("the seed list cannot be added to")
        void seedListIsUnmodifiable() {
            Subgraph subgraph = new Subgraph.Builder().seed(1L).build();
            List<Long> seeds = subgraph.seedList();

            assertThat(seeds).containsExactly(1L);
            assertThatThrownBy(() -> seeds.add(2L))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("building twice does not entangle the two results")
        void buildingTwiceIsIndependent() {
            Subgraph.Builder builder = new Subgraph.Builder().seed(1L);
            Subgraph first = builder.build();
            builder.edge(edge(2, 3, 10L, 1));
            Subgraph second = builder.build();

            assertThat(first.edgeCount())
                .as("a subgraph already handed out must not gain edges afterwards")
                .isZero();
            assertThat(first.entities()).containsExactly(1L);
            assertThat(second.edgeCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("builder contract")
    class BuilderContract {

        @Test
        @DisplayName("a null edge is refused rather than stored")
        void nullEdgesAreRefused() {
            assertThatThrownBy(() -> new Subgraph.Builder().edge(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        }

        @Test
        @DisplayName("edgeCount tracks what has been accumulated so far")
        void edgeCountTracksAccumulation() {
            Subgraph.Builder builder = new Subgraph.Builder();
            assertThat(builder.edgeCount()).isZero();

            builder.edge(edge(1, 2, 10L, 1));
            assertThat(builder.edgeCount()).isEqualTo(1);

            builder.edge(edge(1, 2, 10L, 1));
            assertThat(builder.edgeCount())
                .as("a duplicate does not advance the count a budget is checked against")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("toString reports the shape without dumping the contents")
        void toStringSummarises() {
            Subgraph subgraph = new Subgraph.Builder().edge(edge(1, 2, 10L, 2)).build();

            assertThat(subgraph.toString()).isEqualTo("Subgraph[2 entities, 1 edges, depth<=2]");
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        @DisplayName("an edge exposes its extractor confidence as a fraction")
        void confidenceIsAFraction() {
            assertThat(edge(1, 2, 10L, 0, 0.75).confidence()).isCloseTo(0.75,
                org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("an edge without provenance is refused")
        void provenanceIsRequired() {
            assertThatThrownBy(() -> new SubgraphEdge(1, 0, 2, 10L, 0,
                TemporalFilter.OPEN, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provenance");
        }

        @Test
        @DisplayName("a negative depth is refused")
        void negativeDepthIsRefused() {
            assertThatThrownBy(() -> new SubgraphEdge(1, 0, 2, 10L, 0,
                TemporalFilter.OPEN, Provenance.UNKNOWN, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth");
        }

        @Test
        @DisplayName("an open-ended edge carries the open sentinel, not a date")
        void openEdgesCarryTheSentinel() {
            SubgraphEdge open = edge(1, 2, 10L, 0);

            assertThat(open.validTo()).isEqualTo(TemporalFilter.OPEN);
            assertThat(open.subject()).isEqualTo(1);
            assertThat(open.object()).isEqualTo(2);
            assertThat(open.forwardEdgeIndex()).isEqualTo(10L);
        }
    }
}
