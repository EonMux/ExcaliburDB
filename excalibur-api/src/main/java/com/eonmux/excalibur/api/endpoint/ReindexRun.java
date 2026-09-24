package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.core.util.StopSignal;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.pipeline.ReindexCancelledException;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
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
 * <p>A run is failed only when it indexed nothing and confirmed nothing current. Anything else
 * is a completed run, because failing on any error at all reported a run that reindexed four
 * hundred documents and could not read one of them as a failed reindex - which is the opposite
 * of what an operator would do about it. A document its source confirmed unchanged counts as
 * work done: a re-crawl of a source that has barely moved indexes little or nothing, and one
 * timeout among four hundred confirmed pages is not a failed run either. A completed run that did lose documents still says so in {@code last_error}, with how many,
 * because a readback that answered null for it could not be told from a clean run. Both the
 * rolling and the standard branch apply that same rule, deliberately: which of them ran is a
 * property of how the node is deployed, not something a caller asked for, and the two must not
 * disagree about whether the same corpus reindexed successfully.</p>
 *
 * <h2>Cancelled is not failed</h2>
 * <p>{@code DELETE /v1/reindex} requests the run's {@link StopSignal} and tells the caller to
 * poll the status for when it has stopped. The run ends by throwing, like a run that broke, and
 * recording that as a failure made the one readback the route points at unable to answer the
 * question the route was asked. So a run whose signal was requested, or that ended in
 * {@link ReindexCancelledException}, is recorded as cancelled - as is one ended by an interrupt
 * from anywhere else, found in its cause chain or on its thread.</p>
 *
 * <p>The signal, not an interrupt of the run's thread, because the run spends its time writing
 * through channels every writer of a segment shares, and an interrupt reaching one of those
 * writes closes the channel for all of them for the life of the process.</p>
 *
 * <h2>Progress is counted, not assumed</h2>
 * <p>What the status reports is what the reindexer says it has finished, document by document,
 * and what it has failed on so far. The three constants this class used to publish instead
 * described a run that nobody had measured, and in units - chunks, in the total - that nothing
 * else on the route used.</p>
 *
 * <h2>What the source's ingestion status is told</h2>
 * <p>Two numbers: how many of the documents the crawl listed were passed over because they are
 * not text, and how many documents the run left alone because their publisher forbids fetching
 * them - a site's robots.txt. A re-crawl applies both tests exactly as the first crawl did, so a
 * run that newly skipped three hundred photographs, or found a page newly disallowed, has to be
 * able to say so - and {@code entries_skipped} and {@code entries_disallowed} on
 * {@code GET /v1/ingest/status} are where the node says it. Nothing else on that status is
 * touched, because a reindex has its own readback for how far it got and how it ended.</p>
 *
 * <p>A run that was stopped, or broke, once it had begun on its documents reports the two as far
 * as it got: what it passed over is what the source is now known to hold, and the figure the run
 * before it left describes a corpus this one has looked at since. A run that ended before it
 * judged any document - stopped or broken while listing - has found nothing to replace them
 * with, and leaves them as they were.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Every field is final and the mutual exclusion that matters - one reindex per
 * source - is the caller's lease, taken before {@link #execute(String, StopSignal)} is
 * reached.</p>
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
     * <p>A node with a volume manager and a segment catalog takes the rolling route, which asks
     * the storage engine for a segment to rebuild the source into. The engine never offers one,
     * so that route ends in the same in-place rebuild the standard route runs directly; either
     * way each changed document's previous version serves until its replacement is published.
     * A node without the two has nothing to ask with and goes straight to the standard
     * route.</p>
     *
     * <p>No exception is allowed to escape: this runs on a detached virtual thread, so one
     * thrown here would be lost to the default handler and leave the source reading as
     * mid-reindex forever. An {@link Error} is rethrown, as nothing here can recover from one,
     * but only once the run has been recorded as failed.</p>
     *
     * @param sourceId The source to reindex
     * @return true when the run produced something, which is the same verdict the readback is
     *         given - a run that indexed nothing is the only kind that failed
     */
    public boolean execute(String sourceId) {
        return execute(sourceId, new StopSignal());
    }

    /**
     * As {@link #execute(String)}, for a run that can be asked to stop.
     *
     * <p>A stop is read by the reindexer between documents and never delivered as an interrupt;
     * see {@link DocumentReindexer#reindexAll(Source, DocumentReindexer.ReindexProgress,
     * StopSignal)}. The run returns with its thread's interrupt flag clear, whatever ended it,
     * because the thread is pooled or scheduled and the next task it serves would otherwise
     * close the first channel it touches.</p>
     *
     * @param sourceId The source to reindex
     * @param stop     Asks the run to stop at its next document boundary
     * @return true when the run produced something; false when it failed or was stopped
     */
    public boolean execute(String sourceId, StopSignal stop) {
        Objects.requireNonNull(stop, "stop cannot be null");
        String threadName = Thread.currentThread().getName();
        LOGGER.info("[reindex-{}] Starting reindex execution for source: {}", threadName, sourceId);

        Source source = null;
        RunReport report = null;
        try {
            source = sourceResolver.resolve(sourceId);
            if (source == null) {
                String error = "Source not found: " + sourceId;
                LOGGER.error("[reindex-{}] {}", threadName, error);
                statusRegistry.failReindex(sourceId, error);
                return false;
            }

            // 0.0, not an invented tenth: nothing has been counted yet, and the first thing
            // the reindexer publishes is the size of the run.
            statusRegistry.updateProgress(sourceId, STATE_CRAWLING, 0.0, 0, 0);

            report = new RunReport(sourceId);
            boolean useRollingReplacement = volumeManager != null && segmentCatalog != null
                && reindexer.hasRollingReplacement();
            return useRollingReplacement
                ? runRolling(threadName, sourceId, source, report, stop)
                : runStandard(threadName, sourceId, source, report, stop);

        } catch (Exception e) {
            reportEndedEarly(sourceId, source, report);
            if (wasCancelled(stop, e)) {
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
        } catch (Throwable t) {
            // An Error - out of memory, a stack overflow in a parser, a linkage failure - is not
            // this run's to swallow, so it goes on up. But it must not leave the source reading
            // as mid-reindex forever on its way: that is what the readback would say, and it is
            // what a caller polling it would wait on.
            LOGGER.error("[reindex-{}] Reindex of source {} ended by {}", threadName, sourceId,
                t.toString(), t);
            try {
                statusRegistry.failReindex(sourceId,
                    "Reindex failed (" + t.getClass().getSimpleName() + ")" + SEE_THE_LOG);
            } catch (Throwable recording) {
                t.addSuppressed(recording);
            }
            throw t;
        }
    }

    /**
     * Records on the source's ingestion status what a run that ended early had found.
     *
     * <p>Only once it had begun on its documents; see the class documentation. Kept from
     * throwing, since it runs on the way to recording the run's verdict and a failure here must
     * not cost the verdict.</p>
     *
     * @param sourceId The source being reindexed
     * @param source   The resolved source, or null when it was never resolved
     * @param report   What the run reported, or null when it never began
     */
    private void reportEndedEarly(String sourceId, Source source, RunReport report) {
        if (source == null || report == null || !report.begun()) {
            return;
        }
        try {
            reportSkipped(sourceId, report.notText());
            reportDisallowed(sourceId, source);
        } catch (RuntimeException e) {
            LOGGER.warn("What the stopped reindex of {} passed over was not recorded: {}",
                sourceId, e.getMessage());
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
    private void reportSkipped(String sourceId, long skipped) {
        if (ingestionStatusRegistry == null) {
            return;
        }
        IngestStatusEndpoint.IngestionStatus status = ingestionStatusRegistry.getStatus(sourceId);
        if (status != null) {
            status.setSkipped(skipped);
        }
    }

    /**
     * Records on the source's ingestion status how many documents this run was forbidden.
     *
     * <p>Read from the source rather than from the reindexer's result, because the source is what
     * was told no: the URLs the re-crawl never listed, which change detection only sees as
     * documents that have gone, and any fetch refused after it. Reported when it is zero for the
     * reason {@link #reportSkipped} gives.</p>
     *
     * @param sourceId The source that was reindexed
     * @param source   The source, which counted what its run was forbidden
     */
    private void reportDisallowed(String sourceId, Source source) {
        if (ingestionStatusRegistry == null) {
            return;
        }
        IngestStatusEndpoint.IngestionStatus status = ingestionStatusRegistry.getStatus(sourceId);
        if (status != null) {
            status.setDisallowed(source.getDisallowedCount());
        }
    }

    /**
     * Whether this run stopped because someone asked it to, rather than because it broke.
     *
     * <p>The run's own signal and the exception the reindexer ends a stopped run with are the
     * answer for every stop this node asks for. An interrupt from anywhere else is still a
     * request to stop, and takes whichever shape the blocked call gave it: a call that threw
     * {@link InterruptedException} cleared the flag on the way out and is findable only in the
     * cause chain, possibly several wrappers deep; a call interrupted inside a channel of its
     * own throws {@link ClosedByInterruptException}, an {@code IOException}; and a call that
     * noticed the interrupt and returned leaves only the flag - which is cleared here, into the
     * signal, so the thread goes back to its pool without it.</p>
     *
     * @param stop    The run's stop signal
     * @param failure What ended the run
     * @return true if the run was cancelled
     */
    private static boolean wasCancelled(StopSignal stop, Exception failure) {
        if (Thread.interrupted()) {
            stop.request();
        }
        if (stop.isRequested()) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ReindexCancelledException
                    || cause instanceof InterruptedException
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
     * The listener that turns the reindexer's counts into the status a caller polls, and keeps
     * what a run that ends early still has to report.
     *
     * <p>The phase is settled once, from what this node can do, rather than guessed per
     * document: a node with no embedding endpoint never enters an embedding phase, and
     * reporting one would be reporting work it does not perform.</p>
     *
     * <p>The count of documents passed over as not text is held here rather than written to
     * the ingestion status as it moves, because each write of that status to a settled source
     * rewrites its history on disk; it is written once, when the run ends.</p>
     */
    private final class RunReport implements DocumentReindexer.ReindexProgress {

        private final String sourceId;
        private final String state;
        private volatile boolean begun;
        private volatile long notText;

        RunReport(String sourceId) {
            this.sourceId = sourceId;
            this.state = reindexer.embedsChunks() ? STATE_EMBEDDING : STATE_INDEXING;
        }

        @Override
        public void documentsDone(long processed, long total) {
            begun = true;
            statusRegistry.updateProgress(sourceId, state, fractionDone(processed, total),
                processed, total);
        }

        @Override
        public void documentsCounted(long documentsFailed, long documentsNotText) {
            notText = documentsNotText;
            statusRegistry.recordFailures(sourceId, documentsFailed);
        }

        @Override
        public void documentsUnchecked(long documentsUnchecked, List<String> sample) {
            statusRegistry.recordUnchecked(sourceId, documentsUnchecked, sample);
        }

        /** Whether the run got past its listing and began on its documents. */
        boolean begun() {
            return begun;
        }

        /** Documents passed over as not text, as far as the run got. */
        long notText() {
            return notText;
        }
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
     * Reindexes by the rolling route, which asks for a segment to rebuild the source into.
     *
     * <p>A volume has to be chosen before the candidate segment can be created, and there may
     * not be one. That fails the run: a node whose volumes are all out of space has nowhere to
     * write what the rebuild produces either.</p>
     *
     * @param threadName The worker thread, which is how a run is followed through the log
     * @param sourceId   The source being reindexed
     * @param source     The resolved source
     * @param report     Told what the run does as it does it
     * @param stop       Asks the run to stop
     * @throws SourceException if the source cannot be read
     */
    private boolean runRolling(String threadName, String sourceId, Source source,
                               RunReport report, StopSignal stop)
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
            reindexer.reindexWithRollingReplacement(source, volumePath, report, stop);

        reportDisallowed(sourceId, source);
        return reportRolling(threadName, sourceId, result);
    }

    /**
     * Records the verdict on a rolling run, and says which segments changed hands.
     *
     * <p>The new segment id and the tombstoned ones are logged on both the clean and the partial
     * outcome because they are what an operator needs to inspect the result. A run that rebuilt
     * in place logs segment {@code -1} and none tombstoned, which is the answer to the same
     * question: nothing changed hands.</p>
     *
     * @param threadName The worker thread, for the log
     * @param sourceId   The source that was reindexed
     * @param result     What the rolling replacement produced
     */
    private boolean reportRolling(String threadName, String sourceId,
                                  DocumentReindexer.RollingReindexResult result) {
        reportSkipped(sourceId, result.getBasicResult().getDocumentsNotText());
        reportFailures(sourceId, result.getBasicResult());
        if (failedOutright(result.getBasicResult())) {
            LOGGER.error("[reindex-{}] Reindex failed completely with {} errors for "
                    + "source {}: {}", threadName, result.getErrorCount(), sourceId,
                errorDetail(result.getBasicResult().getErrors()));
            statusRegistry.failReindex(sourceId, countedFailure(result.getBasicResult()));
            return false;
        }
        if (result.hasErrors()) {
            LOGGER.warn("[reindex-{}] Rolling reindex completed with {} errors for source {}: "
                    + "{} docs indexed, {} old segments tombstoned, new segment {}: {}",
                threadName, result.getErrorCount(), sourceId, result.getDocumentsIndexed(),
                result.getTombstonedSegmentIds().size(), result.getNewSegmentId(),
                errorDetail(result.getBasicResult().getErrors()));
            statusRegistry.completeReindexWithErrors(sourceId,
                countedPartialFailure(result.getBasicResult()));
            return true;
        }
        LOGGER.info("[reindex-{}] Rolling reindex completed successfully for source {}: "
                + "{} docs indexed, {} old segments tombstoned, new segment {}",
            threadName, sourceId, result.getDocumentsIndexed(),
            result.getTombstonedSegmentIds().size(), result.getNewSegmentId());
        statusRegistry.completeReindex(sourceId);
        return true;
    }

    /**
     * Reindexes in place, on a node that has nowhere to put a replacement segment.
     *
     * @param threadName The worker thread, for the log
     * @param sourceId   The source being reindexed
     * @param source     The resolved source
     * @param report     Told what the run does as it does it
     * @param stop       Asks the run to stop
     * @throws SourceException if the source cannot be read
     */
    private boolean runStandard(String threadName, String sourceId, Source source,
                                RunReport report, StopSignal stop)
            throws SourceException {
        LOGGER.info("[reindex-{}] Using standard reindex (rolling replacement not configured) "
            + "for source: {}", threadName, sourceId);

        DocumentReindexer.ReindexResult result =
            reindexer.reindexAll(source, report, stop);

        reportDisallowed(sourceId, source);
        return reportStandard(threadName, sourceId, result);
    }

    /**
     * Records the verdict on an in-place run.
     *
     * <p>Applies the same rule the rolling branch does - failed only when nothing was indexed
     * and nothing confirmed current, finished with its failures counted otherwise - and reports
     * the counts an operator compares against the previous run to see whether the corpus
     * actually changed.</p>
     *
     * @param threadName The worker thread, for the log
     * @param sourceId   The source that was reindexed
     * @param result     What the reindex produced
     */
    private boolean reportStandard(String threadName, String sourceId,
                                   DocumentReindexer.ReindexResult result) {
        reportSkipped(sourceId, result.getDocumentsNotText());
        reportFailures(sourceId, result);
        if (failedOutright(result)) {
            LOGGER.error("[reindex-{}] Reindex failed completely with {} errors for "
                    + "source {}: {}", threadName, result.getErrorCount(), sourceId,
                errorDetail(result.getErrors()));
            statusRegistry.failReindex(sourceId, countedFailure(result));
            return false;
        }
        if (result.hasErrors()) {
            LOGGER.warn("[reindex-{}] Reindex completed with {} errors for {}: {} docs "
                    + "indexed, {} skipped, {} chunks created: {}",
                threadName, result.getErrorCount(), sourceId, result.getDocumentsIndexed(),
                result.getDocumentsSkipped(), result.getChunksCreated(),
                errorDetail(result.getErrors()));
            statusRegistry.completeReindexWithErrors(sourceId,
                countedPartialFailure(result));
            return true;
        }
        LOGGER.info("[reindex-{}] Reindex completed successfully for {}: {} docs "
                + "indexed, {} skipped, {} chunks created",
            threadName, sourceId, result.getDocumentsIndexed(),
            result.getDocumentsSkipped(), result.getChunksCreated());
        statusRegistry.completeReindex(sourceId);
        return true;
    }

    /**
     * Whether a run that ended produced nothing: it failed, and neither indexed a document nor
     * confirmed one current.
     *
     * @param result What the run produced
     * @return true for a failed run
     */
    private static boolean failedOutright(DocumentReindexer.ReindexResult result) {
        return result.hasErrors() && result.getDocumentsIndexed() == 0
            && result.getDocumentsUnchanged() == 0;
    }

    /**
     * Records the run's whole count of the documents it failed on and could not check.
     *
     * <p>The listener has reported them as they came; the result is the final word, and the
     * only word from a reindexer that reports nothing as it goes.</p>
     *
     * @param sourceId The source that was reindexed
     * @param result   What the run produced
     */
    private void reportFailures(String sourceId, DocumentReindexer.ReindexResult result) {
        statusRegistry.recordFailures(sourceId, result.getDocumentsFailed());
        statusRegistry.recordUnchecked(sourceId, result.getDocumentsUnchecked(),
            result.getUncheckedSample());
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
     * @param result What the run came to
     * @return What the caller is told
     */
    private static String countedFailure(DocumentReindexer.ReindexResult result) {
        return "Reindex failed completely with " + result.getErrorCount() + " errors"
            + keysTaken(result) + SEE_THE_LOG;
    }

    /**
     * The readback text for a run that finished and lost some documents on the way.
     *
     * <p>The counts and nothing else, for the reason {@link #SEE_THE_LOG} gives: the messages
     * name the documents and the paths and URLs they were read from.</p>
     *
     * @param result What the run came to
     * @return What the caller is told
     */
    private static String countedPartialFailure(DocumentReindexer.ReindexResult result) {
        return "Reindex completed with " + result.getErrorCount() + " errors"
            + keysTaken(result) + "; " + result.getDocumentsIndexed() + " documents indexed"
            + SEE_THE_LOG;
    }

    /**
     * The clause naming the failures a retry cannot cure, or nothing when there are none.
     *
     * @param result What the run came to
     * @return The clause, with its separator
     */
    private static String keysTaken(DocumentReindexer.ReindexResult result) {
        int keysTaken = result.getDocumentsKeyTaken();
        return keysTaken == 0 ? "" : ", " + DocumentRegistry.keysTakenNote(keysTaken);
    }
}
