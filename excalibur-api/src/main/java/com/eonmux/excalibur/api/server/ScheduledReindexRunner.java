package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.endpoint.ReindexEndpoint;
import com.eonmux.excalibur.api.endpoint.ReindexRun;
import com.eonmux.excalibur.api.reindex.ReindexCoordinator;
import com.eonmux.excalibur.core.util.StopSignal;
import com.eonmux.excalibur.shard.ReindexOperationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Executes one scheduled reindex under the shared reindex claim.
 *
 * <p>The scheduler and {@code POST /v1/reindex} are two independent triggers for
 * the same operation. Both take the same per-source claim from
 * {@link ReindexCoordinator}, because two rolling replacements of one source run
 * concurrently will each tombstone the segment the other has just published.</p>
 *
 * <p>They also run the same {@link ReindexRun}. What a reindex does, how it decides between
 * rolling replacement and publishing in place, and what it reports to the two status readbacks
 * are properties of the operation rather than of what triggered it - and a scheduled re-crawl
 * that reported none of them left an operator with no record that it had happened at all.</p>
 *
 * <h2>Stopping</h2>
 * <p>A scheduled run registers a stop signal on its claim, as a requested one does, so
 * {@code DELETE /v1/reindex} and a shutdown's drain stop it the same way: between documents,
 * and never by interrupting the scheduler's thread, which would close whatever shared channel
 * the run was writing through at that moment.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every field is final and the mutual exclusion that matters - one reindex per
 * source - is the coordinator's, which the scheduler may call this from any thread against.</p>
 *
 * @author ExcaliburDB
 */
final class ScheduledReindexRunner implements ReindexOperationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledReindexRunner.class);

    private final ReindexCoordinator coordinator;
    private final ReindexRun run;
    private final ReindexEndpoint.ReindexStatusRegistry statusRegistry;

    /**
     * Creates a runner for the scheduler to call.
     *
     * @param coordinator    The claim both reindex triggers take
     * @param run            The reindex itself, shared with {@code POST /v1/reindex}
     * @param statusRegistry Where a run is opened, so the readback reports a scheduled re-crawl
     *                       from its first moment rather than only once it has ended
     * @throws NullPointerException if any parameter is null
     */
    ScheduledReindexRunner(ReindexCoordinator coordinator,
                           ReindexRun run,
                           ReindexEndpoint.ReindexStatusRegistry statusRegistry) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator cannot be null");
        this.run = Objects.requireNonNull(run, "run cannot be null");
        this.statusRegistry =
            Objects.requireNonNull(statusRegistry, "statusRegistry cannot be null");
    }

    @Override
    public boolean executeReindex(String sourceId) {
        ReindexCoordinator.Lease lease = coordinator.tryAcquire(sourceId);
        if (lease == null) {
            LOGGER.info("Scheduled reindex skipped: {}", coordinator.isAccepting()
                ? "source " + sourceId + " is already being reindexed"
                : "the node is shutting down");
            return false;
        }
        try {
            StopSignal stop = new StopSignal();
            lease.onCancel(stop::request);
            statusRegistry.startReindex(sourceId);
            return run.execute(sourceId, stop);
        } catch (Exception e) {
            LOGGER.error("ReindexOperationHandler failed for source {}: {}", sourceId, e.getMessage());
            return false;
        } catch (Throwable t) {
            // The run records its own failures, an Error included, before rethrowing. One that
            // arrives while the run is still open - thrown between opening it and the run taking
            // over - is closed here, so the readback does not report a re-crawl in progress that
            // nothing is running. Rethrown: the scheduler's thread is the one to learn of it.
            failIfStillOpen(sourceId, t);
            throw t;
        } finally {
            lease.release();
            // Cleared before this thread goes back to the scheduler's pool. Nothing here sets
            // it, but an interrupt from elsewhere that arrived after the run's last check was
            // addressed to the run that has just ended. Carried away, it fails the next task
            // scheduled onto this thread at its first interruptible channel operation, which
            // closes that channel for good.
            Thread.interrupted();
        }
    }

    /**
     * Records a run as failed if it still reads as in progress.
     *
     * @param sourceId The source whose run ended
     * @param cause    What ended it, to which a failure to record is attached
     */
    private void failIfStillOpen(String sourceId, Throwable cause) {
        try {
            ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus(sourceId);
            if (status != null && status.isInProgress()) {
                statusRegistry.failReindex(sourceId,
                    "Reindex failed (" + cause.getClass().getSimpleName() + ")");
            }
        } catch (Throwable recording) {
            cause.addSuppressed(recording);
        }
    }
}
