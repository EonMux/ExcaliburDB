package io.excalibur.vector.quantization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for PQCodebook class.
 */
@DisplayName("PQCodebook Tests")
class PQCodebookTest {

    private float[][] centroids;
    private int numCentroids = 16;
    private int dimension = 8;

    @BeforeEach
    void setUp() {
        // Create synthetic centroids for testing
        centroids = new float[numCentroids][dimension];
        java.util.Random random = new java.util.Random(42);

        for (int c = 0; c < numCentroids; c++) {
            for (int d = 0; d < dimension; d++) {
                // Create distinct centroids with some structure
                centroids[c][d] = (float) c / numCentroids + random.nextFloat() * 0.1f;
            }
        }
    }

    @Test
    @DisplayName("Should create codebook from centroids")
    void testCreateCodebook() {
        PQCodebook codebook = new PQCodebook(centroids);

        assertThat(codebook.numCentroids()).isEqualTo(numCentroids);
        assertThat(codebook.dimension()).isEqualTo(dimension);
    }

    @Test
    @DisplayName("Should find nearest centroid")
    void testFindNearestCentroid() {
        PQCodebook codebook = new PQCodebook(centroids);

        // Use a centroid as the query - should find itself
        float[] query = centroids[5].clone();
        int nearest = codebook.findNearestCentroid(query);

        assertThat(nearest).isEqualTo(5);
    }

    @Test
    @DisplayName("Nearest centroid should always return valid index")
    void testValidIndex() {
        PQCodebook codebook = new PQCodebook(centroids);

        java.util.Random random = new java.util.Random(123);
        for (int i = 0; i < 100; i++) {
            float[] query = new float[dimension];
            for (int d = 0; d < dimension; d++) {
                query[d] = random.nextFloat() * 2 - 1;
            }

            int nearest = codebook.findNearestCentroid(query);

            assertThat(nearest).isBetween(0, numCentroids - 1);
        }
    }

    @Test
    @DisplayName("Should find k nearest centroids")
    void testFindKNearestCentroids() {
        PQCodebook codebook = new PQCodebook(centroids);

        float[] query = centroids[5].clone();
        int[] kNearest = codebook.findKNearestCentroids(query, 3);

        assertThat(kNearest.length).isEqualTo(3);
        assertThat(kNearest[0]).isEqualTo(5); // First should be the exact match

        // All indices should be valid
        for (int idx : kNearest) {
            assertThat(idx).isBetween(0, numCentroids - 1);
        }
    }

    @Test
    @DisplayName("K nearest should be sorted by distance")
    void testKNearestSorted() {
        PQCodebook codebook = new PQCodebook(centroids);

        java.util.Random random = new java.util.Random(456);
        float[] query = new float[dimension];
        for (int d = 0; d < dimension; d++) {
            query[d] = random.nextFloat() * 2 - 1;
        }

        int k = 5;
        int[] kNearest = codebook.findKNearestCentroids(query, k);

        // Verify sorted by distance
        float prevDist = 0.0f;
        for (int idx : kNearest) {
            float dist = codebook.distanceToCentroid(query, idx);
            assertThat(dist).isGreaterThanOrEqualTo(prevDist);
            prevDist = dist;
        }
    }

    @Test
    @DisplayName("Should get centroid by index")
    void testGetCentroid() {
        PQCodebook codebook = new PQCodebook(centroids);

        float[] centroid = codebook.getCentroid(5);

        assertThat(centroid.length).isEqualTo(dimension);
        assertThat(centroid).containsExactly(centroids[5]);
    }

    @Test
    @DisplayName("Get centroid should return copy")
    void testGetCentroidReturnsCopy() {
        PQCodebook codebook = new PQCodebook(centroids);

        float[] centroid1 = codebook.getCentroid(5);
        float[] centroid2 = codebook.getCentroid(5);

        assertThat(centroid1).isNotSameAs(centroid2);
        assertThat(centroid1).containsExactly(centroid2);

        // Modifying returned array should not affect codebook
        centroid1[0] = 999.0f;
        assertThat(codebook.getCentroid(5)[0]).isNotEqualTo(999.0f);
    }

    @Test
    @DisplayName("Should compute distance to centroid")
    void testDistanceToCentroid() {
        PQCodebook codebook = new PQCodebook(centroids);

        float[] query = centroids[5].clone();
        float dist = codebook.distanceToCentroid(query, 5);

        // Distance to self should be zero
        assertThat(dist).isCloseTo(0.0f, within(0.0001f));
    }

    @Test
    @DisplayName("Distance to different centroid should be positive")
    void testDistanceToDifferentCentroid() {
        PQCodebook codebook = new PQCodebook(centroids);

        float[] query = centroids[5].clone();
        float dist = codebook.distanceToCentroid(query, 7);

        assertThat(dist).isGreaterThan(0.0f);
    }

