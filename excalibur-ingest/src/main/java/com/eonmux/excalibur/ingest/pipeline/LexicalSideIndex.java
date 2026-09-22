package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * Takes rolled-back chunks back out of the lexical index.
     *
     * <p>A failed document is never added here, but its chunk identifiers may already be: a
     * re-crawl writes a changed document under the identifiers its previous version held, and
     * the rollback deletes what storage had under them. A search that went on returning the
     * previous version would report hits whose text nothing can resolve.</p>
     */
    void erase(List<String> chunkIds) {
        ChunkIndex target = this.index;
        if (target == null || chunkIds.isEmpty()) {
            return;
        }
        try {
            target.erase(new LinkedHashSet<>(chunkIds));
        } catch (IOException e) {
            LOGGER.warn("Rolled-back chunks of a failed document remain lexically searchable: {}",
                e.getMessage());
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
     */
    void compactIfDue() {
        ChunkIndex target = this.index;
        if (target == null) {
            return;
        }
        try {
            long started = System.currentTimeMillis();
            CompactionResult result = target.compactIfDue(StoredChunkText.of(storageEngine));
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
