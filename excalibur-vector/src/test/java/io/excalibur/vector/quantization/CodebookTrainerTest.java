package io.excalibur.vector.quantization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CodebookTrainer.
 */
@DisplayName("CodebookTrainer Tests")
class CodebookTrainerTest {

    private CodebookTrainer trainer;

    @BeforeEach
    void setUp() {
        trainer = new CodebookTrainer(1000, 20, 1e-4f, 42);
    }

    @Test
    @DisplayName("Should train codebooks with valid parameters")
    void testTrainCodebooks() {
        // Generate sample vectors
        float[][] vectors = generateRandomVectors(100, 16);

        float[][][] codebooks = trainer.trainCodebooks(vectors, 4, 16);

        // Verify structure
        assertThat(codebooks.length).isEqualTo(4);  // 4 subspaces
        for (int s = 0; s < 4; s++) {
            assertThat(codebooks[s].length).isEqualTo(16);  // 16 centroids per subspace
            for (int c = 0; c < 16; c++) {
                assertThat(codebooks[s][c].length).isEqualTo(4);  // subvectorDim = 16/4 = 4
            }
        }
    }

    @Test
    @DisplayName("Should handle dimension that evenly divides by numSubspaces")
    void testEvenDivision() {
        float[][] vectors = generateRandomVectors(50, 128);

        float[][][] codebooks = trainer.trainCodebooks(vectors, 16, 32);

        assertThat(codebooks.length).isEqualTo(16);
        assertThat(codebooks[0][0].length).isEqualTo(8);  // 128 / 16 = 8
    }

    @Test
    @DisplayName("Should reject dimension that doesn't divide evenly")
    void testUnevenDivision() {
        float[][] vectors = generateRandomVectors(50, 100);  // 100 doesn't divide by 16

        assertThatThrownBy(() -> trainer.trainCodebooks(vectors, 16, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("divisible");
    }

    @Test
    @DisplayName("Should sample when sample size exceeds max")
    void testSampling() {
        CodebookTrainer smallTrainer = new CodebookTrainer(50, 10, 1e-4f, 42);
        float[][] vectors = generateRandomVectors(200, 16);

        // Should not throw - sampling should kick in
        float[][][] codebooks = smallTrainer.trainCodebooks(vectors, 4, 16);

        assertThat(codebooks.length).isEqualTo(4);
    }

    @Test
    @DisplayName("Should reject null vectors")
    void testNullVectors() {
        assertThatThrownBy(() -> trainer.trainCodebooks(null, 4, 16))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject empty vectors")
    void testEmptyVectors() {
        assertThatThrownBy(() -> trainer.trainCodebooks(new float[0][], 4, 16))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Should reject invalid numSubspaces")
    void testInvalidNumSubspaces() {
        float[][] vectors = generateRandomVectors(50, 16);

        assertThatThrownBy(() -> trainer.trainCodebooks(vectors, 0, 16))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> trainer.trainCodebooks(vectors, -1, 16))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject invalid numCentroids")
    void testInvalidNumCentroids() {
        float[][] vectors = generateRandomVectors(50, 16);

        assertThatThrownBy(() -> trainer.trainCodebooks(vectors, 4, 1))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> trainer.trainCodebooks(vectors, 4, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject null vectors in array")
    void testNullVectorInArray() {
        float[][] vectors = new float[3][];
        vectors[0] = new float[] {1.0f, 2.0f, 3.0f, 4.0f};
        vectors[1] = null;
        vectors[2] = new float[] {5.0f, 6.0f, 7.0f, 8.0f};

        assertThatThrownBy(() -> trainer.trainCodebooks(vectors, 2, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Should reject inconsistent dimensions")
    void testInconsistentDimensions() {
        float[][] vectors = {
            {1.0f, 2.0f, 3.0f, 4.0f},
            {1.0f, 2.0f}  // Wrong dimension
        };

        assertThatThrownBy(() -> trainer.trainCodebooks(vectors, 2, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Inconsistent");
    }

    @Test
    @DisplayName("Should train with progress callback")
    void testProgressCallback() {
        float[][] vectors = generateRandomVectors(50, 16);
        final int[] lastProgress = {-1, -1};

        float[][][] codebooks = trainer.trainCodebooks(vectors, 4, 16,
            (current, total) -> {
                lastProgress[0] = current;
                lastProgress[1] = total;
            });

        assertThat(codebooks.length).isEqualTo(4);
        assertThat(lastProgress[0]).isEqualTo(4);
        assertThat(lastProgress[1]).isEqualTo(4);
    }

    @Test
    @DisplayName("Should reject null progress callback")
    void testNullProgressCallback() {
        float[][] vectors = generateRandomVectors(50, 16);

        assertThatThrownBy(() -> trainer.trainCodebooks(vectors, 4, 16, (CodebookTrainer.ProgressCallback) null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Default constructor should work")
    void testDefaultConstructor() {
        CodebookTrainer defaultTrainer = new CodebookTrainer();

        assertThat(defaultTrainer.maxTrainingSamples()).isEqualTo(CodebookTrainer.DEFAULT_MAX_TRAINING_SAMPLES);
        assertThat(defaultTrainer.maxIterations()).isEqualTo(CodebookTrainer.DEFAULT_MAX_ITERATIONS);
        assertThat(defaultTrainer.convergenceThreshold()).isEqualTo(CodebookTrainer.DEFAULT_CONVERGENCE_THRESHOLD);
    }

    @Test
    @DisplayName("Should reject invalid constructor parameters")
    void testInvalidConstructorParams() {
        assertThatThrownBy(() -> new CodebookTrainer(0, 10, 1e-4f, 0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CodebookTrainer(100, 0, 1e-4f, 0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CodebookTrainer(100, 10, -1.0f, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // Helper method to generate random vectors
    private float[][] generateRandomVectors(int count, int dimension) {
        float[][] vectors = new float[count][dimension];
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < dimension; d++) {
                vectors[i][d] = random.nextFloat();
            }
        }
        return vectors;
    }
}
