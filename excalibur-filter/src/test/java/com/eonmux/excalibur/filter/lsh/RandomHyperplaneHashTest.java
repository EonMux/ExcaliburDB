package com.eonmux.excalibur.filter.lsh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for RandomHyperplaneHash.
 */
class RandomHyperplaneHashTest {

    private static final int DIMENSIONS = 128;
    private static final int NUM_BITS = 16;
    private static final long SEED = 42L;

    private RandomHyperplaneHash hashFunction;

    @BeforeEach
    void setUp() {
        hashFunction = new RandomHyperplaneHash(DIMENSIONS, NUM_BITS, SEED);
    }

    @Test
    @DisplayName("Should create hash function with valid parameters")
    void testCreate() {
        assertThat(hashFunction.numBits()).isEqualTo(NUM_BITS);
        assertThat(hashFunction.dimensions()).isEqualTo(DIMENSIONS);
    }

    @Test
    @DisplayName("Should reject invalid dimensions")
    void testInvalidDimensions() {
        assertThatThrownBy(() -> new RandomHyperplaneHash(0, 16, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimensions must be >= 1");

        assertThatThrownBy(() -> new RandomHyperplaneHash(-1, 16, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject invalid numBits")
    void testInvalidNumBits() {
        assertThatThrownBy(() -> new RandomHyperplaneHash(DIMENSIONS, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("numBits must be in range");

        assertThatThrownBy(() -> new RandomHyperplaneHash(DIMENSIONS, 64, 0))
            .isInstanceOf(IllegalArgumentException.class);

        // 63 should be valid (max)
        RandomHyperplaneHash valid = new RandomHyperplaneHash(DIMENSIONS, 63, 0);
        assertThat(valid.numBits()).isEqualTo(63);
    }

    @Test
    @DisplayName("Should produce deterministic hash for same vector")
    void testDeterminism() {
        float[] vector = createRandomVector(DIMENSIONS, 1);

        long hash1 = hashFunction.hash(vector);
        long hash2 = hashFunction.hash(vector);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("Should produce same hash for same seed")
    void testSameSeed() {
        RandomHyperplaneHash hash1 = new RandomHyperplaneHash(DIMENSIONS, NUM_BITS, SEED);
        RandomHyperplaneHash hash2 = new RandomHyperplaneHash(DIMENSIONS, NUM_BITS, SEED);

        float[] vector = createRandomVector(DIMENSIONS, 1);

        assertThat(hash1.hash(vector)).isEqualTo(hash2.hash(vector));
    }

    @Test
    @DisplayName("Should produce different hash for different seed")
    void testDifferentSeed() {
        RandomHyperplaneHash hash1 = new RandomHyperplaneHash(DIMENSIONS, NUM_BITS, SEED);
        RandomHyperplaneHash hash2 = new RandomHyperplaneHash(DIMENSIONS, NUM_BITS, SEED + 1);

        float[] vector = createRandomVector(DIMENSIONS, 1);

        // Very unlikely to produce same hash with different seeds
        assertThat(hash1.hash(vector)).isNotEqualTo(hash2.hash(vector));
    }

    @Test
    @DisplayName("Should reject null vector")
    void testNullVector() {
        assertThatThrownBy(() -> hashFunction.hash(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vector cannot be null");
    }

    @Test
    @DisplayName("Should reject wrong size vector")
    void testWrongSizeVector() {
        float[] wrongSize = new float[64];

        assertThatThrownBy(() -> hashFunction.hash(wrongSize))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vector length");
    }

    @Test
    @DisplayName("Should produce non-negative hash")
    void testNonNegativeHash() {
        for (int i = 0; i < 100; i++) {
            float[] vector = createRandomVector(DIMENSIONS, i);
            long hash = hashFunction.hash(vector);
            assertThat(hash).isNotNegative();
        }
    }

    @Test
    @DisplayName("Should produce hash within bit range")
    void testHashWithinBitRange() {
        long maxHash = (1L << NUM_BITS) - 1;

        for (int i = 0; i < 100; i++) {
            float[] vector = createRandomVector(DIMENSIONS, i);
            long hash = hashFunction.hash(vector);
            assertThat(hash).isBetween(0L, maxHash);
        }
    }

    @Test
    @DisplayName("Should have reasonable bit distribution")
    void testBitDistribution() {
        int[] bitCounts = new int[NUM_BITS];
        int samples = 1000;

        for (int i = 0; i < samples; i++) {
            float[] vector = createRandomVector(DIMENSIONS, i);
            long hash = hashFunction.hash(vector);

            for (int bit = 0; bit < NUM_BITS; bit++) {
                if ((hash & (1L << bit)) != 0) {
                    bitCounts[bit]++;
                }
            }
        }

        // Each bit should be set roughly 50% of the time (±15% tolerance)
        for (int bit = 0; bit < NUM_BITS; bit++) {
            double ratio = (double) bitCounts[bit] / samples;
            assertThat(ratio).isBetween(0.35, 0.65);
        }
    }

    @Test
    @DisplayName("Should compute Hamming distance correctly")
    void testHammingDistance() {
        long hash1 = 0b1010;
        long hash2 = 0b1100;

        int distance = hashFunction.hammingDistance(hash1, hash2);

        // 1010 XOR 1100 = 0110 -> 2 bits differ
        assertThat(distance).isEqualTo(2);
    }

    @Test
    @DisplayName("Should flip bit correctly")
    void testHashWithFlip() {
        float[] vector = createRandomVector(DIMENSIONS, 1);
        long baseHash = hashFunction.hash(vector);

        for (int bit = 0; bit < NUM_BITS; bit++) {
            long flipped = hashFunction.hashWithFlip(vector, bit);

            // Should differ by exactly 1 bit
            int distance = hashFunction.hammingDistance(baseHash, flipped);
            assertThat(distance).isEqualTo(1);

            // Flipping again should restore
            long doubleFlipped = flipped ^ (1L << bit);
            assertThat(doubleFlipped).isEqualTo(baseHash);
        }
    }

    @Test
    @DisplayName("Should reject invalid flip bit")
    void testInvalidFlipBit() {
        float[] vector = createRandomVector(DIMENSIONS, 1);

        assertThatThrownBy(() -> hashFunction.hashWithFlip(vector, -1))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> hashFunction.hashWithFlip(vector, NUM_BITS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should get hyperplane copy")
    void testGetHyperplane() {
        for (int i = 0; i < NUM_BITS; i++) {
            float[] hyperplane = hashFunction.getHyperplane(i);
            assertThat(hyperplane).hasSize(DIMENSIONS);

            // Should be unit vector
            float norm = 0;
            for (float v : hyperplane) {
                norm += v * v;
            }
            assertThat(Math.sqrt(norm)).isBetween(0.99, 1.01);
        }
    }

    @Test
    @DisplayName("Should reject invalid hyperplane index")
    void testInvalidHyperplaneIndex() {
        assertThatThrownBy(() -> hashFunction.getHyperplane(-1))
            .isInstanceOf(IndexOutOfBoundsException.class);

        assertThatThrownBy(() -> hashFunction.getHyperplane(NUM_BITS))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("Should compute collision probability")
    void testCollisionProbability() {
        // Same angle = 1.0
        double prob0 = RandomHyperplaneHash.collisionProbability(0);
        assertThat(prob0).isEqualTo(1.0);

        // 90 degrees = 0.5
        double prob90 = RandomHyperplaneHash.collisionProbability(Math.PI / 2);
        assertThat(prob90).isEqualTo(0.5);

        // 180 degrees = 0
        double prob180 = RandomHyperplaneHash.collisionProbability(Math.PI);
        assertThat(prob180).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should compute angle from collision probability")
    void testAngleFromCollisionProbability() {
        assertThat(RandomHyperplaneHash.angleFromCollisionProbability(1.0))
            .isEqualTo(0.0);

        assertThat(RandomHyperplaneHash.angleFromCollisionProbability(0.5))
            .isEqualTo(Math.PI / 2);

        assertThat(RandomHyperplaneHash.angleFromCollisionProbability(0.0))
            .isEqualTo(Math.PI);
    }

    @Test
    @DisplayName("Should serialize and deserialize")
    void testSerialization() {
        float[] vector = createRandomVector(DIMENSIONS, 1);
        long originalHash = hashFunction.hash(vector);

        byte[] bytes = hashFunction.toBytes();
        RandomHyperplaneHash restored = RandomHyperplaneHash.fromBytes(bytes);

        assertThat(restored.numBits()).isEqualTo(NUM_BITS);
        assertThat(restored.dimensions()).isEqualTo(DIMENSIONS);
        assertThat(restored.hash(vector)).isEqualTo(originalHash);
    }

    @Test
    @DisplayName("Should reject invalid serialized data")
    void testInvalidSerialization() {
        assertThatThrownBy(() -> RandomHyperplaneHash.fromBytes(null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> RandomHyperplaneHash.fromBytes(new byte[4]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should have correct toString")
    void testToString() {
        String str = hashFunction.toString();
        assertThat(str).contains("RandomHyperplaneHash");
        assertThat(str).contains("dimensions=" + DIMENSIONS);
        assertThat(str).contains("numBits=" + NUM_BITS);
    }

    // Helper methods

    private float[] createRandomVector(int dimensions, int seed) {
        float[] vector = new float[dimensions];
        java.util.Random random = new java.util.Random(seed);
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }
}
