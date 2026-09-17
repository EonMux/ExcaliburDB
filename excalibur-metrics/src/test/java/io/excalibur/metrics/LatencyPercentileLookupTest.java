package io.excalibur.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests which percentiles {@link MetricsRegistry#getLatencyPercentile(int)} can actually name.
 *
 * <p>The method builds a JMX attribute name by concatenating, so a percentile it has no name
 * for misses the bean and falls through to computing one from the histogram directly. For a
 * percentile outside the range that is not a fallback, it is a fabrication: the histogram
 * answers a request for the 999th percentile with the edge of its range, and the caller gets
 * ten seconds back as though a tenth of a percent of queries really took that long. A number
 * that is wrong is worse than an error, because only the error gets investigated.</p>
 *
 * @author ExcaliburDB
 */
class LatencyPercentileLookupTest {

    private MetricsRegistry registry;

    @BeforeEach
    void recordKnownLatencies() {
        registry = new MetricsRegistry();
        Metrics.QueryLatency().reset();
        for (int i = 1; i <= 100; i++) {
            Metrics.recordQueryLatency(i);
        }
    }

    @Test
    @DisplayName("the tail percentile the bean publishes is reachable")
    void tailPercentileIsReachable() {
        assertEquals(Metrics.QueryLatency().getPercentile(99.9), registry.getLatencyPercentile(999),
            "999 names p99.9, the same spelling Metrics.getP999() uses");
        assertEquals(Metrics.getP999(), registry.getLatencyPercentile(999),
            "and it must agree with the facade's own tail accessor");
        assertTrue(registry.getLatencyPercentile(999) < FixedBucketHistogram.RANGE_MS,
            "a tail over sub-second samples is not the edge of the histogram's range");
    }

    @Test
    @DisplayName("the percentiles the bean publishes agree with the histogram")
    void publishedPercentilesAgree() {
        assertEquals(Metrics.QueryLatency().getPercentile(50.0), registry.getLatencyPercentile(50));
        assertEquals(Metrics.QueryLatency().getPercentile(90.0), registry.getLatencyPercentile(90));
        assertEquals(Metrics.QueryLatency().getPercentile(95.0), registry.getLatencyPercentile(95));
        assertEquals(Metrics.QueryLatency().getPercentile(99.0), registry.getLatencyPercentile(99));
    }

    @Test
    @DisplayName("a percentile with no meaning is refused, not invented")
    void outOfRangePercentileIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> registry.getLatencyPercentile(1000),
            "1000 is not a percentile");
        assertThrows(IllegalArgumentException.class, () -> registry.getLatencyPercentile(101),
            "101 is not a percentile");
        assertThrows(IllegalArgumentException.class, () -> registry.getLatencyPercentile(-1),
            "a negative percentile is not a percentile");
    }

    @Test
    @DisplayName("an unpublished but valid percentile still answers from the histogram")
    void unpublishedPercentileFallsBackRatherThanFailing() {
        assertEquals(Metrics.QueryLatency().getPercentile(75.0), registry.getLatencyPercentile(75),
            "the bean publishes no p75, but 75 is a percentile and has an answer");
    }
}
