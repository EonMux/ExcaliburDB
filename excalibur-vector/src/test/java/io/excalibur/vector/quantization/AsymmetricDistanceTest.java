package io.excalibur.vector.quantization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for AsymmetricDistance utility class.
 */
@DisplayName("AsymmetricDistance Tests")
class AsymmetricDistanceTest {

    private float[][][] codebooks;
    private int numSubspaces = 4;
    private int numCentroids = 16;
    private int subvectorDim = 4;
    private int totalDim = numSubspaces * subvectorDim;

    @BeforeEach
    void setUp() {
        // Create simple synthetic codebooks for testing
        codebooks = new float[numSubspaces][numCentroids][subvectorDim];
        java.util.Random random = new java.util.Random(42);

        for (int s = 0; s < numSubspaces; s++) {
            for (int c = 0; c < numCentroids; c++) {
                for (int d = 0; d < subvectorDim; d++) {
                    codebooks[s][c][d] = random.nextFloat() * 2 - 1;
                }
            }
        }
    }

    @Test
    @DisplayName("Should build distance table with correct dimensions")
    void testBuildTableDimensions() {
        float[] query = new float[totalDim];
        float[][] table = AsymmetricDistance.buildTable(query, codebooks);

        assertThat(table.length).isEqualTo(numSubspaces);
        for (int s = 0; s < numSubspaces; s++) {
            assertThat(table[s].length).isEqualTo(numCentroids);
        }
    }

    @Test
    @DisplayName("Should compute distance using distance table")
    void testComputeDistance() {
        float[] query = new float[totalDim];
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < totalDim; i++) {
            query[i] = random.nextFloat() * 2 - 1;
        }

        float[][] table = AsymmetricDistance.buildTable(query, codebooks);

        byte[] pqCodes = new byte[numSubspaces];
        for (int s = 0; s < numSubspaces; s++) {
            pqCodes[s] = (byte) (s % numCentroids);
        }

        float distance = AsymmetricDistance.compute(table, pqCodes);

        // Distance should be non-negative (sum of squared distances)
        assertThat(distance).isGreaterThanOrEqualTo(0.0f);
        assertThat(distance).isFinite();
    }

    @Test
    @DisplayName("Distance table should match direct computation")
    void testDistanceTableAccuracy() {
        float[] query = new float[totalDim];
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < totalDim; i++) {
            query[i] = random.nextFloat() * 2 - 1;
        }

        float[][] table = AsymmetricDistance.buildTable(query, codebooks);

        byte[] pqCodes = new byte[numSubspaces];
        for (int s = 0; s < numSubspaces; s++) {
            pqCodes[s] = (byte) ((s * 3) % numCentroids);
        }

        float tableDist = AsymmetricDistance.compute(table, pqCodes);

        // Compute manually
        double manualDist = 0.0;
        for (int s = 0; s < numSubspaces; s++) {
            int centroidIdx = pqCodes[s] & 0xFF;
            int startOffset = s * subvectorDim;
            for (int d = 0; d < subvectorDim; d++) {
                double diff = query[startOffset + d] - codebooks[s][centroidIdx][d];
                manualDist += diff * diff;
            }
        }

        assertThat(tableDist).isCloseTo((float) manualDist, within(0.001f));
    }

    @Test
    @DisplayName("Should compute batch distances")
    void testComputeBatch() {
        float[] query = new float[totalDim];
        float[][] table = AsymmetricDistance.buildTable(query, codebooks);

        byte[][] pqCodesBatch = new byte[5][numSubspaces];
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 5; i++) {
            for (int s = 0; s < numSubspaces; s++) {
                pqCodesBatch[i][s] = (byte) random.nextInt(numCentroids);
            }
        }

        float[] distances = AsymmetricDistance.computeBatch(table, pqCodesBatch);

        assertThat(distances.length).isEqualTo(5);
        for (float dist : distances) {
            assertThat(dist).isGreaterThanOrEqualTo(0.0f);
        }
    }

    @Test
    @DisplayName("Should compute quantization error")
    void testQuantizationError() {
        float[] vector = new float[totalDim];
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < totalDim; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }

        byte[] pqCodes = new byte[numSubspaces];
        for (int s = 0; s < numSubspaces; s++) {
            pqCodes[s] = (byte) (s % numCentroids);
        }

        float error = AsymmetricDistance.quantizationError(vector, codebooks, pqCodes);

        // Error should be non-negative
        assertThat(error).isGreaterThanOrEqualTo(0.0f);
    }

    @Test
    @DisplayName("Self-quantization error should be small")
    void testSelfQuantizationError() {
        // Use a centroid as the vector - should have zero error
        float[] vector = new float[totalDim];
        byte[] pqCodes = new byte[numSubspaces];

        for (int s = 0; s < numSubspaces; s++) {
            int centroidIdx = s % numCentroids;
            pqCodes[s] = (byte) centroidIdx;
            System.arraycopy(codebooks[s][centroidIdx], 0, vector, s * subvectorDim, subvectorDim);
        }

        float error = AsymmetricDistance.quantizationError(vector, codebooks, pqCodes);

        // Error should be very small (floating point precision)
        assertThat(error).isCloseTo(0.0f, within(0.0001f));
    }

    @Test
    @DisplayName("Should report table memory size")
    void testTableMemorySize() {
        long size = AsymmetricDistance.tableMemorySize(numSubspaces, numCentroids);

        // 4 subspaces * 16 centroids * 4 bytes per float = 256 bytes
        assertThat(size).isEqualTo(numSubspaces * numCentroids * Float.BYTES);
    }

    @Test
    @DisplayName("Should reject null inputs")
    void testNullInputs() {
        float[][] table = new float[numSubspaces][numCentroids];
        byte[] codes = new byte[numSubspaces];
        float[] query = new float[totalDim];

        assertThatThrownBy(() -> AsymmetricDistance.compute(null, codes))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> AsymmetricDistance.compute(table, null))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> AsymmetricDistance.buildTable(null, codebooks))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> AsymmetricDistance.buildTable(query, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject invalid dimensions")
    void testInvalidDimensions() {
        float[][] table = new float[numSubspaces][numCentroids];
        byte[] wrongCodes = new byte[8]; // Wrong number of subspaces

        assertThatThrownBy(() -> AsymmetricDistance.compute(table, wrongCodes))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match");

        float[] wrongDimQuery = new float[totalDim + 10];
        assertThatThrownBy(() -> AsymmetricDistance.buildTable(wrongDimQuery, codebooks))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must match");
    }

    @Test
    @DisplayName("Should reject invalid centroid index")
    void testInvalidCentroidIndex() {
        float[] query = new float[totalDim];
        float[][] table = AsymmetricDistance.buildTable(query, codebooks);

        byte[] invalidCodes = new byte[numSubspaces];
        invalidCodes[0] = (byte) 200; // Exceeds numCentroids (16)

        assertThatThrownBy(() -> AsymmetricDistance.compute(table, invalidCodes))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid centroid index");
    }
}
