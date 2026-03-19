package io.excalibur.vector.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Percentage.withPercentage;

/**
 * Tests for VectorBlock with focus on domain correctness.
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>Distance computations produce values in correct ranges</li>
 *   <li>Identical vectors produce expected distances</li>
 *   <li>Orthogonal vectors produce expected distances</li>
 *   <li>Edge cases are handled correctly</li>
 * </ul>
 */
class VectorBlockTest {

    private static final float EPSILON = 1e-5f;
    private static final int TEST_DIMENSION = 128;
    private static final int TEST_VECTOR_COUNT = 100;

    @Nested
    @DisplayName("Vector storage and retrieval")
    class VectorStorageAndRetrieval {

        @Test
        @DisplayName("Stored vector can be retrieved unchanged")
        void storedVectorRetrievedUnchanged() {
            VectorLayout layout = VectorLayout.create(TEST_DIMENSION, TEST_VECTOR_COUNT);
            VectorBlock block = new VectorBlock(layout);

            float[] original = createRandomVector(TEST_DIMENSION, 42);
            block.putVector(0, original);
            float[] retrieved = block.getVector(0);

            assertThat(retrieved).containsExactly(original);
        }

        @Test
        @DisplayName("Multiple vectors stored and retrieved correctly")
        void multipleVectorsStoredCorrectly() {
            VectorLayout layout = VectorLayout.create(TEST_DIMENSION, 10);
            VectorBlock block = new VectorBlock(layout);

            float[][] vectors = new float[10][];
            for (int i = 0; i < 10; i++) {
                vectors[i] = createRandomVector(TEST_DIMENSION, i);
                block.putVector(i, vectors[i]);
            }

            for (int i = 0; i < 10; i++) {
                assertThat(block.getVector(i)).containsExactly(vectors[i]);
            }
        }

        @Test
        @DisplayName("Individual element access works")
        void individualElementAccess() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            float[] vector = {1.0f, 2.0f, 3.0f, 4.0f};
            block.putVector(5, vector);

            assertThat(block.getElement(5, 0)).isEqualTo(1.0f);
            assertThat(block.getElement(5, 1)).isEqualTo(2.0f);
            assertThat(block.getElement(5, 2)).isEqualTo(3.0f);
            assertThat(block.getElement(5, 3)).isEqualTo(4.0f);
        }

        @Test
        @DisplayName("putElement modifies single element")
        void putElementWorks() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            float[] vector = {1.0f, 2.0f, 3.0f, 4.0f};
            block.putVector(5, vector);
            block.putElement(5, 2, 99.0f);

