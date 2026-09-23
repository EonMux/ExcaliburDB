package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.shard.CompactionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs one on-demand compaction at a time and remembers what it did.
 *
 * <p>Compaction rewrites segment files and rebuilds vector graphs. On a large corpus that is
 * minutes of I/O, which is far longer than a request should be held open, so the route that
 * starts it answers immediately and this runner does the work behind it. What the run found is
 * kept here until the next one replaces it, because a run whose verdict reached only the node's
 * log is a run the operator who asked for it cannot see.</p>
 *
 * <h2>Two stores, one run</h2>
 * <p>A run compacts the segment store and its graphs first, then the lexical chunk index, which
 * keeps its own tombstones for the same erased and re-crawled chunks. The two are independent: a
 * failure in one is reported beside the other's counts rather than in place of them, and the
 * chunk index is compacted even when the store's half failed. {@code force} means the same to
 * both - rewrite everything - and without it each applies its own thresholds.</p>
 *
 * <h2>Nothing is interrupted</h2>
 * <p>Neither half of a run is ever interrupted. An interrupt delivered during file I/O closes
 * the channel being used, and both halves work through channels other threads share: the
 * segment store's half rewrites segments, forces the new segments' write logs, and reopens the
 * graphs queries are served from; the chunk index shares its channels with every query. So each
 * half is told to stop through a cooperative signal - the run's stop request - which the segment
 * store reads before each segment and between graphs, and the chunk index between segment groups
 * and between chunks. What a stop costs is the run's own unfinished work: a rewrite builds its
 * replacement beside the original and swaps it in only at the end. A cancel sends that signal,
 * and so does {@link #drain(Duration)}: a rewrite can outlast any shutdown budget, and one still reading
 * when the storage engine closes is reading memory that is no longer mapped.</p>
 *
 * <p>The chunk index is rewritten on a thread of its own, so the run's wait for it can be
 * bounded without abandoning it.</p>
 *
 * <p>One at a time. A second run overlapping the first would be safe - both compaction workers
 * serialise on each segment's and each graph's own write lock - but it would report a run that
 * mostly skipped, and "nothing was worth reclaiming" and "something else was reclaiming it" are
 * different answers.</p>
 *
 * <h2>Runs, and telling them apart</h2>
 * <p>Every run has an identity of its own - what it was asked for, whether it has been asked to
 * stop, and which thread is carrying it out - and everything that acts on "the run in progress"
 * acts on that identity rather than on the pool thread or its task. The one pool thread serves
 * every run in turn, and a run publishes its outcome before that thread is free for the next;
 * so a client who read "completed" and asked again has a second run queued behind the first
 * one's last steps. Acting on the thread there would reach the wrong run, and cancelling the
 * task of a run still queued means it never runs to report that it ended - the status reads
 * "running" for the life of the process and every later start is refused. Instead a cancel sets
 * the stop request of the run it targets, and a run that finds it was cancelled before it began
 * reports itself cancelled without compacting anything.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Starting a run, cancelling it and settling its outcome all take one lock, so
 * the published state and the run it describes never disagree; reading the state does not.</p>
 */
public final class CompactionRunner implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompactionRunner.class);

    /** What {@link #close()} waits for: a run it asked to stop, for as long as that takes. */
    private static final Duration UNTIL_STOPPED = Duration.ofNanos(Long.MAX_VALUE);

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
         * @param force         true to rewrite everything regardless of the thresholds
         * @param stopRequested Answers true once the run has been cancelled or the runner is
         *                      draining; the compaction should then return what it had done,
         *                      at the next point where stopping leaves nothing half-done
         * @return What the run examined, reclaimed and failed on
         */
        CompactionReport compact(boolean force, BooleanSupplier stopRequested);
    }

    /**
     * The lexical chunk index's half of a run.
     *
     * <p>Kept apart from {@link Compactor} because the chunk index is not part of the shard layer
     * and is absent on a node that keeps none; a runner without one reports no lexical block.</p>
     */
    @FunctionalInterface
    public interface LexicalCompactor {

        /**
         * Compacts the chunk index, on the calling thread.
         *
         * @param force         true to rewrite every segment of the index, false to let its own
         *                      policy decide whether anything is due
         * @param stopRequested Answers true once the run has been cancelled or the runner is
         *                      draining; the compaction should then end without publishing,
         *                      at the next point where it can
         * @return What the compaction did, or why it did nothing
         * @throws IOException if the index could not be rewritten
         */
        CompactionResult compact(boolean force, BooleanSupplier stopRequested) throws IOException;

        /**
         * Whether this compaction reads chunk text back out of the storage engine.
         *
         * <p>What {@link #drain(Duration)} needs to know about a rewrite that outlived it: one that
         * reads storage keeps the storage engine and its arena open, and one that rewrites from
         * its own copy of the text keeps only the chunk index open. True unless told otherwise,
         * because the wrong answer in that direction closes storage under a running read.</p>
         *
         * @return true if the compaction reads the storage engine
         */
        default boolean readsStorage() {
            return true;
        }
    }

    /**
     * What a drain left running, as the two things a shutdown closes afterwards see it.
     *
     * @param storageReleased    Whether nothing this runner started is still reading the storage
     *                           engine or its mappings; false means both must stay open
     * @param chunkIndexReleased Whether nothing this runner started is still rewriting the chunk
     *                           index; false means its files must stay open
     */
    public record Drained(boolean storageReleased, boolean chunkIndexReleased) {

        /** Nothing was left running. */
        public static final Drained CLEAN = new Drained(true, true);

        /**
         * Whether nothing at all was left running.
         *
         * @return true when both are released
         */
        public boolean clean() {
            return storageReleased && chunkIndexReleased;
        }
    }

    /**
     * What the chunk index's half of a run came to.
     *
     * @param performed         whether the index rewrote anything; false when its policy
     *                          declined, when the run stopped first, when every group it planned
     *                          was abandoned, or when it failed
     * @param segmentsRewritten index segments read and replaced
     * @param segmentsProduced  index segments written in their place
     * @param chunksRemoved     chunks physically removed from disk
     * @param groupsAbandoned   planned groups of segments left exactly as they were because a
     *                          surviving chunk's text could not be read back; non-zero means the
     *                          rewrite was partial, or did not happen at all when
     *                          {@code performed} is false
     * @param segmentsRebuilt   of the segments rewritten, those that could not be opened and are
     *                          searchable again
     * @param segmentsUpgraded  of the segments rewritten, those written in an older format and
     *                          now in the current one
     * @param reason            why it ran or did not, or null when it failed
     * @param error             why it failed, or null
     */
    public record LexicalOutcome(boolean performed, int segmentsRewritten, int segmentsProduced,
                                 int chunksRemoved, int groupsAbandoned, int segmentsRebuilt,
                                 int segmentsUpgraded, String reason, String error) {

        /**
         * The outcome of a compaction the index carried out, or declined.
         *
         * @param result What the index reported
         * @return The outcome
         */
        static LexicalOutcome of(CompactionResult result) {
            return new LexicalOutcome(result.performed(), result.segmentsRewritten(),
                result.segmentsProduced(), result.documentsRemoved(), result.groupsAbandoned(),
                result.segmentsRebuilt(), result.segmentsUpgraded(), result.reason(), null);
        }

        /**
         * A compaction that was never attempted.
         *
         * @param reason Why not
         * @return The outcome
         */
        static LexicalOutcome skipped(String reason) {
            return new LexicalOutcome(false, 0, 0, 0, 0, 0, 0, reason, null);
        }

        /**
         * A compaction that raised an error.
         *
         * @param error Why it failed
         * @return The outcome
         */
        static LexicalOutcome failed(String error) {
            return new LexicalOutcome(false, 0, 0, 0, 0, 0, 0, null, error);
        }
    }

    /**
     * What the runner has to say about the current or last run.
     *
     * @param state        one of the {@code STATE_*} constants
     * @param force        whether that run was asked to ignore the thresholds
     * @param startedAtMs  wall-clock start, or 0 when nothing has run
     * @param finishedAtMs wall-clock finish, or 0 while a run is in progress
     * @param report       what the run did to the segment store, or null while it is running,
     *                     if that half threw, or if the run was cancelled before it began
     * @param error        why the segment store's half failed, or null
     * @param lexical      what the chunk index's half came to, or null while the run is in
     *                     progress or when this node keeps no chunk index
     */
    public record Snapshot(String state, boolean force, long startedAtMs, long finishedAtMs,
                           CompactionReport report, String error, LexicalOutcome lexical) {

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
        new Snapshot(STATE_IDLE, false, 0L, 0L, null, null, null);

    /** Reported when a run was asked to stop before it reached the chunk index. */
    static final String LEXICAL_SKIPPED_ON_CANCEL =
        "the run was asked to stop before the chunk index was compacted";

    private final Compactor compactor;
    private final LexicalCompactor lexicalCompactor;
    private final ExecutorService executor;

    /**
     * Where the chunk index is rewritten: a thread nothing interrupts. Null when there is no
     * chunk index to compact.
     */
    private final ExecutorService lexicalExecutor;

    /**
     * Runs on a run's worker once its outcome is published, before the thread is free for the
     * next run. Nothing in production; a test holds the worker here to open the window in which
     * a second run is queued behind the first.
     */
    private final Runnable afterSettle;

    /** Guards {@link #current} and every write of {@link #snapshot}. */
    private final Object lock = new Object();

    /** The run started and not yet settled, or null. Guarded by {@link #lock}. */
    private Run current;

    /**
     * Whether a run is inside the segment store's half, which reads storage.
     *
     * <p>Kept apart from whether the run's thread has ended: once that half returns, the thread
     * only waits for the chunk index's half, and a run waiting on an index that rewrites from its
     * own text reads nothing a shutdown is about to close.</p>
     */
    private volatile boolean segmentHalfRunning;

    /** What the current or last run has to say. Written under {@link #lock}, read without. */
    private volatile Snapshot snapshot = NOTHING_YET;

    /**
     * One run: what it was asked for, and whether it has been asked to stop.
     *
     * <p>The stop request is how both halves are told and how the outcome is named. A run that
     * has already passed its last boundary finishes everything it was going to and is still
     * reported as cancelled, which is true: the request was made and the run ended no later than
     * it otherwise would have.</p>
     */
    private static final class Run {

        /** Whether the thresholds are to be ignored. */
        final boolean force;

        /** When the run was asked for. */
        final long startedAtMs;

        /** Whether the run has been asked to stop. */
        volatile boolean cancelRequested;

        Run(boolean force, long startedAtMs) {
            this.force = force;
            this.startedAtMs = startedAtMs;
        }
    }

    /**
     * Creates a runner over the segment store alone, for a node that keeps no chunk index.
     *
     * @param compactor What a run does to the segment store and its graphs
     * @throws NullPointerException if compactor is null
     */
    public CompactionRunner(Compactor compactor) {
        this(compactor, null);
    }

    /**
     * Creates a runner over the segment store and the lexical chunk index.
     *
     * @param compactor        What a run does to the segment store and its graphs
     * @param lexicalCompactor What a run does to the chunk index, or null when there is none
     * @throws NullPointerException if compactor is null
     */
    public CompactionRunner(Compactor compactor, LexicalCompactor lexicalCompactor) {
        this(compactor, lexicalCompactor, () -> { });
    }

    /**
     * Creates a runner with a hook on each run's worker, after the run's outcome is published.
     *
     * @param compactor        What a run does to the segment store and its graphs
     * @param lexicalCompactor What a run does to the chunk index, or null when there is none
     * @param afterSettle      Runs on the worker once a run has settled
     * @throws NullPointerException if compactor or afterSettle is null
     */
    CompactionRunner(Compactor compactor, LexicalCompactor lexicalCompactor,
                     Runnable afterSettle) {
        this.compactor = Objects.requireNonNull(compactor, "compactor cannot be null");
        this.lexicalCompactor = lexicalCompactor;
        this.afterSettle = Objects.requireNonNull(afterSettle, "afterSettle cannot be null");
        this.executor = daemonExecutor("compaction-on-demand");
        this.lexicalExecutor = lexicalCompactor != null
            ? daemonExecutor("compaction-on-demand-lexical")
            : null;
    }

    /**
     * A single daemon thread under the given name.
     *
     * @param name The thread's name
     * @return The executor
     */
    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
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
        synchronized (lock) {
            // The run in progress and the published state change together, under this lock, so
            // a caller that polled the status until it read "completed" and posted again is
            // always answered with a new run - never 409 from a run that had already said it
            // was finished.
            if (current != null) {
                return null;
            }
            long startedAtMs = System.currentTimeMillis();
            Run run = new Run(force, startedAtMs);
            try {
                executor.execute(() -> execute(run));
            } catch (RejectedExecutionException e) {
                // A rejected task would otherwise leave the claim held and every later run
                // answered 409 for the life of the process.
                snapshot = new Snapshot(STATE_FAILED, force, startedAtMs,
                    System.currentTimeMillis(), null, "Compaction could not be scheduled: "
                    + e.getMessage(), null);
                throw e;
            }
            current = run;
            Snapshot started =
                new Snapshot(STATE_RUNNING, force, startedAtMs, 0L, null, null, null);
            snapshot = started;
            return started;
        }
    }

    /**
     * Returns what the current or last run has to say.
     *
     * @return The snapshot, never null
     */
    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Asks the run in progress to stop.
     *
     * <p>A request rather than a stop, in the same sense {@code DELETE /v1/reindex} is: the
     * run ends at its next boundary, returns what it had reclaimed by then and reports itself
     * {@link #STATE_CANCELLED}. It is safe at any point - a rewrite builds the replacement
     * beside the original and swaps it in only at the end, so what a stopped run loses is its
     * own work and never data. A run still waiting for the pool thread when it is cancelled
     * reports itself cancelled without compacting anything.</p>
     *
     * <p>Why it exists: a forced compaction rewrites every live segment and rebuilds every
     * graph, which on a large corpus is minutes of saturated I/O. Without it, the only way to
     * end one started at the wrong time would be to stop the process - the more dangerous act
     * of the two, since shutdown closes the arena the segments are mapped out of.</p>
     *
     * @return true if a run was in progress and has been asked to stop
     */
    public boolean cancel() {
        if (!requestStop()) {
            return false;
        }
        LOGGER.info("On-demand compaction was asked to stop");
        return true;
    }

    /**
     * Asks the run in progress, if there is one, to stop.
     *
     * <p>Only through its stop request, which both halves read at their own boundaries. A run
     * still queued learns of it when it begins.</p>
     *
     * @return true if there was a run to ask
     */
    private boolean requestStop() {
        synchronized (lock) {
            Run run = current;
            if (run == null) {
                return false;
            }
            run.cancelRequested = true;
            return true;
        }
    }

    /**
     * Carries out one run on the pool thread and settles its outcome.
     *
     * @param run The run
     */
    private void execute(Run run) {
        boolean begun;
        synchronized (lock) {
            begun = !run.cancelRequested;
            if (!begun) {
                // Cancelled while it waited for the thread. It still ends, and says so: a run
                // that never reported would leave the status "running" and refuse every later
                // start.
                settle(run, new Snapshot(STATE_CANCELLED, run.force, run.startedAtMs,
                    System.currentTimeMillis(), null, null,
                    lexicalCompactor == null
                        ? null
                        : LexicalOutcome.skipped(LEXICAL_SKIPPED_ON_CANCEL)));
            }
        }
        Snapshot outcome = null;
        try {
            if (begun) {
                outcome = perform(run);
            } else {
                LOGGER.info("On-demand compaction was cancelled before it began");
            }
        } finally {
            if (begun) {
                synchronized (lock) {
                    // Null only when perform() ended in a throwable of its own; the run still
                    // has to end, or it holds the claim for the life of the process.
                    settle(run, outcome != null
                        ? outcome
                        : new Snapshot(STATE_FAILED, run.force, run.startedAtMs,
                            System.currentTimeMillis(), null, "Compaction ended unexpectedly",
                            null));
                }
            }
            // Nothing here interrupts a run, but a flag left set by anything else would carry
            // into the next run on this thread and close the first channel it touches, for a
            // reason nothing in its own request explains.
            Thread.interrupted();
            afterSettle.run();
        }
    }

    /**
     * Publishes a run's outcome and releases its claim. Caller holds {@link #lock}.
     *
     * @param run     The run that ended
     * @param outcome What it came to
     */
    private void settle(Run run, Snapshot outcome) {
        snapshot = outcome;
        // By identity: only the run that holds the claim may release it.
        if (current == run) {
            current = null;
        }
    }

    /**
     * Performs one run.
     *
     * @param run The run
     * @return Its outcome
     */
    private Snapshot perform(Run run) {
        CompactionReport report = null;
        String error = null;
        segmentHalfRunning = true;
        try {
            report = compactor.compact(run.force, () -> run.cancelRequested);
        } catch (RuntimeException | Error e) {
            // Recorded, not just logged. The operator who asked for this run reads the
            // status, and a failure that reaches only the log reads there as a run that
            // finished having reclaimed nothing.
            error = String.valueOf(e.getMessage());
            LOGGER.error("On-demand compaction of the segment store failed", e);
        } finally {
            segmentHalfRunning = false;
        }
        LexicalOutcome lexical = compactLexical(run);
        boolean cancelled = run.cancelRequested;
        String state = error != null
            ? STATE_FAILED
            : cancelled ? STATE_CANCELLED : STATE_COMPLETED;
        LOGGER.info("On-demand compaction {}: {}; chunk index: {}",
            error != null ? "failed" : cancelled ? "stopped early" : "finished",
            report, lexical);
        return new Snapshot(state, run.force, run.startedAtMs, System.currentTimeMillis(),
            report, error, lexical);
    }

    /**
     * Compacts the chunk index on its own thread and waits for it to end.
     *
     * <p>The wait does not end on an interrupt: a rewrite left running with nobody waiting for
     * it would report nothing, and one that overlapped the next run would be refused by the index
     * as already in progress. An interrupt is taken as the run's stop request instead, which the
     * rewrite reads at its own boundaries, and is not left set on the run's thread.</p>
     *
     * @param run The run
     * @return What came of it, or null when this node keeps no chunk index
     */
    private LexicalOutcome compactLexical(Run run) {
        if (lexicalCompactor == null) {
            return null;
        }
        if (run.cancelRequested) {
            return LexicalOutcome.skipped(LEXICAL_SKIPPED_ON_CANCEL);
        }
        Future<CompactionResult> rewrite;
        try {
            rewrite = lexicalExecutor.submit(
                () -> lexicalCompactor.compact(run.force, () -> run.cancelRequested));
        } catch (RejectedExecutionException e) {
            // A drain shuts the executor down after asking the run to stop, so a run that read
            // its stop request as unset just before the drain and was refused just after is a
            // cancelled run, and says so.
            if (run.cancelRequested) {
                return LexicalOutcome.skipped(LEXICAL_SKIPPED_ON_CANCEL);
            }
            return LexicalOutcome.failed("Chunk index compaction could not be scheduled: "
                + e.getMessage());
        }
        while (true) {
            try {
                return LexicalOutcome.of(rewrite.get());
            } catch (InterruptedException e) {
                // Nothing in this runner interrupts a run; an interrupt from elsewhere is taken
                // as the stop it conventionally means, and cleared rather than carried on.
                run.cancelRequested = true;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                LOGGER.error("On-demand compaction of the chunk index failed", cause);
                return LexicalOutcome.failed(String.valueOf(cause.getMessage()));
            }
        }
    }

    /**
     * Stops accepting runs, asks one in progress to stop, and waits for it to end.
     *
     * <p>Asked at once, cooperatively, and before either executor stops taking work: a run that
     * finds itself refused by a shut-down executor must already be able to see that it was asked
     * to stop, or it reports a chunk index that "could not be scheduled" rather than a run that
     * was cancelled. The segment store's half stops before its next segment or graph, the chunk
     * index at its next boundary, and a run not yet past the segment store skips the index,
     * because a rewrite of the whole index can outlast any shutdown budget. Neither is
     * interrupted, for the reason the class documentation gives. What is lost is the run's own
     * work, never data - every rewrite builds its replacement beside the original and swaps it
     * in at the end.</p>
     *
     * <p>Waiting is the point of this method. The caller closes the storage engine and its arena
     * next, and a rewrite still copying entries into a mapping whose arena has closed takes the
     * JVM down rather than throwing. So a stop that the run has not yet acted on is not a drain,
     * and reporting one would be the same lie as reporting an erasure that did not happen. What
     * is reported is split the way the caller acts on it: the segment store's half, or a chunk
     * index rewrite that reads text back out of storage, holds storage; any chunk index rewrite
     * still running holds the chunk index.</p>
     *
     * <p>One wait covers both halves, because the caller's budget does: a node's shutdown
     * passes what its budget leaves the drains, and the chunk index gets whatever the segment
     * store's half did not use. The segment store's half reads the stop before each segment, so
     * a stopped run needs about one segment's rewrite.</p>
     *
     * @param wait How long to wait for both halves of a run asked to stop to end
     * @return What is still running, as storage and the chunk index see it
     * @throws NullPointerException if wait is null
     */
    public Drained drain(Duration wait) {
        Objects.requireNonNull(wait, "wait must not be null");
        requestStop();
        // shutdown, never shutdownNow: both halves are told to stop, not interrupted.
        executor.shutdown();
        if (lexicalExecutor != null) {
            lexicalExecutor.shutdown();
        }
        boolean runEnded;
        boolean lexicalEnded;
        // Wraps rather than saturates for a wait near Long.MAX_VALUE nanoseconds; the
        // subtraction below undoes it, so what is left is still the wait less the time spent.
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            runEnded = executor.awaitTermination(wait.toNanos(), TimeUnit.NANOSECONDS);
            lexicalEnded = lexicalExecutor == null || lexicalExecutor.awaitTermination(
                Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runEnded = executor.isTerminated();
            lexicalEnded = lexicalExecutor == null || lexicalExecutor.isTerminated();
        }
        if (!runEnded && segmentHalfRunning) {
            LOGGER.error("Compaction of the segment store did not stop within {}ms of being "
                + "asked to; the storage it rewrites must stay open", wait.toMillis());
        }
        if (!lexicalEnded) {
            LOGGER.error("Compaction of the chunk index did not stop within {}ms of being asked "
                + "to; the index's files must stay open", wait.toMillis());
        }
        // The run's thread outliving the wait holds storage only while it is inside the segment
        // store's half; past it, it is waiting on the chunk index and nothing else.
        boolean segmentsReleased = runEnded || !segmentHalfRunning;
        boolean lexicalReadsStorage = lexicalCompactor != null && lexicalCompactor.readsStorage();
        return new Drained(segmentsReleased && (lexicalEnded || !lexicalReadsStorage),
            lexicalEnded);
    }

    /**
     * As {@link #drain(Duration)}, waiting for as long as a run takes to stop, for an owner with
     * no deadline and no use for the answer - the way {@code ExecutorService.close} waits. Only
     * an interrupt ends the wait early, and the drain logs what that left running.
     */
    @Override
    public void close() {
        drain(UNTIL_STOPPED);
    }

}
