package com.eonmux.excalibur.vector.quantization;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the PQ distance table is built for the metric the caller actually ranks with.
 *
 * <p>It was not: the table always computed squared L2, whatever the graph was configured with.
 * Cosine is the default in this system, so every PQ-accelerated search on a production
 * deployment ranked by a geometry the graph was not built with, and returned values on a range
 * the caller's threshold was never calibrated against. Nothing failed - the search returned
 * results, just the wrong ones - which is why this needs a test that compares rankings rather
 * than one that checks a search returns something.
 */
@DisplayName("PQ distance table metric")
class PQDistanceTableMetricTest {

    private static final int DIMENSION = 32;
    private static final int SUBSPACES = 8;
    private static final int CENTROIDS = 16;

    /** Unit-norm vectors: what an embedding model produces, and where cosine decomposes. */
    private static float[] unitVector(Random random) {
        float[] vector = new float[DIMENSION];
        double norm = 0;
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += (double) vector[i] * vector[i];
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    private static ProductQuantizer trained(Random random) {
        float[][] training = new float[512][];
        for (int i = 0; i < training.length; i++) {
            training[i] = unitVector(random);
        }
        ProductQuantizer pq = new ProductQuantizer(DIMENSION, SUBSPACES, CENTROIDS);
        pq.train(training);
        return pq;
    }

    @Test
    @DisplayName("a cosine table is not an L2 table")
    void metricChangesTheTable() {
        Random random = new Random(20260830L);
        ProductQuantizer pq = trained(random);
        float[] query = unitVector(random);

        PQDistanceTable l2 = pq.createDistanceTable(query, L2Distance.getInstance());
        PQDistanceTable cosine = pq.createDistanceTable(query, CosineSimilarity.getInstance());

        byte[] codes = pq.encode(unitVector(random));

        assertThat(cosine.distanceFunction()).isSameAs(CosineSimilarity.getInstance());
        assertThat(cosine.lookup(codes)).isNotEqualTo(l2.lookup(codes));
    }

    @Test
    @DisplayName("the default table is still squared L2, for callers that ask for nothing")
    void defaultRemainsL2() {
        Random random = new Random(7L);
        ProductQuantizer pq = trained(random);
        float[] query = unitVector(random);
        byte[] codes = pq.encode(unitVector(random));

        assertThat(pq.createDistanceTable(query).lookup(codes))
            .isEqualTo(pq.createDistanceTable(query, L2Distance.getInstance()).lookup(codes));
    }

    @Test
    @DisplayName("a cosine table approximates the cosine dissimilarity, on its scale")
    void cosineTableIsOnTheCosineScale() {
        // Scale, not ranking, is where this bug bites. For unit-norm vectors squared L2 is a
        // monotone function of cosine, so both tables rank the same; what differs is the value.
        // A cosine graph's search mixes PQ lookups with exact distances in one priority queue
        // and normalises the result against cosine's range, so a table returning squared L2
        // (0 to 4) where cosine dissimilarity (-1 to 1) is expected corrupts both.
        Random random = new Random(99L);
        ProductQuantizer pq = trained(random);
        DistanceFunction cosine = CosineSimilarity.getInstance();

        float[] query = unitVector(random);
        PQDistanceTable cosineTable = pq.createDistanceTable(query, cosine);
        PQDistanceTable l2Table = pq.createDistanceTable(query, L2Distance.getInstance());

        double cosineError = 0;
        double l2Error = 0;
        int samples = 200;
        for (int i = 0; i < samples; i++) {
            float[] vector = unitVector(random);
            byte[] codes = pq.encode(vector);
            float exact = cosine.distance(query, vector);

            cosineError += Math.abs(cosineTable.lookup(codes) - exact);
            l2Error += Math.abs(l2Table.lookup(codes) - exact);
        }
        cosineError /= samples;
        l2Error /= samples;

        // The cosine table lands near the value the graph would have computed exactly; the L2
        // table is off by roughly the constant that separates the two scales.
        assertThat(cosineError).isLessThan(0.15);
        assertThat(l2Error).isGreaterThan(4 * cosineError);
    }

    @Test
    @DisplayName("a larger-is-closer table stays smaller-is-closer once accumulated")
    void cosineTableIsADissimilarity() {
        // The negation in the table is what puts a similarity on the same ordering as a
        // distance. Without it every comparison in the search would be backwards.
        Random random = new Random(5L);
        ProductQuantizer pq = trained(random);
        DistanceFunction cosine = CosineSimilarity.getInstance();

        float[] query = unitVector(random);
        PQDistanceTable table = pq.createDistanceTable(query, cosine);

        float near = table.lookup(pq.encode(query));
        float far = table.lookup(pq.encode(negated(query)));

        assertThat(near).isLessThan(far);
    }

    private static float[] negated(float[] vector) {
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = -vector[i];
        }
        return result;
    }

    @Test
    @DisplayName("a cosine graph's PQ search returns distances on the cosine scale")
    void graphPassesItsOwnMetric(@TempDir Path dir) throws IOException {
        // End to end, and deterministic: the graph has to hand its own metric to the table it
        // builds. What that changes is not mainly the ranking -- for unit vectors squared L2 is
        // a monotone function of cosine, so both orders agree -- but the values. A cosine
        // dissimilarity lies in [-1, 1]; squared L2 between unit vectors lies in [0, 4] and sits
        // near 2 for random pairs. Those results are merged with exact ones in one priority
        // queue and normalised against cosine's range, so a table on the wrong scale corrupts
        // both without changing the shape of the answer.
        Random random = new Random(4242L);
        HnswConfig config = HnswConfig.create(DIMENSION);

        try (HnswGraph graph = new HnswGraph(
                dir.resolve("graph.hnsw"), config, CosineSimilarity.getInstance(), 1000)) {

            for (int i = 0; i < 300; i++) {
                graph.insert(unitVector(random));
            }
            graph.trainProductQuantizer(256, SUBSPACES, CENTROIDS);
            graph.encodeAllVectors();

            // Without these the test would silently exercise the exact-search fallback that
            // searchWithPQ takes when no trained quantizer is attached -- and pass whatever the
            // table was built for.
            assertThat(graph.isPQEnabled()).as("PQ must actually be in use").isTrue();
            assertThat(graph.encodedVectorCount()).isPositive();

            for (int trial = 0; trial < 20; trial++) {
                float[] query = unitVector(random);
                List<HnswSearchResult> approximate = graph.searchWithPQ(query, 10);

                assertThat(approximate).isNotEmpty();
                for (HnswSearchResult result : approximate) {
                    assertThat(result.distance())
                        .as("PQ distance for node %s is on cosine's scale, not squared L2's",
                            result.nodeId())
                        .isBetween(-1.2f, 1.2f);
                }
            }
        }
    }
}
