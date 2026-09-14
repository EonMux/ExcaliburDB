package excalibur.api.endpoint;

import excalibur.ingest.pipeline.DocumentReindexer;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.ingest.source.SourceResolver;
import excalibur.shard.allocator.VolumeManager;
import io.excalibur.store.SegmentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One reindex, from resolving the source to deciding what the status registry is told.
 *
 * <p>Separate from {@link ReindexEndpoint} because it is the half that runs after the response
 * has already been sent. The endpoint's job ends at 202: it validates, claims the source and
 * hands the work to a virtual thread. From there nothing a caller is waiting on is affected by
 * what happens, and the only way anyone learns the outcome is
 * {@code GET /v1/reindex/status} - so what that readback is set to is the entire contract of
 * this class, and it is a judgement rather than a transcription.</p>
 *
 * <h2>What counts as a failure</h2>
 * <p>A run is failed only when it indexed nothing. Anything else is a completed run with a
 * warning in the log, because failing on any error at all reported a run that reindexed four
 * hundred documents and could not read one of them as a failed reindex - which is the opposite
 * of what an operator would do about it. Both the rolling and the standard branch apply that
 * same rule, deliberately: which of them ran is a property of how the node is deployed, not
 * something a caller asked for, and the two must not disagree about whether the same corpus
 * reindexed successfully.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every field is final and the mutual exclusion that matters - one reindex per
 * source - is the caller's lease, taken before {@link #execute(String)} is reached.</p>
 *
 * @author ExcaliburDB
 */
