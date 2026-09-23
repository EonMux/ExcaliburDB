package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.ReindexProgress;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.ReindexResult;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.RollingReindexResult;

import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.VisibilityTracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Asks the storage engine for a segment to replace a source's segments with, and re-indexes
 * through the routing directory when it offers none - which it never does.
 *
 * <h2>Why the run never replaces anything</h2>
 * <p>The segment created here is a catalog entry, and the catalog allocates created IDs from
 * {@code SegmentCatalog.FIRST_CREATED_SEGMENT_ID} upward - a range above every index the storage
 * engine's routing directory can hold. A created entry therefore names no segment of the engine's
 * and resolves to nothing, however far the directory has split, so it is not a write target. That
 * is detected before a single document is fetched, the entry is retired, and the run re-indexes
 * through the routing directory. The old segments stay exactly as they were.</p>
 *
 * <p>Asking once is the point. Discovering it one chunk at a time would cost a crawl, a chunking
 * pass and an embedding call for every chunk of every document in the source before the first
 * write was even attempted, and the run would then end having written nothing.</p>
 *
 * <h2>Why a writable target is refused rather than filled</h2>
 * <p>A re-index pass is driven by change detection: it fetches and writes what is new or
 * changed, and a document the source still lists unchanged - one whose server answered
 * {@code 304 Not Modified}, above all - is not read again. That is exactly right for a pass
 * writing where the old version already lives, and exactly wrong for one filling a new segment
 * that is about to replace the old ones: retiring them would take every unchanged document out
 * of service, and every document whose fetch failed with it. A replacement that is safe has to
 * carry the whole source into the new segment and refuse to retire anything a document is
 * missing from, and no pass here does that. So a writer that reports the created segment
 * writable is a broken invariant, not an opportunity: the run retires the created entry and
 * fails before anything is fetched, leaving the source served as it was.</p>
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

    private final SegmentCatalog segmentCatalog;
    private final VisibilityTracker visibilityTracker;
    private final OffsetAwareStorageWriter offsetAwareWriter;
    private final RoutedReindex routed;

    /**
     * @param segmentCatalog    Creates the candidate segment and retires it
     * @param visibilityTracker Supplies the epoch a retirement is recorded at
     * @param offsetAwareWriter Asked whether the created segment is a write target at all
     * @param routed            Re-indexes through the routing directory
     * @throws NullPointerException if any argument is null
     */
    RollingReplacement(SegmentCatalog segmentCatalog, VisibilityTracker visibilityTracker,
                       OffsetAwareStorageWriter offsetAwareWriter, RoutedReindex routed) {
        this.segmentCatalog =
            Objects.requireNonNull(segmentCatalog, "segmentCatalog must not be null");
        this.visibilityTracker =
            Objects.requireNonNull(visibilityTracker, "visibilityTracker must not be null");
        this.offsetAwareWriter =
            Objects.requireNonNull(offsetAwareWriter, "offsetAwareWriter must not be null");
        this.routed = Objects.requireNonNull(routed, "routed must not be null");
    }

    /**
     * Re-indexes the source, through the routing directory once the engine has declined to
     * offer a segment to replace its segments with.
     *
     * @param source     The source to re-index
     * @param volumePath Where the candidate segment would have been created
     * @param progress   Told how many documents the run has finished with
     * @return The routed run's result, with no new segment ({@code -1}) and nothing retired
     * @throws SourceException           if the candidate segment could not be created or
     *                                   retired, or if crawling or fetching fails
     *                                   catastrophically
     * @throws IllegalStateException     if the writer reports the created segment writable; see
     *                                   the class documentation
     * @throws ReindexCancelledException if the run was asked to stop
     */
    RollingReindexResult run(Source source, Path volumePath, ReindexProgress progress)
            throws SourceException {

        String sourceId = source.getSourceId();
        LOG.info("Starting rolling reindex for source: {} with volume: {}", sourceId, volumePath);

        SegmentCatalog.SegmentMetadata newSegment;
        try {
            newSegment = segmentCatalog.create(sourceId, volumePath);
        } catch (Exception e) {
            LOG.error("Failed to create new segment for source {}: {}", sourceId, e.getMessage());
            throw new SourceException("Failed to create new segment: " + e.getMessage(), e);
        }
        long newSegmentId = newSegment.getSegmentId();

        boolean writableTarget;
        try {
            // INDEXING is the state a created entry has to be in to be retired.
            segmentCatalog.beginIndexing(newSegmentId);
            writableTarget = offsetAwareWriter.canWriteTo(newSegmentId);
            segmentCatalog.tombstone(newSegmentId, visibilityTracker.currentEpoch());
        } catch (Exception e) {
            LOG.error("Rolling reindex failed for source {}: {}", sourceId, e.getMessage(), e);
            retireQuietly(newSegmentId);
            throw new SourceException("Rolling reindex failed: " + e.getMessage(), e);
        }

        if (writableTarget) {
            throw new IllegalStateException("Segment " + newSegmentId + " created to replace "
                + "the segments of source " + sourceId + " is reported writable. A re-index "
                + "pass writes only what is new or changed, so replacing the old segments with "
                + "it would take every unchanged document out of service; the created segment "
                + "is retired and the source is left as it was.");
        }

        LOG.info("Segment {} created for source {} is not a write target for the storage "
            + "engine; re-indexing through the routing directory. No old segment is retired.",
            newSegmentId, sourceId);
        // Outside the handling above on purpose: the created entry is already retired, and the
        // routed run is an ordinary reindex, whose failure is not a rolling replacement's to
        // rename.
        return new RollingReindexResult(routed.run(source, progress), -1, List.of());
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
}