    @Test
    @DisplayName("Should reject invalid centroid index")
    void testInvalidCentroidIndex() {
        PQCodebook codebook = new PQCodebook(centroids);

        float[] query = new float[dimension];

        assertThatThrownBy(() -> codebook.getCentroid(-1))
            .isInstanceOf(IndexOutOfBoundsException.class);

        assertThatThrownBy(() -> codebook.getCentroid(numCentroids))
            .isInstanceOf(IndexOutOfBoundsException.class);

        assertThatThrownBy(() -> codebook.distanceToCentroid(query, -1))
            .isInstanceOf(IndexOutOfBoundsException.class);

        assertThatThrownBy(() -> codebook.distanceToCentroid(query, numCentroids))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("Should reject wrong dimension subvector")
    void testWrongDimension() {
        PQCodebook codebook = new PQCodebook(centroids);

        float[] wrongDim = new float[dimension + 5];

        assertThatThrownBy(() -> codebook.findNearestCentroid(wrongDim))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimension");

        assertThatThrownBy(() -> codebook.distanceToCentroid(wrongDim, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("Should reject null inputs")
    void testNullInputs() {
        PQCodebook codebook = new PQCodebook(centroids);

        assertThatThrownBy(() -> codebook.findNearestCentroid(null))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> codebook.distanceToCentroid(null, 0))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> codebook.findKNearestCentroids(null, 3))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject invalid k for k-nearest")
    void testInvalidK() {
        PQCodebook codebook = new PQCodebook(centroids);
        float[] query = new float[dimension];

        assertThatThrownBy(() -> codebook.findKNearestCentroids(query, 0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> codebook.findKNearestCentroids(query, -1))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> codebook.findKNearestCentroids(query, numCentroids + 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject null centroids in constructor")
    void testNullCentroids() {
        assertThatThrownBy(() -> new PQCodebook(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject empty centroids")
    void testEmptyCentroids() {
        assertThatThrownBy(() -> new PQCodebook(new float[0][]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Should reject null centroid in array")
    void testNullCentroidInArray() {
        float[][] invalidCentroids = new float[3][];
        invalidCentroids[0] = new float[dimension];
        invalidCentroids[1] = null; // Invalid
        invalidCentroids[2] = new float[dimension];

        assertThatThrownBy(() -> new PQCodebook(invalidCentroids))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Should reject inconsistent centroid dimensions")
    void testInconsistentDimensions() {
        float[][] invalidCentroids = new float[3][];
        invalidCentroids[0] = new float[dimension];
        invalidCentroids[1] = new float[dimension + 5]; // Wrong dimension
        invalidCentroids[2] = new float[dimension];

        assertThatThrownBy(() -> new PQCodebook(invalidCentroids))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("Should report memory size")
    void testMemorySize() {
        PQCodebook codebook = new PQCodebook(centroids);

        long expectedSize = (long) numCentroids * dimension * Float.BYTES;
        assertThat(codebook.memorySize()).isEqualTo(expectedSize);
    }

    @Test
    @DisplayName("Should implement equals and hashCode")
    void testEqualsAndHashCode() {
        PQCodebook codebook1 = new PQCodebook(centroids);
        PQCodebook codebook2 = new PQCodebook(centroids);

        assertThat(codebook1).isEqualTo(codebook2);
        assertThat(codebook1.hashCode()).isEqualTo(codebook2.hashCode());
        assertThat(codebook1).isEqualTo(codebook1);

        // Different centroids
        float[][] differentCentroids = new float[numCentroids][dimension];
        PQCodebook codebook3 = new PQCodebook(differentCentroids);
        assertThat(codebook1).isNotEqualTo(codebook3);
    }

    @Test
    @DisplayName("Should implement toString")
    void testToString() {
        PQCodebook codebook = new PQCodebook(centroids);

        String str = codebook.toString();

        assertThat(str).contains("PQCodebook");
        assertThat(str).contains("numCentroids=" + numCentroids);
        assertThat(str).contains("dimension=" + dimension);
    }

    @Test
    @DisplayName("Get centroids should return deep copy")
    void testGetCentroidsReturnsCopy() {
        PQCodebook codebook = new PQCodebook(centroids);

        float[][] copy = codebook.getCentroids();

        assertThat(copy.length).isEqualTo(numCentroids);
        assertThat(copy).isNotSameAs(centroids);

        // Verify deep copy
        copy[0][0] = 999.0f;
        assertThat(codebook.getCentroid(0)[0]).isNotEqualTo(999.0f);
    }
}
