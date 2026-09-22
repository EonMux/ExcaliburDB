package com.eonmux.excalibur.api.reindex;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Acquisition is a single atomic map operation, so
 * there is no window between "is it running?" and "mark it running".</p>
 *
 * @author ExcaliburDB
 */
public final class ReindexCoordinator {

    private final Map<String, Lease> inFlight = new ConcurrentHashMap<>();

    /**
     * Attempts to claim exclusive reindex rights for a source.
     *
     * @param sourceId The source to reindex
     * @return A lease that must be released when the reindex finishes, or null when
     *         a reindex of this source is already running
     */
    public Lease tryAcquire(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        Lease lease = new Lease(sourceId);
        if (inFlight.putIfAbsent(sourceId, lease) != null) {
            return null;
        }
        return lease;
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
         * <p>Set by whoever starts the run, once it has something to interrupt. A cancellation
         * that arrives before then is remembered rather than lost: {@link #isCancelled()} is
         * already true, and the canceller registered afterwards is run immediately.</p>
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
            }
        }

        @Override
        public void close() {
            release();
        }
    }
}
