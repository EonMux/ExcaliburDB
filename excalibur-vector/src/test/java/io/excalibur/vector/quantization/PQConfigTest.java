package io.excalibur.vector.quantization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for PQConfig.
 */
@DisplayName("PQConfig Tests")
class PQConfigTest {

    @Test
    @DisplayName("Default constructor should use default values")
    void testDefaultConstructor() {
        PQConfig config = new PQConfig();

        assertThat(config.numSubspaces()).isEqualTo(PQConfig.DEFAULT_NUM_SUBSPACES);
        assertThat(config.numCentroids()).isEqualTo(PQConfig.DEFAULT_NUM_CENTROIDS);
        assertThat(config.maxTrainingSamples()).isEqualTo(PQConfig.DEFAULT_MAX_TRAINING_SAMPLES);
        assertThat(config.maxIterations()).isEqualTo(PQConfig.DEFAULT_MAX_ITERATIONS);
        assertThat(config.convergenceThreshold()).isEqualTo(PQConfig.DEFAULT_CONVERGENCE_THRESHOLD);
        assertThat(config.seed()).isEqualTo(0);
    }

    @Test
    @DisplayName("Constructor with M and K should use default training params")
    void testTwoParamConstructor() {
        PQConfig config = new PQConfig(16, 128);

        assertThat(config.numSubspaces()).isEqualTo(16);
        assertThat(config.numCentroids()).isEqualTo(128);
        assertThat(config.maxTrainingSamples()).isEqualTo(PQConfig.DEFAULT_MAX_TRAINING_SAMPLES);
    }

    @Test
    @DisplayName("Full constructor should set all parameters")
    void testFullConstructor() {
        PQConfig config = new PQConfig(8, 256, 50000, 10, 1e-3f, 42);

        assertThat(config.numSubspaces()).isEqualTo(8);
        assertThat(config.numCentroids()).isEqualTo(256);
        assertThat(config.maxTrainingSamples()).isEqualTo(50000);
        assertThat(config.maxIterations()).isEqualTo(10);
        assertThat(config.convergenceThreshold()).isEqualTo(1e-3f);
        assertThat(config.seed()).isEqualTo(42);
    }

    @Test
    @DisplayName("Should reject invalid numSubspaces")
    void testInvalidNumSubspaces() {
        assertThatThrownBy(() -> new PQConfig(0, 256))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("numSubspaces");

        assertThatThrownBy(() -> new PQConfig(129, 256))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("numSubspaces");
    }

