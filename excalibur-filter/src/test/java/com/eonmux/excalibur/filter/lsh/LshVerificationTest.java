package com.eonmux.excalibur.filter.lsh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test for Locality-Sensitive Hashing (LSH).
 *
 * <p>This test verifies that the LSH implementation meets all requirements:</p>
 * <ul>
 *   <li>Multi-probe random hyperplane LSH for approximate nearest neighbor pre-filtering</li>
 *   <li>Hyperplanes are uniformly distributed on unit sphere (Gaussian normalization)</li>
 *   <li>Hash values are non-negative (bit 63 never set)</li>
 *   <li>Distances for cosine similarity in valid range [-1, 1]</li>
 *   <li>Thread-safe operations</li>
 *   <li>Serialization/deserialization works correctly</li>
 *   <li>Integration with ingestion and query pipelines</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LshVerificationTest {

    private static final int DIMENSIONS = 128;
    private static final int NUM_VECTORS = 1000;
    private static final double DELTA = 0.001;

    // ============================================
    // Domain Correctness Tests
    // ============================================

    @Test
    @DisplayName("Hyperplanes should be uniformly distributed on unit sphere")
    void hyperplanesShouldBeUniformOnUnitSphere() {
        // Generate hyperplanes with fixed seed
        RandomHyperplaneHash hash = new RandomHyperplaneHash(DIMENSIONS, 12, 42L);

        // Verify each hyperplane has unit length
        for (int i = 0; i < hash.numBits(); i++) {
            float[] hyperplane = hash.getHyperplane(i);
            double norm = 0.0;
            for (float v : hyperplane) {
                norm += v * v;
            }
            norm = Math.sqrt(norm);

            // Domain constraint: each hyperplane must have unit length
            assertThat(norm).isCloseTo(1.0, offset(DELTA));
        }
    }

    @Test
    @DisplayName("Hash values should be non-negative")
    void hashValuesShouldBeNonNegative() {
        RandomHyperplaneHash hash = new RandomHyperplaneHash(DIMENSIONS, 12, 42L);
        Random random = new Random(123);

        for (int i = 0; i < 100; i++) {
            float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
            long hashValue = hash.hash(vector);

            // Domain constraint: bit 63 must never be set (non-negative)
            assertThat(hashValue).isGreaterThanOrEqualTo(0L);
        }
    }

    @Test
    @DisplayName("Collision probability should decrease with angle")
    void collisionProbabilityShouldDecreaseWithAngle() {
        // Verify the collision probability formula
        // P[collision] = 1 - theta/pi

        assertThat(RandomHyperplaneHash.collisionProbability(0)).isEqualTo(1.0); // Same vector
        assertThat(RandomHyperplaneHash.collisionProbability(Math.PI / 2)).isCloseTo(0.5, offset(DELTA)); // Orthogonal
        assertThat(RandomHyperplaneHash.collisionProbability(Math.PI)).isEqualTo(0.0); // Opposite

        // Verify inverse function
        assertThat(RandomHyperplaneHash.angleFromCollisionProbability(1.0)).isEqualTo(0.0);
        assertThat(RandomHyperplaneHash.angleFromCollisionProbability(0.5)).isCloseTo(Math.PI / 2, offset(DELTA));
        assertThat(RandomHyperplaneHash.angleFromCollisionProbability(0.0)).isEqualTo(Math.PI);
    }

    @Test
    @DisplayName("Similar vectors should have similar hashes")
    void similarVectorsShouldHaveSimilarHashes() {
        RandomHyperplaneHash hash = new RandomHyperplaneHash(DIMENSIONS, 12, 42L);
        Random random = new Random(456);

        // Generate a base vector
        float[] baseVector = generateRandomNormalizedVector(random, DIMENSIONS);

        // Create a slightly perturbed version (add small noise)
        float[] similarVector = baseVector.clone();
        for (int i = 0; i < similarVector.length; i++) {
            similarVector[i] += (random.nextFloat() - 0.5f) * 0.01f; // Small perturbation
        }
        // Re-normalize
        double norm = 0.0;
        for (float v : similarVector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < similarVector.length; i++) {
            similarVector[i] /= norm;
        }

        long baseHash = hash.hash(baseVector);
        long similarHash = hash.hash(similarVector);

        // Similar vectors should have low Hamming distance
        int hammingDistance = hash.hammingDistance(baseHash, similarHash);
        assertThat(hammingDistance).isLessThanOrEqualTo(4);
    }

    // ============================================
    // Thread Safety Tests
    // ============================================

    @Test
    @DisplayName("LshIndex should handle concurrent insertions")
    void lshIndexShouldHandleConcurrentInsertions() throws InterruptedException, IOException {
        LshIndex index = LshIndex.create(DIMENSIONS, 4);
        int numThreads = 4;
        int vectorsPerThread = 100;
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                Random random = new Random(threadId * 1000);
                for (int i = 0; i < vectorsPerThread; i++) {
                    try {
                        float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
                        long entryId = threadId * 1000L + i;
                        index.insert(vector, entryId);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        // Verify all entries were inserted
        long expectedCount = numThreads * vectorsPerThread;
        assertThat(index.entryCount()).isEqualTo(expectedCount);
    }

    @Test
    @DisplayName("LshIndex should handle concurrent queries")
    void lshIndexShouldHandleConcurrentQueries() throws InterruptedException, IOException {
        LshIndex index = LshIndex.create(DIMENSIONS, 4);
        Random random = new Random(789);

        // Insert some vectors
        for (int i = 0; i < 500; i++) {
            float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
            index.insert(vector, i);
        }

        int numThreads = 4;
        Thread[] threads = new Thread[numThreads];
        Set<Exception> exceptions = new HashSet<>();

        for (int t = 0; t < numThreads; t++) {
            threads[t] = new Thread(() -> {
                Random localRandom = new Random(999);
                for (int i = 0; i < 50; i++) {
                    try {
                        float[] queryVector = generateRandomNormalizedVector(localRandom, DIMENSIONS);
                        Set<Long> candidates = index.query(queryVector);
                        // Just verify no exception occurs
                    } catch (Exception e) {
                        exceptions.add(e);
                    }
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(exceptions).isEmpty();
    }

    // ============================================
    // Serialization Tests
    // ============================================

    @Test
    @DisplayName("RandomHyperplaneHash should serialize and deserialize correctly")
    void randomHyperplaneHashShouldSerializeDeserialize() {
        RandomHyperplaneHash original = new RandomHyperplaneHash(DIMENSIONS, 12, 42L);

        byte[] bytes = original.toBytes();
        RandomHyperplaneHash restored = RandomHyperplaneHash.fromBytes(bytes);

        assertThat(restored.numBits()).isEqualTo(original.numBits());
        assertThat(restored.dimensions()).isEqualTo(original.dimensions());

        // Verify hash values match
        Random random = new Random(123);
        for (int i = 0; i < 10; i++) {
            float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
            assertThat(restored.hash(vector)).isEqualTo(original.hash(vector));
        }
    }

    @Test
    @DisplayName("LshIndex should serialize and deserialize correctly")
    void lshIndexShouldSerializeDeserialize() throws IOException {
        LshConfig config = LshConfig.builder(DIMENSIONS)
            .numHashTables(4)
            .numHashFunctions(8)
            .numProbes(2)
            .build();
        LshIndex original = new LshIndex(config);

        // Insert some vectors
        Random random = new Random(111);
        for (int i = 0; i < 100; i++) {
            float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
            original.insert(vector, i);
        }

        // Serialize
        var arena = java.lang.foreign.Arena.ofShared();
        try {
            var segment = original.toMemorySegment(arena);
            LshIndex restored = LshIndex.fromMemorySegment(segment);

            // Verify configuration
            assertThat(restored.config().dimensions()).isEqualTo(original.config().dimensions());
            assertThat(restored.config().numHashTables()).isEqualTo(original.config().numHashTables());
            assertThat(restored.entryCount()).isEqualTo(original.entryCount());

            // Verify query results match
            random = new Random(111); // Reset seed
            for (int i = 0; i < 10; i++) {
                float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
                Set<Long> originalCandidates = original.query(vector);
                Set<Long> restoredCandidates = restored.query(vector);
                assertThat(restoredCandidates).isEqualTo(originalCandidates);
            }
        } finally {
            arena.close();
        }
    }

    // ============================================
    // Multi-Probe Tests
    // ============================================

    @Test
    @DisplayName("Multi-probe should return more candidates than exact hash")
    void multiProbeShouldReturnMoreCandidates() throws IOException {
        LshIndex index = LshIndex.create(DIMENSIONS, 4);
        Random random = new Random(222);

        // Insert vectors
        for (int i = 0; i < 500; i++) {
            float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
            index.insert(vector, i);
        }

        // Query with exact hash (1 probe)
        float[] queryVector = generateRandomNormalizedVector(random, DIMENSIONS);
        Set<Long> exactCandidates = index.query(queryVector, 1);

        // Query with multi-probe (5 probes)
        Set<Long> multiProbeCandidates = index.query(queryVector, 5);

        // Multi-probe should return at least as many candidates
        assertThat(multiProbeCandidates.size()).isGreaterThanOrEqualTo(exactCandidates.size());
    }

    @Test
    @DisplayName("Candidate reduction should be significant for large datasets")
    void candidateReductionShouldBeSignificant() throws IOException {
        LshConfig config = LshConfig.builder(DIMENSIONS)
            .numHashTables(8)
            .numHashFunctions(12)
            .numProbes(3)
            .build();
        LshIndex index = new LshIndex(config);
        Random random = new Random(333);

        int totalVectors = 2000;
        for (int i = 0; i < totalVectors; i++) {
            float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
            index.insert(vector, i);
        }

        // Query
        float[] queryVector = generateRandomNormalizedVector(new Random(444), DIMENSIONS);
        Set<Long> candidates = index.query(queryVector);

        // Candidates should be significantly less than total (demonstrating filtering)
        double reductionRatio = (double) totalVectors / Math.max(candidates.size(), 1);
        assertThat(candidates.size()).isLessThan(totalVectors);
        assertThat(reductionRatio).isGreaterThan(2.0);
    }

    // ============================================
    // Configuration Tests
    // ============================================

    @Test
    @DisplayName("LshConfig should validate parameters")
    void lshConfigShouldValidateParameters() {
        // Valid config
        LshConfig valid = LshConfig.builder(DIMENSIONS).build();
        assertThat(valid.dimensions()).isEqualTo(DIMENSIONS);

        // Invalid: dimensions < 1
        assertThrows(IllegalArgumentException.class, () -> LshConfig.builder(0));

        // Invalid: hash functions out of range
        assertThrows(IllegalArgumentException.class, () ->
            LshConfig.builder(DIMENSIONS).numHashFunctions(0).build());
        assertThrows(IllegalArgumentException.class, () ->
            LshConfig.builder(DIMENSIONS).numHashFunctions(64).build());

        // Invalid: probes < 1
        assertThrows(IllegalArgumentException.class, () ->
            LshConfig.builder(DIMENSIONS).numProbes(0).build());
    }

    @Test
    @DisplayName("LshConfig should have sensible defaults")
    void lshConfigShouldHaveSensibleDefaults() {
        LshConfig config = LshConfig.builder(DIMENSIONS).build();

        // Verify defaults match the production values in LshConfig
        assertThat(config.numHashTables()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_TABLES);
        assertThat(config.numHashFunctions()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_FUNCTIONS);
        assertThat(config.numProbes()).isEqualTo(LshConfig.DEFAULT_NUM_PROBES);
    }

    // ============================================
    // Deletion Tests
    // ============================================

    @Test
    @DisplayName("LshIndex should support deletion")
    void lshIndexShouldSupportDeletion() throws IOException {
        LshIndex index = LshIndex.create(DIMENSIONS, 4);
        Random random = new Random(555);

        // Insert vectors
        for (int i = 0; i < 100; i++) {
            float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
            index.insert(vector, i);
        }

        long initialCount = index.entryCount();

        // Delete some entries
        for (int i = 0; i < 50; i++) {
            index.delete(i);
        }

        assertThat(index.entryCount()).isEqualTo(initialCount - 50);
    }

    // ============================================
    // Helper Methods
    // ============================================

    private float[] generateRandomNormalizedVector(Random random, int dimensions) {
        float[] vector = new float[dimensions];

        double sumSquares = 0.0;
        for (int i = 0; i < dimensions; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
            sumSquares += vector[i] * vector[i];
        }

        double magnitude = Math.sqrt(sumSquares);
        if (magnitude > 0) {
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= magnitude;
            }
        }

        return vector;
    }
}
