package com.eonmux.excalibur.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SegmentLifecycleManager monitors and manages segment lifecycle state transitions.
 *
 * <p>This background worker runs in a virtual thread and periodically checks segments
 * to determine if they should transition to new lifecycle states based on configurable
 * criteria.</p>
 *
 * <h2>Lifecycle State Machine</h2>
 * <pre>
 * BOOTSTRAPPING → INDEXING → WARMING → ACTIVE ⟷ COMPACTING → TOMBSTONED → PURGED
 * </pre>
 *
 * <h2>Transition Criteria</h2>
 * <ul>
 *   <li><strong>INDEXING → WARMING:</strong> When indexing batch is complete
 *       (signaled via {@link #completeIndexing(long)})</li>
 *   <li><strong>WARMING → ACTIVE:</strong> When optimization is complete
 *       (signaled via {@link #completeWarmup(long)})</li>
 *   <li><strong>ACTIVE → COMPACTING:</strong> When tombstone threshold is exceeded</li>
 *   <li><strong>COMPACTING → ACTIVE:</strong> When compaction is complete
 *       (signaled via {@link #completeCompaction(long)})</li>
 *   <li><strong>ACTIVE/COMPACTING → TOMBSTONED:</strong> When segment is marked for deletion
 *       (signaled via {@link #tombstone(long)})</li>
 *   <li><strong>TOMBSTONED → PURGED:</strong> When all in-flight queries have drained
 *       (epoch-based reclamation)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. All state transitions are synchronized
 * through the {@link SegmentCatalog}.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public class SegmentLifecycleManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SegmentLifecycleManager.class);

    /**
     * Default interval for lifecycle checks (5 seconds).
     */
    public static final long DEFAULT_CHECK_INTERVAL_MS = 5000;

    /**
     * Default tombstone threshold ratio for triggering compaction (0.3 = 30% tombstones).
     */
    public static final double DEFAULT_TOMBSTONE_THRESHOLD = 0.3;

    /**
     * Default minimum entries before compaction is considered.
     */
    public static final int DEFAULT_MIN_ENTRIES_FOR_COMPACTION = 100;

    /**
     * Default epoch lag threshold for reclamation (number of epochs).
     * A segment can be purged when the current epoch is this many epochs
     * past the tombstone epoch.
     */
    public static final long DEFAULT_EPOCH_LAG_THRESHOLD = 10;

    private final SegmentCatalog catalog;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final long checkIntervalMs;
    private final double tombstoneThreshold;
    private final int minEntriesForCompaction;
    private final long epochLagThreshold;

    /**
     * Wall-clock grace period applied to tombstoned segments when no visibility epoch
     * source is available.
     */
    private static final long LEGACY_RECLAMATION_GRACE_MS = TimeUnit.MINUTES.toMillis(5);

    // Callbacks for external integration (e.g., HNSW, Cuckoo filter)
    private volatile CompactionCallback compactionCallback;
    private volatile ReclamationCallback reclamationCallback;

    /**
     * Answers, for a segment still in {@link LifecycleState#INDEXING}, whether its index has
     * absorbed everything written to it. Supplied by the owning engine, which is the only
     * component that holds both the segment's write log and its integration checkpoint.
     * When null no segment is advanced out of {@code INDEXING}.
     */
    private volatile IndexingCompletionProbe indexingCompletionProbe;

    /**
     * Source of the current visibility epoch, injected by the owning engine.
     * When null the scheduled reclamation check falls back to a wall-clock grace period.
     */
    private volatile java.util.function.LongSupplier currentEpochSupplier;

    /**
     * Callback interface for compaction operations.
     */
    @FunctionalInterface
    public interface CompactionCallback {
        /**
         * Called when a segment should be compacted.
         *
         * @param segmentId The segment to compact
         * @return true if compaction was initiated successfully
         */
        boolean onCompactionNeeded(long segmentId);
    }

    /**
     * Callback interface for reclamation operations.
     */
    @FunctionalInterface
    public interface ReclamationCallback {
        /**
         * Called when a segment can be purged.
         *
         * @param segmentId The segment to purge
         * @return true if the segment was purged successfully
         */
        boolean onReclamationReady(long segmentId);
    }

    /**
     * Reports whether a segment's index has caught up with the segment.
     *
     * <p>{@code INDEXING} is the one lifecycle state this manager cannot leave on evidence the
     * catalog holds: the catalog records how many entries a segment has, not how much of its
     * write log the HNSW graph and pre-filter have absorbed. The owning engine supplies that
     * answer through this probe.</p>
     */
    @FunctionalInterface
    public interface IndexingCompletionProbe {
        /**
         * Called for each segment in {@link LifecycleState#INDEXING}.
         *
         * @param segmentId The segment to judge
         * @return true if the segment's index speaks for every entry the segment holds
         */
        boolean isIndexingComplete(long segmentId);
    }

    /**
     * Creates a new SegmentLifecycleManager with default settings.
     *
     * @param catalog The segment catalog to manage
     */
    public SegmentLifecycleManager(SegmentCatalog catalog) {
        this(catalog, DEFAULT_CHECK_INTERVAL_MS, DEFAULT_TOMBSTONE_THRESHOLD,
             DEFAULT_MIN_ENTRIES_FOR_COMPACTION, DEFAULT_EPOCH_LAG_THRESHOLD);
    }

    /**
     * Creates a new SegmentLifecycleManager with custom settings.
     *
     * @param catalog The segment catalog to manage
     * @param checkIntervalMs Interval between lifecycle checks in milliseconds
     * @param tombstoneThreshold Fraction of tombstones that triggers compaction (0.0-1.0)
     * @param minEntriesForCompaction Minimum entries before compaction is considered
     * @param epochLagThreshold Number of epochs to wait before reclaiming tombstoned segments
     */
    public SegmentLifecycleManager(SegmentCatalog catalog, long checkIntervalMs,
                                   double tombstoneThreshold, int minEntriesForCompaction,
                                   long epochLagThreshold) {
        if (catalog == null) {
            throw new IllegalArgumentException("Catalog cannot be null");
        }
        if (checkIntervalMs <= 0) {
            throw new IllegalArgumentException("Check interval must be positive");
        }
        if (tombstoneThreshold < 0 || tombstoneThreshold > 1) {
            throw new IllegalArgumentException("Tombstone threshold must be between 0 and 1");
        }
        if (minEntriesForCompaction < 0) {
            throw new IllegalArgumentException("Min entries for compaction cannot be negative");
        }
        if (epochLagThreshold < 0) {
            throw new IllegalArgumentException("Epoch lag threshold cannot be negative");
        }

        this.catalog = catalog;
        this.checkIntervalMs = checkIntervalMs;
        this.tombstoneThreshold = tombstoneThreshold;
        this.minEntriesForCompaction = minEntriesForCompaction;
        this.epochLagThreshold = epochLagThreshold;

        // Use virtual thread executor
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().factory());
    }

    /**
     * Sets the callback for compaction operations.
     *
     * @param callback The compaction callback
     */
    public void setCompactionCallback(CompactionCallback callback) {
        this.compactionCallback = callback;
    }

    /**
     * Sets the probe consulted for segments in {@link LifecycleState#INDEXING}.
     *
     * @param probe The probe, or null to leave indexing segments where they are
     */
    public void setIndexingCompletionProbe(IndexingCompletionProbe probe) {
        this.indexingCompletionProbe = probe;
    }

    /**
     * Sets the reclamation callback.
     *
     * @param callback The callback to invoke when a segment can be purged
     */
    public void setReclamationCallback(ReclamationCallback callback) {
        this.reclamationCallback = callback;
    }

    /**
     * Starts the lifecycle manager.
     *
     * <p>After starting, the manager will periodically check segments and
     * trigger appropriate lifecycle transitions.</p>
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(
                this::performLifecycleCheck,
                checkIntervalMs,
                checkIntervalMs,
                TimeUnit.MILLISECONDS
            );
            logger.info("SegmentLifecycleManager started with check interval {}ms", checkIntervalMs);
        }
    }

    /**
     * Stops the lifecycle manager and says whether a check in progress ended.
     *
     * <p>A check is waited for and never interrupted. It moves segments through the catalog,
     * which writes the catalog to disk, and purges reclaimable segments, which closes their
     * files and deletes them; an interrupt landing inside one of those writes fails it with the
     * transition half-recorded, and gains nothing, because a check holds no wait an interrupt
     * could cut short. A check that outlives the wait is reported, since the caller closes the
     * storage the check is working on next.</p>
     *
     * @return true if no lifecycle check is still running
     */
    public boolean stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("A segment lifecycle check was still running 5s after the "
                        + "manager was asked to stop");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            logger.info("SegmentLifecycleManager stopped");
        }
        return !scheduler.isShutdown() || scheduler.isTerminated();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Checks if the lifecycle manager is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Lifecycle Transition Methods ====================

    /**
     * Signals that indexing is complete for a segment.
     *
     * <p>This transitions the segment from INDEXING to WARMING state.</p>
     *
     * @param segmentId The segment ID
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in INDEXING state
     */
    public void completeIndexing(long segmentId) {
        logger.debug("Completing indexing for segment {}", segmentId);
        catalog.completeIndexing(segmentId);
        logger.info("Segment {} transitioned from INDEXING to WARMING", segmentId);
    }

    /**
     * Signals that warmup is complete for a segment.
     *
     * <p>This transitions the segment from WARMING to ACTIVE state.</p>
     *
     * @param segmentId The segment ID
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in WARMING state
     */
    public void completeWarmup(long segmentId) {
        logger.debug("Completing warmup for segment {}", segmentId);
        catalog.completeWarmup(segmentId);
        logger.info("Segment {} transitioned from WARMING to ACTIVE", segmentId);
    }

    /**
     * Begins compaction for a segment.
     *
     * <p>This transitions the segment from ACTIVE to COMPACTING state.</p>
     *
     * @param segmentId The segment ID
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in ACTIVE state
     */
    public void beginCompaction(long segmentId) {
        logger.debug("Beginning compaction for segment {}", segmentId);
        catalog.beginCompaction(segmentId);
        logger.info("Segment {} transitioned from ACTIVE to COMPACTING", segmentId);
    }

    /**
     * Signals that compaction is complete for a segment.
     *
     * <p>This transitions the segment from COMPACTING to ACTIVE state.</p>
     *
     * @param segmentId The segment ID
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in COMPACTING state
     */
    public void completeCompaction(long segmentId) {
        logger.debug("Completing compaction for segment {}", segmentId);
        catalog.completeCompaction(segmentId);
        logger.info("Segment {} transitioned from COMPACTING to ACTIVE", segmentId);
    }

    /**
     * Marks a segment as tombstoned.
     *
     * <p>This transitions the segment from ACTIVE or COMPACTING to TOMBSTONED state.</p>
     *
     * @param segmentId The segment ID
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in a valid state for tombstoning
     */
    public void tombstone(long segmentId) {
        logger.debug("Tombstoning segment {}", segmentId);
        catalog.tombstone(segmentId);
        logger.info("Segment {} transitioned to TOMBSTONED", segmentId);
    }

    /**
     * Marks a segment as tombstoned with visibility epoch tracking.
     *
     * <p>This transitions the segment from ACTIVE or COMPACTING to TOMBSTONED state
     * and records the visibility epoch for epoch-based reclamation.</p>
     *
     * @param segmentId The segment ID
     * @param visibilityEpoch The current visibility epoch
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in a valid state for tombstoning
     */
    public void tombstone(long segmentId, long visibilityEpoch) {
        logger.debug("Tombstoning segment {} at epoch {}", segmentId, visibilityEpoch);
        catalog.tombstone(segmentId, visibilityEpoch);
        logger.info("Segment {} transitioned to TOMBSTONED at epoch {}", segmentId, visibilityEpoch);
    }

    /**
     * Purges a tombstoned segment.
     *
     * <p>This transitions the segment from TOMBSTONED to PURGED state and removes
     * it from the catalog. The segment must have been tombstoned for sufficient
     * time (based on epoch lag threshold) to ensure no in-flight queries are affected.</p>
     *
     * @param segmentId The segment ID
     * @throws IllegalArgumentException if segment not found
     * @throws IllegalStateException if segment is not in TOMBSTONED state
     */
    public void purge(long segmentId) {
        logger.debug("Purging segment {}", segmentId);
        catalog.remove(segmentId);
        logger.info("Segment {} transitioned to PURGED and removed from catalog", segmentId);
    }

    // ==================== Background Lifecycle Checks ====================

    /**
     * Performs a lifecycle check on all segments.
     *
     * <p>This method is called periodically by the scheduler.</p>
     */
    private void performLifecycleCheck() {
        try {
            checkForIndexingCompletion();
            checkForCompactionCandidates();
            checkForReclamationCandidates();
        } catch (Exception e) {
            logger.error("Error during lifecycle check", e);
        }
    }

    /**
     * Advances every segment whose index has caught up with it out of {@code INDEXING}.
     *
     * <p>A segment enters {@code INDEXING} the moment it is created and nothing else in the
     * engine takes it out again: the states after it are reached by workers that select on
     * {@code ACTIVE} or {@code WARMING} and so never see a segment that is still indexing.
     * Without this sweep a live-ingested segment stays partially indexed for the life of the
     * process, and compaction, PQ training and scheduled reindexing never run at all.</p>
     *
     * <p>Public so the sweep can be driven directly by a caller that does not want to wait for
     * the scheduler; the scheduled {@link #performLifecycleCheck()} calls it on every tick.</p>
     */
    public void checkForIndexingCompletion() {
        IndexingCompletionProbe probe = this.indexingCompletionProbe;
        if (probe == null) {
            return;
        }

        for (Long segmentId : catalog.getSegmentsByState(LifecycleState.INDEXING)) {
            try {
                if (probe.isIndexingComplete(segmentId)) {
                    completeIndexing(segmentId);
                }
            } catch (RuntimeException e) {
                // The probe reads live engine state and the catalog listing is a snapshot, so a
                // segment can be retired between the two. That is one segment's answer, not the
                // sweep's: letting it escape would retire the transition for every segment
                // behind it on this tick, and the next tick would fail the same way.
                logger.warn("Could not advance segment {} out of INDEXING: {}",
                    segmentId, e.getMessage());
            }
        }
    }

    /**
     * Checks for segments that need compaction.
     *
     * <p>A segment is a compaction candidate if:
     * <ul>
     *   <li>It is in ACTIVE state</li>
     *   <li>It has at least {@link #minEntriesForCompaction} entries</li>
     *   <li>Its tombstone ratio reaches {@link #tombstoneThreshold}</li>
     * </ul></p>
     */
    private void checkForCompactionCandidates() {
        List<Long> activeSegments = catalog.getSegmentsByState(LifecycleState.ACTIVE);

        for (Long segmentId : activeSegments) {
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata == null) {
                continue;
            }

            // Check if segment meets compaction criteria
            if (shouldCompact(metadata)) {
                CompactionCallback callback = this.compactionCallback;
                if (callback != null) {
                    boolean initiated = callback.onCompactionNeeded(segmentId);
                    if (initiated) {
                        logger.info("Initiated compaction for segment {}", segmentId);
                    }
                } else {
                    logger.debug("Segment {} is a compaction candidate but no callback registered", segmentId);
                }
            }
        }
    }

    /**
     * Determines if a segment should be compacted.
     *
     * @param metadata The segment metadata
     * @return true if the segment should be compacted
     */
    private boolean shouldCompact(SegmentCatalog.SegmentMetadata metadata) {
        if (metadata.getEntryCount() < minEntriesForCompaction) {
            return false;
        }

        // Compaction is only worth doing when there are tombstoned records to remove.
        // Scheduling on elapsed time alone re-compacts every segment on every tick,
        // rewriting whole segments that hold no tombstones at all.
        return metadata.tombstoneRatio() >= tombstoneThreshold;
    }

    /**
     * Checks for tombstoned segments that can be purged.
     *
     * <p>A segment can be purged if:
     * <ul>
     *   <li>It is in TOMBSTONED state</li>
     *   <li>The current epoch is at least {@link #epochLagThreshold} epochs
     *       past the tombstone epoch</li>
     * </ul></p>
     *
     * @param currentEpoch The current visibility epoch
     */
    public void checkForReclamationCandidates(long currentEpoch) {
        List<Long> tombstonedSegments = catalog.getSegmentsByState(LifecycleState.TOMBSTONED);

        for (Long segmentId : tombstonedSegments) {
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata == null) {
                continue;
            }

            long tombstoneEpoch = metadata.getTombstoneEpoch();
            if (tombstoneEpoch < 0) {
                // Tombstoned before epoch tracking: fall back to the wall-clock grace
                // period rather than either purging immediately or never purging.
                if (isPastGracePeriod(metadata)) {
                    attemptPurge(segmentId, tombstoneEpoch);
                }
                continue;
            }

            // Check if enough epochs have passed for safe reclamation
            if (currentEpoch - tombstoneEpoch >= epochLagThreshold) {
                ReclamationCallback callback = this.reclamationCallback;
                if (callback != null) {
                    boolean purged = callback.onReclamationReady(segmentId);
                    if (purged) {
                        logger.info("Purged segment {} (tombstoned at epoch {}, current epoch {})",
                            segmentId, tombstoneEpoch, currentEpoch);
                    }
                } else {
                    // If no callback, directly purge
                    try {
                        purge(segmentId);
                        logger.info("Purged segment {} (tombstoned at epoch {}, current epoch {})",
                            segmentId, tombstoneEpoch, currentEpoch);
                    } catch (Exception e) {
                        logger.warn("Failed to purge segment {}: {}", segmentId, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Checks for reclamation candidates on the scheduled tick.
     *
     * <p>When an epoch source has been wired via
     * {@link #setCurrentEpochSupplier(java.util.function.LongSupplier)}, this defers to
     * the epoch-based check. Without one, it falls back to a time-based grace period -
     * applied to <em>every</em> tombstoned segment, not only the ones that are missing
     * a tombstone epoch. Purging only the untagged ones is backwards: it reclaims
     * exactly the segments whose safety cannot be established and never reclaims the
     * ones that were properly tagged.</p>
     */
    private void checkForReclamationCandidates() {
        java.util.function.LongSupplier epochSource = this.currentEpochSupplier;
        if (epochSource != null) {
            checkForReclamationCandidates(epochSource.getAsLong());
            return;
        }

        List<Long> tombstonedSegments = catalog.getSegmentsByState(LifecycleState.TOMBSTONED);
        for (Long segmentId : tombstonedSegments) {
            SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
            if (metadata == null) {
                continue;
            }
            if (isPastGracePeriod(metadata)) {
                attemptPurge(segmentId, metadata.getTombstoneEpoch());
            }
        }
    }

    /**
     * Returns whether a tombstoned segment is past the wall-clock grace period.
     *
     * @param metadata The segment metadata
     * @return true if the segment may be purged without epoch information
     */
    private boolean isPastGracePeriod(SegmentCatalog.SegmentMetadata metadata) {
        long reference = metadata.getLastActiveTime() > 0
            ? metadata.getLastActiveTime()
            : metadata.getCreationTime();
        return System.currentTimeMillis() - reference > LEGACY_RECLAMATION_GRACE_MS;
    }

    /**
     * Sets the source of the current visibility epoch used for reclamation checks.
     *
     * <p>Without it, the scheduled reclamation check can only apply a wall-clock grace
     * period, which cannot guarantee that no in-flight query still sees the segment.</p>
     *
     * @param supplier Supplier of the current visibility epoch, or null to unset
     */
    public void setCurrentEpochSupplier(java.util.function.LongSupplier supplier) {
        this.currentEpochSupplier = supplier;
    }

    /**
     * Attempts to purge a segment, handling callbacks if registered.
     */
    private void attemptPurge(long segmentId, long tombstoneEpoch) {
        ReclamationCallback callback = this.reclamationCallback;
        if (callback != null) {
            boolean purged = callback.onReclamationReady(segmentId);
            if (purged) {
                logger.info("Purged segment {} via callback", segmentId);
            }
        } else {
            try {
                purge(segmentId);
                logger.info("Purged segment {} directly", segmentId);
            } catch (Exception e) {
                logger.warn("Failed to purge segment {}: {}", segmentId, e.getMessage());
            }
        }
    }

    // ==================== Manual Transition Helpers ====================

    /**
     * Attempts to advance a segment to the next lifecycle state.
     *
     * <p>This is useful for testing or manual lifecycle management.</p>
     *
     * @param segmentId The segment ID
     * @return The new lifecycle state, or null if segment not found
     */
    public LifecycleState tryAdvance(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
        if (metadata == null) {
            return null;
        }

        LifecycleState currentState = metadata.getLifecycleState();
        try {
            switch (currentState) {
                case BOOTSTRAPPING:
                    catalog.beginIndexing(segmentId);
                    return LifecycleState.INDEXING;
                case INDEXING:
                    completeIndexing(segmentId);
                    return LifecycleState.WARMING;
                case WARMING:
                    completeWarmup(segmentId);
                    return LifecycleState.ACTIVE;
                case ACTIVE:
                    // Don't auto-advance from ACTIVE
                    return LifecycleState.ACTIVE;
                case COMPACTING:
                    completeCompaction(segmentId);
                    return LifecycleState.ACTIVE;
                case TOMBSTONED:
                    purge(segmentId);
                    return LifecycleState.PURGED;
                default:
                    return currentState;
            }
        } catch (IllegalStateException e) {
            logger.debug("Cannot advance segment {} from {}: {}", segmentId, currentState, e.getMessage());
            return currentState;
        }
    }

    /**
     * Gets the current lifecycle state of a segment.
     *
     * @param segmentId The segment ID
     * @return The lifecycle state, or null if segment not found
     */
    public LifecycleState getLifecycleState(long segmentId) {
        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
        return metadata != null ? metadata.getLifecycleState() : null;
    }

    /**
     * Gets the catalog managed by this lifecycle manager.
     *
     * @return The segment catalog
     */
    public SegmentCatalog getCatalog() {
        return catalog;
    }

    /**
     * Gets the check interval in milliseconds.
     *
     * @return The check interval
     */
    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    /**
     * Gets the tombstone threshold ratio.
     *
     * @return The tombstone threshold (0.0-1.0)
     */
    public double getTombstoneThreshold() {
        return tombstoneThreshold;
    }

    /**
     * Gets the minimum entries for compaction.
     *
     * @return The minimum entries
     */
    public int getMinEntriesForCompaction() {
        return minEntriesForCompaction;
    }

    /**
     * Gets the epoch lag threshold for reclamation.
     *
     * @return The epoch lag threshold
     */
    public long getEpochLagThreshold() {
        return epochLagThreshold;
    }
}
