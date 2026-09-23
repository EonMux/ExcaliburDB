package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a candidate set restricts.
 *
 * <p>A pre-filter names the nodes worth looking at, and the search is asked for the nearest of
 * them. The set it names is a scattering of node ids drawn from wherever the filter's buckets
 * happened to fall - not a connected region of the graph - so a walk that may only step from one
 * named node to another is walking an edgeless subgraph and comes back with whatever it started
 * on. These tests pin the answer to the question actually asked: the nearest members of the set,
 * however the graph happens to link them.</p>
 */
@DisplayName("Candidate-restricted search")
class CandidateRestrictedSearchTest {

    private static final int DIMENSION = 16;
    private static final int NODE_COUNT = 400;

    /**
     * Small on purpose. It is the beam the graph walk fills, and it also sets where measuring
     * every named node stops being the cheaper answer - at {@code sqrt(ef * nodeCount)} names,
     * eighty here - so both sides of that line stay reachable from a few hundred nodes.
     */
    private static final int EF_SEARCH = 16;

    private static final int TOP_K = 5;

    /** Where measuring every name gives way to walking the graph: {@code |C|² > ef * nodeCount}. */
    private static final int CROSSOVER = (int) Math.sqrt((long) EF_SEARCH * NODE_COUNT);

    @TempDir
    Path tempDir;

    private final DistanceFunction distance = L2Distance.getInstance();
    private final Map<Long, float[]> vectorsById = new HashMap<>();
    private HnswGraph graph;

    @BeforeEach
    void insertACorpus() throws IOException {
        HnswConfig config = new HnswConfig(
            HnswConfig.DEFAULT_MAX_CONNECTIONS,
            HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0,
            HnswConfig.DEFAULT_EF_CONSTRUCTION,
            EF_SEARCH,
            HnswConfig.DEFAULT_ML,
            DIMENSION);

        graph = new HnswGraph(tempDir.resolve("candidates.hnsw"), config, distance, NODE_COUNT + 16);

        Random random = new Random(20240517L);
        for (int i = 0; i < NODE_COUNT; i++) {
            float[] vector = randomVector(random);
            vectorsById.put(graph.insert(vector), vector);
        }
    }

    @AfterEach
    void closeGraph() {
        if (graph != null) {
            graph.close();
        }
    }

    @Test
    @DisplayName("a small scattered set is answered exactly")
    void aSmallScatteredSetIsAnsweredExactly() {
        // Eleven nodes spread across four hundred. Under M=16 the chance that any two of them are
        // linked is small, so a walk confined to the set could not leave the one it started on.
        Set<Long> candidates = everyNthNode(37);
        assertThat(candidates).hasSizeLessThanOrEqualTo(CROSSOVER);

        float[] query = randomVector(new Random(99L));

        assertThat(idsOf(graph.search(query, TOP_K, candidates)))
            .containsExactlyElementsOf(nearestWithin(candidates, query, TOP_K));
    }

    @Test
    @DisplayName("a set too large to measure is walked and still yields its nearest")
    void aSetPastTheCrossoverStillYieldsItsNearest() {
        // A hundred nodes: past the point where measuring every one of them is the cheaper answer,
        // so this is the graph walk, reaching named nodes through the unnamed ones between them.
        Set<Long> candidates = everyNthNode(4);
        assertThat(candidates).hasSizeGreaterThan(CROSSOVER);

        float[] query = randomVector(new Random(1234L));

        List<Long> found = idsOf(graph.search(query, TOP_K, candidates));

        assertThat(found).hasSize(TOP_K).isSubsetOf(candidates);
        assertThat(found).containsExactlyElementsOf(nearestWithin(candidates, query, TOP_K));
    }

    @Test
    @DisplayName("a set holding every node answers as an unrestricted search does")
    void acceptingEveryNodeMatchesAnUnrestrictedSearch() {
        Set<Long> everyNode = new LinkedHashSet<>(vectorsById.keySet());
        float[] query = randomVector(new Random(4321L));

        assertThat(idsOf(graph.search(query, TOP_K, everyNode)))
            .isEqualTo(idsOf(graph.search(query, TOP_K)));
    }

