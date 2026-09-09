package io.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the throughput counters that the IngestionThroughput MBean publishes.
 *
 * <p>"Current ingestion rate" has to mean the rate right now. A lifetime average wears the
 * same units and the same name, stays plausible, and never moves - so an operator watching it
 * cannot see ingestion stall, and a "peak" derived from it is not a peak at all. These tests
 * pin the interval semantics: entries since the previous update over the time since it.</p>
 *
 * @author ExcaliburDB
 */
class MetricsThroughputTest {

    /** Long enough that the millisecond clock advances between two updates. */
    private static final long INTERVAL_MS = 60L;

    @Test
    @DisplayName("The current rate measures the last interval, not the whole run")
    void currentRateMeasuresTheLastInterval() throws Exception {
        // Establish a baseline so the interval under test starts here.
        Metrics.updateIngestionRate();
        Thread.sleep(INTERVAL_MS);

        int entries = 500;
        for (int i = 0; i < entries; i++) {
            Metrics.recordIngestEntry();
        }
        Metrics.updateIngestionRate();

        double busyRate = Metrics.getIngestionRate();
        assertTrue(busyRate > 0.0, "the rate must reflect entries ingested in the interval");

        Thread.sleep(INTERVAL_MS);
        Metrics.updateIngestionRate();

        assertEquals(0.0, Metrics.getIngestionRate(), 0.0,
            "an interval with no ingestion must report a rate of zero, "
                + "which a lifetime average never would");
    }

    @Test
    @DisplayName("The peak rate keeps the highest interval rate")
    void peakRateKeepsTheHighestInterval() throws Exception {
        Metrics.updateIngestionRate();
        Thread.sleep(INTERVAL_MS);
        for (int i = 0; i < 1000; i++) {
            Metrics.recordIngestEntry();
        }
        Metrics.updateIngestionRate();

        double busyRate = Metrics.getIngestionRate();
        double peakAfterBurst = Metrics.getPeakIngestionRate();
        assertTrue(peakAfterBurst >= busyRate,
            "the peak must be at least the interval rate that just happened");

        Thread.sleep(INTERVAL_MS);
        Metrics.updateIngestionRate();

        assertEquals(0.0, Metrics.getIngestionRate(), 0.0, "the current rate falls back to zero");
        assertEquals(peakAfterBurst, Metrics.getPeakIngestionRate(), 0.0,
            "the peak is retained across an idle interval");
        assertTrue(Metrics.getPeakIngestionRate() > Metrics.getIngestionRate(),
            "peak and current are distinct measurements");
    }

    @Test
    @DisplayName("Rates stay finite and non-negative")
    void ratesAreNonNegativeAndFinite() {
        Metrics.updateIngestionRate();
        Metrics.updateIngestionRate();

        assertTrue(Metrics.getIngestionRate() >= 0.0, "rate must not go negative");
        assertTrue(Double.isFinite(Metrics.getIngestionRate()),
            "a zero-length interval must not divide by zero");
        assertTrue(Double.isFinite(Metrics.getPeakIngestionRate()), "peak must stay finite");
    }

    @Test
    @DisplayName("Reading the rate does not consume the interval it measures")
    void readingTheRateIsIdempotent() throws Exception {
        Metrics.updateIngestionRate();
        Thread.sleep(INTERVAL_MS);
        for (int i = 0; i < 250; i++) {
            Metrics.recordIngestEntry();
        }
        Metrics.updateIngestionRate();

        double first = Metrics.sampledIngestionRate();
        // Far enough apart that a re-sampling read would measure a fresh, empty interval, and
        // far short of the sample window, so a sampling read must not start one.
        Thread.sleep(5);
        double second = Metrics.sampledIngestionRate();
        Thread.sleep(5);
        double third = Metrics.sampledIngestionRate();

        // Reading used to re-sample, so the second read measured a window nothing was ingested in
        // and reported zero: one observer's poll silently reset what every other observer saw.
        assertEquals(first, second, 0.0,
            "two reads inside one sample window must agree");
        assertEquals(first, third, 0.0,
            "and a third must not drift either");
        assertTrue(first > 0.0, "the sampled rate still reflects the burst that just happened");
    }
}
