package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns "this source has gone stale" into a re-index, and tells the scheduler what came of it.
 *
 * <p>The scheduler raises a flag before it calls out and clears it only on being told the
 * attempt finished. Every path out of here therefore has to report something: an attempt that
 * escapes without marking the source complete or failed leaves that flag raised, and the
 * source is never scheduled again for the life of the process.</p>
 *
 * <p>The same reasoning is why an unconfigured handler reports failure rather than success.
 * Success would stamp the completion counter and the last-reindex time for work that never
 * ran, resetting the staleness clock and hiding the missing wiring behind a healthy-looking
 * metric; failure clears the in-progress flag, lets the scheduler back off, and leaves the
 * source visibly stale.</p>
 */
final class ReindexDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexDispatcher.class);

    private final SegmentReindexScheduler scheduler;

    /**
     * Where re-indexes are actually executed, or null when nothing is wired up.
     */
    private volatile ReindexOperationHandler handler;

    /**
     * Creates the dispatcher for one shard's re-index scheduler.
     *
     * @param scheduler The scheduler whose in-progress flags this dispatcher clears
     */
    ReindexDispatcher(SegmentReindexScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Called when a re-index is needed for a source.
     *
     * <p>This method triggers the re-index operation and properly manages
     * the reindexInProgress flag by calling markReindexComplete() on success
     * or markReindexFailed() on failure. This ensures subsequent scheduled
     * reindexes are not permanently blocked.</p>
     *
     * <p>If a {@link ReindexOperationHandler} is configured via
     * {@link #setHandler(ReindexOperationHandler)}, this method
     * will delegate to it. Otherwise, it logs a warning and completes without
     * performing any actual reindexing.</p>
     *
     * @param sourceId The source ID that has gone stale
     * @return true if the re-index ran and succeeded
     */
    boolean onReindexNeeded(String sourceId) {
        LOGGER.info("Re-index needed for source {}", sourceId);
        try {
            // Trigger the ingestion pipeline for reindex
            boolean success = triggerReindexOperation(sourceId);

            if (success) {
                // Mark as complete to allow future scheduled reindexes
                scheduler.markReindexComplete(sourceId);
                LOGGER.info("Re-index completed successfully for source {}", sourceId);
            } else {
                scheduler.markReindexFailed(sourceId, "Reindex returned failure");
            }
            return success;
        } catch (Exception e) {
            LOGGER.error("Re-index failed for source {}: {}", sourceId, e.getMessage());
            scheduler.markReindexFailed(sourceId, e.getMessage());
            return false;
        }
    }

    /**
     * Triggers the actual re-index operation for a source.
     *
     * <p>If a {@link ReindexOperationHandler} is configured, this method delegates to it.
     * Without one, no document is touched, so this reports failure: reporting success
     * would stamp {@code lastReindexTime} and the completed counter for a re-index that
     * never ran, which resets the source's staleness clock and hides the missing wiring.
     * The failure path still clears {@code reindexInProgress}, so future scheduled
     * re-indexes are not blocked.</p>
     *
     * @param sourceId The source ID to re-index
     * @return true if reindex succeeded, false otherwise
     */
    private boolean triggerReindexOperation(String sourceId) {
        // If handler is not configured, the reindex cannot run
        if (handler == null) {
            LOGGER.warn("Reindex triggered for source {} but ReindexOperationHandler not configured. " +
                "Reindex will not execute. Configure via setReindexOperationHandler().", sourceId);
            return false;
        }

        // Delegate to the handler
        LOGGER.debug("Executing reindex operation for source {} via ReindexOperationHandler", sourceId);
        try {
            return handler.executeReindex(sourceId);
        } catch (Exception e) {
            LOGGER.error("ReindexOperationHandler threw exception for source {}: {}", sourceId, e.getMessage());
            return false;
        }
    }

    /**
     * Sets the handler scheduled re-indexes are executed through.
     *
     * @param handler The reindex operation handler, or null to disable
     */
    void setHandler(ReindexOperationHandler handler) {
        this.handler = handler;
    }

    /**
     * Returns the configured handler.
     *
     * @return The handler, or null if not configured
     */
    ReindexOperationHandler handler() {
        return handler;
    }
}
