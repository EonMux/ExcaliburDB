package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.processor.TextChunk;

import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The lexical index a reindex has to keep in step with storage.
 *
 * <h2>Why a reindex has to touch it at all</h2>
 * <p>A reindex removes content a query could still be matching: a deleted document's chunks, and
 * the tail chunks a shortened document no longer has. Left in this
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
 * <h2>When a run's writes become searchable</h2>
 * <p>On the interval ingestion refreshes on, {@code excalibur.ingest.lexical-refresh-interval-ms},
 * and when the run ends. A refresh publishes a segment on the native engine and reopens a reader
 * on Lucene, so it is paced rather than made per document; refreshing only at the end kept every
 * document a long re-crawl had republished out of BM25 until the last one was done, and on a
 * lexical-only node out of search. A refresh is only ever made between documents, and each
 * document reaches the index in one {@link ChunkIndex#addDocument} call, so no refresh publishes
 * part of one.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. The wiring is held in volatile fields because it is set after
 * construction, by the bootstrap that opens the index; the index itself carries its own
 * guarantees. A {@link Pacing} belongs to the one run that asked for it.</p>
 */
final class ReindexLexicalIndex {

    private static final Logger LOG = LoggerFactory.getLogger(ReindexLexicalIndex.class);

    /** The index, or null when this node keeps none. */
    private volatile ChunkIndex index;

    /** Reads a surviving chunk's text back out of storage, for the index's compaction. */
    private volatile Function<String, String> storedText;

    /** Longest a document a run in progress has published waits to become searchable. */
    private volatile long refreshIntervalNanos = TimeUnit.MILLISECONDS.toNanos(
        IngestionOrchestrator.DEFAULT_LEXICAL_REFRESH_INTERVAL_MS);

    /**
     * Sets how long a document a run in progress has published may wait to become lexically
     * searchable.
     *
     * @param intervalMs The interval in milliseconds
     * @throws IllegalArgumentException if the interval is not positive
     */
    void refreshEvery(long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException(
                "lexical refresh interval must be positive: " + intervalMs);
        }
        this.refreshIntervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMs);
    }

    /**
     * Starts pacing the refreshes of one run.
     *
     * @return A pacing whose interval starts now
     */
    Pacing pacing() {
        return new Pacing();
    }

    /**
     * Refreshes one run's writes once the interval has passed since the last time it did.
     *
     * <p>Not thread-safe: a run calls it from its own thread, between documents.</p>
     */
    final class Pacing {

        private long lastRefresh = System.nanoTime();

        private Pacing() {
        }

        /**
         * Publishes what the run has written and reclaims what it erased, if the interval has
         * passed.
         *
         * <p>Call it between documents, never inside one. A refresh that fails is logged and the
         * run goes on: the documents stay published and become searchable at the next refresh,
         * which is a delay, whereas failing the run would abandon the documents it had not
         * reached for the sake of ones it already had.</p>
         *
         * @param sourceId      The source being reindexed, for the log
         * @param stopRequested Answers true once a compaction should end without publishing
         */
        void refreshIfDue(String sourceId, BooleanSupplier stopRequested) {
            if (index == null || System.nanoTime() - lastRefresh < refreshIntervalNanos) {
                return;
            }
            try {
                settle(stopRequested);
            } catch (RuntimeException e) {
                LOG.warn("Lexical index not refreshed partway through reindexing {}; the "
                    + "documents republished so far become searchable by BM25 at the next "
                    + "refresh: {}", sourceId, e.getMessage());
            }
            lastRefresh = System.nanoTime();
        }
    }

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
     * Indexes a published document's chunks, as one unit.
     *
     * <p>Called once the document is published rather than as each chunk is written: a reindex
     * that fails partway leaves the previous version in place, and indexing the fragments it did
     * write would make half of the new version searchable beside all of the old one.</p>
     *
     * <p>Added in one call rather than chunk by chunk for the same reason, one step further on.
     * Runs over different sources share this index and refresh it on their own schedules, so a
     * refresh can land between any two calls made into it, and it would publish the chunks added
     * before it without the ones after. {@link ChunkIndex#addDocument} is published by a refresh
     * whole or not at all, as ingestion's documents are.</p>
     *
     * @param chunkIds The published chunk ids
     * @param chunks   Their text, positionally aligned with the ids
     */
    void index(List<String> chunkIds, List<TextChunk> chunks) {
        ChunkIndex target = this.index;
        if (target == null || chunkIds.isEmpty()) {
            return;
        }
        Map<String, String> byId = new LinkedHashMap<>(chunkIds.size() * 2);
        for (int i = 0; i < chunkIds.size() && i < chunks.size(); i++) {
            byId.put(chunkIds.get(i), chunks.get(i).getText());
        }
        try {
            target.addDocument(byId);
        } catch (IOException e) {
            // The chunks are in storage and the document is published; losing them from BM25
            // costs recall on this document, where failing here would cost the run.
            LOG.warn("{} chunk(s) reindexed but not lexically indexed: {}", byId.size(),
                e.getMessage());
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
     * <p>At the end of the run, and on its {@link Pacing} before then, for the reasons the
     * ingestion pipeline does both: a refresh is what makes a batch searchable at one instant,
     * and a run that has just erased documents is the moment there is something to reclaim.
     * Compaction is asked each time rather than only at the end, because every refresh on the
     * native engine adds a segment; the usual answer is that nothing is due.</p>
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
