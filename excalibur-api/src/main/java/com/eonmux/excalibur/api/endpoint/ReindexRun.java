package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.store.SegmentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedByInterruptException;
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
 * <h2>Cancelled is not failed</h2>
 * <p>{@code DELETE /v1/reindex} interrupts the run thread and tells the caller to poll the
 * status for when it has stopped. The interrupt arrives here as an exception like any other, and
 * recording it as a failure made the one readback the route points at unable to answer the
 * question the route was asked. Both shapes the interrupt can take are recognised: the
 * exception, wrapped however deeply by whatever was blocked at the time, and the thread's own
 * interrupt flag, which is what survives when the blocked call reported the interrupt by
 * returning rather than by throwing.</p>
 *
 * <h2>Progress is counted, not assumed</h2>
 * <p>What the status reports is what the reindexer says it has finished, document by document.
 * The three constants this class used to publish instead described a run that nobody had
 * measured, and in units - chunks, in the total - that nothing else on the route used.</p>
 *
 * <h2>What the source's ingestion status is told</h2>
 * <p>One number: how many of the documents the crawl listed were passed over because they are
 * not text. A re-crawl applies that test exactly as the first crawl did, so a run that newly
 * skipped three hundred photographs has to be able to say so - and {@code entries_skipped} on
 * {@code GET /v1/ingest/status} is where the node says it. Nothing else on that status is
 * touched, because a reindex has its own readback for how far it got and how it ended.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every field is final and the mutual exclusion that matters - one reindex per
 * source - is the caller's lease, taken before {@link #execute(String)} is reached.</p>
 *
 * @author ExcaliburDB
 */
public final class ReindexRun {

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

    /** Reported while the source is being listed and its changed documents worked out. */
    private static final String STATE_CRAWLING = "crawling";

    /** Reported per document on a node that turns the chunks it writes into vectors. */
    private static final String STATE_EMBEDDING = "embedding";

    /** Reported per document on a node that indexes its corpus lexically. */
    private static final String STATE_INDEXING = "indexing";

    private final DocumentReindexer reindexer;
    private final SourceResolver sourceResolver;
    private final VolumeManager volumeManager;
    private final SegmentCatalog segmentCatalog;
    private final ReindexEndpoint.ReindexStatusRegistry statusRegistry;
    private final IngestStatusEndpoint.IngestionStatusRegistry ingestionStatusRegistry;

