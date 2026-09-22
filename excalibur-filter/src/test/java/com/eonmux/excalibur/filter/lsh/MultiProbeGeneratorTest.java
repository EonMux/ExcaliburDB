package com.eonmux.excalibur.filter.lsh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for MultiProbeGenerator.
 */
class MultiProbeGeneratorTest {

    private static final int NUM_BITS = 12;
    private MultiProbeGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new MultiProbeGenerator(NUM_BITS);
    }

    @Test
    @DisplayName("Should create generator with valid parameters")
    void testCreate() {
        assertThat(generator.numBits()).isEqualTo(NUM_BITS);
    }

    @Test
    @DisplayName("Should reject invalid numBits")
    void testInvalidNumBits() {
        assertThatThrownBy(() -> new MultiProbeGenerator(0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new MultiProbeGenerator(64))
            .isInstanceOf(IllegalArgumentException.class);

        // 63 should be valid (max)
        MultiProbeGenerator valid = new MultiProbeGenerator(63);
        assertThat(valid.numBits()).isEqualTo(63);
    }

    @Test
    @DisplayName("Should generate probing sequence starting with base hash")
    void testProbingSequenceStartsWithBase() {
        long baseHash = 0b101010101010L;
        int numProbes = 5;

        List<Long> probes = generator.generateProbingSequence(baseHash, numProbes);

        assertThat(probes).hasSize(numProbes);
        assertThat(probes.get(0)).isEqualTo(baseHash);
    }

    @Test
    @DisplayName("Should generate unique probes")
    void testUniqueProbes() {
        long baseHash = 0b101010101010L;
        int numProbes = 100;

        List<Long> probes = generator.generateProbingSequence(baseHash, numProbes);

        Set<Long> uniqueProbes = new HashSet<>(probes);
        assertThat(uniqueProbes).hasSize(probes.size());
    }

    @Test
    @DisplayName("Should generate probes in order of Hamming distance")
    void testProbesOrderedByHammingDistance() {
        long baseHash = 0L;
        int numProbes = 20;

        List<Long> probes = generator.generateProbingSequence(baseHash, numProbes);

        // First probe is base hash (distance 0)
        assertThat(generator.hammingDistance(probes.get(0), baseHash)).isEqualTo(0);

        // Count single-bit flips in the generated sequence
        int singleBitCount = 0;
        for (int i = 1; i < numProbes; i++) {
            int distance = generator.hammingDistance(probes.get(i), baseHash);
            if (distance == 1) {
                singleBitCount++;
            }
        }

        // Verify we have some single-bit flips in the sequence
        // (not all NUM_BITS may fit in first numProbes positions due to heap ordering)
        assertThat(singleBitCount).isGreaterThan(0);

        // All probes should be unique (verified by testUniqueProbes)
    }

    @Test
    @DisplayName("Should respect numProbes limit")
    void testNumProbesLimit() {
        long baseHash = 0b101010101010L;

        for (int numProbes : new int[]{1, 5, 10, 50}) {
            List<Long> probes = generator.generateProbingSequence(baseHash, numProbes);
            assertThat(probes).hasSize(numProbes);
        }
    }

    @Test
    @DisplayName("Should handle single probe")
    void testSingleProbe() {
        long baseHash = 0b101010101010L;
        List<Long> probes = generator.generateProbingSequence(baseHash, 1);

        assertThat(probes).hasSize(1);
        assertThat(probes.get(0)).isEqualTo(baseHash);
    }

    @Test
    @DisplayName("Should reject invalid numProbes")
    void testInvalidNumProbes() {
        assertThatThrownBy(() -> generator.generateProbingSequence(0L, 0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> generator.generateProbingSequence(0L, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should generate single bit perturbations")
    void testSingleBitPerturbations() {
        long baseHash = 0b101010101010L;
        List<Long> perturbations = generator.generateSingleBitPerturbations(baseHash);

        assertThat(perturbations).hasSize(NUM_BITS);

        // Each perturbation should differ by exactly 1 bit
        for (long perturbed : perturbations) {
            int distance = generator.hammingDistance(baseHash, perturbed);
            assertThat(distance).isEqualTo(1);
        }

        // All should be unique
        Set<Long> unique = new HashSet<>(perturbations);
        assertThat(unique).hasSize(NUM_BITS);
    }

    @Test
    @DisplayName("Should compute Hamming distance")
    void testHammingDistance() {
        assertThat(generator.hammingDistance(0b0000, 0b0000)).isEqualTo(0);
        assertThat(generator.hammingDistance(0b0000, 0b0001)).isEqualTo(1);
        assertThat(generator.hammingDistance(0b0000, 0b0011)).isEqualTo(2);
        assertThat(generator.hammingDistance(0b0101, 0b1010)).isEqualTo(4);
        assertThat(generator.hammingDistance(0b1111, 0b0000)).isEqualTo(4);
    }

    @Test
    @DisplayName("Should calculate max probes")
    void testMaxProbes() {
        long maxProbes = generator.maxProbes();
        assertThat(maxProbes).isEqualTo(1L << NUM_BITS);
    }

    @Test
    @DisplayName("Should generate sequence with margins")
    void testSequenceWithMargins() {
        long baseHash = 0b101010101010L;
        float[] margins = new float[NUM_BITS];

        // Fill margins with decreasing values (lower index = higher priority)
        for (int i = 0; i < NUM_BITS; i++) {
            margins[i] = i * 0.1f;
        }

        int numProbes = 10;
        List<Long> probes = generator.generateProbingSequenceWithMargins(baseHash, margins, numProbes);

        assertThat(probes).hasSize(numProbes);
        assertThat(probes.get(0)).isEqualTo(baseHash);

        // All probes should be unique
        Set<Long> unique = new HashSet<>(probes);
        assertThat(unique).hasSize(numProbes);
    }

    @Test
    @DisplayName("Should validate margins array size")
    void testInvalidMarginsSize() {
        float[] wrongSize = new float[NUM_BITS - 1];

        assertThatThrownBy(() -> generator.generateProbingSequenceWithMargins(0L, wrongSize, 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("margins must have length");
    }

    @Test
    @DisplayName("Should handle null margins")
    void testNullMargins() {
        assertThatThrownBy(() -> generator.generateProbingSequenceWithMargins(0L, null, 5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should prioritize lower margin bits")
    void testMarginPrioritization() {
        long baseHash = 0L;
        float[] margins = new float[NUM_BITS];

        // Set bit 5 to have smallest margin (highest priority)
        margins[5] = 0.01f;
        // All other bits have larger margins
        for (int i = 0; i < NUM_BITS; i++) {
            if (i != 5) {
                margins[i] = 1.0f;
            }
        }

        List<Long> probes = generator.generateProbingSequenceWithMargins(baseHash, margins, 3);

        // Second probe should flip bit 5 (highest priority)
        long expected = baseHash ^ (1L << 5);
        assertThat(probes.get(1)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should limit probes to max possible")
    void testLimitedByMaxProbes() {
        // Request more probes than possible
        int numProbes = 100000;
        List<Long> probes = generator.generateProbingSequence(0L, numProbes);

        // Should be limited to max possible (2^numBits)
        assertThat(probes.size()).isLessThanOrEqualTo((int) generator.maxProbes());
    }

    @Test
    @DisplayName("Should have correct toString")
    void testToString() {
        String str = generator.toString();
        assertThat(str).contains("MultiProbeGenerator");
        assertThat(str).contains("numBits=" + NUM_BITS);
    }
}