    @Test
    @DisplayName("an empty set names nothing admissible")
    void anEmptySetYieldsNothing() {
        assertThat(graph.search(randomVector(new Random(7L)), TOP_K, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("no set at all is no restriction")
    void aNullSetSearchesTheWholeGraph() {
        float[] query = randomVector(new Random(8L));

        assertThat(idsOf(graph.search(query, TOP_K, null)))
            .isEqualTo(idsOf(graph.search(query, TOP_K)));
    }

    @Test
    @DisplayName("names that match no node are passed over rather than counted")
    void unknownNamesAreSkipped() {
        Set<Long> candidates = new LinkedHashSet<>(everyNthNode(37));
        List<Long> real = nearestWithin(candidates, query(), TOP_K);
        candidates.add((long) NODE_COUNT + 500);
        candidates.add(-1L);

        assertThat(idsOf(graph.search(query(), TOP_K, candidates)))
            .containsExactlyElementsOf(real);
    }

    @Test
    @DisplayName("a k as large as an int holds is answered in full rather than overflowing the beam")
    void aSaturatedKReturnsEveryAdmissibleNode() {
        // k is client-controlled and widens the beam; at Integer.MAX_VALUE, sizing the beam from
        // it overflowed, and doubling it for a restricted walk went negative.
        float[] query = randomVector(new Random(55L));
        Set<Long> candidates = everyNthNode(4);
        assertThat(candidates).hasSizeGreaterThan(CROSSOVER);

        assertThat(graph.search(query, Integer.MAX_VALUE)).hasSize(NODE_COUNT);
        assertThat(idsOf(graph.search(query, Integer.MAX_VALUE, candidates)))
            .containsExactlyElementsOf(nearestWithin(candidates, query, candidates.size()));
    }

    /**
     * How far the walk goes before it stops.
     *
     * <p>Stopping as soon as the frontier passes the worst result held is the right rule while
     * every node walked is also kept: the frontier is then a subset of the results and cannot
     * pass them. Once only named nodes are kept that no longer holds, and a single well-placed
     * name ends the search holding one answer where five were asked for.</p>
     *
     * <p>The corpus here is a line of evenly spaced points, so which nodes neighbour which is
     * fixed by the geometry rather than by the levels the graph draws at random, and the walk
     * this pins down is the same one every run.</p>
     */
    @Nested
    @DisplayName("A walk that starts on a name and must leave it behind")
    class ALineOfPoints {

        private static final int LINE_LENGTH = 1000;
        private static final int LINE_EF_SEARCH = 10;

        /** Node 500 sits all but on top of it; every other name is a long way off. */
        private static final float QUERY_POSITION = 5.003f;

        private static final long NEAR_NAME = 500L;
        private static final int FAR_NAMES = 120;

        @TempDir
        Path lineDir;

        private final Map<Long, float[]> lineVectors = new HashMap<>();
        private HnswGraph lineGraph;

        @BeforeEach
        void layOutALine() throws IOException {
            HnswConfig config = new HnswConfig(
                HnswConfig.DEFAULT_MAX_CONNECTIONS,
                HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0,
                HnswConfig.DEFAULT_EF_CONSTRUCTION,
                LINE_EF_SEARCH,
                HnswConfig.DEFAULT_ML,
                DIMENSION);

            lineGraph = new HnswGraph(
                lineDir.resolve("line.hnsw"), config, distance, LINE_LENGTH + 16);

            for (int i = 0; i < LINE_LENGTH; i++) {
                float[] vector = pointAt(i * 0.01f);
                lineVectors.put(lineGraph.insert(vector), vector);
            }
        }

        @AfterEach
        void closeLineGraph() {
            if (lineGraph != null) {
                lineGraph.close();
            }
        }

        @Test
        @DisplayName("does not stop on the one name it starts next to")
        void theWalkCarriesOnPastItsFirstResult() {
            // One name under the query and a hundred and twenty far down the line. The walk starts
            // on the near one, and everything it can reach next is further away than that.
            Set<Long> candidates = new LinkedHashSet<>();
            candidates.add(NEAR_NAME);
            for (long id = 0; id < FAR_NAMES; id++) {
                candidates.add(id);
            }
            assertThat(candidates)
                .hasSizeGreaterThan((int) Math.sqrt((long) LINE_EF_SEARCH * LINE_LENGTH));

            float[] query = pointAt(QUERY_POSITION);
            List<Long> expected = candidates.stream()
                .sorted(Comparator.comparingDouble(
                    id -> distance.distance(query, lineVectors.get(id))))
                .limit(TOP_K)
                .toList();

            assertThat(idsOf(lineGraph.search(query, TOP_K, candidates)))
                .containsExactlyElementsOf(expected);
        }

        private float[] pointAt(float position) {
            float[] vector = new float[DIMENSION];
            vector[0] = position;
            return vector;
        }
    }

    private float[] query() {
        return randomVector(new Random(99L));
    }

    private Set<Long> everyNthNode(int stride) {
        Set<Long> candidates = new LinkedHashSet<>();
        for (long id = 0; id < NODE_COUNT; id += stride) {
            candidates.add(id);
        }
        return candidates;
    }

    /** The answer the search is being asked for, computed the slow and certain way. */
    private List<Long> nearestWithin(Set<Long> candidates, float[] query, int k) {
        return candidates.stream()
            .sorted(Comparator.comparingDouble(id -> distance.distance(query, vectorsById.get(id))))
            .limit(k)
            .toList();
    }

    private static List<Long> idsOf(List<HnswSearchResult> results) {
        List<Long> ids = new ArrayList<>(results.size());
        for (HnswSearchResult result : results) {
            ids.add(result.nodeId());
        }
        return ids;
    }

    private static float[] randomVector(Random random) {
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = random.nextFloat();
        }
        return vector;
    }
}
