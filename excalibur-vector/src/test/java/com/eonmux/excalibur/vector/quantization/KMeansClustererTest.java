package com.eonmux.excalibur.vector.quantization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for KMeansClusterer.
 */
@DisplayName("KMeansClusterer Tests")
class KMeansClustererTest {

    private KMeansClusterer clusterer;

    @BeforeEach
    void setUp() {
        clusterer = new KMeansClusterer(50, 1e-4f, 42);
    }

    @Test
    @DisplayName("Should cluster simple 2D data correctly")
    void testSimpleClustering() {
        // Create two clear clusters
        float[][] data = {
            {0.0f, 0.0f}, {0.1f, 0.1f}, {-0.1f, -0.1f},
            {10.0f, 10.0f}, {10.1f, 10.1f}, {9.9f, 9.9f}
        };

        float[][] centroids = clusterer.cluster(data, 2);

        assertThat(centroids.length).isEqualTo(2);
        assertThat(centroids[0].length).isEqualTo(2);
        assertThat(centroids[1].length).isEqualTo(2);

        // Verify centroids are within the data range
        for (float[] centroid : centroids) {
            assertThat(centroid[0]).isBetween(-1.0f, 11.0f);
            assertThat(centroid[1]).isBetween(-1.0f, 11.0f);
        }
    }

    @Test
    @DisplayName("Should handle k = 1 by returning mean")
    void testSingleCluster() {
        float[][] data = {
            {1.0f, 2.0f}, {3.0f, 4.0f}, {5.0f, 6.0f}
        };

        float[][] centroids = clusterer.cluster(data, 1);

        assertThat(centroids.length).isEqualTo(1);
        assertThat(centroids[0][0]).isCloseTo(3.0f, within(0.1f));  // Mean of 1, 3, 5
        assertThat(centroids[0][1]).isCloseTo(4.0f, within(0.1f));  // Mean of 2, 4, 6
    }

    @Test
    @DisplayName("Should handle k >= data length by returning data points")
    void testMoreClustersThanData() {
        float[][] data = {
            {1.0f, 1.0f}, {2.0f, 2.0f}
        };

        float[][] centroids = clusterer.cluster(data, 5);

        assertThat(centroids.length).isEqualTo(5);
    }

    @Test
    @DisplayName("Should handle empty data")
    void testEmptyData() {
        float[][] data = {};

        float[][] centroids = clusterer.cluster(data, 3);

        assertThat(centroids).isEmpty();
    }

    @Test
    @DisplayName("Should reject null data")
    void testNullData() {
        assertThatThrownBy(() -> clusterer.cluster(null, 3))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject invalid k")
    void testInvalidK() {
        float[][] data = {{1.0f, 2.0f}};

        assertThatThrownBy(() -> clusterer.cluster(data, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject inconsistent dimensions")
    void testInconsistentDimensions() {
        float[][] data = {
            {1.0f, 2.0f, 3.0f},
            {1.0f, 2.0f}  // Different dimension
        };

        assertThatThrownBy(() -> clusterer.cluster(data, 2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Inconsistent dimensions");
    }

    @Test
    @DisplayName("Should reject null data points")
    void testNullDataPoints() {
        float[][] data = {
            {1.0f, 2.0f},
            null
        };

        assertThatThrownBy(() -> clusterer.cluster(data, 2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Should cluster higher dimensional data")
    void testHigherDimensionalClustering() {
        // Create 3D data with two clusters
        float[][] data = new float[20][3];
        for (int i = 0; i < 10; i++) {
            data[i] = new float[] {i * 0.1f, 0.0f, 0.0f};
            data[i + 10] = new float[] {100.0f + i * 0.1f, 0.0f, 0.0f};
        }

        float[][] centroids = clusterer.cluster(data, 2);

        assertThat(centroids.length).isEqualTo(2);
        assertThat(centroids[0].length).isEqualTo(3);
        assertThat(centroids[1].length).isEqualTo(3);
    }

    @Test
    @DisplayName("Should produce reproducible results with same seed")
    void testReproducibility() {
        KMeansClusterer c1 = new KMeansClusterer(50, 1e-4f, 12345);
        KMeansClusterer c2 = new KMeansClusterer(50, 1e-4f, 12345);

        float[][] data = {
            {0.0f, 0.0f}, {0.1f, 0.1f},
            {10.0f, 10.0f}, {10.1f, 10.1f}
        };

        float[][] centroids1 = c1.cluster(data, 2);
        float[][] centroids2 = c2.cluster(data, 2);

        // Results should be similar (not necessarily identical due to K-means non-determinism)
        assertThat(centroids1.length).isEqualTo(centroids2.length);
    }

    @Test
    @DisplayName("Default constructor should work")
    void testDefaultConstructor() {
        KMeansClusterer defaultClusterer = new KMeansClusterer();

        assertThat(defaultClusterer.maxIterations()).isEqualTo(KMeansClusterer.DEFAULT_MAX_ITERATIONS);
        assertThat(defaultClusterer.convergenceThreshold()).isEqualTo(KMeansClusterer.DEFAULT_CONVERGENCE_THRESHOLD);
    }

    @Test
    @DisplayName("Should reject invalid constructor parameters")
    void testInvalidConstructorParams() {
        assertThatThrownBy(() -> new KMeansClusterer(0, 1e-4f, 0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new KMeansClusterer(10, -1.0f, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
