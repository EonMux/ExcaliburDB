package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 * <h2>What it costs when there is nothing to do</h2>
 * <p>An integer read. The index reports how many erasure requests it has accepted and not yet
 * discharged, and with none there is nothing any compaction could reclaim, so the planner is
 * never asked and no segment is touched.</p>
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

    /** How long {@link #close()} waits for a compaction in flight. */
    private static final long SHUTDOWN_WAIT_SECONDS = 30L;

    private final ChunkIndex index;
    private final Function<String, String> storedText;
    private final ScheduledExecutorService scheduler;

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
        this.index = Objects.requireNonNull(index, "index must not be null");
        this.storedText = Objects.requireNonNull(storedText, "storedText must not be null");

        long intervalMs = sweepIntervalMillis(maxLatencySeconds);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lexical-erasure-sweep");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::sweep, intervalMs, intervalMs,
            TimeUnit.MILLISECONDS);
        LOGGER.info("lexical erasure deadline swept every {}ms against a bound of {}s",
            intervalMs, maxLatencySeconds);
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
            if (index.stats().erasureRequests() == 0) {
                return false;
            }
            CompactionResult result = index.compactIfDue(storedText);
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

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("the lexical erasure sweep did not stop within {}s",
                    SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
