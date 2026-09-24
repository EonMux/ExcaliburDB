package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.registry.ChangeDetector;
import com.eonmux.excalibur.ingest.registry.ChangeSet;
import com.eonmux.excalibur.ingest.registry.DocumentLocks;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchDisallowedException;
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
import java.util.List;
import java.util.Objects;
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
 * until its replacement has been written in full, because tombstoning first is what makes a
 * failed re-embed destructive. That retirement belongs to the {@link DocumentStep}, which makes it
 * under the document's lock while the document is still hidden, just ahead of the publication:
 * made here, after the step had published, it would leave the previous version's tail served
 * beside the new version until it went.</p>
 *
 * <h2>Why a removal takes the document's lock</h2>
 * <p>Change detection reads the registry once, at the start of the pass, and a removal acts on
 * what it read. A client may re-post the same document in between: its chunk ids are
 * deterministic, so the re-post writes exactly the ids the removal is about to tombstone, and
 * registers a new entry the removal is about to delete. So each document is removed under its
 * lock - the one ingestion, the reindex's own publication and an erasure all take - and only if
 * its registry entry is still the one change detection read. An entry that has changed or gone
 * belongs to a later write, which is left alone; the next pass reads it afresh. A removed
 * document's visibility state goes with it, as an erasure's does, so an in-flight mark left by an
 * attempt that could not publish it does not outlive the chunks it was hiding.</p>
 *
 * <h2>What the graph is told</h2>
 * <p>A removed document is reported to the graph through the same {@link GraphReporter} its
 * publications were, numbered under its lock, so whatever was staged from it is withdrawn and a
 * report of an earlier version still in flight cannot stage it again. Re-indexed documents are
 * reported by the step that publishes them. The registry is written only once the graph has
 * forced what it staged, for the reason ingestion orders the two that way.</p>
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

    /**
     * Retires a removed document's graph nodes and a changed one's surplus chunks' nodes, or null
     * on a node with no vector index.
     */
    private final DocumentErasureService.VectorEraser vectorEraser;

    /** Tells the graph about every document this pass removes. */
    private final GraphReporter graphReporter;

    /** Forgets a removed document's visibility state, or null on a node that publishes directly. */
    private final DocumentBatchPublisher publisher;

    private final DocumentStep documentStep;

    /**
     * What removing the documents a source no longer lists came to.
     *
     * <p>Accumulated as the removals go rather than returned at the end, so a pass stopped partway
     * through them still reports the ones it made.</p>
     */
    private static final class Removals {
        int documents;
        int chunksTombstoned;
        int vectorsRetired;
        int passedOver;
    }

    /**
     * What removing one document did, under its lock.
     *
     * @param stored      What tombstoning it in storage did
     * @param vectors     How many of its graph nodes were retired
     * @param publication Its removal, to report to the graph once the lock is released
     */
    private record Removed(UpsertHandler.UpsertResult stored, int vectors,
                           GraphReporter.Publication publication) {
    }

    /**
     * @param registry      Holds the previous pass's record of the source, which is this pass's
     *                      entire input
     * @param upsertHandler Tombstones what the source no longer lists
     * @param lexical       The lexical index to keep in step with storage
     * @param vectorEraser  Retires graph nodes of what this pass removes, or null for none
     * @param graphReporter Tells the graph about every document this pass removes
     * @param publisher     Forgets a removed document's visibility state, or null when the
     *                      reindex publishes directly and keeps none
     * @param documentStep  Re-indexes one document
     * @throws NullPointerException if any argument other than the eraser or the publisher is
     *                              null
     */
    SourceReindexPass(DocumentRegistry registry, UpsertHandler upsertHandler,
                      ReindexLexicalIndex lexical,
                      DocumentErasureService.VectorEraser vectorEraser,
                      GraphReporter graphReporter, DocumentBatchPublisher publisher,
                      DocumentStep documentStep) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.upsertHandler =
            Objects.requireNonNull(upsertHandler, "upsertHandler must not be null");
        this.lexical = Objects.requireNonNull(lexical, "lexical must not be null");
        this.vectorEraser = vectorEraser;
        this.graphReporter =
            Objects.requireNonNull(graphReporter, "graphReporter must not be null");
        this.publisher = publisher;
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
        Removals removals = new Removals();
        ReindexCancelledException cancelled = null;
        try {
            // Only removals are safe to apply before the replacement content exists: a changed
            // document removed now and then failing to re-embed would be lost in both forms.
            // Changed documents retire their surplus chunks once the replacement is written,
            // inside the step; see SupersededVersion.
            removeDeleted(sourceId, changeSet, stop, removals, resultBuilder);

            for (String newDocId : changeSet.getNewDocuments()) {
                // Before the fetch, so a run that has been asked to stop starts no new work.
                ReindexCancellation.checkpoint(stop, sourceId, documentsDone.get(),
                    documentsTotal);
                try {
                    FetchedDocument fetched = stop.interruptibly(() -> source.fetch(newDocId));
                    DocumentResult docResult =
                        documentStep.reindex(sourceId, newDocId, fetched, stop);
                    resultBuilder.addDocumentResult(docResult);
                    // Listed as new, but a client may have posted it since: the step retires
                    // whatever version the registry held when it wrote this one.
                    chunksTombstoned += docResult.getChunksRetired();
                } catch (FetchDisallowedException e) {
                    // The publisher said no, which is not a failure of this run; the source
                    // counts it for the status it reports.
                    LOG.info("Not indexing new document {}: {}", newDocId, e.getMessage());
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
                    chunksTombstoned += docResult.getChunksRetired();
                } catch (FetchDisallowedException e) {
                    // Change detection asked moments ago and was allowed, so only a redirect
                    // that has since moved onto a disallowed URL gets here. The indexed version
                    // stays until the next run, whose change detection reports it gone.
                    LOG.info("Not re-indexing changed document {}: {}",
                        changed.getDocumentId(), e.getMessage());
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
        logRemovals(sourceId, removals);
        resultBuilder.documentsTombstoned(removals.documents);
        resultBuilder.chunksTombstoned(chunksTombstoned + removals.chunksTombstoned);
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
     * Removes every document the source has stopped listing, one at a time under its lock.
     *
     * <p>Within each document the lexical index is erased first, then its graph nodes are
     * retired, then storage is tombstoned and the registry entry removed: a document that has
     * been deleted should stop being findable before its bytes go, not after. The graph nodes go
     * beside the lexical erasure and ahead of the storage tombstones. The tombstones reach the
     * graph as well, as DELETEs the integration worker replays, but only once it gets to them,
     * and until then a query sets the nodes aside only as far as its bounded tail scan reaches.
     * Retiring here is what makes the removal hold however far integration has fallen
     * behind.</p>
     *
     * <p>A <em>changed</em> document is not removed here: its replacement shares chunk ids with
     * it, and retiring by document would take the replacement's own nodes. Its surplus chunks are
     * retired by id by the step that writes the replacement, and the ids it overwrote are
     * superseded by the integration worker in log order; see {@link SupersededVersion}.</p>
     *
     * <p>Checked for cancellation per document, like the indexing loops and for the same
     * reason: a source that dropped a hundred thousand documents is exactly the run an operator
     * cancels, and one removal is a safe place to stop. Nothing is half-done - each document is
     * removed whole under its lock - and the next reindex detects the documents this one did not
     * reach as deleted again.</p>
     *
     * @throws ReindexCancelledException if the run was asked to stop
     */
    private void removeDeleted(String sourceId, ChangeSet changeSet, StopSignal stop,
                               Removals removals, ReindexResult.Builder resultBuilder) {
        List<DocumentMetadata> deleted = changeSet.getDeletedDocuments();
        int considered = 0;
        for (DocumentMetadata listed : deleted) {
            ReindexCancellation.checkpoint(stop, sourceId, considered++, deleted.size());
            String documentKey = ChunkIdentifiers.documentKey(sourceId, listed.getDocumentId());
            Removed removed = DocumentLocks.shared().inDocument(documentKey,
                () -> removeIfUnchanged(sourceId, documentKey, listed));
            if (removed == null) {
                removals.passedOver++;
                continue;
            }
            // Outside the lock, as ingestion reports: withdrawing staged facts may be slow, and
            // the removal was numbered under the lock, which is what orders it.
            graphReporter.report(removed.publication(), null);
            removals.documents += removed.stored().getDocumentsDeleted();
            removals.chunksTombstoned += removed.stored().getChunksTombstoned();
            removals.vectorsRetired += removed.vectors();
            removed.stored().getErrors().forEach(error ->
                resultBuilder.addError("tombstone", error.getChunkId(), error.getMessage()));
        }
    }

    /**
     * Removes one document, provided its registry entry is still the one change detection read.
     *
     * <p>Called under the document's lock. An entry that has changed was written by a re-post
     * since the listing, and one that has gone was erased; either way the removal would undo a
     * later write rather than the one it was decided on. The comparison is of the whole entry,
     * which carries the time it was indexed, so a re-post of identical content still counts as a
     * later write.</p>
     *
     * @return What the removal did, or null when the document was passed over
     */
    private Removed removeIfUnchanged(String sourceId, String documentKey,
                                      DocumentMetadata listed) {
        DocumentMetadata current = registry.get(sourceId, listed.getDocumentId());
        if (!listed.equals(current)) {
            LOG.info("Not removing {}: it was {} after this reindex listed its source", documentKey,
                current == null ? "erased" : "written again");
            return null;
        }
        lexical.retire(listed.getChunkIds());
        int vectors = retireVectors(documentKey);
        UpsertHandler.UpsertResult stored = upsertHandler.removeDocument(listed);
        if (publisher != null) {
            publisher.forget(sourceId, listed.getDocumentId());
        }
        return new Removed(stored, vectors, graphReporter.removed(sourceId, documentKey));
    }

    /**
     * Retires one removed document's graph nodes.
     *
     * @return How many nodes were retired
     */
    private int retireVectors(String documentKey) {
        DocumentErasureService.VectorEraser eraser = this.vectorEraser;
        if (eraser == null) {
            return 0;
        }
        try {
            return eraser.erase(documentKey);
        } catch (RuntimeException e) {
            // Reported rather than thrown, as the erasure service does: the chunks leaving
            // storage is the part this run can always complete, and a graph that will not take
            // the write must not cost that.
            LOG.warn("Document {} stays rankable by vector search: {}",
                documentKey, e.getMessage());
            return 0;
        }
    }

    /** Logs what the removals came to, when they came to anything. */
    private static void logRemovals(String sourceId, Removals removals) {
        if (removals.vectorsRetired > 0) {
            LOG.info("Reindex of {} retired {} graph node(s) of {} deleted document(s)",
                sourceId, removals.vectorsRetired, removals.documents);
        }
        if (removals.passedOver > 0) {
            LOG.info("Reindex of {} left {} document(s) it had listed as deleted: each was "
                + "written again or erased while the reindex ran", sourceId, removals.passedOver);
        }
    }

    /**
     * Writes the registry, so what this reindex changed survives a restart.
     *
     * <p>A reindex is the operation whose whole input is the registry's previous contents: it
     * compares what a source lists now against what was recorded last time. Not writing the result
     * would make the next run repeat this one.</p>
     */
    private void persistRegistry() {
        // The graph first, for the reason ingestion forces it first: a registry that reaches the
        // disk ahead of the facts extracted from the documents it lists records those facts as
        // taken, and nothing extracts them again.
        graphReporter.sync();
        try {
            registry.persist();
        } catch (IOException e) {
            LOG.warn("Document registry not persisted; the next reindex would repeat this one: {}",
                e.getMessage());
        }
    }
}
