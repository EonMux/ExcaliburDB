package com.eonmux.excalibur.vector.quantization;

import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.InnerProduct;
import com.eonmux.excalibur.vector.distance.L2Distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The PQ distance table against the metric the graph was actually built with.
 *
 * <p>The table used to compute squared L2 whatever it was handed, so a cosine graph's PQ results
 * came back on the L2 scale while the query layer normalised them by cosine's range. The ordering
 * happened to survive for unit-norm vectors, which is what made it hard to notice.
 */
class AsymmetricDistanceMetricTest {

    /** Two subspaces of two dimensions, one centroid each, so the arithmetic is checkable by hand. */
    private static float[][][] codebooks(float[] centroid) {
        return new float[][][] {
            { { centroid[0], centroid[1] } },
            { { centroid[2], centroid[3] } }
        };
    }

    @Test
    @DisplayName("L2 tables hold squared distances")
    void l2IsSquaredDistance() {
        float[] query = { 1f, 0f, 0f, 0f };
        float[][] table = AsymmetricDistance.buildTable(
            query, codebooks(new float[] { 0f, 0f, 0f, 0f }), L2Distance.getInstance());

        // Subspace 0: (1-0)^2 + (0-0)^2 = 1. Subspace 1: 0.
        assertThat(table[0][0]).isCloseTo(1.0f, within(1e-6f));
        assertThat(table[1][0]).isCloseTo(0.0f, within(1e-6f));
    }

    @Test
    @DisplayName("inner-product tables are negated so smaller stays closer")
    void innerProductIsNegated() {
        float[] query = { 1f, 1f, 1f, 1f };
        float[][] table = AsymmetricDistance.buildTable(
            query, codebooks(new float[] { 1f, 1f, 1f, 1f }), InnerProduct.getInstance());

        // Per-subspace dot product is 2; negated, an identical vector is the most-negative value.
        assertThat(table[0][0]).isCloseTo(-2.0f, within(1e-6f));
        assertThat(table[1][0]).isCloseTo(-2.0f, within(1e-6f));
    }

    @Test
    @DisplayName("cosine uses the same decomposition as inner product")
    void cosineMatchesInnerProduct() {
        // Cosine does not decompose in general, but for the unit-norm vectors embedding models
        // produce it equals the inner product, which does.
        float[] query = { 0.5f, 0.5f, 0.5f, 0.5f };
        float[][][] books = codebooks(new float[] { 0.5f, 0.5f, 0.5f, 0.5f });

        float[][] cosine = AsymmetricDistance.buildTable(query, books, CosineSimilarity.getInstance());
        float[][] inner = AsymmetricDistance.buildTable(query, books, InnerProduct.getInstance());

        assertThat(cosine[0][0]).isCloseTo(inner[0][0], within(1e-6f));
        assertThat(cosine[1][0]).isCloseTo(inner[1][0], within(1e-6f));
    }

    @Test
    @DisplayName("a nearer vector scores lower under every metric")
    void smallerIsAlwaysCloser() {
        float[] query = { 1f, 0f, 0f, 0f };
        float[][][] near = codebooks(new float[] { 1f, 0f, 0f, 0f });
        float[][][] far = codebooks(new float[] { -1f, 0f, 0f, 0f });

        for (var metric : new com.eonmux.excalibur.vector.distance.DistanceFunction[] {
                L2Distance.getInstance(), InnerProduct.getInstance(),
                CosineSimilarity.getInstance() }) {

            float nearTerm = AsymmetricDistance.buildTable(query, near, metric)[0][0];
            float farTerm = AsymmetricDistance.buildTable(query, far, metric)[0][0];

            assertThat(nearTerm)
                .as("%s: the nearer centroid must accumulate to a smaller value", metric.name())
                .isLessThan(farTerm);
        }
    }

    @Test
    @DisplayName("a null metric still means L2, as the convenience overload relies on")
    void nullMetricIsL2() {
        float[] query = { 2f, 0f, 0f, 0f };
        float[][][] books = codebooks(new float[] { 0f, 0f, 0f, 0f });

        assertThat(AsymmetricDistance.buildTable(query, books, null)[0][0])
            .isCloseTo(AsymmetricDistance.buildTable(query, books, L2Distance.getInstance())[0][0],
                within(1e-6f));
    }
}
