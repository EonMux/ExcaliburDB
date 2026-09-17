package io.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests that "query latency" and "the current epoch" each name exactly one thing.
 *
 * <p>Two histograms published under one name is worse than one histogram, because an operator
 * reading a latency panel cannot tell which of them they are looking at, and one of the two
 * was never written to - so half the accessors that claim to be percentiles were reporting a
 * permanent zero. The same goes for the epoch: a node that publishes two numbers both called
 * the current epoch is telling an operator that its visibility is in two places at once.</p>
 *
 * @author ExcaliburDB
 */
class QueryLatencyHistogramIdentityTest {

    @Test
    @DisplayName("there is one query latency histogram, not two")
    void oneHistogramForQueryLatency() {
        assertSame(FixedBucketHistogram.getInstance(), Metrics.QueryLatency(),
            "the histogram the static accessors publish must be the one latencies land in");
    }

    @Test
    @DisplayName("a recorded latency lands in the histogram the static accessors publish")
    void recordedLatencyReachesThePublishedHistogram() {
        long before = FixedBucketHistogram.getInstance().getTotalCount();

        Metrics.recordQueryLatency(7L);

        assertEquals(before + 1, FixedBucketHistogram.getInstance().getTotalCount(),
            "a latency recorded through Metrics must be counted by the published histogram");
    }

    @Test
    @DisplayName("the static percentile accessors agree with the facade's")
    void staticAccessorsAgreeWithTheFacade() {
        for (int i = 1; i <= 40; i++) {
            Metrics.recordQueryLatency(i);
        }

        assertEquals(Metrics.getP50(), FixedBucketHistogram.getP50(), "p50 is one number");
        assertEquals(Metrics.getP90(), FixedBucketHistogram.getP90(), "p90 is one number");
        assertEquals(Metrics.getP95(), FixedBucketHistogram.getP95(), "p95 is one number");
        assertEquals(Metrics.getP99(), FixedBucketHistogram.getP99(), "p99 is one number");
        assertEquals(Metrics.getP999(), FixedBucketHistogram.getP999(), "p99.9 is one number");
        assertEquals(Metrics.getP0(), FixedBucketHistogram.getP0(), "p0 is one number");
    }

    @Test
    @DisplayName("advancing the epoch advances it on the histogram latencies are recorded into")
    void recordEpochAdvancesTheRecordingHistogram() {
        long epoch = Metrics.getCurrentEpoch() + 1_000L;

        Metrics.recordEpoch(epoch);

        assertEquals(epoch, Metrics.QueryLatency().getCurrentEpoch(),
            "recordEpoch must stamp the histogram that production latencies go into");
    }

    @Test
    @DisplayName("one epoch is published, whatever else stamps the histogram")
    void oneEpochIsPublished() throws Exception {
        long epoch = Metrics.getCurrentEpoch() + 2_000L;
        Metrics.recordEpoch(epoch);

        // A query recording the epoch it read at must not move the node's published epoch:
        // the visibility tracker owns that number, and a bean reporting a different one sends
        // an operator looking for a visibility problem that is not there.
        Metrics.recordQueryLatency(11L, epoch + 77L);

        Object published = Metrics.getVisibilityEpochMBean().getAttribute("epoch");
        Object onFpRateBean = Metrics.getCuckooFpRateMBean().getAttribute("currentEpoch");

        assertEquals(epoch, published, "the visibility epoch bean publishes the recorded epoch");
        assertEquals(published, onFpRateBean,
            "both beans that publish a current epoch must publish the same one");
    }
}
