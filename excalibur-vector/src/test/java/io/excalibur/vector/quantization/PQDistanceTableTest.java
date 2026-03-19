package io.excalibur.vector.quantization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for PQDistanceTable.
 */
@DisplayName("PQDistanceTable Tests")
class PQDistanceTableTest {

    private ProductQuantizer pq;
    private float[][] trainingData;

    @BeforeEach
    void setUp() {
        pq = new ProductQuantizer(16, 4, 16, new CodebookTrainer(1000, 20, 1e-4f, 42));
        trainingData = generateRandomVectors(100, 16);
        pq.train(trainingData);
    }

    @Test
    @DisplayName("Should create distance table with correct dimensions")
    void testCreateDistanceTable() {
        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table = pq.createDistanceTable(query);

        assertThat(table.numSubspaces()).isEqualTo(4);
        assertThat(table.numCentroids()).isEqualTo(16);
        assertThat(table.memorySize()).isEqualTo(4 * 16 * Float.BYTES);
    }

    @Test
    @DisplayName("Should lookup distance correctly")
    void testLookupDistance() {
        float[] query = generateRandomVectors(1, 16)[0];
        float[] target = generateRandomVectors(1, 16)[0];

        byte[] codes = pq.encode(target);

        PQDistanceTable table = pq.createDistanceTable(query);
        float distance = table.lookup(codes);

        assertThat(distance).isGreaterThanOrEqualTo(0.0f);

        // Should match asymmetric distance
        float directDistance = pq.asymmetricDistance(query, codes);
        assertThat(distance).isCloseTo(directDistance, within(0.001f));
    }

    @Test
    @DisplayName("Should lookup batch distances")
    void testLookupBatch() {
        float[] query = generateRandomVectors(1, 16)[0];
        float[][] targets = generateRandomVectors(5, 16);
        byte[][] codes = pq.encodeBatch(targets);

        PQDistanceTable table = pq.createDistanceTable(query);
        float[] distances = table.lookupBatch(codes);

        assertThat(distances).hasSize(5);
        for (float dist : distances) {
            assertThat(dist).isGreaterThanOrEqualTo(0.0f);
        }
    }

    @Test
    @DisplayName("Should reject null codes for lookup")
    void testLookupNull() {
        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table = pq.createDistanceTable(query);

        assertThatThrownBy(() -> table.lookup(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject wrong code length for lookup")
    void testLookupWrongLength() {
        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table = pq.createDistanceTable(query);

        byte[] wrongCodes = new byte[8];  // Should be 4

        assertThatThrownBy(() -> table.lookup(wrongCodes))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("numSubspaces");
    }

    @Test
    @DisplayName("Should get individual distances")
    void testGetDistance() {
        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table = pq.createDistanceTable(query);

        float dist00 = table.getDistance(0, 0);
        float dist01 = table.getDistance(0, 1);

        assertThat(dist00).isGreaterThanOrEqualTo(0.0f);
        assertThat(dist01).isGreaterThanOrEqualTo(0.0f);
    }

    @Test
    @DisplayName("Should reject out of bounds access")
    void testGetDistanceOutOfBounds() {
        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table = pq.createDistanceTable(query);

        assertThatThrownBy(() -> table.getDistance(-1, 0))
            .isInstanceOf(IndexOutOfBoundsException.class);

        assertThatThrownBy(() -> table.getDistance(4, 0))  // Only 4 subspaces
            .isInstanceOf(IndexOutOfBoundsException.class);

        assertThatThrownBy(() -> table.getDistance(0, 16))  // Only 16 centroids
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("Should return copy of query")
    void testGetQuery() {
        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table = pq.createDistanceTable(query);

        float[] returnedQuery = table.getQuery();

        assertThat(returnedQuery).containsExactly(query);
        // Verify it's a copy
        returnedQuery[0] = 999.0f;
        assertThat(table.getQuery()[0]).isNotEqualTo(999.0f);
    }

    @Test
    @DisplayName("Should return quantizer reference")
    void testGetQuantizer() {
        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table = pq.createDistanceTable(query);

        assertThat(table.getQuantizer()).isSameAs(pq);
    }

    @Test
    @DisplayName("Should implement equals and hashCode")
    void testEqualsAndHashCode() {
        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table1 = pq.createDistanceTable(query);
        PQDistanceTable table2 = pq.createDistanceTable(query);

        // Different instances with same query should be equal
        assertThat(table1).isEqualTo(table2);
        assertThat(table1.hashCode()).isEqualTo(table2.hashCode());

        // Same instance
        assertThat(table1).isEqualTo(table1);

        // Different query
        float[] otherQuery = generateRandomVectors(1, 16)[0];
        otherQuery[0] = 999.0f;
        PQDistanceTable table3 = pq.createDistanceTable(otherQuery);
        assertThat(table1).isNotEqualTo(table3);
    }

    @Test
    @DisplayName("Should implement toString")
    void testToString() {
        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table = pq.createDistanceTable(query);

        String str = table.toString();

        assertThat(str).contains("PQDistanceTable");
        assertThat(str).contains("subspaces=4");
        assertThat(str).contains("centroids=16");
    }

    @Test
    @DisplayName("Should reject creation with untrained quantizer")
    void testUntrainedQuantizer() {
        ProductQuantizer untrainedPq = new ProductQuantizer(16, 4, 16);
        float[] query = new float[16];

        assertThatThrownBy(() -> new PQDistanceTable(query, untrainedPq))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be trained");
    }

    @Test
    @DisplayName("Should reject wrong query dimension")
    void testWrongQueryDimension() {
        float[] wrongDim = new float[32];

        assertThatThrownBy(() -> new PQDistanceTable(wrongDim, pq))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("Should reject null query")
    void testNullQuery() {
        assertThatThrownBy(() -> new PQDistanceTable(null, pq))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject null quantizer")
    void testNullQuantizer() {
        float[] query = new float[16];

        assertThatThrownBy(() -> new PQDistanceTable(query, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Distance to self-encoded vector should be small")
    void testSelfDistance() {
        float[] vector = generateRandomVectors(1, 16)[0];
        byte[] codes = pq.encode(vector);

        PQDistanceTable table = pq.createDistanceTable(vector);
        float selfDistance = table.lookup(codes);

        // Distance to self should be very small (quantization error only)
        assertThat(selfDistance).isGreaterThanOrEqualTo(0.0f);
        assertThat(selfDistance).isLessThan(2.0f);
    }

    // Helper method to generate random vectors
    private float[][] generateRandomVectors(int count, int dimension) {
        float[][] vectors = new float[count][dimension];
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < dimension; d++) {
                vectors[i][d] = random.nextFloat() * 2 - 1;  // Range [-1, 1]
            }
        }
        return vectors;
    }
}
