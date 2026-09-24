package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the throughput counters that the IngestionThroughput MBean publishes.
 *
 * <p>"Current ingestion rate" has to mean the rate right now. A lifetime average wears the
 * same units and the same name, stays plausible, and never moves - so an operator watching it
 * cannot see ingestion stall, and a "peak" derived from it is not a peak at all. These tests
 * pin the interval semantics: entries ingested during a sample window over the window's
 * length. The counters read time from a clock the tests drive, so a window closes exactly when
 * a test says it does.</p>
 *
 * @author ExcaliburDB
 */
class MetricsThroughputTest {

    /** Exactly one sample window. */
    private static final long WINDOW_MS = 1000L;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ThroughputCounters counters = new ThroughputCounters(now::get);

    @Test
    @DisplayName("The current rate measures the last window, not the whole run")
    void currentRateMeasuresTheLastWindow() {
        counters.addIngested(500);
        now.addAndGet(WINDOW_MS);

        assertEquals(500.0, counters.sampledRate(), 1e-9,
            "the rate must reflect the entries ingested in the window");

        now.addAndGet(WINDOW_MS);

        assertEquals(0.0, counters.sampledRate(), 0.0,
            "a window with no ingestion must report a rate of zero, "
                + "which a lifetime average never would");
    }

    @Test
    @DisplayName("The rate is measured over the time that actually elapsed")
    void rateUsesTheElapsedTime() {
        counters.addIngested(300);
        now.addAndGet(3 * WINDOW_MS);

        assertEquals(100.0, counters.sampledRate(), 1e-9,
            "300 entries over three seconds is 100 per second, however late the read");
    }

    @Test
    @DisplayName("The peak rate keeps the highest window rate")
    void peakRateKeepsTheHighestWindow() {
        counters.addIngested(1000);
        now.addAndGet(WINDOW_MS);
        double busyRate = counters.sampledRate();

        assertEquals(busyRate, counters.peakRate(), 0.0,
            "the peak must be the window rate that just happened");

        counters.addIngested(10);
        now.addAndGet(WINDOW_MS);

        assertEquals(10.0, counters.sampledRate(), 1e-9, "the current rate falls back");
        assertEquals(busyRate, counters.peakRate(), 0.0,
            "the peak is retained across a quieter window");
        assertTrue(counters.peakRate() > counters.sampledRate(),
            "peak and current are distinct measurements");
    }

    @Test
    @DisplayName("Reading the rate does not consume the window it measures")
    void readingTheRateIsIdempotent() {
        counters.addIngested(250);
        now.addAndGet(WINDOW_MS);
        double first = counters.sampledRate();

        // Past the instant the window closed, and short of the next window: a read that
        // re-sampled would measure a fresh, empty interval and report zero.
        now.addAndGet(WINDOW_MS / 2);
        counters.addIngested(1);
        double second = counters.sampledRate();
        now.addAndGet(WINDOW_MS / 4);
        double third = counters.sampledRate();

        assertTrue(first > 0.0, "the sampled rate reflects the burst that just happened");
        assertEquals(first, second, 0.0, "two reads inside one sample window must agree");
        assertEquals(first, third, 0.0, "and a third must not drift either");
    }

    @Test
    @DisplayName("No rate is reported before the first window closes")
    void noRateBeforeTheFirstWindow() {
        counters.addIngested(100);
        now.addAndGet(WINDOW_MS - 1);

        assertEquals(0.0, counters.sampledRate(), 0.0);
        assertEquals(0.0, counters.peakRate(), 0.0);
    }

    @Test
    @DisplayName("The totals count every entry and query")
    void totalsAccumulate() {
        assertEquals(3, counters.addIngested(3));
        assertEquals(5, counters.addIngested(2));
        counters.recordQueryExecution();
        counters.recordQueryExecution();

        assertEquals(5, counters.entriesIngested());
        assertEquals(2, counters.queriesExecuted());
    }

    @Test
    @DisplayName("The published rate stays finite and non-negative")
    void publishedRateIsNonNegativeAndFinite() {
        Metrics.recordIngestEntries(1);

        double rate = Metrics.sampledIngestionRate();

        assertTrue(rate >= 0.0, "rate must not go negative");
        assertTrue(Double.isFinite(rate), "rate must stay finite");
        assertTrue(Double.isFinite(Metrics.getPeakIngestionRate()), "peak must stay finite");
    }
}
