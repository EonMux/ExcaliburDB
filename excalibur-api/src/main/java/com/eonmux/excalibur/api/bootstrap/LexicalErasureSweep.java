package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Makes the chunk index's erasure deadline a deadline rather than a hope.
 *
 * <h2>Why this exists</h2>
 * <p>{@code excalibur.ingest.lexical-max-latency-seconds} says how long an erased chunk may stay
 * on disk before cost stops being a consideration - the bound that turns an accepted erasure into
 * a promise, since a single erased chunk in a large corpus never reaches the tombstone ratio on
 * its own. Nothing evaluated it on a quiet node: the compaction was checked at the end of an
 * ingestion run, at the end of a reindex, and when the next erasure arrived. A node that erased
 * something and then had nothing else asked of it kept the chunk indefinitely, so the promise
 * held exactly as long as the node stayed busy.</p>
 *
 * <h2>The other work only a compaction does</h2>
 * <p>Two more kinds of work wait on a compaction and are just as invisible on a quiet node: a
 * damaged segment the index can rebuild from stored text, whose chunks no query finds until it
 * is, and a segment still in an older format after an upgrade. Neither moves the policy's
 * numbers, and a node that is not ingesting runs no other compaction, so the sweep asks for one
 * when either is present - which is what lets both finish without an operator forcing it.</p>
 *
 * <h2>What it costs when there is nothing to do</h2>
 * <p>A read of the index's own report. With no erasure owed and nothing to rebuild or upgrade
 * there is nothing any compaction could do, so the planner is never asked and no segment is
 * touched.</p>
 *
 * <h2>Stopping</h2>
 * <p>A sweep can be in the middle of a rewrite of the whole index when the node shuts down, and
 * that rewrite reads every surviving chunk's text back out of the storage engine the shutdown is
 * about to close. It is told to stop through the cooperative signal the index reads between
 * segment groups and between chunks, never by an interrupt: an interrupt reaching a thread inside
 * file I/O closes the channel it was using, and the index shares its channels with every query.
 * A stopped rewrite publishes nothing and leaves every segment as it was.
 * {@link #drain(Duration)} says
 * whether the sweep actually ended, because a shutdown that closed storage under one that had not
 * would crash the process rather than fail it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. {@link #sweep()} may be called by anyone; the index serialises its
 * own compactions.</p>
 *
 * @author ExcaliburDB
 */
public final class LexicalErasureSweep implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalErasureSweep.class);

    /**
     * How much of the configured bound may pass between two checks.
     *
     * <p>A tenth, so an erased chunk is rewritten out within 110% of the bound rather than within
     * twice it.</p>
     */
    private static final long SWEEP_FRACTION = 10;

    /** Shortest gap between sweeps, so a small configured bound cannot turn into a spin. */
    static final long MIN_SWEEP_INTERVAL_MS = 1_000L;

    /** What {@link #close()} waits for: a compaction in flight, for as long as it takes to stop. */
    private static final Duration UNTIL_STOPPED = Duration.ofNanos(Long.MAX_VALUE);

    private final ChunkIndex index;
    private final Function<String, String> storedText;
    private final ScheduledExecutorService scheduler;

    /**
     * Set once by {@link #drain(Duration)}: read by a compaction in flight at its next boundary, and by
     * every later {@link #sweep()} before it starts one.
     */
    private volatile boolean stopping;

    /**
     * Starts sweeping.
     *
     * @param index             The chunk index whose obligations are being discharged
     * @param storedText        Reads a chunk's text back, which a rewrite needs
     * @param maxLatencySeconds The configured bound
     * @throws NullPointerException if index or storedText is null
     */
    public LexicalErasureSweep(ChunkIndex index, Function<String, String> storedText,
                               long maxLatencySeconds) {
        this(index, storedText, sweepIntervalMillis(maxLatencySeconds),
            sweepIntervalMillis(maxLatencySeconds));
        LOGGER.info("lexical erasure deadline swept every {}ms against a bound of {}s",
            sweepIntervalMillis(maxLatencySeconds), maxLatencySeconds);
    }

    /**
     * Starts sweeping on a schedule of the caller's choosing.
     *
     * <p>Package-private so a test can have the first sweep start at once and observe it
     * without waiting out the production intervals.</p>
     *
     * @param index          The chunk index whose obligations are being discharged
     * @param storedText     Reads a chunk's text back, which a rewrite needs
     * @param initialDelayMs How long before the first sweep
     * @param intervalMs     The gap between one sweep ending and the next starting
     * @throws NullPointerException if index or storedText is null
     */
    LexicalErasureSweep(ChunkIndex index, Function<String, String> storedText,
                        long initialDelayMs, long intervalMs) {
        this.index = Objects.requireNonNull(index, "index must not be null");
        this.storedText = Objects.requireNonNull(storedText, "storedText must not be null");

        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lexical-erasure-sweep");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::sweep, initialDelayMs, intervalMs,
            TimeUnit.MILLISECONDS);
    }

    /**
     * How often to check whether an erased chunk has passed its deadline.
     *
     * @param maxLatencySeconds The configured bound
     * @return The gap between sweeps, never below {@link #MIN_SWEEP_INTERVAL_MS}
     */
    static long sweepIntervalMillis(long maxLatencySeconds) {
        return Math.max(MIN_SWEEP_INTERVAL_MS, maxLatencySeconds * 1_000L / SWEEP_FRACTION);
    }

    /**
     * Runs one check, and swallows whatever it throws.
     *
     * <p>Nothing is rethrown, because this is what the scheduler runs: a scheduled task that
     * throws is silently cancelled and never runs again, which would turn one failed compaction
     * into an erasure deadline that stops being enforced for the life of the process - the exact
     * failure this exists to prevent, arrived at quietly.</p>
     *
     * @return true when a compaction was attempted and did not fail
     */
    public boolean sweep() {
        try {
            if (stopping || !hasWork(index.stats())) {
                return false;
            }
            CompactionResult result = index.compactIfDue(storedText, () -> stopping);
            if (result.performed()) {
                LOGGER.info("lexical erasure deadline reached: {} segment(s) rewritten into {}",
                    result.segmentsRewritten(), result.segmentsProduced());
            } else {
                LOGGER.debug("lexical erasure not due yet: {}", result);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("lexical erasure sweep failed; the next one will try again", e);
            return false;
        }
    }

    /**
     * Whether a compaction now could do anything the index is waiting on.
     *
     * <p>An erasure owed, a damaged segment the index can rebuild, or a segment still in an older
     * format. Not a damaged segment it cannot rebuild: a compaction has no names to rebuild it
     * from, and the index removes such a segment itself, outright, the moment an erasure is
     * recorded or found owed when it opens - so asking for a compaction every sweep would only
     * repeat the log line that already reported the segment.</p>
     *
     * @param stats What the index says about itself
     * @return true when a compaction is worth asking for
     */
    static boolean hasWork(ChunkIndexStats stats) {
        if (stats.erasureRequests() > 0 || stats.outdatedSegments() > 0) {
            return true;
        }
        for (ChunkIndexStats.UnreadableSegment segment : stats.unreadableSegments()) {
            if (segment.rebuildable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stops sweeping, tells a compaction in flight to stop, and waits for it to end.
     *
     * <p>{@code shutdown}, never {@code shutdownNow}: the compaction is told through its stop
     * signal, because an interrupt delivered inside file I/O closes a channel the index shares
     * with every query. What a stopped compaction loses is its own work; it publishes nothing.</p>
     *
     * <p>The answer is the one a shutdown needs before it closes storage. False means a
     * compaction is still running - one that had not reached a boundary within the wait, or an
     * index that cannot stop mid-rewrite - and whatever it reads must be left open.</p>
     *
     * <p>How long to wait is the caller's: a node's shutdown passes what its budget leaves the
     * drains, so a longer budget is a longer wait.</p>
     *
     * @param wait How long to wait for a compaction in flight to act on the stop
     * @return true if no sweep is still running
     * @throws NullPointerException if wait is null
     */
    public boolean drain(Duration wait) {
        Objects.requireNonNull(wait, "wait must not be null");
        stopping = true;
        scheduler.shutdown();
        try {
            if (scheduler.awaitTermination(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                return true;
            }
            LOGGER.error("the lexical erasure sweep was still compacting {}ms after being asked "
                + "to stop", wait.toMillis());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return scheduler.isTerminated();
        }
    }

    /**
     * As {@link #drain(Duration)}, waiting for as long as a compaction takes to stop, for an
     * owner with no deadline and no use for the answer - the way {@code ExecutorService.close}
     * waits.
     */
    @Override
    public void close() {
        drain(UNTIL_STOPPED);
    }

}
