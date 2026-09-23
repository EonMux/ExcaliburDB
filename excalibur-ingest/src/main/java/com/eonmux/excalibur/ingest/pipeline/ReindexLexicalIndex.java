package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.processor.TextChunk;

import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The lexical index a reindex has to keep in step with storage.
 *
 * <h2>Why a reindex has to touch it at all</h2>
 * <p>A reindex is the one operation that removes content a query could still be matching: a
 * deleted document's chunks, and the tail chunks a shortened document no longer has. Left in this
 * index they stay searchable under identifiers whose storage entries are gone, so BM25 goes on
 * returning documents the corpus no longer contains.</p>
 *
 * <h2>Optional, and what that costs</h2>
 * <p>A node may keep no lexical index, and then every operation here is a no-op and the reindex
 * behaves exactly as it did before there was one. An index may not be wired without a way to read
 * a surviving chunk's text back: the index stores postings rather than text, so reclaiming an
 * erased chunk means rewriting the segments around it from what storage still holds, and a
 * reindexer that could erase but never reclaim would meet the visibility half of the promise and
 * never the disk half.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. The wiring is held in volatile fields because it is set after
 * construction, by the bootstrap that opens the index; the index itself carries its own
 * guarantees.</p>
 */
final class ReindexLexicalIndex {

    private static final Logger LOG = LoggerFactory.getLogger(ReindexLexicalIndex.class);

    /** The index, or null when this node keeps none. */
    private volatile ChunkIndex index;

    /** Reads a surviving chunk's text back out of storage, for the index's compaction. */
    private volatile Function<String, String> storedText;

    /**
     * Wires the index this reindex must keep in step with storage.
     *
     * @param index      The index, or null to keep none
     * @param storedText Reads a chunk's text back from storage, which is what compaction rebuilds
     *                   surviving segments from
     * @throws IllegalArgumentException if an index is given without a way to read text back
     */
    void use(ChunkIndex index, Function<String, String> storedText) {
        if (index != null && storedText == null) {
            throw new IllegalArgumentException(
                "A lexical index needs a text supplier: without one its erased chunks can never "
                    + "be reclaimed, which is the half of erasure that is a promise");
        }
        this.index = index;
        this.storedText = storedText;
        LOG.info("Reindex lexical index {}",
            index == null ? "disabled" : "enabled (" + index.engine() + " engine)");
    }

    /**
     * Indexes a published document's chunks.
     *
     * <p>Called once the document is published rather than as each chunk is written: a reindex
     * that fails partway leaves the previous version in place, and indexing the fragments it did
     * write would make half of the new version searchable beside all of the old one.</p>
     *
     * @param chunkIds The published chunk ids
     * @param chunks   Their text, positionally aligned with the ids
     */
    void index(List<String> chunkIds, List<TextChunk> chunks) {
        ChunkIndex target = this.index;
        if (target == null || chunkIds.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunkIds.size() && i < chunks.size(); i++) {
            try {
                target.add(chunkIds.get(i), chunks.get(i).getText());
            } catch (IOException e) {
                // The chunk is in storage and the document is published; losing one chunk from
                // BM25 costs recall on that chunk, where failing here would cost the document.
                LOG.warn("Chunk {} reindexed but not lexically indexed: {}",
                    chunkIds.get(i), e.getMessage());
            }
        }
    }

    /**
     * Takes retired chunks out of the index.
     *
     * <p>Storage has tombstoned them, so a search still returning them would report hits whose
     * text nothing can resolve - and for a deleted document, would go on answering with content
     * its owner asked to have removed.</p>
     *
     * @param chunkIds The chunks to stop returning
     * @return How many the index found and removed
     */
    int retire(Collection<String> chunkIds) {
        ChunkIndex target = this.index;
        if (target == null || chunkIds.isEmpty()) {
            return 0;
        }
        try {
            return target.erase(new LinkedHashSet<>(chunkIds));
        } catch (IOException e) {
            LOG.warn("{} retired chunk(s) remain lexically searchable: {}",
                chunkIds.size(), e.getMessage());
            return 0;
        }
    }

    /**
     * Publishes this run's writes and reclaims what it erased, if that is now due.
     *
     * <p>Both at the end of the run for the reason the ingestion pipeline does it there: a
     * refresh is what makes a batch searchable at one instant, and a run that has just erased
     * documents is the moment there is something to reclaim.</p>
     *
     * <p>The compaction reads {@code stopRequested} where it can end without publishing anything
     * half-done, so a stopped or shutting-down run is not held for a rewrite of the whole index;
     * the erased chunks stay owed to the next one. It is never interrupted instead: the index's
     * files are channels every query shares.</p>
     *
     * @param stopRequested Answers true once the compaction should end without publishing
     */
    void settle(BooleanSupplier stopRequested) {
        Objects.requireNonNull(stopRequested, "stopRequested must not be null");
        ChunkIndex target = this.index;
        if (target == null) {
            return;
        }
        try {
            target.refresh();
        } catch (IOException e) {
            LOG.warn("Lexical index not refreshed; this reindex stays unsearchable until the "
                + "next refresh: {}", e.getMessage());
            return;
        }
        Function<String, String> text = this.storedText;
        if (text == null) {
            return;
        }
        reclaim(target, text, stopRequested);
    }

    /** Rewrites the segments holding erased chunks, so they leave disk. */
    private void reclaim(ChunkIndex target, Function<String, String> text,
                         BooleanSupplier stopRequested) {
        try {
            long started = System.currentTimeMillis();
            CompactionResult result = target.compactIfDue(text, stopRequested);
            if (result.performed()) {
                LOG.info("Lexical compaction after reindex: {} segment(s) rewritten to {}, {} "
                        + "chunk(s) physically removed in {}ms ({})", result.segmentsRewritten(),
                    result.segmentsProduced(), result.documentsRemoved(),
                    System.currentTimeMillis() - started, result.reason());
            }
        } catch (IOException e) {
            LOG.warn("Lexical compaction did not run; erased chunks stay on disk until the next "
                + "attempt: {}", e.getMessage());
        }
    }
}
