package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.shard.CompactionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one on-demand compaction at a time and remembers what it did.
 *
 * <p>Compaction rewrites segment files and rebuilds vector graphs. On a large corpus that is
 * minutes of I/O, which is far longer than a request should be held open, so the route that
 * starts it answers immediately and this runner does the work behind it. What the run found is
 * kept here until the next one replaces it, because a run whose verdict reached only the node's
 * log is a run the operator who asked for it cannot see.</p>
 *
 * <p>One at a time. A second run overlapping the first would be safe - both compaction workers
 * serialise on each segment's and each graph's own write lock - but it would report a run that
 * mostly skipped, and "nothing was worth reclaiming" and "something else was reclaiming it" are
 * different answers.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. The in-progress flag is the only thing two callers contend for, and it is
 * claimed atomically.</p>
 */
public final class CompactionRunner implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompactionRunner.class);

    /**
     * How long {@link #close()} waits for a run in progress before interrupting it.
     *
     * <p>The same budget the reindex and lexical-erasure drains use, because it is the same
     * class of work: a rewrite of the served data that cannot be abandoned cheaply.</p>
     */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    /** How long {@link #close()} waits after interrupting, before it gives up and says so. */
    private static final long INTERRUPTED_TIMEOUT_SECONDS = 30;

    /** No run has been asked for since this node started. */
    public static final String STATE_IDLE = "idle";

    /** A run is happening now. */
    public static final String STATE_RUNNING = "running";

    /** The last run finished and its counts are the report. */
    public static final String STATE_COMPLETED = "completed";

    /** The last run raised an error and reclaimed only what it reports. */
    public static final String STATE_FAILED = "failed";

    /**
     * The last run was asked to stop and ended early, having reclaimed what it reports.
     *
     * <p>A state of its own rather than {@code completed}: a run that stopped at the third of
     * sixty segments reclaimed real space and its counts are worth reading, but reporting it as
     * completed would say the other fifty-seven had nothing worth reclaiming.</p>
     */
    public static final String STATE_CANCELLED = "cancelled";

    /**
     * The compaction work itself, as the shard layer exposes it.
     *
     * <p>Narrower than the orchestrator it is normally bound to, so the route can be tested
     * without a storage engine behind it.</p>
     */
    @FunctionalInterface
    public interface Compactor {

        /**
         * Compacts what is worth compacting, on the calling thread.
         *
         * @param force true to rewrite everything regardless of the thresholds
         * @return What the run examined, reclaimed and failed on
         */
        CompactionReport compact(boolean force);
    }

    /**
     * What the runner has to say about the current or last run.
     *
     * @param state        one of the {@code STATE_*} constants
     * @param force        whether that run was asked to ignore the thresholds
     * @param startedAtMs  wall-clock start, or 0 when nothing has run
     * @param finishedAtMs wall-clock finish, or 0 while a run is in progress
     * @param report       what the run did, or null while it is running or if it threw
     * @param error        why the run failed, or null
     */
    public record Snapshot(String state, boolean force, long startedAtMs, long finishedAtMs,
                           CompactionReport report, String error) {

        /**
         * Whether a run is happening now.
         *
         * @return true while a run is in progress
         */
        public boolean isRunning() {
            return STATE_RUNNING.equals(state);
        }
    }

    private static final Snapshot NOTHING_YET =
        new Snapshot(STATE_IDLE, false, 0L, 0L, null, null);

    private final Compactor compactor;
    private final ExecutorService executor;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(NOTHING_YET);

    /**
     * Whether the run currently in progress has been asked to stop.
     *
     * <p>Separate from interrupting the thread, because the interrupt is how the run is told
     * and this is how its outcome is named. A run that has already passed its last boundary
     * finishes everything it was going to and is still reported as cancelled, which is true:
     * the request was made and the run ended earlier than it otherwise would have.</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean cancelRequested =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** The task of the run in progress, held so it can be interrupted. */
    private final AtomicReference<java.util.concurrent.Future<?>> running =
        new AtomicReference<>();

    /**
     * Creates a runner over the given compaction work.
     *
     * @param compactor What a run actually does
     * @throws NullPointerException if compactor is null
     */
    public CompactionRunner(Compactor compactor) {
        this.compactor = Objects.requireNonNull(compactor, "compactor cannot be null");
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "compaction-on-demand");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts a run, unless one is already happening.
     *
     * <p>Returns the snapshot it published rather than a flag, so the caller can describe the
     * run it just started instead of re-reading shared state that a short run may already have
     * moved on from.</p>
     *
     * @param force true to rewrite every live segment and graph regardless of the thresholds
     * @return The run that was started, or null if one was already in progress
     */
    public Snapshot start(boolean force) {
        // The snapshot is the claim, rather than a flag beside it. Two pieces of state for one
        // fact can disagree for as long as it takes to write the second: a caller that polled
        // the status until it read "completed" and posted again was answered 409 "already in
        // progress", and a loop written against the documented contract failed intermittently
        // and only under load.
        Snapshot previous = snapshot.get();
        if (previous.isRunning()) {
            return null;
        }
        long startedAtMs = System.currentTimeMillis();
        Snapshot started = new Snapshot(STATE_RUNNING, force, startedAtMs, 0L, null, null);
        if (!snapshot.compareAndSet(previous, started)) {
            // Another caller claimed it between the read and here, or a run that was finishing
            // published its result. Either way this request did not start anything.
            return null;
        }
        cancelRequested.set(false);
        try {
            running.set(executor.submit(() -> run(force, startedAtMs)));
        } catch (RuntimeException e) {
            // A rejected task would otherwise leave the claim held and every later run
            // answered 409 for the life of the process.
            snapshot.set(new Snapshot(STATE_FAILED, force, startedAtMs,
                System.currentTimeMillis(), null, "Compaction could not be scheduled: "
                + e.getMessage()));
            throw e;
        }
        return started;
    }

    /**
     * Returns what the current or last run has to say.
     *
     * @return The snapshot, never null
     */
    public Snapshot snapshot() {
        return snapshot.get();
    }

    /**
     * Asks the run in progress to stop.
     *
     * <p>A request rather than a stop, in the same sense {@code DELETE /v1/reindex} is: the
     * run ends at its next segment boundary, returns what it had reclaimed by then and reports
     * itself {@link #STATE_CANCELLED}. It is safe at any point - a rewrite builds the
     * replacement beside the original and swaps it in only at the end, so what an interrupted
     * run loses is its own work and never data.</p>
     *
     * <p>Why it exists: a forced compaction rewrites every live segment and rebuilds every
     * graph, which on a large corpus is minutes of saturated I/O. Without it, the only way to
     * end one started at the wrong time would be to stop the process - the more dangerous act
     * of the two, since shutdown closes the arena the segments are mapped out of.</p>
     *
     * @return true if a run was in progress and has been asked to stop
     */
    public boolean cancel() {
        java.util.concurrent.Future<?> task = running.get();
        if (task == null || task.isDone() || !snapshot.get().isRunning()) {
            return false;
        }
        cancelRequested.set(true);
        // May interrupt: the run reads the flag between segments, and the interrupt is what
        // makes a run blocked on I/O notice at all.
        task.cancel(true);
        LOGGER.info("On-demand compaction was asked to stop");
        return true;
    }

    /**
     * Performs one run and records its outcome.
     *
     * @param force       Whether the thresholds were to be ignored
     * @param startedAtMs When the run was asked for
     */
    private void run(boolean force, long startedAtMs) {
        try {
            CompactionReport report = compactor.compact(force);
            boolean cancelled = cancelRequested.get();
            snapshot.set(new Snapshot(cancelled ? STATE_CANCELLED : STATE_COMPLETED, force,
                startedAtMs, System.currentTimeMillis(), report, null));
            LOGGER.info("On-demand compaction {}: {}",
                cancelled ? "stopped early" : "finished", report);
        } catch (RuntimeException | Error e) {
            // Recorded, not just logged. The operator who asked for this run reads the
            // status, and a failure that reaches only the log reads there as a run that
            // finished having reclaimed nothing.
            snapshot.set(new Snapshot(STATE_FAILED, force, startedAtMs,
                System.currentTimeMillis(), null, String.valueOf(e.getMessage())));
            LOGGER.error("On-demand compaction failed", e);
        } finally {
            // The compaction leaves the interrupt flag set for whoever set it, and this pool
            // has one thread that every later run uses. Carrying the flag forward would make
            // the next run die on its first blocking call, for a reason nothing in its own
            // request explains.
            Thread.interrupted();
            running.set(null);
        }
    }

    /**
     * Stops accepting runs and waits for one in progress to finish.
     *
     * <p>A compaction interrupted partway leaves the segment it was rewriting untouched - the
     * replacement is built beside the original and swapped in at the end - so an interrupted
     * run loses work, not data.</p>
     *
     * <p>Waiting again after the interrupt is the point of this method. The caller closes the
     * storage engine and its arena next, and a rewrite still copying entries into a mapping
     * whose arena has closed takes the JVM down rather than throwing. So an interrupt that the
     * run has not yet noticed is not a drain, and reporting one would be the same lie as
     * reporting an erasure that did not happen.</p>
     *
     * @return true if no run is still in progress, false if one outlived both waits
     */
    public boolean drain() {
        executor.shutdown();
        try {
            if (executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
            LOGGER.warn("Compaction did not finish within {}s of shutdown; interrupting it",
                SHUTDOWN_TIMEOUT_SECONDS);
            executor.shutdownNow();
            return executor.awaitTermination(INTERRUPTED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            return false;
        }
    }

    /**
     * Stops accepting runs and waits for one in progress, logging if it outlives the wait.
     */
    @Override
    public void close() {
        if (!drain()) {
            LOGGER.error("A compaction is still rewriting segments {}s after being asked to "
                + "stop. Closing the storage engine underneath it can take the process down; "
                + "the run itself loses only its own work, never data.",
                SHUTDOWN_TIMEOUT_SECONDS + INTERRUPTED_TIMEOUT_SECONDS);
        }
    }
}