    /**
     * Creates the runner a node with reindexing configured uses for every reindex.
     *
     * @param reindexer      The reindexer to run
     * @param sourceResolver Resolver for the source instance
     * @param volumeManager  Where a new segment is placed, or null when this node has no volume
     *                       manager and rolling replacement is therefore unavailable
     * @param segmentCatalog The catalog rolling replacement moves segments through, or null
     * @param statusRegistry Where the outcome is recorded for {@code GET /v1/reindex/status}
     * @param ingestionStatusRegistry Where {@code entries_skipped} is recorded for
     *                                {@code GET /v1/ingest/status}, or null on a node that keeps
     *                                no ingestion status
     * @throws NullPointerException if the reindexer, resolver or status registry is null
     */
    public ReindexRun(DocumentReindexer reindexer,
                      SourceResolver sourceResolver,
                      VolumeManager volumeManager,
                      SegmentCatalog segmentCatalog,
                      ReindexEndpoint.ReindexStatusRegistry statusRegistry,
                      IngestStatusEndpoint.IngestionStatusRegistry ingestionStatusRegistry) {
        this.reindexer = Objects.requireNonNull(reindexer, "reindexer cannot be null");
        this.sourceResolver =
            Objects.requireNonNull(sourceResolver, "sourceResolver cannot be null");
        this.volumeManager = volumeManager;
        this.segmentCatalog = segmentCatalog;
        this.statusRegistry =
            Objects.requireNonNull(statusRegistry, "statusRegistry cannot be null");
        this.ingestionStatusRegistry = ingestionStatusRegistry;
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
     * @return true when the run produced something, which is the same verdict the readback is
     *         given - a run that indexed nothing is the only kind that failed
     */
    public boolean execute(String sourceId) {
        String threadName = Thread.currentThread().getName();
        LOGGER.info("[reindex-{}] Starting reindex execution for source: {}", threadName, sourceId);

        try {
            Source source = sourceResolver.resolve(sourceId);
            if (source == null) {
                String error = "Source not found: " + sourceId;
                LOGGER.error("[reindex-{}] {}", threadName, error);
                statusRegistry.failReindex(sourceId, error);
                return false;
            }

            // 0.0, not an invented tenth: nothing has been counted yet, and the first thing
            // the reindexer publishes is the size of the run.
            statusRegistry.updateProgress(sourceId, STATE_CRAWLING, 0.0, 0, 0);

            boolean useRollingReplacement = volumeManager != null && segmentCatalog != null
                && reindexer.hasRollingReplacement();
            return useRollingReplacement
                ? runRolling(threadName, sourceId, source)
                : runStandard(threadName, sourceId, source);

        } catch (Exception e) {
            if (wasCancelled(e)) {
                LOGGER.info("[reindex-{}] Reindex of source {} stopped on request after {} of "
                        + "{} document(s)", threadName, sourceId,
                    processedSoFar(sourceId), totalSoFar(sourceId));
                statusRegistry.cancelReindex(sourceId);
                return false;
            }
            LOGGER.error("[reindex-{}] Reindex failed for source {}: {}",
                threadName, sourceId, e.getMessage(), e);
            statusRegistry.failReindex(sourceId,
                "Reindex failed (" + e.getClass().getSimpleName() + ")" + SEE_THE_LOG);
            return false;
        }
    }

    /**
     * Records on the source's ingestion status how many documents this run passed over.
     *
     * <p>Reported after the run rather than during it, because it is a property of the run as a
     * whole and the count is not final until the last document has been judged. Reported even
     * when it is zero: a re-crawl of a root whose images have since been removed has to be able
     * to clear the figure the crawl before it left, or the source goes on reading as though it
     * still holds them.</p>
     *
     * @param sourceId The source that was reindexed
     * @param skipped  Documents listed whose bytes are not text
     */
    private void reportSkipped(String sourceId, int skipped) {
        if (ingestionStatusRegistry == null) {
            return;
        }
        IngestStatusEndpoint.IngestionStatus status = ingestionStatusRegistry.getStatus(sourceId);
        if (status != null) {
            status.setSkipped(skipped);
        }
    }

    /**
     * Whether this run stopped because someone asked it to, rather than because it broke.
     *
     * <p>Both shapes are checked because the interrupt takes whichever the blocked call gave
     * it. A call that threw {@link InterruptedException} cleared the flag on the way out and is
     * findable only in the cause chain - and it may be wrapped several deep, because every
     * layer between here and the fetch wraps what it catches. A call that was interrupted
     * inside a channel throws {@link ClosedByInterruptException}, an {@code IOException},
     * which no cause-chain test for {@code InterruptedException} would find. And a call that
     * noticed the interrupt and returned without throwing leaves only the flag.</p>
     *
     * @param failure What ended the run
     * @return true if the run was cancelled
     */
    private static boolean wasCancelled(Exception failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || cause instanceof ClosedByInterruptException) {
                return true;
            }
            if (cause.getCause() == cause) {
                // A self-referencing cause is a malformed exception chain, not a cancellation,
                // and walking it would not terminate.
                break;
            }
        }
        return false;
    }

    /**
     * How many documents the run had finished with, for the log line about its cancellation.
     *
     * @param sourceId The source
     * @return The count the run last published, or 0 if it published none
     */
    private long processedSoFar(String sourceId) {
        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus(sourceId);
        return status == null ? 0 : status.entriesProcessed();
    }

    /**
     * How many documents the run was going to finish with.
     *
     * @param sourceId The source
     * @return The total the run last published, or 0 if it published none
     */
    private long totalSoFar(String sourceId) {
        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus(sourceId);
        return status == null ? 0 : status.entriesTotal();
    }

    /**
     * Builds the listener that turns the reindexer's counts into the status a caller polls.
     *
     * <p>The phase is settled once, from what this node can do, rather than guessed per
     * document: a node with no embedding endpoint never enters an embedding phase, and
     * reporting one would be reporting work it does not perform.</p>
     *
     * @param sourceId The source being reindexed
     * @return The listener to hand the reindexer
     */
    private DocumentReindexer.ReindexProgress progressFor(String sourceId) {
        String state = reindexer.embedsChunks() ? STATE_EMBEDDING : STATE_INDEXING;
        return (processed, total) -> statusRegistry.updateProgress(sourceId, state,
            fractionDone(processed, total), processed, total);
    }

    /**
     * How far through the run is, as a fraction.
     *
     * @param processed Documents finished with
     * @param total     Documents the run will finish with
     * @return The fraction, and 1.0 for a run that found nothing to do
     */
    private static double fractionDone(long processed, long total) {
        return total <= 0 ? 1.0 : (double) processed / total;
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
    private boolean runRolling(String threadName, String sourceId, Source source)
            throws SourceException {
        LOGGER.info("[reindex-{}] Using rolling replacement for zero-downtime reindex of "
            + "source: {}", threadName, sourceId);

        Path volumePath = volumeManager.getVolumeWithMostSpace();
        if (volumePath == null) {
            String error = "No volume available for reindex";
            LOGGER.error("[reindex-{}] {} for source: {}", threadName, error, sourceId);
            statusRegistry.failReindex(sourceId, error);
            return false;
        }

        LOGGER.info("[reindex-{}] Selected volume {} for new segment of source: {}",
            threadName, volumePath, sourceId);

        DocumentReindexer.RollingReindexResult result =
            reindexer.reindexWithRollingReplacement(source, volumePath, progressFor(sourceId));

        return reportRolling(threadName, sourceId, result);
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
    private boolean reportRolling(String threadName, String sourceId,
                                  DocumentReindexer.RollingReindexResult result) {
        reportSkipped(sourceId, result.getBasicResult().getDocumentsSkipped());
        if (result.hasErrors() && result.getDocumentsIndexed() == 0) {
            LOGGER.error("[reindex-{}] Reindex failed completely with {} errors for "
                    + "source {}: {}", threadName, result.getErrorCount(), sourceId,
                errorDetail(result.getBasicResult().getErrors()));
            statusRegistry.failReindex(sourceId, countedFailure(result.getErrorCount()));
            return false;
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
        return true;
    }

    /**
     * Reindexes in place, on a node that has nowhere to put a replacement segment.
     *
     * @param threadName The worker thread, for the log
     * @param sourceId   The source being reindexed
     * @param source     The resolved source
     * @throws SourceException if the source cannot be read
     */
    private boolean runStandard(String threadName, String sourceId, Source source)
            throws SourceException {
        LOGGER.info("[reindex-{}] Using standard reindex (rolling replacement not configured) "
            + "for source: {}", threadName, sourceId);

        DocumentReindexer.ReindexResult result =
            reindexer.reindexAll(source, progressFor(sourceId));

        return reportStandard(threadName, sourceId, result);
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
    private boolean reportStandard(String threadName, String sourceId,
                                   DocumentReindexer.ReindexResult result) {
        reportSkipped(sourceId, result.getDocumentsSkipped());
        if (result.hasErrors() && result.getDocumentsIndexed() == 0) {
            LOGGER.error("[reindex-{}] Reindex failed completely with {} errors for "
                    + "source {}: {}", threadName, result.getErrorCount(), sourceId,
                errorDetail(result.getErrors()));
            statusRegistry.failReindex(sourceId, countedFailure(result.getErrorCount()));
            return false;
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
        return true;
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
