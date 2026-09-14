package io.excalibur.vector.hnsw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Tracks the integration point between the write log and HNSW graph.
 *
 * <p>This class manages the {@code lastIntegratedOffset} which tracks the write log
 * offset up to which all entries have been linked into the HNSW graph. Entries
 * beyond this offset exist in the write log but are not yet in the graph.</p>
 *
 * <h2>Offset Semantics (CRITICAL)</h2>
 * <p>All offsets passed to integration methods ({@link #integrateVector},
 * {@link #integrateBatch}, {@link #markIntegrated}, {@link #markBatchIntegrated})
 * MUST be <strong>END offsets</strong> - the first byte position after the entry.</p>
 *
 * <p>The {@code lastIntegratedOffset} represents "all entries up to but not including
 * this position are integrated". This aligns with how the
 * {@code HnswIntegrationWorker} passes offsets.</p>
 *
 * <h2>Purpose</h2>
 * <p>This offset is critical for the live-indexing tail scan (Phase 6):
 * <ul>
 *   <li>When {@code lastIntegratedOffset < segment.writeLogHead}, a brute-force
 *       tail scan is performed over the un-integrated portion of the write log</li>
 *   <li>The tail scan computes exact distances on full-precision vectors</li>
 *   <li>Tail size is bounded by backpressure (Phase 5)</li>
 * </ul>
 * </p>
 *
 * <h2>Integration Workflow</h2>
 * <ol>
 *   <li>Write log receives new vector entries</li>
 *   <li>Background worker reads entries from write log</li>
 *   <li>Worker inserts entries into HNSW graph</li>
 *   <li>On success, worker updates lastIntegratedOffset via this tracker</li>
 *   <li>Offset is persisted to disk for crash recovery</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>All offset updates are atomic using CAS operations</li>
 *   <li>Offset can only increase (monotonic guarantee)</li>
 *   <li>Read operations are lock-free</li>
 *   <li>Persistence operations are synchronized</li>
 * </ul>
 *
 * <h2>Domain Constraints</h2>
 * <ul>
 *   <li>Offset MUST be monotonically increasing (never decrease)</li>
 *   <li>Offset MUST be persisted atomically with graph updates</li>
 *   <li>Offset MUST be recoverable after crash</li>
 *   <li>Offset updates MUST be thread-safe</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class TailOffsetTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(TailOffsetTracker.class);

    /**
     * The graph this tracker is associated with.
     */
    private final HnswGraph graph;

    /**
     * The current last integrated offset.
     * Updated atomically to ensure thread safety.
     */
    private final AtomicLong lastIntegratedOffset;

    /**
     * Lock for coordinating persistence operations.
     */
    private final ReentrantReadWriteLock persistenceLock = new ReentrantReadWriteLock();

    /**
     * Creates a new TailOffsetTracker for the given graph.
     *
     * @param graph The HNSW graph to track
     * @throws NullPointerException if graph is null
     */
    public TailOffsetTracker(HnswGraph graph) {
        if (graph == null) {
            throw new NullPointerException("Graph cannot be null");
        }
        this.graph = graph;
        this.lastIntegratedOffset = new AtomicLong(graph.getLastIntegratedOffset());
        LOGGER.debug("TailOffsetTracker initialized for graph at {} with offset {}",
            graph.path(), lastIntegratedOffset.get());
    }

    // ==================== Offset Query Operations ====================

    /**
     * Returns the last integrated offset.
     *
     * <p>This is the write log offset up to which all entries have been
     * successfully integrated into the HNSW graph.</p>
     *
     * @return The last integrated offset (always >= 0)
     */
    public long getLastIntegratedOffset() {
        return lastIntegratedOffset.get();
    }

    /**
     * Checks if there are un-integrated entries in the write log.
     *
     * @param writeLogHead The current write log head offset
     * @return true if there are entries beyond the integrated offset
     */
    /**
     * Rebases the checkpoint onto a replacement write log.
     *
     * <p>Only for the case where the segment's log has been replaced wholesale — compaction
     * discards the old log and starts an empty one, and the checkpoint is a byte offset into a file
     * that no longer exists. Left alone it sits above the new log's head and every subsequent
     * append is skipped, so the segment quietly stops being indexed.
     *
     * <p>Not monotone, unlike everything else here. See
     * {@link HnswGraph#resetLastIntegratedOffset(long)}.
     *
     * @param offset the new checkpoint, normally the origin of the replacement log
     */
    public void resetTo(long offset) {
        // Both copies. This tracker caches the offset in its own AtomicLong, which every read here
        // goes through, so persisting to the graph alone would leave the tracker answering from a
        // stale value and the rebase would appear to do nothing.
        graph.resetLastIntegratedOffset(offset);
        lastIntegratedOffset.set(offset);
    }

    public boolean hasTailEntries(long writeLogHead) {
        return writeLogHead > lastIntegratedOffset.get();
    }

    /**
     * Returns the size of the tail (un-integrated entries).
     *
     * @param writeLogHead The current write log head offset
     * @return Number of bytes in the tail, or 0 if fully integrated
     */
    public long getTailSize(long writeLogHead) {
        long current = lastIntegratedOffset.get();
        return Math.max(0, writeLogHead - current);
    }

    /**
     * Returns the range of offsets that need to be integrated.
     *
     * @param writeLogHead The current write log head offset
     * @return An array of [startOffset, endOffset] for tail scan, or null if no tail
     */
    public long[] getTailRange(long writeLogHead) {
        long start = lastIntegratedOffset.get();
        if (writeLogHead <= start) {
            return null; // No tail
        }
        return new long[] { start, writeLogHead };
    }

    /**
     * Integrates a single vector and updates the offset.
     *
     * <p>This is a convenience method that combines insertion and offset update
     * in a single atomic operation.</p>
     *
     * <p><strong>Offset semantics:</strong> The writeLogOffset should be the END offset
     * (the first byte position after the entry). This aligns with how
     * {@code HnswIntegrationWorker} passes offsets.</p>
     *
     * @param vector The vector to integrate
     * @param writeLogOffset The END offset for this vector (first byte after entry)
     * @return The insertion result
     */
    public IncrementalInsertResult integrateVector(float[] vector, long writeLogOffset) {
        IncrementalInsertResult result = graph.insertWithResult(vector, writeLogOffset);
        if (result.isSuccess() && writeLogOffset >= 0) {
            // writeLogOffset is the END offset (first byte after entry), so use it directly
            compareAndSetOffset(writeLogOffset);
        }
        return result;
    }

    /**
     * Integrates a batch of vectors and updates the offset.
     *
     * <p>This is a convenience method that combines batch insertion and offset update.</p>
     *
     * <p><strong>Offset semantics:</strong> The writeLogOffsets should contain END offsets
     * (the first byte position after each entry). This aligns with how
     * {@code HnswIntegrationWorker} passes offsets from its
     * integration loop.</p>
     *
     * @param vectors The vectors to integrate
     * @param writeLogOffsets The corresponding END offsets (first byte after each entry)
     * @return List of insertion results
     */
    public java.util.List<IncrementalInsertResult> integrateBatch(
            java.util.List<float[]> vectors, java.util.List<Long> writeLogOffsets) {
        java.util.List<IncrementalInsertResult> results = graph.insertBatchWithResults(vectors, writeLogOffsets);

        // Find maximum offset from successful insertions
        // writeLogOffsets are END offsets (first byte after entry), so use them directly
        // lastIntegratedOffset represents "all entries up to but not including this position are integrated"
        long maxOffset = lastIntegratedOffset.get();
        for (IncrementalInsertResult result : results) {
            if (result.isSuccess() && result.writeLogOffset() >= 0) {
                maxOffset = Math.max(maxOffset, result.writeLogOffset());
            }
        }

        if (maxOffset > lastIntegratedOffset.get()) {
            compareAndSetOffset(maxOffset);
        }

        return results;
    }

    /**
     * Marks an insertion result as integrated by updating the offset.
     *
     * <p><strong>Offset semantics:</strong> The result's writeLogOffset should be
     * the END offset (first byte after the entry).</p>
     *
     * @param result The insertion result to mark (must have END offset)
     * @return true if the offset was updated
     */
    public boolean markIntegrated(IncrementalInsertResult result) {
        if (result == null || !result.isSuccess()) {
            return false;
        }
        long writeLogOffset = result.writeLogOffset();
        if (writeLogOffset < 0) {
            return false;
        }
        // writeLogOffset is the END offset, use directly
        return compareAndSetOffset(writeLogOffset);
    }

    /**
     * Marks a batch of insertion results as integrated.
     *
     * <p><strong>Offset semantics:</strong> The results' writeLogOffsets should be
     * END offsets (first byte after each entry).</p>
     *
     * @param results The insertion results to mark (must have END offsets)
     * @return The number of results that were marked
     */
    public int markBatchIntegrated(Iterable<IncrementalInsertResult> results) {
        if (results == null) {
            return 0;
        }

        long maxOffset = lastIntegratedOffset.get();
        int count = 0;

        for (IncrementalInsertResult result : results) {
            if (result != null && result.isSuccess() && result.writeLogOffset() >= 0) {
                // writeLogOffset is the END offset, use directly
                long entryEndOffset = result.writeLogOffset();
                if (entryEndOffset > maxOffset) {
                    maxOffset = entryEndOffset;
                }
                count++;
            }
        }

        if (maxOffset > lastIntegratedOffset.get()) {
            compareAndSetOffset(maxOffset);
        }

        return count;
    }

    // ==================== Offset Update Operations ====================

    /**
     * Sets the last integrated offset with atomic persistence guarantee.
     *
     * <p><strong>Domain constraint:</strong> The new offset must be greater than
     * the current offset. This method will not decrease the offset.</p>
     *
     * <p><strong>Atomicity and persistence guarantee:</strong> This method ensures
     * that the offset is both atomically updated in memory AND persisted to disk
     * before returning. The implementation guarantees:</p>
     * <ul>
     *   <li>Persisted value is always &lt;= in-memory value</li>
     *   <li>On crash recovery, entries between persisted and in-memory values will be replayed</li>
     *   <li>Monotonic increase is guaranteed (offset never decreases)</li>
     * </ul>
     *
     * @param newOffset The new offset value (must be >= 0)
     * @return true if the offset was updated, false if {@code newOffset <= current}
     * @throws IllegalArgumentException if newOffset is negative
     */
    public boolean setLastIntegratedOffset(long newOffset) {
        if (newOffset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + newOffset);
        }

        persistenceLock.writeLock().lock();
        try {
            // Check monotonicity first
            long current = lastIntegratedOffset.get();
            if (newOffset <= current) {
                LOGGER.debug("Offset not updated: newOffset={} <= current={}", newOffset, current);
                return false;
            }

            // Delegate to graph which handles persistence ordering correctly:
            // 1. Write to mapped file (persists to page cache)
            // 2. Update in-memory atomic
            graph.setLastIntegratedOffset(newOffset);

            // Update our local atomic to match
            lastIntegratedOffset.set(newOffset);

            LOGGER.debug("Updated lastIntegratedOffset from {} to {}", current, newOffset);
            return true;
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    /**
     * Attempts to atomically update the offset if the new value is greater.
     *
     * <p>This is a lock-free operation that uses CAS (compare-and-swap) to
     * ensure thread-safe updates without blocking readers.</p>
     *
     * <p><strong>Persistence ordering:</strong> After successful CAS, the offset
     * is persisted to the graph. The ordering ensures persisted &lt;= in-memory.</p>
     *
     * @param newOffset The new offset value
     * @return true if the offset was updated
     */
    public boolean compareAndSetOffset(long newOffset) {
        if (newOffset < 0) {
            return false;
        }

        // Use the graph's CAS method which handles persistence ordering
        boolean updated = graph.compareAndSetLastIntegratedOffset(newOffset);
        if (updated) {
            // Accumulate the maximum rather than blind-setting: a concurrent caller
            // may already have advanced the local offset past newOffset, and a plain
            // set() would move the tracker backwards behind the graph.
            lastIntegratedOffset.accumulateAndGet(newOffset, Math::max);
        }
        return updated;
    }

    /**
     * Atomically updates the offset with explicit disk sync for durability.
     *
     * <p>This method provides stronger durability guarantees by flushing
     * the offset to durable storage. Use for critical checkpoints.</p>
     *
     * <p><strong>Performance note:</strong> This method may block on I/O.
     * Use sparingly.</p>
     *
     * @param newOffset The new offset value
     * @return true if the offset was updated
     */
    public boolean compareAndSetOffsetSync(long newOffset) {
        if (newOffset < 0) {
            return false;
        }

        persistenceLock.writeLock().lock();
        try {
            long current = lastIntegratedOffset.get();
            if (newOffset <= current) {
                return false;
            }

            // Use graph's sync method for explicit disk flush
            boolean updated = graph.compareAndSetLastIntegratedOffsetSync(newOffset);
            if (updated) {
                lastIntegratedOffset.set(newOffset);
            }
            return updated;
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    // ==================== Persistence Operations ====================

    /**
     * Flushes the current offset to persistent storage.
     *
     * <p>This ensures the offset survives a crash. Called automatically
     * after offset updates, but can be called explicitly for additional
     * durability guarantees.</p>
     */
    public void flush() {
        persistenceLock.writeLock().lock();
        try {
            graph.setLastIntegratedOffset(lastIntegratedOffset.get());
            graph.flush();
            LOGGER.debug("Flushed tail offset tracker, offset={}", lastIntegratedOffset.get());
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    /**
     * Reloads the offset from persistent storage.
     *
     * <p>This is used during crash recovery to restore the offset from
     * the persisted graph state.</p>
     */
    public void reload() {
        persistenceLock.writeLock().lock();
        try {
            long persistedOffset = graph.getLastIntegratedOffset();
            lastIntegratedOffset.set(persistedOffset);
            LOGGER.info("Reloaded tail offset from graph: {}", persistedOffset);
        } finally {
            persistenceLock.writeLock().unlock();
        }
    }

    // ==================== Statistics ====================

    /**
     * Returns statistics about the tail offset tracker.
     *
     * @param writeLogHead The current write log head offset
     * @return Statistics string for logging/monitoring
     */
    public String getStats(long writeLogHead) {
        long offset = lastIntegratedOffset.get();
        long tailSize = Math.max(0, writeLogHead - offset);
        double integrationProgress = writeLogHead > 0 ? (double) offset / writeLogHead * 100 : 100.0;

        return String.format("TailOffsetTracker{offset=%d, writeLogHead=%d, tailSize=%d, progress=%.1f%%}",
            offset, writeLogHead, tailSize, integrationProgress);
    }

    @Override
    public String toString() {
        return String.format("TailOffsetTracker{offset=%d, graph=%s}",
            lastIntegratedOffset.get(), graph.path());
    }
}
