package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The BM25 index kept alongside the chunks ingestion writes.
 *
 * <p>Every operation is a no-op when no index is configured, because a node that keeps none is a
 * supported deployment rather than a fault: its chunks are stored and vector-searchable and
 * simply not lexically searchable.</p>
 *
 * <p>No failure here is rethrown. The chunk is in storage either way, and losing one chunk from
 * BM25 costs recall on that chunk, while failing the document costs the document.</p>
 */
final class LexicalSideIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalSideIndex.class);

    private final StorageEngine storageEngine;

    /**
     * BM25 index over the chunks the orchestrator writes, or null when the node keeps none.
     *
     * <p>Written inside the same try that writes the chunks, so a document that fails and rolls
     * back does not leave its text searchable under a key nothing resolves.</p>
     */
    private volatile ChunkIndex index;

    /**
     * @param storageEngine Where chunk text is resolved from during compaction
     */
    LexicalSideIndex(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
    }

    /**
     * Points this side index at a lexical index, so stored chunks are also BM25-searchable.
     *
     * @param index The index, or null to keep none
     */
    void setIndex(ChunkIndex index) {
        this.index = index;
        LOGGER.info("Lexical index {}", index == null
            ? "disabled" : "enabled (" + index.engine() + " engine)");
    }

    /**
     * Adds a published document's chunks to the lexical index, if this node keeps one.
     *
     * <p>Once the document is published and not before. Indexed while its chunks were still
     * being written, a document could be made searchable by a refresh from another run before
     * storage had published it - or before it rolled back - and a lexical hit would then name a
     * chunk no query can see. The chunks go in together, so no refresh publishes part of one.</p>
     *
     * <p>A failure here is logged and not rethrown. The document is already in storage and still
     * worth having: losing it from BM25 costs recall on that document, while failing it costs the
     * document. The two are not the same size of loss.</p>
     *
     * @param documentKey The document, for the log
     * @param chunks      Chunk identifier to text
     */
    void addDocument(String documentKey, Map<String, String> chunks) {
        ChunkIndex target = this.index;
        if (target == null || chunks.isEmpty()) {
            return;
        }
        try {
            target.addDocument(chunks);
        } catch (IOException e) {
            LOGGER.warn("Document {} stored but not lexically indexed: {}",
                documentKey, e.getMessage());
        }
    }

    /**
     * Takes chunks storage is about to stop holding back out of the lexical index.
     *
     * <p>Two cases reach here: the chunks of a document that was rolled back, and the tail of a
     * previous version that a shorter replacement no longer reaches. A failed document is never
     * added here, but its chunk identifiers may already be, because a document ingested again is
     * written under the identifiers its previous version held. Either way a search that went on
     * returning these chunks would report hits whose text nothing can resolve.</p>
     *
     * @param chunkIds The chunks to retire
     * @return How many the index reports removing, zero when it keeps none or refused
     */
    int retire(Collection<String> chunkIds) {
        ChunkIndex target = this.index;
        if (target == null || chunkIds.isEmpty()) {
            return 0;
        }
        try {
            return target.erase(new LinkedHashSet<>(chunkIds));
        } catch (IOException e) {
            LOGGER.warn("{} retired chunk(s) remain lexically searchable: {}",
                chunkIds.size(), e.getMessage());
            return 0;
        }
    }

    /**
     * Makes the lexical writes so far searchable.
     *
     * <p>Every document added before the call, from any run: the index is one per node. What
     * that publishes is always whole documents, because each arrives in one
     * {@link ChunkIndex#addDocument} call.</p>
     */
    void refresh() {
        ChunkIndex target = this.index;
        if (target == null) {
            return;
        }
        try {
            target.refresh();
        } catch (IOException e) {
            LOGGER.warn("Lexical index not refreshed; this run's chunks stay unsearchable "
                + "until the next refresh: {}", e.getMessage());
        }
    }

    /**
     * Reclaims erased chunks if the index's policy says it is due.
     *
     * <p>Run at the end of an ingestion run rather than on a timer of its own: this is the moment
     * the index has just changed, and a node that is not ingesting is not accumulating anything to
     * reclaim. The usual outcome is that nothing is due, which costs a comparison of four
     * numbers.</p>
     *
     * <p>A compaction that is due can rewrite many segments, and the run it belongs to may be
     * stopped - or the node shut down - while it does. It reads {@code stopRequested} where it
     * can end without publishing anything half-done, and is never interrupted instead: the
     * index's files are channels every query shares, and an interrupt reaching the rewrite would
     * close them.</p>
     *
     * @param stopRequested Answers true once the compaction should end without publishing
     */
    void compactIfDue(BooleanSupplier stopRequested) {
        Objects.requireNonNull(stopRequested, "stopRequested cannot be null");
        ChunkIndex target = this.index;
        if (target == null) {
            return;
        }
        try {
            long started = System.currentTimeMillis();
            CompactionResult result =
                target.compactIfDue(StoredChunkText.of(storageEngine), stopRequested);
            if (result.performed()) {
                // The elapsed time is reported because queries wait on it: a compaction holds the
                // index exclusively while it rewrites. Merges pick the smallest segments and stay
                // brief; a compaction driven by an overdue erasure takes every segment holding a
                // tombstone and can take much longer, which is the deadline being met rather than
                // a fault, but is not something an operator should have to infer.
                LOGGER.info("Lexical compaction: {} segment(s) rewritten to {}, {} chunk(s) "
                    + "physically removed in {}ms ({})", result.segmentsRewritten(),
                    result.segmentsProduced(), result.documentsRemoved(),
                    System.currentTimeMillis() - started, result.reason());
            }
        } catch (IOException e) {
            LOGGER.warn("Lexical compaction did not run; erased chunks stay on disk "
                + "until the next attempt: {}", e.getMessage());
        }
    }
}
