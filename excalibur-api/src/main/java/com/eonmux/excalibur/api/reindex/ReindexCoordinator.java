package com.eonmux.excalibur.api.reindex;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single mutual-exclusion primitive for reindex operations.
 *
 * <p>A source can be reindexed from two places: {@code POST /v1/reindex} and the
 * scheduled {@code ReindexOperationHandler} the orchestrator drives. Both go through
 * this coordinator, so a timer tick and an HTTP request can never start two rolling
 * replacements of the same source. Two concurrent rolling replacements each tombstone
 * the segments the other has just published, which loses data.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (ReindexCoordinator.Lease lease = coordinator.tryAcquire(sourceId)) {
 *     if (lease == null) {
 *         return alreadyRunning();
 *     }
 *     reindex(sourceId);
 * }
 * }</pre>
 *
 * <p>A lease that is handed to a background task must be released by that task, not
 * by the thread that acquired it.</p>
 *
 * <h2>Shutdown</h2>
 * <p>The coordinator is also the one place that sees every reindex in flight, whichever
 * trigger started it, so it is what shutdown drains: {@link #stopAccepting()} refuses new
 * claims, {@link #cancelAll()} asks every run to stop, and {@link #awaitIdle(long)} says whether
 * they all ended in time. A run is asked through the canceller it registered, which is expected
 * to be cooperative - see {@link Lease#onCancel(Runnable)}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Acquisition is a single atomic map operation, so
 * there is no window between "is it running?" and "mark it running".</p>
 *
 * @author ExcaliburDB
 */
public final class ReindexCoordinator {

    private final Map<String, Lease> inFlight = new ConcurrentHashMap<>();

    /** Notified whenever a claim is released, for {@link #awaitIdle(long)}. */
    private final Object releaseMonitor = new Object();

    /** Set once by {@link #stopAccepting()}; no claim is granted afterwards. */
    private volatile boolean accepting = true;

    /**
     * Attempts to claim exclusive reindex rights for a source.
     *
     * @param sourceId The source to reindex
     * @return A lease that must be released when the reindex finishes, or null when
     *         a reindex of this source is already running or the node is shutting down;
     *         {@link #isAccepting()} tells the two apart
     */
    public Lease tryAcquire(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        if (!accepting) {
            return null;
        }
        Lease lease = new Lease(sourceId);
        if (inFlight.putIfAbsent(sourceId, lease) != null) {
            return null;
        }
        // Checked again once the claim is published, so a claim racing stopAccepting() is
        // either seen by a later cancelAll() or withdrawn here - never granted unseen.
        if (!accepting) {
            lease.release();
            return null;
        }
        return lease;
    }

    /**
     * Whether new claims are still granted.
     *
     * @return false once shutdown has begun
     */
    public boolean isAccepting() {
        return accepting;
    }

    /**
     * Refuses every claim from now on.
     *
     * <p>The first step of a shutdown: a reindex that starts after the drain has looked would
     * be writing into storage while it is closed. Irreversible, like the shutdown it serves.</p>
     */
    public void stopAccepting() {
        accepting = false;
    }

    /**
     * Asks every reindex in flight to stop.
     *
     * <p>As {@link #cancel(String)} for each: a request, not a release.</p>
     *
     * @return How many runs were asked
     */
    public int cancelAll() {
        int asked = 0;
        for (Lease lease : inFlight.values()) {
            lease.cancel();
            asked++;
        }
        return asked;
    }

    /**
     * Waits until no reindex holds a claim.
     *
     * @param timeoutMillis How long to wait at most
     * @return true if none is running; false if one still was when the time ran out
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (releaseMonitor) {
            while (!inFlight.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(releaseMonitor, remaining);
            }
            return true;
        }
    }

    /**
     * Asks a running reindex to stop.
     *
     * <h3>What this does not do</h3>
     * <p>It does not release the claim. A rolling replacement that has been asked to stop is
     * still running until it stops, and handing the source to a second replacement in the
     * meantime is the concurrent-replacement data loss this coordinator exists to prevent - each
     * tombstones the segments the other has just published. The run releases its own claim when
     * it actually ends, cancelled or not.</p>
     *
     * <p>Stopping is a request rather than a guarantee, for the same reason cancelling any
     * running work is: the run stops at its next checkpoint, and how long that takes depends on
     * what it is doing.</p>
     *
     * @param sourceId The source whose reindex should stop
     * @return true when a reindex of this source was running and has been asked to stop
     */
    public boolean cancel(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        Lease lease = inFlight.get(sourceId);
        if (lease == null) {
            return false;
        }
        lease.cancel();
        return true;
    }

    /**
     * Reports whether a reindex of the given source is currently running.
     *
     * @param sourceId The source
     * @return true when a lease is held
     */
    public boolean isRunning(String sourceId) {
        return inFlight.containsKey(sourceId);
    }

    /**
     * Returns the number of reindex operations currently running.
     *
     * @return In-flight reindex count
     */
    public int inFlightCount() {
        return inFlight.size();
    }

    /**
     * An exclusive claim on one source's reindex.
     *
     * <p>Releasing is idempotent, so a lease may be closed by a try-with-resources
     * block and again by a completion callback without double-releasing the source.</p>
     */
    public final class Lease implements AutoCloseable {

        private final String sourceId;
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean cancellerRun = new AtomicBoolean();
        private volatile Runnable canceller;

        private Lease(String sourceId) {
            this.sourceId = sourceId;
        }

        /**
         * Registers how this reindex is stopped.
         *
         * <p>Set by whoever starts the run, once it has something to ask. A cancellation that
         * arrives before then is remembered rather than lost: {@link #isCancelled()} is already
         * true, and the canceller registered afterwards is run immediately.</p>
         *
         * <p>The canceller should request the run's
         * {@link com.eonmux.excalibur.core.util.StopSignal} rather than interrupt its thread. A
         * reindex spends its time writing through channels every writer of a segment shares,
         * and an interrupt that reaches one of those writes closes the channel for all of them
         * for the life of the process.</p>
         *
         * @param canceller Asks the run to stop; must be safe to call from another thread
         */
        public void onCancel(Runnable canceller) {
            Objects.requireNonNull(canceller, "canceller cannot be null");
            this.canceller = canceller;
            // Only if cancel() did not already run one. Without the claim, a cancellation that
            // raced this registration is run by both sides, and a canceller that is not
            // idempotent is then run twice for one cancellation.
            if (cancelled.get() && cancellerRun.compareAndSet(false, true)) {
                canceller.run();
            }
        }

        /**
         * Whether this reindex has been asked to stop.
         *
         * <p>A run that checks this between documents stops promptly without needing to be
         * interruptible at whatever it happens to be blocked on.</p>
         *
         * @return true once cancellation has been requested
         */
        public boolean isCancelled() {
            return cancelled.get();
        }

        /** Asks the run to stop. The canceller is run at most once for the lease's life. */
        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            Runnable toRun = canceller;
            if (toRun != null && cancellerRun.compareAndSet(false, true)) {
                toRun.run();
            }
        }

        /**
         * Returns the source this lease covers.
         *
         * @return The source ID
         */
        public String sourceId() {
            return sourceId;
        }

        /**
         * Releases the claim.
         */
        public void release() {
            if (released.compareAndSet(false, true)) {
                // Keyed removal: a later reindex of the same source holds a different lease, and
                // a stale release must not drop a claim it does not own.
                inFlight.remove(sourceId, this);
                synchronized (releaseMonitor) {
                    releaseMonitor.notifyAll();
                }
            }
        }

        @Override
        public void close() {
            release();
        }
    }
}
