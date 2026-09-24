package com.eonmux.excalibur.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The ingest and query totals, and the interval a throughput rate is measured over.
 *
 * <h2>Why the rate is not just a counter</h2>
 * <p>A total is a fact; a rate is a measurement over an interval, and so it needs somewhere to
 * keep the interval's start. That state - when the last sample was taken and what the total
 * read then - is the reason this is an object rather than two counters, and keeping it beside
 * the counters is what lets the sampling window be enforced rather than left to whoever calls.</p>
 *
 * <h2>Thread-Safety</h2>
 * <p>The two totals are atomics written from every ingest and query thread in the process. The
 * rate window is a read-modify-write across four fields, so it is taken under a lock; the four
 * are also {@code volatile} because {@link #peakRate()} deliberately reads the peak without
 * acquiring it. A scrape must not be able to block an ingest thread, and a
 * rate read one sample stale is a rate, whereas a rate read behind a contended lock is a
 * latency spike in the scrape path.</p>
 *
 * @author ExcaliburDB
 */
final class ThroughputCounters {

    /**
     * The shortest window an observer can start a new rate sample over.
     *
     * <p>A rate is a measurement over an interval, so reading one has to name an interval. Letting
     * every read start a fresh one made the metric depend on who was watching it: a console that
     * polled twice in quick succession measured the second poll over a window in which nothing
     * could have been ingested, and reported zero. Within one window every observer sees the same
     * sample.</p>
     */
    private static final long RATE_SAMPLE_WINDOW_MS = 1000;

    private final AtomicLong entriesIngested = new AtomicLong(0);
    private final AtomicLong queriesExecuted = new AtomicLong(0);

    private final LongSupplier clock;
    private final Object rateLock = new Object();
    private volatile long lastRateCalculationTimestamp;
    private volatile long entriesIngestedAtLastRateCalculation = 0;
    private volatile double currentIngestionRate = 0.0;
    private volatile double peakIngestionRate = 0.0;

    /**
     * Creates a counter set with every total at zero and the rate window open from now.
     */
    ThroughputCounters() {
        this(System::currentTimeMillis);
    }

    /**
     * Creates a counter set that reads time from the given clock.
     *
     * @param clock Milliseconds since an arbitrary origin, never decreasing
     */
    ThroughputCounters(LongSupplier clock) {
        this.clock = clock;
        this.lastRateCalculationTimestamp = clock.getAsLong();
    }

    /**
     * Adds to the ingested total.
     *
     * @param count How many entries were ingested; callers screen out non-positive counts
     * @return The new total, so the caller can publish it without a second read
     */
    long addIngested(long count) {
        return entriesIngested.addAndGet(count);
    }

    /**
     * Gets the total number of entries ingested.
     *
     * @return Lifetime ingested entry count
     */
    long entriesIngested() {
        return entriesIngested.get();
    }

    /**
     * Counts one query execution.
     */
    void recordQueryExecution() {
        queriesExecuted.incrementAndGet();
    }

    /**
     * Gets the total number of queries executed.
     *
     * @return Lifetime executed query count
     */
    long queriesExecuted() {
        return queriesExecuted.get();
    }

    /**
     * Gets the highest interval rate seen since startup.
     *
     * @return Peak ingestion rate in entries per second
     */
    double peakRate() {
        return peakIngestionRate;
    }

    /**
     * Returns the rate for an observer, re-sampling at most once per sample window.
     *
     * <p>Safe to call from any number of observers at any frequency: reading the rate does not
     * consume the interval it was measured over, so two reads in the same window agree, and one
     * console's polling cannot zero the value another console is watching.</p>
     *
     * <p>Once a window has elapsed, the read closes it: the rate becomes the number of entries
     * ingested during it divided by its length, so it reflects current throughput rather than a
     * lifetime average and drops to zero while ingestion is idle. The peak is the maximum of
     * those window rates. Values are non-negative.</p>
     *
     * @return The current ingestion rate in entries per second
     */
    double sampledRate() {
        synchronized (rateLock) {
            long now = clock.getAsLong();
            long elapsed = now - lastRateCalculationTimestamp;
            if (elapsed >= RATE_SAMPLE_WINDOW_MS) {
                long entries = entriesIngested.get();
                long ingestedInInterval = entries - entriesIngestedAtLastRateCalculation;
                currentIngestionRate = (ingestedInInterval * 1000.0) / elapsed;
                if (currentIngestionRate > peakIngestionRate) {
                    peakIngestionRate = currentIngestionRate;
                }
                entriesIngestedAtLastRateCalculation = entries;
                lastRateCalculationTimestamp = now;
            }
            return currentIngestionRate;
        }
    }
}