            assertThat(block.getElement(5, 2)).isEqualTo(99.0f);
            assertThat(block.getElement(5, 0)).isEqualTo(1.0f); // Unchanged
        }
    }

    @Nested
    @DisplayName("L2 Distance domain correctness")
    class L2DistanceDomainCorrectness {

        @Test
        @DisplayName("L2 distance to identical vector is 0.0")
        void l2DistanceToIdenticalIsZero() {
            VectorLayout layout = VectorLayout.create(TEST_DIMENSION, 10);
            VectorBlock block = new VectorBlock(layout);

            float[] vector = createRandomVector(TEST_DIMENSION, 42);
            block.putVector(0, vector);

            float[] distances = new float[10];
            block.computeDistances(vector, distances, DistanceType.L2);

            assertThat(distances[0]).isCloseTo(0.0f, within(EPSILON));
            assertThat(distances[0]).isGreaterThanOrEqualTo(0.0f);
        }

        @Test
        @DisplayName("L2 distance is always non-negative")
        void l2DistanceNonNegative() {
            VectorLayout layout = VectorLayout.create(TEST_DIMENSION, TEST_VECTOR_COUNT);
            VectorBlock block = new VectorBlock(layout);

            // Fill with random vectors
            for (int i = 0; i < TEST_VECTOR_COUNT; i++) {
                block.putVector(i, createRandomVector(TEST_DIMENSION, i));
            }

            float[] query = createRandomVector(TEST_DIMENSION, 999);
            float[] distances = new float[TEST_VECTOR_COUNT];
            block.computeDistances(query, distances, DistanceType.L2);

            for (float d : distances) {
                assertThat(d).isGreaterThanOrEqualTo(0.0f);
            }
        }

        @Test
        @DisplayName("L2 distance matches manual calculation")
        void l2DistanceMatchesManual() {
            VectorLayout layout = VectorLayout.create(4, 2);
            VectorBlock block = new VectorBlock(layout);

            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f};
            float[] v2 = {5.0f, 6.0f, 7.0f, 8.0f};
            block.putVector(0, v1);
            block.putVector(1, v2);

            float[] query = {0.0f, 0.0f, 0.0f, 0.0f};
            float[] distances = new float[2];
            block.computeDistances(query, distances, DistanceType.L2);

            // L2 distance from origin: sqrt(sum of squares)
            float expected1 = (float) Math.sqrt(1 + 4 + 9 + 16); // sqrt(30) ≈ 5.477
            float expected2 = (float) Math.sqrt(25 + 36 + 49 + 64); // sqrt(174) ≈ 13.19

            assertThat(distances[0]).isCloseTo(expected1, within(EPSILON));
            assertThat(distances[1]).isCloseTo(expected2, within(EPSILON));
        }

        @Test
        @DisplayName("L2 distance with known vectors")
        void l2DistanceKnownValues() {
            VectorLayout layout = VectorLayout.create(2, 2);
            VectorBlock block = new VectorBlock(layout);

            // Two points in 2D: (0, 3) and (4, 0)
            block.putVector(0, new float[]{0.0f, 3.0f});
            block.putVector(1, new float[]{4.0f, 0.0f});

            // Query at origin
            float[] distances = new float[2];
            block.computeDistances(new float[]{0.0f, 0.0f}, distances, DistanceType.L2);

            assertThat(distances[0]).isCloseTo(3.0f, within(EPSILON)); // Distance to (0, 3)
            assertThat(distances[1]).isCloseTo(4.0f, within(EPSILON)); // Distance to (4, 0)

            // Query at (3, 4) - should have distance 5 to origin
            block.computeDistances(new float[]{3.0f, 4.0f}, distances, DistanceType.L2);

            // Distance from (3, 4) to (0, 3) = sqrt(9 + 1) = sqrt(10) ≈ 3.162
            // Distance from (3, 4) to (4, 0) = sqrt(1 + 16) = sqrt(17) ≈ 4.123
            assertThat(distances[0]).isCloseTo((float) Math.sqrt(10), within(EPSILON));
            assertThat(distances[1]).isCloseTo((float) Math.sqrt(17), within(EPSILON));
        }
    }

    @Nested
    @DisplayName("Cosine similarity domain correctness")
    class CosineSimilarityDomainCorrectness {

        @Test
        @DisplayName("Cosine similarity to identical vector is 1.0")
        void cosineSimilarityToIdenticalIsOne() {
            VectorLayout layout = VectorLayout.create(TEST_DIMENSION, 10);
            VectorBlock block = new VectorBlock(layout);

            float[] vector = createRandomVector(TEST_DIMENSION, 42);
            block.putVector(0, vector);

            float[] similarities = new float[10];
            block.computeDistances(vector, similarities, DistanceType.COSINE);

            assertThat(similarities[0]).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("Cosine similarity is in range [-1, 1]")
        void cosineSimilarityInRange() {
            VectorLayout layout = VectorLayout.create(TEST_DIMENSION, TEST_VECTOR_COUNT);
            VectorBlock block = new VectorBlock(layout);

            for (int i = 0; i < TEST_VECTOR_COUNT; i++) {
                block.putVector(i, createRandomVector(TEST_DIMENSION, i));
            }

            float[] query = createRandomVector(TEST_DIMENSION, 999);
            float[] similarities = new float[TEST_VECTOR_COUNT];
            block.computeDistances(query, similarities, DistanceType.COSINE);

            for (float s : similarities) {
                assertThat(s).isGreaterThanOrEqualTo(-1.0f);
                assertThat(s).isLessThanOrEqualTo(1.0f);
            }
        }

        @Test
        @DisplayName("Cosine similarity of orthogonal vectors is 0.0")
        void cosineSimilarityOrthogonalIsZero() {
            VectorLayout layout = VectorLayout.create(2, 2);
            VectorBlock block = new VectorBlock(layout);

            // Two orthogonal vectors: (1, 0) and (0, 1)
            block.putVector(0, new float[]{1.0f, 0.0f});
            block.putVector(1, new float[]{0.0f, 1.0f});

            float[] similarities = new float[2];
            block.computeDistances(new float[]{1.0f, 0.0f}, similarities, DistanceType.COSINE);

            assertThat(similarities[0]).isCloseTo(1.0f, within(EPSILON)); // Same direction
            assertThat(similarities[1]).isCloseTo(0.0f, within(EPSILON)); // Orthogonal
        }

        @Test
        @DisplayName("Cosine similarity of opposite vectors is -1.0")
        void cosineSimilarityOppositeIsMinusOne() {
            VectorLayout layout = VectorLayout.create(TEST_DIMENSION, 2);
            VectorBlock block = new VectorBlock(layout);

            float[] vector = createRandomVector(TEST_DIMENSION, 42);
            float[] opposite = new float[TEST_DIMENSION];
            for (int i = 0; i < TEST_DIMENSION; i++) {
                opposite[i] = -vector[i];
            }

            block.putVector(0, vector);
            block.putVector(1, opposite);

            float[] similarities = new float[2];
            block.computeDistances(vector, similarities, DistanceType.COSINE);

            assertThat(similarities[0]).isCloseTo(1.0f, within(EPSILON));
            assertThat(similarities[1]).isCloseTo(-1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("Cosine similarity is scale-invariant")
        void cosineSimilarityScaleInvariant() {
            VectorLayout layout = VectorLayout.create(4, 2);
            VectorBlock block = new VectorBlock(layout);

            // Same direction, different magnitudes
            float[] v1 = {1.0f, 2.0f, 3.0f, 4.0f};
            float[] v2 = {10.0f, 20.0f, 30.0f, 40.0f}; // 10x v1

            block.putVector(0, v1);
            block.putVector(1, v2);

            float[] similarities = new float[2];
            block.computeDistances(v1, similarities, DistanceType.COSINE);

            // Both should have similarity 1.0 to v1
            assertThat(similarities[0]).isCloseTo(1.0f, within(EPSILON));
            assertThat(similarities[1]).isCloseTo(1.0f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("Inner product domain correctness")
    class InnerProductDomainCorrectness {

        @Test
        @DisplayName("Inner product of identical vectors is sum of squares")
        void innerProductIdenticalIsSumOfSquares() {
            VectorLayout layout = VectorLayout.create(4, 1);
            VectorBlock block = new VectorBlock(layout);

            float[] v = {1.0f, 2.0f, 3.0f, 4.0f};
            block.putVector(0, v);

            float[] products = new float[1];
            block.computeDistances(v, products, DistanceType.INNER_PRODUCT);

            float expected = 1 + 4 + 9 + 16; // 30
            assertThat(products[0]).isCloseTo(expected, within(EPSILON));
        }

        @Test
        @DisplayName("Inner product of orthogonal vectors is 0.0")
        void innerProductOrthogonalIsZero() {
            VectorLayout layout = VectorLayout.create(2, 1);
            VectorBlock block = new VectorBlock(layout);

            block.putVector(0, new float[]{0.0f, 5.0f});

            float[] products = new float[1];
            block.computeDistances(new float[]{3.0f, 0.0f}, products, DistanceType.INNER_PRODUCT);

            assertThat(products[0]).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("Inner product can be negative")
        void innerProductCanBeNegative() {
            VectorLayout layout = VectorLayout.create(4, 2);
            VectorBlock block = new VectorBlock(layout);

            float[] query = {1.0f, 1.0f, 1.0f, 1.0f};
            float[] opposite = {-1.0f, -1.0f, -1.0f, -1.0f};

            block.putVector(0, query);
            block.putVector(1, opposite);

            float[] products = new float[2];
            block.computeDistances(query, products, DistanceType.INNER_PRODUCT);

            assertThat(products[0]).isCloseTo(4.0f, within(EPSILON));  // Positive
            assertThat(products[1]).isCloseTo(-4.0f, within(EPSILON)); // Negative
        }
    }

    @Nested
    @DisplayName("Batch operations")
    class BatchOperations {

        @Test
        @DisplayName("putVectorsFlat transposes correctly")
        void putVectorsFlatTransposesCorrectly() {
            VectorLayout layout = VectorLayout.create(3, 2);
            VectorBlock block = new VectorBlock(layout);

            // Flat: [v0_0, v0_1, v0_2, v1_0, v1_1, v1_2]
            float[] flat = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f};
            block.putVectorsFlat(flat, 0, 2);

            assertThat(block.getVector(0)).containsExactly(1.0f, 2.0f, 3.0f);
            assertThat(block.getVector(1)).containsExactly(4.0f, 5.0f, 6.0f);
        }

        @Test
        @DisplayName("getVectorsFlat transposes correctly")
        void getVectorsFlatTransposesCorrectly() {
            VectorLayout layout = VectorLayout.create(3, 2);
            VectorBlock block = new VectorBlock(layout);

            block.putVector(0, new float[]{1.0f, 2.0f, 3.0f});
            block.putVector(1, new float[]{4.0f, 5.0f, 6.0f});

            float[] flat = new float[6];
            block.getVectorsFlat(flat, 0);

            assertThat(flat).containsExactly(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f);
        }

        @Test
        @DisplayName("clear zeros all elements")
        void clearZerosAll() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            for (int i = 0; i < 10; i++) {
                block.putVector(i, new float[]{1.0f, 2.0f, 3.0f, 4.0f});
            }

            block.clear();

            for (int i = 0; i < 10; i++) {
                assertThat(block.getVector(i)).containsOnly(0.0f);
            }
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Zero vector in block produces 0.0 cosine similarity")
        void zeroVectorCosine() {
            VectorLayout layout = VectorLayout.create(4, 1);
            VectorBlock block = new VectorBlock(layout);

            block.putVector(0, new float[]{0.0f, 0.0f, 0.0f, 0.0f});

            float[] similarities = new float[1];
            block.computeDistances(new float[]{1.0f, 2.0f, 3.0f, 4.0f},
                similarities, DistanceType.COSINE);

            // Zero vector has undefined cosine; we return 0.0
            assertThat(similarities[0]).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Single dimension vectors work correctly")
        void singleDimensionVectors() {
            VectorLayout layout = VectorLayout.create(1, 2);
            VectorBlock block = new VectorBlock(layout);

            block.putVector(0, new float[]{3.0f});
            block.putVector(1, new float[]{-2.0f});

            float[] distances = new float[2];

            // L2
            block.computeDistances(new float[]{1.0f}, distances, DistanceType.L2);
            assertThat(distances[0]).isCloseTo(2.0f, within(EPSILON)); // |3 - 1|
            assertThat(distances[1]).isCloseTo(3.0f, within(EPSILON)); // |-2 - 1|

            // Cosine (1D cosine is just sign matching)
            block.computeDistances(new float[]{1.0f}, distances, DistanceType.COSINE);
            assertThat(distances[0]).isCloseTo(1.0f, within(EPSILON)); // Same sign
            assertThat(distances[1]).isCloseTo(-1.0f, within(EPSILON)); // Opposite sign
        }

        @Test
        @DisplayName("Large dimension works correctly")
        void largeDimensionWorks() {
            int largeDim = 2048;
            VectorLayout layout = VectorLayout.create(largeDim, 5);
            VectorBlock block = new VectorBlock(layout);

            for (int i = 0; i < 5; i++) {
                block.putVector(i, createRandomVector(largeDim, i));
            }

            float[] query = createRandomVector(largeDim, 999);
            float[] distances = new float[5];

            assertThatCode(() ->
                block.computeDistances(query, distances, DistanceType.COSINE)
            ).doesNotThrowAnyException();

            // Verify all in valid range
            for (float d : distances) {
                assertThat(d).isGreaterThanOrEqualTo(-1.0f);
                assertThat(d).isLessThanOrEqualTo(1.0f);
            }
        }
    }

    @Nested
    @DisplayName("Direct buffer support")
    class DirectBufferSupport {

        @Test
        @DisplayName("Direct buffer block works correctly")
        void directBufferWorks() {
            VectorLayout layout = VectorLayout.create(128, 10);
            VectorBlock block = new VectorBlock(layout, true);

            assertThat(block.isDirect()).isTrue();

            float[] vector = createRandomVector(128, 42);
            block.putVector(0, vector);

            assertThat(block.getVector(0)).containsExactly(vector);
        }

        @Test
        @DisplayName("Heap buffer block works correctly")
        void heapBufferWorks() {
            VectorLayout layout = VectorLayout.create(128, 10);
            VectorBlock block = new VectorBlock(layout, false);

            assertThat(block.isDirect()).isFalse();

            float[] vector = createRandomVector(128, 42);
            block.putVector(0, vector);

            assertThat(block.getVector(0)).containsExactly(vector);
        }
    }

    @Nested
    @DisplayName("Constructor edge cases")
    class ConstructorEdgeCases {

        @Test
        @DisplayName("Constructor with null layout throws NPE")
        void constructorNullLayout() {
            assertThatThrownBy(() -> new VectorBlock(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Layout must not be null");
        }

        @Test
        @DisplayName("Constructor with null layout and direct flag throws NPE")
        void constructorNullLayoutDirect() {
            assertThatThrownBy(() -> new VectorBlock(null, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Layout must not be null");
        }

        @Test
        @DisplayName("Constructor with null buffer throws NPE")
        void constructorNullBuffer() {
            VectorLayout layout = VectorLayout.create(4, 10);
            assertThatThrownBy(() -> new VectorBlock(layout, (java.nio.ByteBuffer) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Buffer must not be null");
        }

        @Test
        @DisplayName("Constructor with buffer too small throws IAE")
        void constructorBufferTooSmall() {
            VectorLayout layout = VectorLayout.create(128, 100);
            java.nio.ByteBuffer smallBuffer = java.nio.ByteBuffer.allocate(100);

            assertThatThrownBy(() -> new VectorBlock(layout, smallBuffer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Buffer too small");
        }

        @Test
        @DisplayName("Constructor with ByteBuffer wraps correctly")
        void constructorWithByteBuffer() {
            VectorLayout layout = VectorLayout.create(4, 10);
            long size = layout.totalBlockSize();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) size);

            VectorBlock block = new VectorBlock(layout, buffer);

            assertThat(block.layout()).isEqualTo(layout);
            assertThat(block.dimension()).isEqualTo(4);
            assertThat(block.vectorCount()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Index validation")
    class IndexValidation {

        @Test
        @DisplayName("getVector with negative index throws IAE")
        void getVectorNegativeIndex() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.getVector(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vector index out of range");
        }

        @Test
        @DisplayName("getVector with index too large throws IAE")
        void getVectorIndexTooLarge() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.getVector(10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vector index out of range");
        }

        @Test
        @DisplayName("putVector with negative index throws IAE")
        void putVectorNegativeIndex() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] v = {1.0f, 2.0f, 3.0f, 4.0f};

            assertThatThrownBy(() -> block.putVector(-1, v))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vector index out of range");
        }

        @Test
        @DisplayName("putVector with null vector throws IAE")
        void putVectorNullVector() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.putVector(0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vector must have dimension");
        }

        @Test
        @DisplayName("putVector with wrong dimension throws IAE")
        void putVectorWrongDimension() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] v = {1.0f, 2.0f, 3.0f}; // Only 3 elements

            assertThatThrownBy(() -> block.putVector(0, v))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vector must have dimension");
        }

        @Test
        @DisplayName("getElement with negative element index throws IAE")
        void getElementNegativeElementIndex() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.getElement(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Element index out of range");
        }

        @Test
        @DisplayName("getElement with element index too large throws IAE")
        void getElementIndexTooLarge() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.getElement(0, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Element index out of range");
        }

        @Test
        @DisplayName("putElement with negative element index throws IAE")
        void putElementNegativeElementIndex() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.putElement(0, -1, 1.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Element index out of range");
        }
    }

    @Nested
    @DisplayName("getVector with destination array")
    class GetVectorWithDestination {

        @Test
        @DisplayName("getVector with destination array works")
        void getVectorWithDestination() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] v = {1.0f, 2.0f, 3.0f, 4.0f};
            block.putVector(5, v);

            float[] dest = new float[10];
            block.getVector(5, dest, 2);

            assertThat(dest[2]).isEqualTo(1.0f);
            assertThat(dest[3]).isEqualTo(2.0f);
            assertThat(dest[4]).isEqualTo(3.0f);
            assertThat(dest[5]).isEqualTo(4.0f);
        }

        @Test
        @DisplayName("getVector with null destination throws IAE")
        void getVectorNullDestination() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.getVector(0, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination array too small");
        }

        @Test
        @DisplayName("getVector with destination too small throws IAE")
        void getVectorDestinationTooSmall() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] dest = new float[3];

            assertThatThrownBy(() -> block.getVector(0, dest, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination array too small");
        }
    }

    @Nested
    @DisplayName("Batch operations error handling")
    class BatchOperationsErrors {

        @Test
        @DisplayName("putVectors with null array throws IAE")
        void putVectorsNullArray() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.putVectors(null, 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vectors array must not be null");
        }

        @Test
        @DisplayName("putVectors with negative offset throws IAE")
        void putVectorsNegativeOffset() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[][] vectors = new float[5][4];

            assertThatThrownBy(() -> block.putVectors(vectors, -1, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid offset or count");
        }

        @Test
        @DisplayName("putVectors with count exceeding capacity throws IAE")
        void putVectorsCountExceedsCapacity() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[][] vectors = new float[20][4];

            assertThatThrownBy(() -> block.putVectors(vectors, 0, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Count exceeds block capacity");
        }

        @Test
        @DisplayName("putVectorsFlat with null array throws IAE")
        void putVectorsFlatNullArray() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.putVectorsFlat(null, 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vectors array must not be null");
        }

        @Test
        @DisplayName("putVectorsFlat with negative offset throws IAE")
        void putVectorsFlatNegativeOffset() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] flat = new float[100];

            assertThatThrownBy(() -> block.putVectorsFlat(flat, -1, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid offset or count");
        }

        @Test
        @DisplayName("putVectorsFlat with insufficient data throws IAE")
        void putVectorsFlatInsufficientData() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] flat = new float[10]; // Not enough for 5 vectors of dim 4

            assertThatThrownBy(() -> block.putVectorsFlat(flat, 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient data in array");
        }

        @Test
        @DisplayName("getVectorsFlat with null destination throws IAE")
        void getVectorsFlatNullDestination() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThatThrownBy(() -> block.getVectorsFlat(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination array must not be null");
        }

        @Test
        @DisplayName("getVectorsFlat with destination too small throws IAE")
        void getVectorsFlatDestinationTooSmall() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] dest = new float[10]; // Need 40 for 10 vectors of dim 4

            assertThatThrownBy(() -> block.getVectorsFlat(dest, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination array too small");
        }
    }

    @Nested
    @DisplayName("computeDistances error handling")
    class ComputeDistancesErrors {

        @Test
        @DisplayName("computeDistances with null query throws NPE")
        void computeDistancesNullQuery() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] results = new float[10];

            assertThatThrownBy(() -> block.computeDistances(null, results, DistanceType.L2))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Query vector must not be null");
        }

        @Test
        @DisplayName("computeDistances with null results throws NPE")
        void computeDistancesNullResults() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] query = {1.0f, 2.0f, 3.0f, 4.0f};

            assertThatThrownBy(() -> block.computeDistances(query, null, DistanceType.L2))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Results array must not be null");
        }

        @Test
        @DisplayName("computeDistances with null distance type throws NPE")
        void computeDistancesNullDistanceType() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] query = {1.0f, 2.0f, 3.0f, 4.0f};
            float[] results = new float[10];

            assertThatThrownBy(() -> block.computeDistances(query, results, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Distance type must not be null");
        }

        @Test
        @DisplayName("computeDistances with query dimension mismatch throws IAE")
        void computeDistancesQueryDimensionMismatch() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] query = {1.0f, 2.0f, 3.0f}; // Only 3 elements
            float[] results = new float[10];

            assertThatThrownBy(() -> block.computeDistances(query, results, DistanceType.L2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query dimension mismatch");
        }

        @Test
        @DisplayName("computeDistances with results array too small throws IAE")
        void computeDistancesResultsTooSmall() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);
            float[] query = {1.0f, 2.0f, 3.0f, 4.0f};
            float[] results = new float[5]; // Need 10

            assertThatThrownBy(() -> block.computeDistances(query, results, DistanceType.L2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Results array too small");
        }
    }

    @Nested
    @DisplayName("Accessors")
    class Accessors {

        @Test
        @DisplayName("layout returns the layout")
        void layoutAccessor() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThat(block.layout()).isEqualTo(layout);
        }

        @Test
        @DisplayName("dimension returns the dimension")
        void dimensionAccessor() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThat(block.dimension()).isEqualTo(4);
        }

        @Test
        @DisplayName("vectorCount returns the count")
        void vectorCountAccessor() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThat(block.vectorCount()).isEqualTo(10);
        }

        @Test
        @DisplayName("buffer returns the buffer")
        void bufferAccessor() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThat(block.buffer()).isNotNull();
        }

        @Test
        @DisplayName("size returns total block size")
        void sizeAccessor() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            assertThat(block.size()).isEqualTo(layout.totalBlockSize());
        }

        @Test
        @DisplayName("toString contains useful information")
        void toStringContainsInfo() {
            VectorLayout layout = VectorLayout.create(4, 10);
            VectorBlock block = new VectorBlock(layout);

            String str = block.toString();

            assertThat(str).contains("VectorBlock");
            assertThat(str).contains("dimension=4");
            assertThat(str).contains("vectorCount=10");
        }
    }

    // Helper methods

    private static float[] createRandomVector(int dimension, long seed) {
        Random random = new Random(seed);
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2.0f - 1.0f; // Range [-1, 1]
        }
        return vector;
    }
}
