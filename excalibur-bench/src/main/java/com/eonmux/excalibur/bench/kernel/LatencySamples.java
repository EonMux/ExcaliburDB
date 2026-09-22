package com.eonmux.excalibur.bench.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A collection of per-operation latency samples, in milliseconds, with percentiles.
 *
 * <p>The p50 and p99 targets a benchmark is graded against are read off this. The
 * nearest-rank definition is used: p is the smallest sample at or above which p% of
 * the distribution falls, which keeps every reported percentile an actually observed
 * latency rather than an interpolation between two of them.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Thread-safe. A JMH benchmark state object is shared across every benchmark thread,
 * so samples arrive concurrently whenever a run is asked for more than one thread; an
 * unsynchronised list would lose samples or throw part-way through a measurement.</p>
 */
public final class LatencySamples {

    private final List<Double> samplesMs = new ArrayList<>();

    /**
     * Records one observed latency.
     *
     * @param latencyMs the latency in milliseconds
     * @throws IllegalArgumentException if the value is negative or not finite; either
     *         would poison every percentile taken afterwards
     */
    public synchronized void record(double latencyMs) {
        if (!Double.isFinite(latencyMs) || latencyMs < 0.0) {
            throw new IllegalArgumentException(
                "latency must be a non-negative finite number of milliseconds, got " + latencyMs);
        }
        samplesMs.add(latencyMs);
    }

    /**
     * The nearest-rank percentile of the samples recorded so far.
     *
     * @param percentile the percentile to read, from 0 to 100 inclusive
     * @return the latency in milliseconds, or 0 when nothing has been recorded
     * @throws IllegalArgumentException if the percentile is outside {@code [0, 100]}
     */
    public synchronized double percentile(int percentile) {
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException(
                "percentile must be within [0, 100], got " + percentile);
        }
        if (samplesMs.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(samplesMs);
        Collections.sort(sorted);

        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /**
     * The median latency.
     *
     * @return p50 in milliseconds, or 0 when nothing has been recorded
     */
    public double p50() {
        return percentile(50);
    }

    /**
     * The tail latency.
     *
     * @return p99 in milliseconds, or 0 when nothing has been recorded
     */
    public double p99() {
        return percentile(99);
    }

    /**
     * How many samples have been recorded.
     *
     * @return the sample count
     */
    public synchronized int count() {
        return samplesMs.size();
    }
}
