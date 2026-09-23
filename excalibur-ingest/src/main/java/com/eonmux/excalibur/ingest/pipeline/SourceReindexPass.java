package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.registry.ChangeDetector;
import com.eonmux.excalibur.ingest.registry.ChangeSet;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.DocumentResult;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.ReindexProgress;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.ReindexResult;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.core.util.StopSignal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One pass over a source: what the source no longer lists is retired, what it has added or
 * changed is re-indexed, and the registry is written so the next pass starts from this one.
 *
 * <h2>Why the order of the phases is the whole design</h2>
 * <p>Removals go first because they are the only edits that are safe before the replacement
 * content exists, and within the removals the lexical index is erased before storage is
 * tombstoned: a deleted document should stop being findable before its bytes go, not after. A
 * <em>changed</em> document is the opposite case - nothing of its previous version is retired
 * until its replacement has actually been published, because tombstoning first is what makes a
 * failed re-embed destructive.</p>
 *
 * <h2>Why a document that could not be read still counts</h2>
 * <p>The progress counter advances in a {@code finally}. A run answers 202 and continues on a
 * background thread, so the counter is the only thing anyone can watch, and one that stalls on
 * the first unreadable document is indistinguishable from a run that has stopped.</p>
 *
 * <h2>Stopping</h2>
 * <p>The pass is stopped through its {@link StopSignal}, read between documents; see
 * {@link ReindexCancellation} for why never by an interrupt and why never inside a document. A
 * stopped pass still writes out what it finished - the lexical refresh and the registry - and
 * then throws {@link ReindexCancelledException}, with its thread's interrupt flag clear.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>An instance holds no mutable state of its own and is built per run, which is what keeps it
 * reading the lexical index and the vector eraser as they are wired now. Its collaborators carry
 * their own guarantees.</p>
 */
final class SourceReindexPass {

    private static final Logger LOG = LoggerFactory.getLogger(SourceReindexPass.class);

    /**
     * Re-indexes one document into wherever this pass is writing.
     *
     * <p>Given rather than built in: the pass owns the order of the phases and nothing about
     * how one document is chunked, embedded or written.</p>
     */
    @FunctionalInterface
    interface DocumentStep {

        /**
         * @param sourceId   The source the document belongs to
         * @param documentId The document
         * @param fetched    What the source handed back
         * @param stop       The run's stop signal, which may interrupt the step's waits on the
         *                   embedding endpoint and nothing else
         * @return The outcome, carrying the chunk ids the new version consists of
         */
        DocumentResult reindex(String sourceId, String documentId, FetchedDocument fetched,
                               StopSignal stop);
    }

    private final DocumentRegistry registry;
    private final UpsertHandler upsertHandler;
    private final ReindexLexicalIndex lexical;

    /** Retires a removed document's graph nodes, or null on a node with no vector index. */
    private final DocumentErasureService.VectorEraser vectorEraser;

    private final DocumentStep documentStep;