final class ReindexRun {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexRun.class);

    /**
     * How a failure readback points at the detail without carrying it.
     *
     * <p>These failures come from storage writes, the lexical index and the embedding client,
     * and their messages carry filesystem paths and endpoint URLs. {@code GET
     * /v1/reindex/status} with no {@code source_id} returns every source to any caller holding
     * a read token, so a message written for an operator reading the node's log would be
     * served to all of them - and where several tenants share one token, to each other. The
     * reason goes to the log, where it belongs; the readback says where to find it.</p>
     */
    private static final String SEE_THE_LOG = "; see the node's log for the reason";

    private final DocumentReindexer reindexer;
    private final SourceResolver sourceResolver;
    private final VolumeManager volumeManager;
    private final SegmentCatalog segmentCatalog;
    private final ReindexEndpoint.ReindexStatusRegistry statusRegistry;

    /**
     * Creates the runner a node with reindexing configured uses for every reindex.
     *
     * @param reindexer      The reindexer to run
     * @param sourceResolver Resolver for the source instance
     * @param volumeManager  Where a new segment is placed, or null when this node has no volume
     *                       manager and rolling replacement is therefore unavailable
     * @param segmentCatalog The catalog rolling replacement moves segments through, or null
     * @param statusRegistry Where the outcome is recorded for {@code GET /v1/reindex/status}
     * @throws NullPointerException if the reindexer, resolver or status registry is null
     */
    ReindexRun(DocumentReindexer reindexer,
               SourceResolver sourceResolver,
               VolumeManager volumeManager,
               SegmentCatalog segmentCatalog,
               ReindexEndpoint.ReindexStatusRegistry statusRegistry) {
        this.reindexer = Objects.requireNonNull(reindexer, "reindexer cannot be null");
        this.sourceResolver =
            Objects.requireNonNull(sourceResolver, "sourceResolver cannot be null");
        this.volumeManager = volumeManager;
        this.segmentCatalog = segmentCatalog;
        this.statusRegistry =
            Objects.requireNonNull(statusRegistry, "statusRegistry cannot be null");
    }

    /**
     * Reindexes one source, reporting every phase and every outcome to the status registry.
     *
     * <p>Rolling replacement is used when this node can: new segments are created in
     * BOOTSTRAPPING while the old ones keep serving queries, and the old ones are tombstoned
     * only once the new ones are ACTIVE, so the data is never unavailable. A node without a
     * volume manager or a segment catalog has nowhere to put the replacement and falls back to
     * publishing in place.</p>
     *
     * <p>Nothing is allowed to escape: this runs on a detached virtual thread, so an exception
     * thrown here would be lost to the default handler and leave the source reading as
     * mid-reindex forever.</p>
     *
     * @param sourceId The source to reindex
     */
    void execute(String sourceId) {
        String threadName = Thread.currentThread().getName();
        LOGGER.info("[reindex-{}] Starting reindex execution for source: {}", threadName, sourceId);

        try {
            Source source = sourceResolver.resolve(sourceId);
            if (source == null) {
                String error = "Source not found: " + sourceId;
                LOGGER.error("[reindex-{}] {}", threadName, error);
                statusRegistry.failReindex(sourceId, error);
                return;
            }

            statusRegistry.updateProgress(sourceId, "crawling", 0.1, 0, 0);

            boolean useRollingReplacement = volumeManager != null && segmentCatalog != null
                && reindexer.hasRollingReplacement();
            if (useRollingReplacement) {
                runRolling(threadName, sourceId, source);
            } else {
                runStandard(threadName, sourceId, source);
            }

        } catch (Exception e) {
            LOGGER.error("[reindex-{}] Reindex failed for source {}: {}",
                threadName, sourceId, e.getMessage(), e);
            statusRegistry.failReindex(sourceId,
                "Reindex failed (" + e.getClass().getSimpleName() + ")" + SEE_THE_LOG);
        }
    }

    /**
     * Reindexes into a fresh segment, leaving the old one serving until the new one is ready.
     *
     * <p>A volume has to be chosen before anything is written, and there may not be one. That is
     * a failure rather than a fallback to publishing in place: the caller asked for the corpus to
     * be rebuilt, and quietly rebuilding it over the top of the segments still answering queries
     * is the outage rolling replacement exists to avoid.</p>
     *
     * @param threadName The worker thread, which is how a run is followed through the log
     * @param sourceId   The source being reindexed
     * @param source     The resolved source
     * @throws SourceException if the source cannot be read
     */
    private void runRolling(String threadName, String sourceId, Source source)
            throws SourceException {
        LOGGER.info("[reindex-{}] Using rolling replacement for zero-downtime reindex of "
            + "source: {}", threadName, sourceId);

        Path volumePath = volumeManager.getVolumeWithMostSpace();
        if (volumePath == null) {
            String error = "No volume available for reindex";
            LOGGER.error("[reindex-{}] {} for source: {}", threadName, error, sourceId);
            statusRegistry.failReindex(sourceId, error);
            return;
        }

        LOGGER.info("[reindex-{}] Selected volume {} for new segment of source: {}",
            threadName, volumePath, sourceId);

        statusRegistry.updateProgress(sourceId, "embedding", 0.3, 0, 0);

        DocumentReindexer.RollingReindexResult result =
            reindexer.reindexWithRollingReplacement(source, volumePath);

        statusRegistry.updateProgress(sourceId, "indexing", 0.7, result.getDocumentsIndexed(),
            result.getDocumentsIndexed() + result.getBasicResult().getChunksFailed());

        reportRolling(threadName, sourceId, result);
    }

    /**
     * Records the verdict on a rolling run, and says which segments changed hands.
     *
     * <p>The new segment id and the tombstoned ones are logged on both the clean and the partial
     * outcome because they are what an operator needs to inspect the result: nothing else in the
     * node says which segment now holds this source.</p>
     *
     * @param threadName The worker thread, for the log
     * @param sourceId   The source that was reindexed
     * @param result     What the rolling replacement produced
     */
    private void reportRolling(String threadName, String sourceId,
                               DocumentReindexer.RollingReindexResult result) {
        if (result.hasErrors() && result.getDocumentsIndexed() == 0) {
            LOGGER.error("[reindex-{}] Reindex failed completely with {} errors for "
                    + "source {}: {}", threadName, result.getErrorCount(), sourceId,
                errorDetail(result.getBasicResult().getErrors()));
            statusRegistry.failReindex(sourceId, countedFailure(result.getErrorCount()));
            return;
        }
        if (result.hasErrors()) {
            LOGGER.warn("[reindex-{}] Rolling reindex completed with {} errors for source {}: "
                    + "{} docs indexed, {} old segments tombstoned, new segment {}",
                threadName, result.getErrorCount(), sourceId, result.getDocumentsIndexed(),
                result.getTombstonedSegmentIds().size(), result.getNewSegmentId());
        } else {
            LOGGER.info("[reindex-{}] Rolling reindex completed successfully for source {}: "
                    + "{} docs indexed, {} old segments tombstoned, new segment {}",
                threadName, sourceId, result.getDocumentsIndexed(),
                result.getTombstonedSegmentIds().size(), result.getNewSegmentId());
        }
        statusRegistry.completeReindex(sourceId);
    }

    /**
     * Reindexes in place, on a node that has nowhere to put a replacement segment.
     *
     * @param threadName The worker thread, for the log
     * @param sourceId   The source being reindexed
     * @param source     The resolved source
     * @throws SourceException if the source cannot be read
     */
    private void runStandard(String threadName, String sourceId, Source source)
            throws SourceException {
        LOGGER.info("[reindex-{}] Using standard reindex (rolling replacement not configured) "
            + "for source: {}", threadName, sourceId);

        statusRegistry.updateProgress(sourceId, "embedding", 0.3, 0, 0);

        DocumentReindexer.ReindexResult result = reindexer.reindexAll(source);

        statusRegistry.updateProgress(sourceId, "indexing", 0.7, result.getDocumentsIndexed(),
            result.getDocumentsIndexed() + result.getChunksFailed());

        reportStandard(threadName, sourceId, result);
    }

    /**
     * Records the verdict on an in-place run.
     *
     * <p>Applies the same rule the rolling branch does - failed only when nothing was indexed -
     * and reports the counts an operator compares against the previous run to see whether the
     * corpus actually changed.</p>
     *
     * @param threadName The worker thread, for the log
     * @param sourceId   The source that was reindexed
     * @param result     What the reindex produced
     */
    private void reportStandard(String threadName, String sourceId,
                                DocumentReindexer.ReindexResult result) {
        if (result.hasErrors() && result.getDocumentsIndexed() == 0) {
            LOGGER.error("[reindex-{}] Reindex failed completely with {} errors for "
                    + "source {}: {}", threadName, result.getErrorCount(), sourceId,
                errorDetail(result.getErrors()));
            statusRegistry.failReindex(sourceId, countedFailure(result.getErrorCount()));
            return;
        }
        if (result.hasErrors()) {
            LOGGER.warn("[reindex-{}] Reindex completed with {} errors for {}: {} docs "
                    + "indexed, {} skipped, {} chunks created",
                threadName, result.getErrorCount(), sourceId, result.getDocumentsIndexed(),
                result.getDocumentsSkipped(), result.getChunksCreated());
        } else {
            LOGGER.info("[reindex-{}] Reindex completed successfully for {}: {} docs "
                    + "indexed, {} skipped, {} chunks created",
                threadName, sourceId, result.getDocumentsIndexed(),
                result.getDocumentsSkipped(), result.getChunksCreated());
        }
        statusRegistry.completeReindex(sourceId);
    }

    /**
     * Joins every operation error into the one line that goes to the log.
     *
     * <p>All of them, not the first: a run that indexed nothing usually failed the same way for
     * every document, and the one case worth catching is where it did not. This is the log line,
     * not the readback, which is why it may carry the paths and URLs the messages contain.</p>
     *
     * @param errors What the run recorded, possibly empty
     * @return The joined messages, or {@code unknown} when the run reported errors without one
     */
    private static String errorDetail(List<DocumentReindexer.ReindexResult.OperationError> errors) {
        return errors.stream()
            .map(DocumentReindexer.ReindexResult.OperationError::getMessage)
            .reduce((a, b) -> a + "; " + b)
            .orElse("unknown");
    }

    /**
     * The readback text for a run that indexed nothing.
     *
     * @param errorCount How many documents failed
     * @return What the caller is told
     */
    private static String countedFailure(int errorCount) {
        return "Reindex failed completely with " + errorCount + " errors" + SEE_THE_LOG;
    }
}
