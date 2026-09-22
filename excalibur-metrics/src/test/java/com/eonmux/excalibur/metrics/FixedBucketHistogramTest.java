package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FixedBucketHistogram.
 */
class FixedBucketHistogramTest {

    private FixedBucketHistogram histogram;

    @BeforeEach
    void setUp() {
        histogram = new FixedBucketHistogram();
    }

    @Test
    @DisplayName("Record and retrieve basic metrics")
    void testBasicRecording() {
        histogram.record(10);
        histogram.record(20);
        histogram.record(30);

        assertEquals(3, histogram.getTotalCount());
        assertEquals(10, histogram.getMinimum());
        assertEquals(30, histogram.getMaximum());
    }

    @Test
    @DisplayName("Record large latencies without ArrayIndexOutOfBoundsException")
    void testLargeLatenciesNoException() {
        // These should not throw ArrayIndexOutOfBoundsException
        assertDoesNotThrow(() -> histogram.record(10000));
        assertDoesNotThrow(() -> histogram.record(15000));
        assertDoesNotThrow(() -> histogram.record(99999));
        assertDoesNotThrow(() -> histogram.record(1000000));
    }

    @Test
    @DisplayName("Percentile calculation returns correct values")
    void testPercentileCalculation() {
        // Record values 1-100
        for (int i = 1; i <= 100; i++) {
            histogram.record(i);
        }

        // P50 should be around 50
        long p50 = histogram.getPercentile(50.0);
        assertTrue(p50 >= 40 && p50 <= 60,
            "P50 should be around 50, got: " + p50);

        // P90 should be around 90
        long p90 = histogram.getPercentile(90.0);
        assertTrue(p90 >= 80 && p90 <= 100,
            "P90 should be around 90, got: " + p90);

        // P99 should be around 99
        long p99 = histogram.getPercentile(99.0);
        assertTrue(p99 >= 90 && p99 <= 100,
            "P99 should be around 99, got: " + p99);
    }

    @Test
    @DisplayName("Empty histogram returns zero for all metrics")
    void testEmptyHistogram() {
        assertEquals(0, histogram.getTotalCount());
        assertEquals(0, histogram.getMinimum());
        assertEquals(0, histogram.getMaximum());
        assertEquals(0.0, histogram.getAverage());
        assertEquals(0, histogram.getPercentile(50.0));
    }

    @Test
    @DisplayName("Average calculation is correct")
    void testAverageCalculation() {
        histogram.record(10);
        histogram.record(20);
        histogram.record(30);

        double avg = histogram.getAverage();
        assertTrue(avg >= 15 && avg <= 25,
            "Average should be around 20, got: " + avg);
    }

    @Test
    @DisplayName("Static getInstance returns non-null singleton")
    void testGetInstance() {
        assertNotNull(FixedBucketHistogram.getInstance());
        assertSame(FixedBucketHistogram.getInstance(), FixedBucketHistogram.getInstance(),
            "getInstance should return the same instance");
    }

    @Test
    @DisplayName("Static percentile methods work correctly")
    void testStaticPercentileMethods() {
        // Record values to the singleton
        FixedBucketHistogram.getInstance().record(50);

        // These should not throw StackOverflowError
        assertDoesNotThrow(() -> FixedBucketHistogram.getP50());
        assertDoesNotThrow(() -> FixedBucketHistogram.getP90());
        assertDoesNotThrow(() -> FixedBucketHistogram.getP95());
        assertDoesNotThrow(() -> FixedBucketHistogram.getP99());
        assertDoesNotThrow(() -> FixedBucketHistogram.getP999());
    }

    @Test
    @DisplayName("FP rate recording and retrieval")
    void testFpRateMetrics() {
        histogram.recordFpRate(0.001);
        histogram.recordFpRate(0.002);
        histogram.recordFpRate(0.003);

        assertEquals(3, histogram.getFpRateCount());
        assertTrue(histogram.getAverageFpRate() > 0,
            "Average FP rate should be positive");
    }

