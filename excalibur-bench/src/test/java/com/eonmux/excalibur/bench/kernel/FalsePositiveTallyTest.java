package com.eonmux.excalibur.bench.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the false-positive accumulation that the Cuckoo filter's 0.1% specification
 * is checked against.
 *
 * <p>A single batch is far too small to distinguish 0.09% from 0.11%, so the
 * specification is only meaningful over the accumulated total. Averaging per-batch
 * rates instead would let a few tiny clean batches cancel out a large dirty one.</p>
 */
class FalsePositiveTallyTest {

    private static final double SPEC = 0.001;

    @Test
    @DisplayName("the rate is total false positives over total queries")
    void rateIsAccumulatedAcrossBatches() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);
        tally.record(1, 1000);
        tally.record(3, 1000);

        assertThat(tally.rate()).isEqualTo(0.002);
        assertThat(tally.falsePositives()).isEqualTo(4);
        assertThat(tally.queries()).isEqualTo(2000);
    }

    @Test
    @DisplayName("batches of unequal size are weighted by their size, not averaged")
    void unequalBatchesAreWeightedBySize() {
        // Averaging the two batch rates would give (0.0 + 0.01)/2 = 0.005.
        // Weighted by size the answer is 1 in 100_100, which is far below spec.
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);
        tally.record(0, 100_000);
        tally.record(1, 100);

        assertThat(tally.rate()).isCloseTo(1.0 / 100_100, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(tally.meetsSpecification()).isTrue();
    }

    @Test
    @DisplayName("an empty tally reports 0 rather than NaN and is not a pass")
    void emptyTallyIsZeroAndNotAPass() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);

        assertThat(tally.rate()).isEqualTo(0.0);
        assertThat(tally.meetsSpecification())
            .as("nothing was measured, so the specification was not demonstrated")
            .isFalse();
    }

    @Test
    @DisplayName("a rate exactly at the specification passes")
    void rateAtTheSpecificationPasses() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);
        tally.record(10, 10_000);

        assertThat(tally.rate()).isEqualTo(SPEC);
        assertThat(tally.meetsSpecification()).isTrue();
    }

    @Test
    @DisplayName("a rate above the specification fails")
    void rateAboveTheSpecificationFails() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);
        tally.record(11, 10_000);

        assertThat(tally.meetsSpecification()).isFalse();
    }

    @Test
    @DisplayName("more false positives than queries is rejected as impossible")
    void moreFalsePositivesThanQueriesIsRejected() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);

        assertThatThrownBy(() -> tally.record(11, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("11")
            .hasMessageContaining("10");
    }

    @Test
    @DisplayName("negative counts are rejected")
    void negativeCountsAreRejected() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);

        assertThatThrownBy(() -> tally.record(-1, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tally.record(0, -10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the specification threshold must be a positive fraction")
    void invalidSpecificationIsRejected() {
        assertThatThrownBy(() -> new FalsePositiveTally(0.0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FalsePositiveTally(1.5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a rate at the specification is never a significant excess")
    void rateAtTheSpecificationIsNotSignificant() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);
        tally.record(1_000, 1_000_000);

        assertThat(tally.significantlyExceedsSpecification(1_000_000)).isFalse();
    }

    @Test
    @DisplayName("a doubled rate over a large independent sample is a significant excess")
    void doubledRateOverALargeSampleIsSignificant() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);
        tally.record(2_000, 1_000_000);

        assertThat(tally.significantlyExceedsSpecification(1_000_000)).isTrue();
    }

    @Test
    @DisplayName("a handful of collisions in a small sample is sampling noise, not an excess")
    void smallSampleNoiseIsNotSignificant() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);
        tally.record(2, 100);

        assertThat(tally.meetsSpecification()).isFalse();
        assertThat(tally.significantlyExceedsSpecification(100)).isFalse();
    }

    @Test
    @DisplayName("repeated probes of the same keys count as the draws they are, not as new ones")
    void repeatedProbesDoNotNarrowTheMargin() {
        // One pass over 10,000 keys, repeated a hundred times: the same collisions recur, so
        // the rate is known only as well as 10,000 draws know it.
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);
        tally.record(1_500, 1_000_000);

        assertThat(tally.significantlyExceedsSpecification(1_000_000)).isTrue();
        assertThat(tally.significantlyExceedsSpecification(10_000)).isFalse();
    }

    @Test
    @DisplayName("an empty tally has shown no excess, and a sample needs at least one draw")
    void emptyTallyAndInvalidSampleSize() {
        FalsePositiveTally tally = new FalsePositiveTally(SPEC);
        assertThat(tally.significantlyExceedsSpecification(1)).isFalse();

        tally.record(1, 10);
        assertThatThrownBy(() -> tally.significantlyExceedsSpecification(0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
