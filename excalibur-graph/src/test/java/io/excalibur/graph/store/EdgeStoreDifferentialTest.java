package io.excalibur.graph.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the mapped CSR store against a naive scan over the same edges.
 *
 * <p>The mapped store answers questions with binary searches into sorted runs, a transposed second
 * index and a permutation between them. Each of those is an opportunity to be subtly wrong in a way
 * that a hand-written example would not reach — an off-by-one at a run boundary, a relation whose
 * run is empty, an entity whose id is never mentioned, a reverse lookup landing on the neighbouring
 * edge's interval. Generating graphs and comparing every answer against a scan is what covers that
 * space.
 *
 * <p>Seeded, so a failure names the exact graph that produced it.
 */
class EdgeStoreDifferentialTest {

    private static final int RELATION_COUNT = 5;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = { 1L, 2L, 3L, 17L, 42L, 99L, 12345L })
    @DisplayName("every answer matches a naive scan over the same edges")
    void matchesReference(long seed, @TempDir Path dir) throws IOException {
        Random random = new Random(seed);
        int entityCount = 20 + random.nextInt(60);
        int edgeCount = 30 + random.nextInt(200);

        List<ReferenceEdgeStore.Edge> edges = new ArrayList<>(edgeCount);
        EdgeStoreWriter writer = new EdgeStoreWriter();
        for (int i = 0; i < edgeCount; i++) {
            int subject = random.nextInt(entityCount);
            int object = random.nextInt(entityCount);
            int relation = random.nextInt(RELATION_COUNT);
            int from = random.nextInt(1000);
            // A third of the edges stay open; the rest close at a random later day.
            int to = random.nextInt(3) == 0
                ? TemporalFilter.OPEN
                : from + 1 + random.nextInt(1000);

            edges.add(new ReferenceEdgeStore.Edge(subject, relation, object, from, to));
            writer.add(subject, relation, object, from, to, Provenance.UNKNOWN);
        }
        writer.build(dir, entityCount);

        ReferenceEdgeStore reference = new ReferenceEdgeStore(edges, entityCount);
        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {

            for (int entity = 0; entity < entityCount; entity++) {
                assertThat(sorted(store.neighbors(entity, null)))
                    .as("neighbours of %d, all relations", entity)
                    .isEqualTo(sorted(reference.neighbors(entity, null)));

                for (int relation = 0; relation < RELATION_COUNT; relation++) {
                    int[] forward = { RelationSelector.forward(relation) };
                    int[] backward = { RelationSelector.reverse(relation) };

                    assertThat(sorted(store.neighbors(entity, forward)))
                        .as("neighbours of %d along ->%d", entity, relation)
                        .isEqualTo(sorted(reference.neighbors(entity, forward)));

                    assertThat(sorted(store.neighbors(entity, backward)))
                        .as("neighbours of %d along <-%d", entity, relation)
                        .isEqualTo(sorted(reference.neighbors(entity, backward)));

                    assertThat(store.count(entity, RelationSelector.forward(relation)))
                        .as("out-degree of %d along %d", entity, relation)
                        .isEqualTo(reference.count(entity, RelationSelector.forward(relation)));

                    assertThat(store.count(entity, RelationSelector.reverse(relation)))
                        .as("in-degree of %d along %d", entity, relation)
                        .isEqualTo(reference.count(entity, RelationSelector.reverse(relation)));
                }
            }

            // Expansion at several depths and several snapshots, including a day inside the range
            // where roughly half the intervals have closed.
            int[] mixedSelectors = {
                RelationSelector.forward(0), RelationSelector.reverse(1),
                RelationSelector.forward(2) };

            for (int asOfDay : new int[] { 0, 250, 500, 900, 1500 }) {
                TemporalFilter asOf = new TemporalFilter(asOfDay);
                for (int hops = 0; hops <= 3; hops++) {
                    long[] seeds = { random.nextInt(entityCount) };

                    assertThat(sorted(store.expand(seeds, hops, null, asOf)))
                        .as("expand %s %d hops as of %d, all relations",
                            Arrays.toString(seeds), hops, asOfDay)
                        .isEqualTo(sorted(reference.expand(seeds, hops, null, asOf)));

                    assertThat(sorted(store.expand(seeds, hops, mixedSelectors, asOf)))
                        .as("expand %s %d hops as of %d, mixed directions",
                            Arrays.toString(seeds), hops, asOfDay)
                        .isEqualTo(sorted(reference.expand(seeds, hops, mixedSelectors, asOf)));
                }
            }
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = { 7L, 21L })
    @DisplayName("a frontier wide enough to fan out across threads still matches the scan")
    void wideFrontierMatchesReference(long seed, @TempDir Path dir) throws IOException {
        // Above FrontierExpander.PARALLEL_THRESHOLD the hop is split across virtual threads and
        // merged. A dense graph with several hundred entities crosses that on the first hop.
        Random random = new Random(seed);
        int entityCount = 600;
        int edgeCount = 4000;

        List<ReferenceEdgeStore.Edge> edges = new ArrayList<>(edgeCount);
        EdgeStoreWriter writer = new EdgeStoreWriter();
        for (int i = 0; i < edgeCount; i++) {
            int subject = random.nextInt(entityCount);
            int object = random.nextInt(entityCount);
            int relation = random.nextInt(RELATION_COUNT);
            edges.add(new ReferenceEdgeStore.Edge(
                subject, relation, object, Integer.MIN_VALUE, TemporalFilter.OPEN));
            writer.add(subject, relation, object,
                Integer.MIN_VALUE, TemporalFilter.OPEN, Provenance.UNKNOWN);
        }
        writer.build(dir, entityCount);

        ReferenceEdgeStore reference = new ReferenceEdgeStore(edges, entityCount);
        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            long[] seeds = { 0, 1, 2, 3 };
            for (int hops = 1; hops <= 3; hops++) {
                assertThat(sorted(store.expand(seeds, hops, null, TemporalFilter.ALL)))
                    .as("%d hops over a wide frontier", hops)
                    .isEqualTo(sorted(reference.expand(seeds, hops, null, TemporalFilter.ALL)));
            }
        }
    }

    private static long[] sorted(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }
}
