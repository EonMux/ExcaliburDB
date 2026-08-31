package excalibur.api.reindex;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Attempts to claim exclusive reindex rights for a source.
     *
     * @param sourceId The source to reindex
     * @return A lease that must be released when the reindex finishes, or null when
     *         a reindex of this source is already running
     */
    public Lease tryAcquire(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        if (!inFlight.add(sourceId)) {
            return null;
        }
        return new Lease(sourceId);
    }

    /**
     * Reports whether a reindex of the given source is currently running.
     *
     * @param sourceId The source
     * @return true when a lease is held
     */
    public boolean isRunning(String sourceId) {
        return inFlight.contains(sourceId);
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
        private volatile boolean released;

        private Lease(String sourceId) {
            this.sourceId = sourceId;
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
            if (!released) {
                released = true;
                inFlight.remove(sourceId);
            }
        }

        @Override
        public void close() {
            release();
        }
    }
}
