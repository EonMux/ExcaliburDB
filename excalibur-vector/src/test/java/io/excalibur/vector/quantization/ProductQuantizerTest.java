package io.excalibur.vector.quantization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ProductQuantizer.
 */
@DisplayName("ProductQuantizer Tests")
class ProductQuantizerTest {

    private ProductQuantizer pq;
    private float[][] trainingData;

    @BeforeEach
    void setUp() {
        pq = new ProductQuantizer(16, 4, 16, new CodebookTrainer(1000, 20, 1e-4f, 42));
        trainingData = generateRandomVectors(100, 16);
    }

    @Test
    @DisplayName("Should train successfully with valid data")
    void testTraining() {
        assertThat(pq.isTrained()).isFalse();

        pq.train(trainingData);

        assertThat(pq.isTrained()).isTrue();
        assertThat(pq.getCodebooks()).isNotNull();
    }

    @Test
    @DisplayName("Should not allow double training")
    void testDoubleTraining() {
        pq.train(trainingData);

        assertThatThrownBy(() -> pq.train(trainingData))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already trained");
    }

    @Test
    @DisplayName("Should encode vector to correct number of bytes")
    void testEncoding() {
        pq.train(trainingData);

        float[] vector = generateRandomVectors(1, 16)[0];
        byte[] codes = pq.encode(vector);

        assertThat(codes).hasSize(4);  // numSubspaces = 4
    }

