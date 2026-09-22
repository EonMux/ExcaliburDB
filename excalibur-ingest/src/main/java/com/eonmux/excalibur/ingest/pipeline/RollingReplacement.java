package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.ReindexProgress;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.ReindexResult;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.RollingReindexResult;

import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.VisibilityTracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Replaces a source's segments without taking it out of service.
 *
 * <h2>The shape of the swap</h2>
 * <p>A new segment is created in BOOTSTRAPPING, moved to INDEXING, filled with everything the
 * source now lists, and then moved to WARMING. The old segments are retired at that point rather
 * than at ACTIVE: the WARMING to ACTIVE transition is made asynchronously by the warming phase
 * worker once PQ training finishes, and waiting for it here would hold the source's claim for
 * the length of a training run. WARMING is queryable, so a query still reaches the new data; a
 * query that opts out of partial segments sees neither the old segments nor the new one until
 * training finishes.</p>
 *
 * <h2>Why the run usually does not happen</h2>
 * <p>The segment created here is a catalog entry, and the catalog draws IDs from a counter it
 * advances past every ID it issues - the indices a split registers included - so a created entry
 * always names an index the engine holds no segment of its own at. Before the first split that
 * index is past the routing directory and resolves to nothing; after one it resolves to a buddy
 * pointer, and filling it would write this source's data into a segment routing unrelated keys
 * and then retire the source's real segments in favour of it. Neither is a target, so this is
 * detected before a single document is fetched and the run falls back to re-indexing through the
 * routing directory.</p>
 *
 * <p>Asking once is the point. Discovering it one chunk at a time would cost a crawl, a chunking
 * pass and an embedding call for every chunk of every document in the source before the first
 * write was even attempted, and the run would then end having written nothing.</p>
 *
 * <h2>Why an empty result retires nothing</h2>
 * <p>The old segments are only retired once something has actually been written to the new one.
 * Guarding on the document count alone would let a run in which every document failed - or one
 * whose every document turned out empty - tombstone every populated old segment in favour of an
 * empty new one, which erases a corpus on the strength of a crawl that returned no bodies.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>An instance holds no mutable state of its own and is built per run; its collaborators carry
 * their own guarantees.</p>
 */
final class RollingReplacement {

    private static final Logger LOG = LoggerFactory.getLogger(RollingReplacement.class);

    /** Re-indexes a source the ordinary way, through the storage engine's routing directory. */
    @FunctionalInterface
    interface RoutedReindex {

        /**
         * @param source   The source to re-index
         * @param progress Told how many documents the run has finished with
         * @return Result containing counts and any errors
         * @throws SourceException if crawling or fetching fails catastrophically
         */
        ReindexResult run(Source source, ReindexProgress progress) throws SourceException;
    }

    /** Re-indexes a source into one named segment. */
    @FunctionalInterface
    interface SegmentReindex {

        /**
         * @param source    The source to re-index
         * @param writer    Storage writer bound to the target segment
         * @param publisher Publisher bound to the target segment
         * @param progress  Told how many documents the run has finished with
         * @return Result containing counts and any errors
         * @throws SourceException if crawling or fetching fails catastrophically
         */
        ReindexResult run(Source source, OffsetAwareStorageWriter writer,
                          DocumentBatchPublisher publisher, ReindexProgress progress)
            throws SourceException;
    }

    private final SegmentCatalog segmentCatalog;
    private final VisibilityTracker visibilityTracker;
    private final OffsetAwareStorageWriter offsetAwareWriter;
    private final DocumentBatchPublisher batchPublisher;
    private final RoutedReindex routed;
    private final SegmentReindex intoSegment;

    /**
     * @param segmentCatalog    Creates the new segment and moves it through its states
     * @param visibilityTracker Supplies the epoch a tombstone is recorded at
     * @param offsetAwareWriter Asked whether the created segment is a write target at all
     * @param batchPublisher    Rebound to the created segment for the run
     * @param routed            Re-indexes through the routing directory, for the fallbacks
     * @param intoSegment       Re-indexes into the created segment
     * @throws NullPointerException if any argument is null
     */
    RollingReplacement(SegmentCatalog segmentCatalog, VisibilityTracker visibilityTracker,
                       OffsetAwareStorageWriter offsetAwareWriter,
                       DocumentBatchPublisher batchPublisher, RoutedReindex routed,
                       SegmentReindex intoSegment) {
        this.segmentCatalog =
            Objects.requireNonNull(segmentCatalog, "segmentCatalog must not be null");
        this.visibilityTracker =
            Objects.requireNonNull(visibilityTracker, "visibilityTracker must not be null");
        this.offsetAwareWriter =
            Objects.requireNonNull(offsetAwareWriter, "offsetAwareWriter must not be null");
        this.batchPublisher =
            Objects.requireNonNull(batchPublisher, "batchPublisher must not be null");
        this.routed = Objects.requireNonNull(routed, "routed must not be null");
        this.intoSegment = Objects.requireNonNull(intoSegment, "intoSegment must not be null");
    }