    @Test
    @DisplayName("FP rate boundary values do not throw ArrayIndexOutOfBoundsException")
    void testFpRateBoundaryValues() {
        // These should not throw ArrayIndexOutOfBoundsException
        assertDoesNotThrow(() -> histogram.recordFpRate(0.0));
        assertDoesNotThrow(() -> histogram.recordFpRate(1.0));
        assertDoesNotThrow(() -> histogram.recordFpRate(0.9999));
        assertDoesNotThrow(() -> histogram.recordFpRate(-0.1));  // Should be clamped to 0.0
        assertDoesNotThrow(() -> histogram.recordFpRate(1.1));   // Should be clamped to 1.0

        assertEquals(5, histogram.getFpRateCount());
        assertTrue(histogram.getMaxFpRate() >= 0.0 && histogram.getMaxFpRate() <= 1.0,
            "Max FP rate should be in valid range");
    }

    @Test
    @DisplayName("FP rate samples are stamped with the epoch they were taken in")
    void testFpRateEpochStamping() {
        histogram.setEpoch(11L);
        histogram.recordFpRate(0.001);
        assertEquals(11L, histogram.getFpRateEpoch(),
            "a sample must carry the epoch the histogram was told about");

        histogram.recordFpRate(0.002, 12L);
        assertEquals(12L, histogram.getFpRateEpoch(), "an explicit epoch stamps the sample");
        assertEquals(12L, histogram.getCurrentEpoch(),
            "an explicit epoch also advances the histogram's own epoch");
    }

    @Test
    @DisplayName("Recorded FP rates carry the visibility epoch, not zero")
    void testCuckooFpRateCarriesVisibilityEpoch() {
        long epoch = Metrics.getCurrentEpoch() + 500L;
        Metrics.recordEpoch(epoch);
        Metrics.recordCuckooFpRate(0.0005);

        assertEquals(epoch, FixedBucketHistogram.getInstance().getFpRateEpoch(),
            "the FP rate epoch must track the epoch the tick scheduler advances");
    }

    @Test
    @DisplayName("reset() discards latencies and FP rate samples")
    void testReset() {
        histogram.record(25);
        histogram.recordFpRate(0.01);

        histogram.reset();

        assertEquals(0, histogram.getTotalCount(), "latencies are discarded");
        assertEquals(0, histogram.getFpRateCount(), "FP rate samples are discarded");
        assertEquals(0.0, histogram.getAverageFpRate(), "average FP rate returns to zero");
    }

    @Test
    @DisplayName("Synchronized access is thread-safe")
    void testThreadSafety() throws InterruptedException {
        Thread[] threads = new Thread[10];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    histogram.record(i);
                    histogram.getTotalCount();
                    histogram.getPercentile(50.0);
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(10000, histogram.getTotalCount());
    }

    @Test
    @DisplayName("Zero latency records correctly")
    void testZeroLatency() {
        histogram.record(0);
        assertEquals(1, histogram.getTotalCount());
        assertEquals(0, histogram.getMinimum());
    }

    @Test
    @DisplayName("A latency past the last bucket is counted as overflow, not as 9999ms")
    void latencyPastTheLastBucketOverflows() {
        // A 45-second query and a 10-second one both landed in the top bucket, so a tail
        // getting steadily worse past ten seconds charted as a flat line at 9999.
        histogram.record(45_000);
        histogram.record(10_000);
        histogram.record(500);

        assertEquals(2, histogram.getOverflowCount(),
            "both samples are above the range the buckets cover");
        assertEquals(45_000, histogram.getMaximum(),
            "the largest sample is reported as itself, not as the top bucket");
    }

    @Test
    @DisplayName("The average counts an overflowing sample at its real size")
    void theAverageCountsOverflowAtItsRealSize() {
        histogram.record(0);
        histogram.record(40_000);

        assertEquals(20_000.0, histogram.getAverage(), 1.0,
            "clamping the sample would report an average of about 5000");
    }

    @Test
    @DisplayName("A percentile in the overflow region reports the floor of it")
    void aPercentileInTheOverflowRegionReportsItsFloor() {
        for (int i = 0; i < 99; i++) {
            histogram.record(10);
        }
        histogram.record(60_000);

        assertEquals(10, histogram.getPercentile(50.0));
        assertEquals(10_000, histogram.getPercentile(100.0),
            "a percentile the buckets cannot resolve is reported at the range's edge, and "
                + "getOverflowCount says how many samples are past it");
        assertEquals(1, histogram.getOverflowCount());
    }

    @Test
    @DisplayName("A latency at the last bucket is not overflow")
    void aLatencyAtTheLastBucketIsNotOverflow() {
        histogram.record(9_999);

        assertEquals(0, histogram.getOverflowCount());
        assertEquals(9_999, histogram.getMaximum());
    }
}