    @Test
    @DisplayName("Should reject invalid numCentroids")
    void testInvalidNumCentroids() {
        assertThatThrownBy(() -> new PQConfig(8, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("numCentroids");

        assertThatThrownBy(() -> new PQConfig(8, 257))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("numCentroids");
    }

    @Test
    @DisplayName("Should reject invalid maxIterations")
    void testInvalidMaxIterations() {
        assertThatThrownBy(() -> new PQConfig(8, 256, 1000, 0, 1e-4f, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxIterations");
    }

    @Test
    @DisplayName("Should reject negative convergence threshold")
    void testInvalidConvergenceThreshold() {
        assertThatThrownBy(() -> new PQConfig(8, 256, 1000, 10, -0.1f, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("convergenceThreshold");
    }

    @Test
    @DisplayName("Should handle zero maxTrainingSamples (use all)")
    void testZeroMaxTrainingSamples() {
        PQConfig config = new PQConfig(8, 256, 0, 10, 1e-4f, 0);
        assertThat(config.maxTrainingSamples()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("validateDimension should accept compatible dimensions")
    void testValidateDimensionValid() {
        PQConfig config = new PQConfig(8, 256);

        // Should not throw
        config.validateDimension(128);  // 128 / 8 = 16
        config.validateDimension(256);  // 256 / 8 = 32
        config.validateDimension(768);  // 768 / 8 = 96
    }

    @Test
    @DisplayName("validateDimension should reject incompatible dimensions")
    void testValidateDimensionInvalid() {
        PQConfig config = new PQConfig(8, 256);

        assertThatThrownBy(() -> config.validateDimension(127))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be divisible");

        assertThatThrownBy(() -> config.validateDimension(100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be divisible");
    }

    @Test
    @DisplayName("validateDimension should reject dimension < 1")
    void testValidateDimensionZero() {
        PQConfig config = new PQConfig(8, 256);

        assertThatThrownBy(() -> config.validateDimension(0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config.validateDimension(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isCompatible should return correct results")
    void testIsCompatible() {
        PQConfig config = new PQConfig(8, 256);

        assertThat(config.isCompatible(128)).isTrue();
        assertThat(config.isCompatible(256)).isTrue();
        assertThat(config.isCompatible(127)).isFalse();
        assertThat(config.isCompatible(0)).isFalse();
        assertThat(config.isCompatible(-1)).isFalse();
    }

    @Test
    @DisplayName("subvectorDim should return correct dimension")
    void testSubvectorDim() {
        PQConfig config = new PQConfig(8, 256);

        assertThat(config.subvectorDim(128)).isEqualTo(16);
        assertThat(config.subvectorDim(256)).isEqualTo(32);
        assertThat(config.subvectorDim(768)).isEqualTo(96);
    }

    @Test
    @DisplayName("codeSize should equal numSubspaces")
    void testCodeSize() {
        PQConfig config = new PQConfig(16, 256);
        assertThat(config.codeSize()).isEqualTo(16);
    }

    @Test
    @DisplayName("compressionRatio should calculate correct ratio")
    void testCompressionRatio() {
        PQConfig config = new PQConfig(8, 256);

        // 128D * 4 bytes = 512 bytes
        // 8 codes = 8 bytes
        // Ratio = 512 / 8 = 64
        assertThat(config.compressionRatio(128)).isEqualTo(64.0f);

        // 256D * 4 bytes = 1024 bytes
        // 8 codes = 8 bytes
        // Ratio = 1024 / 8 = 128
        assertThat(config.compressionRatio(256)).isEqualTo(128.0f);
    }

    @Test
    @DisplayName("createQuantizer should create ProductQuantizer with correct config")
    void testCreateQuantizer() {
        PQConfig config = new PQConfig(8, 256);
        ProductQuantizer pq = config.createQuantizer(128);

        assertThat(pq).isNotNull();
        assertThat(pq.originalDim()).isEqualTo(128);
        assertThat(pq.numSubspaces()).isEqualTo(8);
        assertThat(pq.numCentroids()).isEqualTo(256);
        assertThat(pq.isTrained()).isFalse();
    }

    @Test
    @DisplayName("createTrainer should create CodebookTrainer")
    void testCreateTrainer() {
        PQConfig config = new PQConfig(8, 256, 50000, 15, 1e-3f, 42);
        CodebookTrainer trainer = config.createTrainer();

        assertThat(trainer).isNotNull();
        assertThat(trainer.maxTrainingSamples()).isEqualTo(50000);
        assertThat(trainer.maxIterations()).isEqualTo(15);
        assertThat(trainer.convergenceThreshold()).isEqualTo(1e-3f);
    }

    // ==================== Factory Method Tests ====================

    @Test
    @DisplayName("forDimension should create compatible config")
    void testForDimension() {
        // 768 is common for BERT
        PQConfig config = PQConfig.forDimension(768);
        assertThat(config.isCompatible(768)).isTrue();

        // 384 is common for smaller models
        config = PQConfig.forDimension(384);
        assertThat(config.isCompatible(384)).isTrue();
    }

    @Test
    @DisplayName("forDimension should reject invalid dimension")
    void testForDimensionInvalid() {
        assertThatThrownBy(() -> PQConfig.forDimension(0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PQConfig.forDimension(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("highCompression should use more subspaces")
    void testHighCompression() {
        PQConfig config = PQConfig.highCompression(128);
        assertThat(config.isCompatible(128)).isTrue();
        // High compression uses more subspaces, resulting in higher compression ratio
        assertThat(config.compressionRatio(128)).isGreaterThan(16.0f);
    }

    @Test
    @DisplayName("highAccuracy should use fewer subspaces")
    void testHighAccuracy() {
        PQConfig config = PQConfig.highAccuracy(128);
        assertThat(config.isCompatible(128)).isTrue();
        // High accuracy uses fewer subspaces (M=4), which gives higher compression
        // but with larger subvectors for better quantization accuracy
        // 128 dims with M=4: ratio = (128 * 4) / 4 = 128x compression
        assertThat(config.numSubspaces()).isLessThanOrEqualTo(8);
        assertThat(config.compressionRatio(128)).isGreaterThan(32.0f);
    }

    // ==================== Builder Tests ====================

    @Test
    @DisplayName("Builder should create config with all parameters")
    void testBuilder() {
        PQConfig config = PQConfig.builder()
            .numSubspaces(16)
            .numCentroids(128)
            .maxTrainingSamples(75000)
            .maxIterations(25)
            .convergenceThreshold(5e-4f)
            .seed(12345)
            .build();

        assertThat(config.numSubspaces()).isEqualTo(16);
        assertThat(config.numCentroids()).isEqualTo(128);
        assertThat(config.maxTrainingSamples()).isEqualTo(75000);
        assertThat(config.maxIterations()).isEqualTo(25);
        assertThat(config.convergenceThreshold()).isEqualTo(5e-4f);
        assertThat(config.seed()).isEqualTo(12345);
    }

    @Test
    @DisplayName("Builder should use defaults for unset parameters")
    void testBuilderDefaults() {
        PQConfig config = PQConfig.builder().build();

        assertThat(config.numSubspaces()).isEqualTo(PQConfig.DEFAULT_NUM_SUBSPACES);
        assertThat(config.numCentroids()).isEqualTo(PQConfig.DEFAULT_NUM_CENTROIDS);
    }

    // ==================== Equality Tests ====================

    @Test
    @DisplayName("equals should compare all fields")
    void testEquals() {
        PQConfig config1 = new PQConfig(8, 256, 50000, 20, 1e-4f, 42);
        PQConfig config2 = new PQConfig(8, 256, 50000, 20, 1e-4f, 42);
        PQConfig config3 = new PQConfig(16, 256, 50000, 20, 1e-4f, 42);

        assertThat(config1).isEqualTo(config2);
        assertThat(config1).isNotEqualTo(config3);
        assertThat(config1).isNotEqualTo(null);
        assertThat(config1).isNotEqualTo("not a config");
    }

    @Test
    @DisplayName("hashCode should be consistent with equals")
    void testHashCode() {
        PQConfig config1 = new PQConfig(8, 256, 50000, 20, 1e-4f, 42);
        PQConfig config2 = new PQConfig(8, 256, 50000, 20, 1e-4f, 42);

        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    @DisplayName("toString should contain all parameters")
    void testToString() {
        PQConfig config = new PQConfig(8, 256, 50000, 20, 1e-4f, 42);
        String str = config.toString();

        assertThat(str).contains("M=8");
        assertThat(str).contains("K=256");
        assertThat(str).contains("maxSamples=50000");
        assertThat(str).contains("seed=42");
    }

    // ==================== Integration Tests ====================

    @Test
    @DisplayName("Config-created quantizer should train and encode correctly")
    void testQuantizerIntegration() {
        PQConfig config = new PQConfig(8, 256, 1000, 20, 1e-4f, 42);
        ProductQuantizer pq = config.createQuantizer(128);

        // Generate training data
        float[][] trainingData = generateRandomVectors(500, 128);

        // Train
        pq.train(trainingData);
        assertThat(pq.isTrained()).isTrue();

        // Encode
        float[] vector = generateRandomVectors(1, 128)[0];
        byte[] codes = pq.encode(vector);

        assertThat(codes).hasSize(config.codeSize());

        // Decode
        float[] reconstructed = pq.decode(codes);
        assertThat(reconstructed).hasSize(128);
    }

    // Helper method
    private float[][] generateRandomVectors(int count, int dimension) {
        float[][] vectors = new float[count][dimension];
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < dimension; d++) {
                vectors[i][d] = random.nextFloat() * 2 - 1;
            }
        }
        return vectors;
    }
}