    /**
     * Re-indexes the source into a new segment and retires the ones it replaces.
     *
     * @param source     The source to re-index
     * @param volumePath Where the new segment file should be created
     * @param progress   Told how many documents the run has finished with
     * @return Result containing counts, new segment ID, and any errors. The segment ID is
     *         {@code -1} when the engine offered no writable target and the run fell back to
     *         re-indexing through the routing directory
     * @throws SourceException           if the segment could not be created, or if crawling or
     *                                   fetching fails catastrophically
     * @throws ReindexCancelledException if the run was asked to stop; the segment it was
     *                                   filling is retired and no old segment is replaced
     */
    RollingReindexResult run(Source source, Path volumePath, ReindexProgress progress)
            throws SourceException {

        String sourceId = source.getSourceId();
        long startTime = System.currentTimeMillis();

        LOG.info("Starting rolling reindex for source: {} with volume: {}", sourceId, volumePath);

        // Step 1: Create new segment in BOOTSTRAPPING state
        SegmentCatalog.SegmentMetadata newSegment;
        try {
            newSegment = segmentCatalog.create(sourceId, volumePath);
            LOG.info("Created new segment {} in BOOTSTRAPPING state for source {}",
                newSegment.getSegmentId(), sourceId);
        } catch (Exception e) {
            LOG.error("Failed to create new segment for source {}: {}", sourceId, e.getMessage());
            throw new SourceException("Failed to create new segment: " + e.getMessage(), e);
        }

        long newSegmentId = newSegment.getSegmentId();

        List<Long> oldSegmentIds;
        boolean writableTarget;
        try {
            // Step 2: Find old segments for this source (to tombstone later)
            oldSegmentIds = replaceableSegmentsOf(sourceId, newSegmentId);
            LOG.info("Found {} existing segments for source {} to tombstone after reindex",
                oldSegmentIds.size(), sourceId);

            // Step 3: Transition new segment to INDEXING (makes it queryable)
            segmentCatalog.beginIndexing(newSegmentId);
            LOG.info("Segment {} transitioned from BOOTSTRAPPING to INDEXING", newSegmentId);

            // Checked after the segment is INDEXING because that is the state it has to be in to
            // be discarded. See the class javadoc for why it is asked once, here, rather than
            // discovered one chunk at a time.
            writableTarget = offsetAwareWriter.canWriteTo(newSegmentId);
            if (!writableTarget) {
                LOG.warn("Segment {} created for source {} is not a write target for the storage "
                    + "engine; re-indexing through the routing directory instead of replacing "
                    + "segments. No old segment is retired.", newSegmentId, sourceId);
                segmentCatalog.tombstone(newSegmentId, visibilityTracker.currentEpoch());
            }
        } catch (ReindexCancelledException e) {
            retireQuietly(newSegmentId);
            throw e;
        } catch (Exception e) {
            throw failedAndRetired(sourceId, newSegmentId, e);
        }

        if (!writableTarget) {
            // Outside the handling above on purpose. The created entry has already been retired,
            // so a failure of the fallback must not retire it a second time and record a later
            // epoch for a retirement that has happened; and the fallback is an ordinary reindex,
            // whose failure is not a rolling replacement's to rename.
            return new RollingReindexResult(routed.run(source, progress), -1, List.of());
        }

        try {
            // Step 4: Create segment-targeted writers for the new segment
            // This is CRITICAL: all reindexed data must go to the NEW segment, not the old one
            OffsetAwareStorageWriter segmentWriter = offsetAwareWriter.forSegment(newSegmentId);
            DocumentBatchPublisher segmentPublisher =
                batchPublisher.withTargetSegment(newSegmentId);
            LOG.info("Created segment-targeted writers for segment {}", newSegmentId);

            // Step 5: Perform the actual reindexing with segment-targeted writers
            ReindexResult reindexResult =
                intoSegment.run(source, segmentWriter, segmentPublisher, progress);

            // The new segment is only usable if something was actually written to it; see the
            // class javadoc for what retiring the old ones anyway would cost.
            if (reindexResult.getChunksCreated() == 0
                    && (reindexResult.hasErrors() || reindexResult.getDocumentsSkipped() > 0)) {
                // Complete failure - mark segment for cleanup
                LOG.error("Reindex failed completely for source {}, tombstoning new segment {}",
                    sourceId, newSegmentId);
                segmentCatalog.tombstone(newSegmentId, visibilityTracker.currentEpoch());
                return new RollingReindexResult(reindexResult, newSegmentId, List.of());
            }

            // Step 6: Transition new segment to WARMING state
            // WarmingPhaseWorker will handle PQ training and the WARMING → ACTIVE transition
            // asynchronously
            segmentCatalog.completeIndexing(newSegmentId);
            LOG.info("Segment {} transitioned from INDEXING to WARMING, awaiting PQ training by "
                + "WarmingPhaseWorker", newSegmentId);

            // Note: completeWarmup() is called by WarmingPhaseWorker after PQ training completes
            // This ensures PQ codebooks are trained before the segment becomes ACTIVE

            // Step 7: Tombstone old segments for this source
            List<Long> tombstonedSegments = tombstone(oldSegmentIds, sourceId);

            // Step 8: Advance epoch to make changes visible
            visibilityTracker.advanceEpoch();

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("Rolling reindex complete for source {}: new segment={}, {} docs indexed, "
                    + "{} old segments tombstoned, {}ms", sourceId, newSegmentId,
                reindexResult.getDocumentsIndexed(), tombstonedSegments.size(), elapsed);

            return new RollingReindexResult(reindexResult, newSegmentId, tombstonedSegments);

        } catch (ReindexCancelledException e) {
            // A stop, not a fault, so it is not renamed - but the new segment still has to go.
            // It holds however much of the corpus the run got through, and leaving it for the
            // warming worker to advance would put a partial replacement into service beside the
            // segments it was going to replace.
            LOG.info("Rolling reindex of source {} stopped on request; segment {} is retired and "
                + "no old segment was replaced", sourceId, newSegmentId);
            retireQuietly(newSegmentId);
            throw e;
        } catch (Exception e) {
            throw failedAndRetired(sourceId, newSegmentId, e);
        }
    }

