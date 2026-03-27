package io.excalibur.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify Metrics static percentile methods read from the correct histogram instance.
 * This test validates the fix for the bug where static methods read from the singleton
 * in FixedBucketHistogram instead of the queryLatencyHistogram instance in Metrics.
 */
class MetricsHistogramIntegrationTest {

    @BeforeEach
    void setUp() {
        // Clear the histogram before each test by using a fresh instance approach
        // We record to the histogram via Metrics.recordQueryLatency()
    }

    @Test
    void recordQueryLatency_staticGetP50_readsFromSameHistogram() {
        // Record a known latency value
        long testLatency = 100L;
        Metrics.recordQueryLatency(testLatency);

        // Static method should now read from the same histogram
        long p50 = Metrics.getP50();

        // The p50 should reflect the recorded value (not 0 from wrong histogram)
        assertTrue(p50 > 0, "P50 should be > 0 after recording latency");
        assertTrue(p50 <= testLatency + 10, "P50 should be close to recorded value");
    }

    @Test
    void recordQueryLatency_staticGetP90_readsFromSameHistogram() {
        long testLatency = 150L;
        Metrics.recordQueryLatency(testLatency);

        long p90 = Metrics.getP90();

        assertTrue(p90 > 0, "P90 should be > 0 after recording latency");
        assertTrue(p90 <= testLatency + 10, "P90 should be close to recorded value");
    }

    @Test
    void recordQueryLatency_staticGetP95_readsFromSameHistogram() {
        long testLatency = 200L;
        Metrics.recordQueryLatency(testLatency);

        long p95 = Metrics.getP95();

        assertTrue(p95 > 0, "P95 should be > 0 after recording latency");
        assertTrue(p95 <= testLatency + 10, "P95 should be close to recorded value");
    }

    @Test
    void recordQueryLatency_staticGetP99_readsFromSameHistogram() {
        long testLatency = 250L;
        Metrics.recordQueryLatency(testLatency);

        long p99 = Metrics.getP99();

        assertTrue(p99 > 0, "P99 should be > 0 after recording latency");
        assertTrue(p99 <= testLatency + 10, "P99 should be close to recorded value");
    }

    @Test
    void recordQueryLatency_staticGetP999_readsFromSameHistogram() {
        long testLatency = 300L;
        Metrics.recordQueryLatency(testLatency);

        long p999 = Metrics.getP999();

        assertTrue(p999 > 0, "P99.9 should be > 0 after recording latency");
        assertTrue(p999 <= testLatency + 10, "P99.9 should be close to recorded value");
    }

    @Test
    void staticMethods_matchInstanceMethod() {
        // Record multiple values to ensure meaningful percentiles
        for (int i = 1; i <= 100; i++) {
            Metrics.recordQueryLatency(i);
        }

        // Static methods should match the instance method on QueryLatency()
        assertEquals(Metrics.QueryLatency().getPercentile(50.0), Metrics.getP50(),
            "Static getP50() should match instance method");
        assertEquals(Metrics.QueryLatency().getPercentile(90.0), Metrics.getP90(),
            "Static getP90() should match instance method");
        assertEquals(Metrics.QueryLatency().getPercentile(95.0), Metrics.getP95(),
            "Static getP95() should match instance method");
        assertEquals(Metrics.QueryLatency().getPercentile(99.0), Metrics.getP99(),
            "Static getP99() should match instance method");
        assertEquals(Metrics.QueryLatency().getPercentile(99.9), Metrics.getP999(),
            "Static getP999() should match instance method");
    }
}
