package com.eonmux.excalibur.core.trace;

import java.util.Locale;

import java.util.EnumMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

/**
 * Per-stage latency for one request.
 *
 * <p>Kept from the first version rather than added once something is slow. Retrieval latency is
 * always the sum of several stages, and without the breakdown the only available action is to guess
 * which one to optimise — usually wrongly, because the stage that feels slow and the stage that is
 * slow are rarely the same. Retrofitting this later means retrofitting it into code whose stage
 * boundaries have already blurred.
 *
 * <p>Records nanoseconds per stage, accumulating when a stage is entered more than once. Safe for
 * concurrent use, because stages such as ranking fan out across threads that each time their own
 * work.
 *
 * <p>Deliberately tiny: an array of longs and a clock read per stage. Instrumentation that costs
 * enough to notice gets turned off, and instrumentation that is off answers nothing.
 */
public final class QueryTrace {

    private final AtomicLongArray nanosByStage = new AtomicLongArray(Stage.values().length);
    private final AtomicLongArray countByStage = new AtomicLongArray(Stage.values().length);
    private final long startNanos = System.nanoTime();

    /** Times {@code work} and attributes it to {@code stage}. */
    public <T> T time(Stage stage, Supplier<T> work) {
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            record(stage, System.nanoTime() - start);
        }
    }

    /** Times {@code work} and attributes it to {@code stage}. */
    public void time(Stage stage, Runnable work) {
        long start = System.nanoTime();
        try {
            work.run();
        } finally {
            record(stage, System.nanoTime() - start);
        }
    }

    /**
     * Adds an already-measured duration.
     *
     * <p>For work whose timing is produced elsewhere - a stage measured on another thread, or by
     * a component that reports its own duration.
     */
    public void record(Stage stage, long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("nanos must not be negative, got " + nanos);
        }
        nanosByStage.addAndGet(stage.ordinal(), nanos);
        countByStage.incrementAndGet(stage.ordinal());
    }

    /** Total nanoseconds attributed to {@code stage}. */
    public long nanosIn(Stage stage) {
        return nanosByStage.get(stage.ordinal());
    }

    /** How many times {@code stage} was entered. */
    public long entriesIn(Stage stage) {
        return countByStage.get(stage.ordinal());
    }

    /**
     * Wall-clock nanoseconds since this trace was created.
     *
     * <p>Not the sum of the stages: stages can overlap when work runs concurrently, and time spent
     * outside any stage belongs to neither. A large gap between this and the sum is itself the
     * finding — it means the time is going somewhere unmeasured.
     */
    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    /** Every stage that recorded time, with its total in nanoseconds. */
    public Map<Stage, Long> byStage() {
        Map<Stage, Long> result = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            long nanos = nanosIn(stage);
            if (nanos > 0) {
                result.put(stage, nanos);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** A one-line summary in milliseconds, for logs. */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("QueryTrace[");
        boolean first = true;
        for (Stage stage : Stage.values()) {
            long nanos = nanosIn(stage);
            if (nanos == 0) {
                continue;
            }
            if (!first) {
                builder.append(' ');
            }
            first = false;
            builder.append(stage.name().toLowerCase(Locale.ROOT)).append('=')
                .append(String.format(java.util.Locale.ROOT, "%.2fms", nanos / 1_000_000.0));
        }
        return builder.append(String.format(java.util.Locale.ROOT,
            " total=%.2fms]", elapsedNanos() / 1_000_000.0)).toString();
    }
}
