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
     * Adds one chunk to the lexical index, if this node keeps one.
     *
     * <p>A failure here is logged and not rethrown. The chunk is already in storage and the
     * document is still worth publishing: losing one chunk from BM25 costs recall on that chunk,
     * while failing the document costs the document. The two are not the same size of loss.</p>
     */
    void add(String chunkId, String text) {
        ChunkIndex target = this.index;
        if (target == null) {
            return;
        }
        try {
            target.add(chunkId, text);
        } catch (IOException e) {
            LOGGER.warn("Chunk {} stored but not lexically indexed: {}", chunkId, e.getMessage());
        }
    }

    /**
     * Takes rolled-back chunks back out of the lexical index.
     *
     * <p>Storage no longer has them, so a search that still returned them would report hits whose
     * text nothing can resolve - a chunk id pointing at a document that was never published. The
     * chunks were indexed as they were written, so undoing the write has to undo that too.</p>
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

    /** Makes a run's lexical writes searchable. */
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