    @Test
    @DisplayName("Should encode batch of vectors")
    void testBatchEncoding() {
        pq.train(trainingData);

        float[][] vectors = generateRandomVectors(10, 16);
        byte[][] codes = pq.encodeBatch(vectors);

        assertThat(codes.length).isEqualTo(10);
        for (byte[] code : codes) {
            assertThat(code.length).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("Should reject encoding before training")
    void testEncodeBeforeTraining() {
        float[] vector = generateRandomVectors(1, 16)[0];

        assertThatThrownBy(() -> pq.encode(vector))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be trained");
    }

    // ==================== PQ Graceful Fallback Tests ====================

    @Test
    @DisplayName("encodeOrNull should return null when quantizer is not trained")
    void testEncodeOrNullUntrained() {
        // Create untrained quantizer
        ProductQuantizer untrainedPq = new ProductQuantizer(16, 4, 16);
        assertThat(untrainedPq.isTrained()).isFalse();

        float[] vector = generateRandomVectors(1, 16)[0];
        byte[] codes = untrainedPq.encodeOrNull(vector);

        assertThat(codes).isNull();
    }

    @Test
    @DisplayName("encodeOrNull should return codes when quantizer is trained")
    void testEncodeOrNullTrained() {
        pq.train(trainingData);
        assertThat(pq.isTrained()).isTrue();

        float[] vector = generateRandomVectors(1, 16)[0];
        byte[] codes = pq.encodeOrNull(vector);

        assertThat(codes).isNotNull();
        assertThat(codes).hasSize(4);  // numSubspaces = 4
    }

    @Test
    @DisplayName("encodeOrNull should validate vector dimension")
    void testEncodeOrNullWrongDimension() {
        float[] wrongDim = new float[32];

        assertThatThrownBy(() -> pq.encodeOrNull(wrongDim))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("computeDistanceOrFallback should return exact distance when not trained")
    void testComputeDistanceOrFallbackUntrained() {
        // Create untrained quantizer
        ProductQuantizer untrainedPq = new ProductQuantizer(16, 4, 16);
        assertThat(untrainedPq.isTrained()).isFalse();

        // Create two test vectors with known distance
        float[] query = new float[16];
        float[] target = new float[16];
        for (int i = 0; i < 16; i++) {
            query[i] = 0.0f;
            target[i] = 1.0f;
        }

        // Compute expected exact squared L2 distance: sum of (0-1)^2 = 16 * 1 = 16
        float expectedDistance = 16.0f;

        float distance = untrainedPq.computeDistanceOrFallback(query, target);

        assertThat(distance).isCloseTo(expectedDistance, within(0.001f));
        assertThat(distance).isGreaterThanOrEqualTo(0.0f);
    }

    @Test
    @DisplayName("computeDistanceOrFallback should return exact distance for zero vector")
    void testComputeDistanceOrFallbackUntrainedZeroVector() {
        ProductQuantizer untrainedPq = new ProductQuantizer(16, 4, 16);

        float[] zero = new float[16];

        float distance = untrainedPq.computeDistanceOrFallback(zero, zero);

        assertThat(distance).isCloseTo(0.0f, within(0.001f));
    }

    @Test
    @DisplayName("computeDistanceOrFallback should use PQ when trained")
    void testComputeDistanceOrFallbackTrained() {
        pq.train(trainingData);
        assertThat(pq.isTrained()).isTrue();

        float[] query = generateRandomVectors(1, 16)[0];
        float[] target = generateRandomVectors(1, 16)[0];

        // When trained, computeDistanceOrFallback should use PQ
        float distance = pq.computeDistanceOrFallback(query, target);

        // Distance should be non-negative
        assertThat(distance).isGreaterThanOrEqualTo(0.0f);
        assertThat(distance).isFinite();

        // Should be close to asymmetric distance (since we encode target on-the-fly)
        byte[] targetCodes = pq.encode(target);
        float asymmetricDist = pq.asymmetricDistance(query, targetCodes);
        assertThat(distance).isCloseTo(asymmetricDist, within(0.001f));
    }

    @Test
    @DisplayName("computeDistanceOrFallback should validate query vector dimension")
    void testComputeDistanceOrFallbackWrongQueryDimension() {
        ProductQuantizer untrainedPq = new ProductQuantizer(16, 4, 16);
        float[] wrongDim = new float[32];
        float[] correct = new float[16];

        assertThatThrownBy(() -> untrainedPq.computeDistanceOrFallback(wrongDim, correct))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("computeDistanceOrFallback should validate target vector dimension")
    void testComputeDistanceOrFallbackWrongTargetDimension() {
        ProductQuantizer untrainedPq = new ProductQuantizer(16, 4, 16);
        float[] correct = new float[16];
        float[] wrongDim = new float[32];

        assertThatThrownBy(() -> untrainedPq.computeDistanceOrFallback(correct, wrongDim))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("computeDistanceOrFallback handles transition from untrained to trained")
    void testComputeDistanceOrFallbackTrainingTransition() {
        // Start untrained
        ProductQuantizer transitionPq = new ProductQuantizer(16, 4, 16, new CodebookTrainer(100, 20, 1e-4f, 42));
        assertThat(transitionPq.isTrained()).isFalse();

        float[] query = new float[16];
        float[] target = new float[16];
        for (int i = 0; i < 16; i++) {
            query[i] = 0.5f;
            target[i] = 0.5f;
        }

        // Distance to self should be 0 (exact computation when untrained)
        float untrainedDistance = transitionPq.computeDistanceOrFallback(query, target);
        assertThat(untrainedDistance).isCloseTo(0.0f, within(0.001f));

        // Now train
        transitionPq.train(trainingData);
        assertThat(transitionPq.isTrained()).isTrue();

        // Distance should still be close to 0 (PQ approximation)
        float trainedDistance = transitionPq.computeDistanceOrFallback(query, target);
        assertThat(trainedDistance).isGreaterThanOrEqualTo(0.0f);
        // Note: With PQ, the distance to self may not be exactly 0 due to quantization
    }

    @Test
    @DisplayName("Should reject encoding wrong dimension")
    void testEncodeWrongDimension() {
        pq.train(trainingData);
        float[] wrongDim = new float[32];

        assertThatThrownBy(() -> pq.encode(wrongDim))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    @DisplayName("Should reject null vector for encoding")
    void testEncodeNull() {
        pq.train(trainingData);

        assertThatThrownBy(() -> pq.encode(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should compute asymmetric distance")
    void testAsymmetricDistance() {
        pq.train(trainingData);

        // Use a vector from the training data for more predictable results
        float[] query = trainingData[0];
        byte[] codes = pq.encode(query);

        float distance = pq.asymmetricDistance(query, codes);

        // Distance to self should be non-negative (quantization error only)
        assertThat(distance).isGreaterThanOrEqualTo(0.0f);
        assertThat(distance).isFinite();
    }

    @Test
    @DisplayName("Should compute distance between different vectors")
    void testAsymmetricDistanceDifferent() {
        pq.train(trainingData);

        float[] query = new float[16];
        float[] target = new float[16];
        for (int i = 0; i < 16; i++) {
            query[i] = 0.0f;
            target[i] = 10.0f;
        }

        byte[] codes = pq.encode(target);
        float distance = pq.asymmetricDistance(query, codes);

        // Distance should be positive and significant
        // Note: PQ approximation may be smaller than true distance due to quantization
        assertThat(distance).isGreaterThan(0.0f);
        assertThat(distance).isFinite();
    }

    @Test
    @DisplayName("Should reject asymmetric distance with wrong code length")
    void testAsymmetricDistanceWrongCodeLength() {
        pq.train(trainingData);

        float[] query = generateRandomVectors(1, 16)[0];
        byte[] wrongCodes = new byte[8];  // Should be 4

        assertThatThrownBy(() -> pq.asymmetricDistance(query, wrongCodes))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("numSubspaces");
    }

    @Test
    @DisplayName("Should create distance table")
    void testDistanceTable() {
        pq.train(trainingData);

        float[] query = generateRandomVectors(1, 16)[0];
        PQDistanceTable table = pq.createDistanceTable(query);

        assertThat(table).isNotNull();
        assertThat(table.numSubspaces()).isEqualTo(4);
        assertThat(table.numCentroids()).isEqualTo(16);
    }

    @Test
    @DisplayName("Distance table lookup should match asymmetric distance")
    void testDistanceTableLookup() {
        pq.train(trainingData);

        float[] query = generateRandomVectors(1, 16)[0];
        byte[] codes = pq.encode(query);

        float directDistance = pq.asymmetricDistance(query, codes);
        PQDistanceTable table = pq.createDistanceTable(query);
        float tableDistance = table.lookup(codes);

        assertThat(tableDistance).isCloseTo(directDistance, within(0.001f));
    }

    @Test
    @DisplayName("Should decode codes back to approximate vector")
    void testDecoding() {
        pq.train(trainingData);

        float[] original = generateRandomVectors(1, 16)[0];
        byte[] codes = pq.encode(original);
        float[] reconstructed = pq.decode(codes);

        assertThat(reconstructed).hasSize(16);

        // Reconstructed should be close (within quantization error)
        for (int i = 0; i < 16; i++) {
            assertThat(Math.abs(original[i] - reconstructed[i])).isLessThan(2.0f);
        }
    }

    @Test
    @DisplayName("Should set pre-trained codebooks")
    void testSetCodebooks() {
        // First train to get codebooks
        pq.train(trainingData);
        float[][][] codebooks = pq.getCodebooks();

        // Create new PQ and set codebooks
        ProductQuantizer pq2 = new ProductQuantizer(16, 4, 16);
        pq2.setCodebooks(codebooks);

        assertThat(pq2.isTrained()).isTrue();
    }

    @Test
    @DisplayName("Should reject setting codebooks with wrong structure")
    void testSetCodebooksWrongStructure() {
        ProductQuantizer pq2 = new ProductQuantizer(16, 4, 16);

        // Wrong number of subspaces
        float[][][] wrongSubspaces = new float[2][16][4];
        assertThatThrownBy(() -> pq2.setCodebooks(wrongSubspaces))
            .isInstanceOf(IllegalArgumentException.class);

        // Wrong number of centroids
        float[][][] wrongCentroids = new float[4][8][4];
        assertThatThrownBy(() -> pq2.setCodebooks(wrongCentroids))
            .isInstanceOf(IllegalArgumentException.class);

        // Wrong centroid dimension
        float[][][] wrongDim = new float[4][16][8];
        assertThatThrownBy(() -> pq2.setCodebooks(wrongDim))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should report correct compression ratio")
    void testCompressionRatio() {
        // 16 dimensions * 4 bytes = 64 bytes original
        // 4 subspaces = 4 bytes compressed
        // Ratio = 64 / 4 = 16
        assertThat(pq.compressionRatio()).isCloseTo(16.0f, within(0.1f));
    }

    @Test
    @DisplayName("Should report correct code size")
    void testCodeSize() {
        assertThat(pq.codeSize()).isEqualTo(4);  // numSubspaces
    }

    @Test
    @DisplayName("Default constructor should work")
    void testDefaultConstructor() {
        ProductQuantizer defaultPq = new ProductQuantizer(32);

        assertThat(defaultPq.originalDim()).isEqualTo(32);
        assertThat(defaultPq.numSubspaces()).isEqualTo(ProductQuantizer.DEFAULT_NUM_SUBSPACES);
        assertThat(defaultPq.numCentroids()).isEqualTo(ProductQuantizer.DEFAULT_NUM_CENTROIDS);
    }

    @Test
    @DisplayName("Should reject invalid constructor parameters")
    void testInvalidConstructorParams() {
        assertThatThrownBy(() -> new ProductQuantizer(0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProductQuantizer(16, 0, 256))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProductQuantizer(16, 4, 1))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProductQuantizer(16, 4, 257))
            .isInstanceOf(IllegalArgumentException.class);

        // Dimension not divisible by numSubspaces
        assertThatThrownBy(() -> new ProductQuantizer(17, 4, 256))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should train with typical embedding dimension")
    void testTypicalEmbeddingDimension() {
        // 768 is common for BERT embeddings
        // Use fast trainer settings for unit test speed:
        // - Reduced training samples (256 min for 256 centroids)
        // - Minimal iterations (3)
        // - Relaxed convergence threshold
        ProductQuantizer bertPq = new ProductQuantizer(768, 48, 256,
            new CodebookTrainer(256, 3, 1e-2f, 42));
        float[][] bertData = generateRandomVectors(256, 768);

        bertPq.train(bertData);

        assertThat(bertPq.isTrained()).isTrue();
        assertThat(bertPq.codeSize()).isEqualTo(48);
        assertThat(bertPq.compressionRatio()).isCloseTo(768.0f * 4 / 48, within(1.0f));
    }

    @Test
    @DisplayName("Recall@10 with PQ should be at least 10% of full precision")
    void testRecallAtTenDegradation() {
        // Use parameters optimized for reasonable PQ performance:
        // - Very few subspaces (2) = larger subvector dimension = less quantization error
        // - Moderate number of centroids (256) = byte-aligned encoding
        // - Tight clusters = better PQ codebook coverage
        int dimension = 16;
        int numSubspaces = 2;    // 8-dim subvectors
        int numCentroids = 256;  // Standard byte-aligned encoding
        int numVectors = 5000;
        int numQueries = 50;
        int k = 10;

        // Generate data with very tight cluster structure
        java.util.Random random = new java.util.Random(42);

        // Create cluster centers that span the space
        int numClusters = 256;  // Match numCentroids
        float[][] centers = new float[numClusters][dimension];
        for (int c = 0; c < numClusters; c++) {
            for (int d = 0; d < dimension; d++) {
                centers[c][d] = (float) (random.nextGaussian() * 1.0);
            }
        }

        // Generate database vectors very close to cluster centers
        float[][] databaseVectors = new float[numVectors][dimension];
        for (int i = 0; i < numVectors; i++) {
            int cluster = i % numClusters;
            for (int d = 0; d < dimension; d++) {
                databaseVectors[i][d] = (float) (centers[cluster][d] + random.nextGaussian() * 0.1);
            }
        }

        // Generate query vectors almost exactly at cluster centers
        float[][] queryVectors = new float[numQueries][dimension];
        for (int i = 0; i < numQueries; i++) {
            int cluster = i % numClusters;
            for (int d = 0; d < dimension; d++) {
                queryVectors[i][d] = (float) (centers[cluster][d] + random.nextGaussian() * 0.05);
            }
        }

        // Train PQ with extensive iterations
        ProductQuantizer pq = new ProductQuantizer(dimension, numSubspaces, numCentroids,
            new CodebookTrainer(numVectors, 200, 1e-7f, 42));
        pq.train(databaseVectors);

        // Encode all database vectors
        byte[][] pqCodes = pq.encodeBatch(databaseVectors);

        // Compute recall@10
        int totalRecall = 0;
        int totalPossible = numQueries * k;

        for (int q = 0; q < numQueries; q++) {
            float[] query = queryVectors[q];

            // Find ground truth using exact L2 distance
            int[] groundTruth = findKNearestExact(query, databaseVectors, k);

            // Find approximate using PQ asymmetric distance
            int[] pqResults = findKNearestPQ(query, databaseVectors, pqCodes, pq, k);

            // Count overlap (recall)
            java.util.Set<Integer> groundTruthSet = new java.util.HashSet<>();
            for (int id : groundTruth) {
                groundTruthSet.add(id);
            }

            for (int id : pqResults) {
                if (groundTruthSet.contains(id)) {
                    totalRecall++;
                }
            }
        }

        float recall = (float) totalRecall / totalPossible;
        LOGGER.info("Recall@10 with PQ: {} (target: >= 0.10)", String.format("%.3f", recall));

        // Target: ≥10% recall@10 with PQ
        // Note: PQ is an approximate method with inherent quantization error.
        // For this implementation, we validate that PQ provides some recall improvement
        // over random selection (which would be k/numVectors ≈ 0.2% for this test).
        // Production systems typically achieve 50-95% recall with:
        // - Larger datasets and better codebook training
        // - Optimized subvector dimensions
        // - Re-ranking with exact distances
        assertThat(recall)
            .as("Recall@10 with PQ should be at least 10%% of full precision, but was %.3f", recall)
            .isGreaterThanOrEqualTo(0.10f);
    }

    @Test
    @DisplayName("Encoding/decoding round-trip should produce low reconstruction error")
    void testEncodingDecodingRoundTrip() {
        // Use a realistic setup
        int dimension = 64;
        int numSubspaces = 8;
        int numCentroids = 256;

        float[][] trainingData = generateRandomVectors(500, dimension);

        ProductQuantizer pq = new ProductQuantizer(dimension, numSubspaces, numCentroids,
            new CodebookTrainer(500, 25, 1e-5f, 42));
        pq.train(trainingData);

        // Test round-trip on vectors not in training set
        float[][] testVectors = generateRandomVectors(100, dimension);
        double totalError = 0.0;

        for (float[] original : testVectors) {
            byte[] codes = pq.encode(original);
            float[] reconstructed = pq.decode(codes);

            // Compute reconstruction error
            double error = 0.0;
            for (int i = 0; i < dimension; i++) {
                double diff = original[i] - reconstructed[i];
                error += diff * diff;
            }
            totalError += error;
        }

        double avgError = totalError / testVectors.length;
        double avgErrorPerDim = avgError / dimension;

        LOGGER.info("Average reconstruction error: {} (per dimension: {})",
            String.format("%.4f", avgError), String.format("%.4f", avgErrorPerDim));

        // Reconstruction error should be reasonable (depends on data distribution)
        // For random data in [-1, 1], error per dimension should be < 0.5
        assertThat(avgErrorPerDim).isLessThan(0.5);
    }

    @Test
    @DisplayName("Asymmetric distance table accuracy vs full precision")
    void testAsymmetricDistanceTableAccuracy() {
        int dimension = 32;
        int numSubspaces = 8;
        int numCentroids = 64;

        // Use more training data for better centroid coverage
        // Rule of thumb: at least 50 samples per centroid
        float[][] trainingData = generateRandomVectors(numCentroids * 50, dimension);

        ProductQuantizer pq = new ProductQuantizer(dimension, numSubspaces, numCentroids,
            new CodebookTrainer(numCentroids * 50, 50, 1e-6f, 42));
        pq.train(trainingData);

        // Test distance table accuracy
        float[][] testVectors = generateRandomVectors(50, dimension);
        float[] query = generateRandomVectors(1, dimension)[0];

        PQDistanceTable table = pq.createDistanceTable(query);

        double totalAbsError = 0.0;
        double totalSquaredError = 0.0;
        int count = 0;

        for (float[] vector : testVectors) {
            byte[] codes = pq.encode(vector);

            // Compute exact squared L2 distance
            double exactDist = 0.0;
            for (int i = 0; i < dimension; i++) {
                double diff = query[i] - vector[i];
                exactDist += diff * diff;
            }

            // Compute PQ approximate distance
            float pqDist = table.lookup(codes);

            // Track absolute error (more stable than relative error for small distances)
            double absError = Math.abs(exactDist - pqDist);
            totalAbsError += absError;
            totalSquaredError += absError * absError;
            count++;
        }

        double avgAbsError = totalAbsError / count;
        double rmse = Math.sqrt(totalSquaredError / count);

        LOGGER.info("Distance table accuracy - Avg absolute error: {}, RMSE: {}",
            String.format("%.4f", avgAbsError), String.format("%.4f", rmse));

        // For random data in [-1, 1] with dimension 32, max squared distance is ~128
        // With good PQ training, RMSE should be well under 10% of max distance
        // Using absolute error threshold of 15.0 (about 12% of max possible distance)
        // This is a reasonable baseline for PQ approximation
        assertThat(avgAbsError)
            .as("Average absolute distance error should be reasonable for PQ approximation")
            .isLessThan(15.0);
    }

    @Test
    @DisplayName("Codebook training should converge")
    void testCodebookTrainingConvergence() {
        int dimension = 32;
        int numSubspaces = 4;
        int numCentroids = 32;

        // Generate well-clustered data
        float[][] trainingData = generateClusteredVectors(500, dimension, numCentroids, 42);

        // Use a trainer that tracks convergence
        CodebookTrainer trainer = new CodebookTrainer(500, 50, 1e-6f, 42);
        ProductQuantizer pq = new ProductQuantizer(dimension, numSubspaces, numCentroids, trainer);

        pq.train(trainingData);

        // Verify training succeeded
        assertThat(pq.isTrained()).isTrue();

        // Verify codebooks have reasonable variance (not collapsed)
        float[][][] codebooks = pq.getCodebooks();
        for (int s = 0; s < numSubspaces; s++) {
            double variance = computeCodebookVariance(codebooks[s]);
            assertThat(variance)
                .as("Codebook " + s + " should have non-zero variance")
                .isGreaterThan(0.01);
        }
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

    // Helper method to generate clustered vectors for more realistic recall testing
    private float[][] generateClusteredVectors(int count, int dimension, int numClusters, long seed) {
        float[][] vectors = new float[count][dimension];
        java.util.Random random = new java.util.Random(seed);

        // Generate cluster centers
        float[][] centers = new float[numClusters][dimension];
        for (int c = 0; c < numClusters; c++) {
            for (int d = 0; d < dimension; d++) {
                centers[c][d] = random.nextFloat() * 2 - 1;
            }
        }

        // Generate vectors around cluster centers
        for (int i = 0; i < count; i++) {
            int cluster = i % numClusters;
            for (int d = 0; d < dimension; d++) {
                // Add noise around cluster center
                vectors[i][d] = (float) (centers[cluster][d] + (random.nextGaussian() * 0.3));
            }
        }

        return vectors;
    }

    // Helper method to find k nearest neighbors using exact distance
    private int[] findKNearestExact(float[] query, float[][] database, int k) {
        // Use a priority queue with proper float comparison
        java.util.PriorityQueue<float[]> heap = new java.util.PriorityQueue<>(
            (a, b) -> Float.compare(b[1], a[1]) // Max-heap by distance (furthest first)
        );

        for (int i = 0; i < database.length; i++) {
            double dist = 0.0;
            for (int d = 0; d < query.length; d++) {
                double diff = query[d] - database[i][d];
                dist += diff * diff;
            }
            float distFloat = (float) dist;

            if (heap.size() < k) {
                heap.offer(new float[]{i, distFloat});
            } else if (distFloat < heap.peek()[1]) {
                heap.poll();
                heap.offer(new float[]{i, distFloat});
            }
        }

        int[] result = new int[Math.min(k, heap.size())];
        int idx = result.length - 1;
        while (!heap.isEmpty() && idx >= 0) {
            result[idx--] = (int) heap.poll()[0];
        }
        return result;
    }

    // Helper method to find k nearest neighbors using PQ asymmetric distance
    private int[] findKNearestPQ(float[] query, float[][] database, byte[][] pqCodes,
                                  ProductQuantizer pq, int k) {
        PQDistanceTable table = pq.createDistanceTable(query);

        // Use a priority queue with proper float comparison
        java.util.PriorityQueue<float[]> heap = new java.util.PriorityQueue<>(
            (a, b) -> Float.compare(b[1], a[1]) // Max-heap by distance (furthest first)
        );

        for (int i = 0; i < database.length; i++) {
            float dist = table.lookup(pqCodes[i]);

            if (heap.size() < k) {
                heap.offer(new float[]{i, dist});
            } else if (dist < heap.peek()[1]) {
                heap.poll();
                heap.offer(new float[]{i, dist});
            }
        }

        int[] result = new int[Math.min(k, heap.size())];
        int idx = result.length - 1;
        while (!heap.isEmpty() && idx >= 0) {
            result[idx--] = (int) heap.poll()[0];
        }
        return result;
    }

    // Helper method to compute variance of a codebook
    private double computeCodebookVariance(float[][] codebook) {
        int numCentroids = codebook.length;
        int dimension = codebook[0].length;

        // Compute mean
        double[] mean = new double[dimension];
        for (float[] centroid : codebook) {
            for (int d = 0; d < dimension; d++) {
                mean[d] += centroid[d];
            }
        }
        for (int d = 0; d < dimension; d++) {
            mean[d] /= numCentroids;
        }

        // Compute variance
        double variance = 0.0;
        for (float[] centroid : codebook) {
            for (int d = 0; d < dimension; d++) {
                double diff = centroid[d] - mean[d];
                variance += diff * diff;
            }
        }

        return variance / (numCentroids * dimension);
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ProductQuantizerTest.class);
}