    /**
     * @param registry      Holds the previous pass's record of the source, which is this pass's
     *                      entire input
     * @param upsertHandler Tombstones what the source no longer lists
     * @param lexical       The lexical index to keep in step with storage
     * @param vectorEraser  Retires a removed document's graph nodes, or null for none
     * @param documentStep  Re-indexes one document
     * @throws NullPointerException if any argument other than the eraser is null
     */
    SourceReindexPass(DocumentRegistry registry, UpsertHandler upsertHandler,
                      ReindexLexicalIndex lexical,
                      DocumentErasureService.VectorEraser vectorEraser,
                      DocumentStep documentStep) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.upsertHandler =
            Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.lexical = Objects.requireNonNull(lexical, "lexical must not be null");
        this.vectorEraser = vectorEraser;
        this.documentStep = Objects.requireNonNull(documentStep, "documentStep must not be null");
    }

    /**
     * Runs the pass.
     *
     * <p>Change detection crawls the source and may be interrupted by the stop, as the fetches
     * may: both wait on the source and touch nothing this node shares.</p>
     *
     * @param source   The source to re-index
     * @param progress Told how many documents the run has finished with
     * @param stop     The run's stop signal
     * @return Result containing counts and any errors
     * @throws SourceException          if crawling or fetching fails catastrophically
     * @throws ReindexCancelledException if the run was asked to stop; what it finished before
     *                                   then is written out first
     */
    ReindexResult run(Source source, ReindexProgress progress, StopSignal stop)
            throws SourceException {
        String sourceId = source.getSourceId();
        LOG.info("Starting re-index for source: {}", sourceId);

        ChangeDetector detector = new ChangeDetector(registry);
        ChangeSet changeSet;
        try {
            changeSet = stop.interruptibly(() -> detector.detectChanges(source));
        } catch (SourceException | RuntimeException e) {
            ReindexCancellation.stopIfCancelled(stop, sourceId, 0, 0, e);
            throw e;
        }
        // A stop that landed during the crawl may have cut it short without a word - a source
        // that treats an interrupted listing as its end - and a change set built from a short
        // listing reads every document it missed as deleted. Nothing has been written yet, so
        // this is also the cheapest place to stop.
        ReindexCancellation.checkpoint(stop, sourceId, 0,
            (long) changeSet.getNewCount() + changeSet.getChangedCount());

        LOG.info("Change detection complete for {}: new={}, changed={}, deleted={}",
                sourceId, changeSet.getNewCount(), changeSet.getChangedCount(),
                changeSet.getDeletedCount());

        ReindexResult.Builder resultBuilder = ReindexResult.builder(sourceId);

        // Published before the first document rather than after it: the size of the run is the
        // one number a caller cannot infer from anything else, and a status that reports a
        // total only once the run is over reports it to nobody who needed it.
        long documentsTotal = (long) changeSet.getNewCount() + changeSet.getChangedCount();
        AtomicInteger documentsDone = new AtomicInteger();
        progress.documentsDone(0, documentsTotal);

        int chunksTombstoned = 0;
        ReindexCancelledException cancelled = null;
        try {
            // Only removals are safe to apply before the replacement content exists; see
            // UpsertHandler.processDeletions. The lexical index is erased first: a document that
            // has been deleted should stop being findable before its bytes go, not after.
            lexical.retire(deletedChunkIds(changeSet));
            retireVectors(sourceId, changeSet, stop);
            UpsertHandler.UpsertResult tombstoneResult = upsertHandler.processDeletions(changeSet);
            resultBuilder.documentsTombstoned(tombstoneResult.getDocumentsDeleted());
            chunksTombstoned = tombstoneResult.getChunksTombstoned();
            tombstoneResult.getErrors().forEach(error ->
                resultBuilder.addError("tombstone", error.getChunkId(), error.getMessage()));

            for (String newDocId : changeSet.getNewDocuments()) {
                // Before the fetch, so a run that has been asked to stop starts no new work.
                ReindexCancellation.checkpoint(stop, sourceId, documentsDone.get(),
                    documentsTotal);
                try {
                    FetchedDocument fetched = stop.interruptibly(() -> source.fetch(newDocId));
                    DocumentResult docResult =
                        documentStep.reindex(sourceId, newDocId, fetched, stop);
                    resultBuilder.addDocumentResult(docResult);
                } catch (SourceException e) {
                    ReindexCancellation.stopIfCancelled(stop, sourceId, documentsDone.get(),
                        documentsTotal, e);
                    String msg = "Failed to fetch new document " + newDocId + ": "
                        + e.getMessage();
                    LOG.error(msg, e);
                    resultBuilder.addError("fetch", newDocId, msg);
                } catch (Exception e) {
                    ReindexCancellation.stopIfCancelled(stop, sourceId, documentsDone.get(),
                        documentsTotal, e);
                    String msg = "Failed to re-index new document " + newDocId + ": "
                        + e.getMessage();
                    LOG.error(msg, e);
                    resultBuilder.addError("reindex", newDocId, msg);
                } finally {
                    // In a finally, so a document that could not be read advances the counter
                    // too. A run that stalls its counter on the first unreadable document is
                    // indistinguishable from one that has stopped.
                    progress.documentsDone(documentsDone.incrementAndGet(), documentsTotal);
                }
            }

            for (ChangeSet.ChangedDocument changed : changeSet.getChangedDocuments()) {
                ReindexCancellation.checkpoint(stop, sourceId, documentsDone.get(),
                    documentsTotal);
                try {
                    FetchedDocument fetched =
                        stop.interruptibly(() -> source.fetch(changed.getDocumentId()));
                    DocumentResult docResult =
                        documentStep.reindex(sourceId, changed.getDocumentId(), fetched, stop);
                    resultBuilder.addDocumentResult(docResult);
                    chunksTombstoned += retireReplacedVersion(changed, docResult);
                } catch (SourceException e) {
                    ReindexCancellation.stopIfCancelled(stop, sourceId, documentsDone.get(),
                        documentsTotal, e);
                    String msg = "Failed to fetch changed document " + changed.getDocumentId()
                        + ": " + e.getMessage();
                    LOG.error(msg, e);
                    resultBuilder.addError("fetch", changed.getDocumentId(), msg);
                } catch (Exception e) {
                    ReindexCancellation.stopIfCancelled(stop, sourceId, documentsDone.get(),
                        documentsTotal, e);
                    String msg = "Failed to re-index changed document " + changed.getDocumentId()
                        + ": " + e.getMessage();
                    LOG.error(msg, e);
                    resultBuilder.addError("reindex", changed.getDocumentId(), msg);
                } finally {
                    progress.documentsDone(documentsDone.incrementAndGet(), documentsTotal);
                }
            }
        } catch (ReindexCancelledException e) {
            cancelled = e;
        }

        // An interrupt from elsewhere is cleared into the signal before the two writes below.
        // Both write files, and a channel write on an interrupted thread throws
        // ClosedByInterruptException and closes that channel for good: the run would lose the
        // registry entries it had already earned, leaving published chunks that nothing names.
        ReindexCancellation.requested(stop);
        resultBuilder.chunksTombstoned(chunksTombstoned);
        lexical.settle(stop);
        persistRegistry();
        ReindexResult result = resultBuilder.build();

        if (cancelled != null) {
            LOG.info("{} stopped on request after {} of {} document(s): {} indexed, {} chunks "
                    + "created", sourceId, documentsDone.get(), documentsTotal,
                result.getDocumentsIndexed(), result.getChunksCreated());
            throw cancelled;
        }

        LOG.info("Re-index complete for {}: {} docs indexed, {} chunks created, {} errors",
            sourceId, result.getDocumentsIndexed(), result.getChunksCreated(),
            result.getErrorCount());

        return result;
    }

    /**
     * Tombstones whatever remains of a changed document's previous version once its replacement
     * has been published; see {@link SupersededVersion} for why only then and only the surplus.
     *
     * @param changed   The changed document, carrying the previous version's metadata
     * @param docResult The outcome of re-indexing it
     * @return The number of obsolete chunks tombstoned
     */
    private int retireReplacedVersion(ChangeSet.ChangedDocument changed, DocumentResult docResult) {
        if (docResult.getSuccessfulChunks() == 0) {
            LOG.warn("Keeping previous version of document {}: replacement produced no chunks",
                changed.getDocumentId());
            return 0;
        }
        return SupersededVersion.retire(changed.getOldMetadata().getChunkIds(),
            docResult.getChunkIds(), lexical::retire, upsertHandler);
    }

    /**
     * Retires the graph nodes of every document the source has stopped listing.
     *
     * <p>Beside the lexical erasure and for the same reason: storage tombstones the chunks and
     * the registry stops naming them, but a graph node is attributed to a document rather than
     * to a chunk, so nothing either of those does reaches it. Left behind, the document goes on
     * being ranked by vector search - reported by name, with a distance, and with no text.</p>
     *
     * <p>A <em>changed</em> document's surplus tail is deliberately not retired here. Attribution
     * is per document, so the only call available would retire the replacement's nodes as well as
     * the previous version's, and the integration worker has no reason to insert them again.</p>
     *
     * <p>Checked for cancellation per document, like the indexing loops and for the same
     * reason: a source that dropped a hundred thousand documents is exactly the run an operator
     * cancels, and one retirement is a safe place to stop. Nothing here is half-done - a
     * document's nodes go in one call - and nothing downstream has run yet, so the next reindex
     * detects the same deletions and retires the rest.</p>
     *
     * @return How many nodes were retired
     * @throws ReindexCancelledException if the run was asked to stop
     */
    private int retireVectors(String sourceId, ChangeSet changeSet, StopSignal stop) {
        DocumentErasureService.VectorEraser eraser = this.vectorEraser;
        if (eraser == null) {
            return 0;
        }
        int retired = 0;
        int considered = 0;
        int deletedTotal = changeSet.getDeletedDocuments().size();
        for (DocumentMetadata deleted : changeSet.getDeletedDocuments()) {
            ReindexCancellation.checkpoint(stop, sourceId, considered++, deletedTotal);
            String documentKey = ChunkIdentifiers.documentKey(sourceId, deleted.getDocumentId());
            try {
                retired += eraser.erase(documentKey);
            } catch (RuntimeException e) {
                // Reported rather than thrown, as the erasure service does: the chunks leaving
                // storage is the part this run can always complete, and a graph that will not
                // take the write must not cost that.
                LOG.warn("Document {} stays rankable by vector search: {}",
                    documentKey, e.getMessage());
            }
        }
        if (retired > 0) {
            LOG.info("Reindex of {} retired {} graph node(s) of {} deleted document(s)",
                sourceId, retired, changeSet.getDeletedDocuments().size());
        }
        return retired;
    }

    /** The chunk ids of every document this change set says has been deleted outright. */
    private static Set<String> deletedChunkIds(ChangeSet changeSet) {
        Set<String> ids = new LinkedHashSet<>();
        for (DocumentMetadata deleted : changeSet.getDeletedDocuments()) {
            ids.addAll(deleted.getChunkIds());
        }
        return ids;
    }

    /**
     * Writes the registry, so what this reindex changed survives a restart.
     *
     * <p>A reindex is the operation whose whole input is the registry's previous contents: it
     * compares what a source lists now against what was recorded last time. Not writing the result
     * would make the next run repeat this one.</p>
     */
    private void persistRegistry() {
        try {
            registry.persist();
        } catch (IOException e) {
            LOG.warn("Document registry not persisted; the next reindex would repeat this one: {}",
                e.getMessage());
        }
    }
}