    /**
     * Reports a rolling replacement that broke, retiring the segment it was filling.
     *
     * @param sourceId     The source being re-indexed
     * @param newSegmentId The segment created for the replacement
     * @param cause        What went wrong
     * @return The failure to throw, so a caller can write {@code throw failedAndRetired(...)}
     *         and the compiler can see the method ends there
     */
    private SourceException failedAndRetired(String sourceId, long newSegmentId, Exception cause) {
        LOG.error("Rolling reindex failed for source {}: {}", sourceId, cause.getMessage(), cause);
        retireQuietly(newSegmentId);
        return new SourceException("Rolling reindex failed: " + cause.getMessage(), cause);
    }

    /**
     * Retires a segment that must not stay in service, without letting that failure replace the
     * one being reported.
     *
     * <p>Cleanup, so it is reported rather than thrown: whatever brought the run here is what
     * the caller needs to see, and a catalog that will not take the retirement on top of it is
     * a second line in the log, not a different answer.</p>
     *
     * @param segmentId The segment to retire
     */
    private void retireQuietly(long segmentId) {
        try {
            segmentCatalog.tombstone(segmentId, visibilityTracker.currentEpoch());
        } catch (Exception cleanupError) {
            LOG.warn("Failed to tombstone segment {} during cleanup: {}",
                segmentId, cleanupError.getMessage());
        }
    }

    /**
     * The source's segments this run stands to replace.
     *
     * <p>One already on its way out is left alone: tombstoning a TOMBSTONED or PURGED segment
     * again would record a second, later epoch for a retirement that has already happened.</p>
     */
    private List<Long> replaceableSegmentsOf(String sourceId, long newSegmentId) {
        List<Long> oldSegmentIds = new ArrayList<>();
        for (SegmentCatalog.SegmentMetadata seg : segmentCatalog.getSegmentsBySource(sourceId)) {
            if (seg.getSegmentId() != newSegmentId
                && seg.getLifecycleState() != LifecycleState.TOMBSTONED
                && seg.getLifecycleState() != LifecycleState.PURGED) {
                oldSegmentIds.add(seg.getSegmentId());
            }
        }
        return oldSegmentIds;
    }

    /**
     * Retires the replaced segments, one failure at a time.
     *
     * <p>Reported rather than thrown: the new segment is already serving, and a catalog that
     * will not take one retirement must not undo a replacement that has otherwise succeeded.</p>
     *
     * @return The segments that were actually retired
     */
    private List<Long> tombstone(List<Long> oldSegmentIds, String sourceId) {
        List<Long> tombstonedSegments = new ArrayList<>();
        long currentEpoch = visibilityTracker.currentEpoch();

        for (Long oldSegmentId : oldSegmentIds) {
            try {
                segmentCatalog.tombstone(oldSegmentId, currentEpoch);
                tombstonedSegments.add(oldSegmentId);
                LOG.info("Tombstoned old segment {} for source {} at epoch {}",
                    oldSegmentId, sourceId, currentEpoch);
            } catch (Exception e) {
                LOG.warn("Failed to tombstone old segment {} for source {}: {}",
                    oldSegmentId, sourceId, e.getMessage());
            }
        }
        return tombstonedSegments;
    }
}
