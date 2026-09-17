package excalibur.ingest.pipeline;

import excalibur.ingest.erasure.DocumentErasureService;
import excalibur.ingest.registry.ChangeDetector;
import excalibur.ingest.registry.ChangeSet;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;

import excalibur.ingest.pipeline.DocumentReindexer.DocumentResult;
import excalibur.ingest.pipeline.DocumentReindexer.ReindexProgress;
import excalibur.ingest.pipeline.DocumentReindexer.ReindexResult;

import io.excalibur.core.text.ChunkIdentifiers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
     * <p>The pass is identical whether the run is writing through the routing directory or into
     * a segment created for a rolling replacement; this is the one thing that differs, so it is
     * the one thing the pass is given.</p>
     */
    @FunctionalInterface
    interface DocumentStep {

        /**
         * @param sourceId   The source the document belongs to
         * @param documentId The document
         * @param fetched    What the source handed back
         * @return The outcome, carrying the chunk ids the new version consists of
         */
        DocumentResult reindex(String sourceId, String documentId, FetchedDocument fetched);
    }

    /**
     * Which of the two routes this pass is taking, for the operator reading the log.
     *
     * <p>Carried as data rather than as two copies of the pass: the routes differ in where the
     * chunks land and in nothing else, and a second copy of the phases is a second place for the
     * ordering above to be got wrong.</p>
     */
    enum Route {

        /** Writing wherever the storage engine's routing directory sends each chunk. */
        THROUGH_ROUTING_DIRECTORY(
            "Starting re-index for source: {}",
            "Re-index complete for {}: {} docs indexed, {} chunks created, {} errors"),

        /** Writing into a segment created for a rolling replacement. */
        INTO_TARGET_SEGMENT(
            "Starting segment-targeted re-index for source: {}",
            "Segment-targeted re-index complete for {}: {} docs indexed, {} chunks created, "
                + "{} errors");

        private final String startMessage;
        private final String completeMessage;

        Route(String startMessage, String completeMessage) {
            this.startMessage = startMessage;
            this.completeMessage = completeMessage;
        }

        /** Logged before change detection, naming the source. */
        String startMessage() {
            return startMessage;
        }

        /** Logged once the run has ended, naming the source and its totals. */
        String completeMessage() {
            return completeMessage;
        }
    }

    private final DocumentRegistry registry;
    private final UpsertHandler upsertHandler;
    private final ReindexLexicalIndex lexical;

    /** Retires a removed document's graph nodes, or null on a node with no vector index. */
    private final DocumentErasureService.VectorEraser vectorEraser;

    private final DocumentStep documentStep;
    private final Route route;

    /**
     * @param registry      Holds the previous pass's record of the source, which is this pass's
     *                      entire input
     * @param upsertHandler Tombstones what the source no longer lists
     * @param lexical       The lexical index to keep in step with storage
     * @param vectorEraser  Retires a removed document's graph nodes, or null for none
     * @param documentStep  Re-indexes one document
     * @param route         Which of the two routes this pass is taking
     * @throws NullPointerException if any argument other than the eraser is null
     */
    SourceReindexPass(DocumentRegistry registry, UpsertHandler upsertHandler,
                      ReindexLexicalIndex lexical,
                      DocumentErasureService.VectorEraser vectorEraser,
                      DocumentStep documentStep, Route route) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.upsertHandler =
            Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.lexical = Objects.requireNonNull(lexical, "lexical must not be null");
        this.vectorEraser = vectorEraser;
        this.documentStep = Objects.requireNonNull(documentStep, "documentStep must not be null");
        this.route = Objects.requireNonNull(route, "route must not be null");
    }

    /**
     * Runs the pass.
     *
     * @param source   The source to re-index
     * @param progress Told how many documents the run has finished with
     * @return Result containing counts and any errors
     * @throws SourceException          if crawling or fetching fails catastrophically
     * @throws ReindexCancelledException if the run was asked to stop; what it finished before
     *                                   then is written out first
     */
    ReindexResult run(Source source, ReindexProgress progress) throws SourceException {
        String sourceId = source.getSourceId();
        LOG.info(route.startMessage(), sourceId);

        ChangeDetector detector = new ChangeDetector(registry);
        ChangeSet changeSet = detector.detectChanges(source);

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
            retireVectors(sourceId, changeSet);
            UpsertHandler.UpsertResult tombstoneResult = upsertHandler.processDeletions(changeSet);
            resultBuilder.documentsTombstoned(tombstoneResult.getDocumentsDeleted());
            chunksTombstoned = tombstoneResult.getChunksTombstoned();
            tombstoneResult.getErrors().forEach(error ->
                resultBuilder.addError("tombstone", error.getChunkId(), error.getMessage()));

            for (String newDocId : changeSet.getNewDocuments()) {
                // Before the fetch, so a run that has been asked to stop starts no new work.
                ReindexCancellation.checkpoint(sourceId, documentsDone.get(), documentsTotal);
                try {
                    FetchedDocument fetched = source.fetch(newDocId);
                    DocumentResult docResult = documentStep.reindex(sourceId, newDocId, fetched);
                    resultBuilder.addDocumentResult(docResult);
                } catch (SourceException e) {
                    ReindexCancellation.stopIfCancelled(sourceId, documentsDone.get(),
                        documentsTotal, e);
                    String msg = "Failed to fetch new document " + newDocId + ": "
                        + e.getMessage();
                    LOG.error(msg, e);
                    resultBuilder.addError("fetch", newDocId, msg);
                } catch (Exception e) {
                    ReindexCancellation.stopIfCancelled(sourceId, documentsDone.get(),
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
                ReindexCancellation.checkpoint(sourceId, documentsDone.get(), documentsTotal);
                try {
                    FetchedDocument fetched = source.fetch(changed.getDocumentId());
                    DocumentResult docResult =
                        documentStep.reindex(sourceId, changed.getDocumentId(), fetched);
                    resultBuilder.addDocumentResult(docResult);
                    chunksTombstoned += retireReplacedVersion(changed, docResult);
                } catch (SourceException e) {
                    ReindexCancellation.stopIfCancelled(sourceId, documentsDone.get(),
                        documentsTotal, e);
                    String msg = "Failed to fetch changed document " + changed.getDocumentId()
                        + ": " + e.getMessage();
                    LOG.error(msg, e);
                    resultBuilder.addError("fetch", changed.getDocumentId(), msg);
                } catch (Exception e) {
                    ReindexCancellation.stopIfCancelled(sourceId, documentsDone.get(),
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

        // The flag is cleared for the two writes below and set again on the way out. Both write
        // files, and a channel write on an interrupted thread throws ClosedByInterruptException
        // and closes that channel for good: the run would lose the registry entries it had
        // already earned, leaving published chunks that nothing names.
        Thread.interrupted();
        resultBuilder.chunksTombstoned(chunksTombstoned);
        lexical.settle();
        persistRegistry();
        ReindexResult result = resultBuilder.build();

        if (cancelled != null) {
            LOG.info("{} stopped on request after {} of {} document(s): {} indexed, {} chunks "
                    + "created", sourceId, documentsDone.get(), documentsTotal,
                result.getDocumentsIndexed(), result.getChunksCreated());
            // Set again only now: the endpoint tells a stop from a fault by reading the flag
            // first, and a run that cleared it and never put it back would be filed as an
            // outage.
            Thread.currentThread().interrupt();
            throw cancelled;
        }

        LOG.info(route.completeMessage(), sourceId, result.getDocumentsIndexed(),
            result.getChunksCreated(), result.getErrorCount());

        return result;
    }

    /**
     * Tombstones whatever remains of a changed document's previous version once its replacement
     * has been published.
     *
     * <p>Tombstoning before the replacement exists is what makes a failed re-embed destructive:
     * the old chunks are gone and nothing re-inserts them. Doing it here, after publication, means
     * a failed document simply keeps serving its previous version. Chunk ids are deterministic, so
     * the new version has already overwritten the ids it reuses; only the surplus tail is
     * removed.</p>
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
        List<String> obsolete = new ArrayList<>(changed.getOldMetadata().getChunkIds());
        obsolete.removeAll(docResult.getChunkIds());
        lexical.retire(obsolete);
        return upsertHandler.tombstoneObsoleteChunks(
            changed.getOldMetadata().getChunkIds(), docResult.getChunkIds());
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
    private int retireVectors(String sourceId, ChangeSet changeSet) {
        DocumentErasureService.VectorEraser eraser = this.vectorEraser;
        if (eraser == null) {
            return 0;
        }
        int retired = 0;
        int considered = 0;
        int deletedTotal = changeSet.getDeletedDocuments().size();
        for (DocumentMetadata deleted : changeSet.getDeletedDocuments()) {
            ReindexCancellation.checkpoint(sourceId, considered++, deletedTotal);
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
