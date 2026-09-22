package com.eonmux.excalibur.vector.hnsw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The write-log integration checkpoint: how far into the segment's write log the graph has
 * been brought up to date.
 *
 * <h2>Why it is its own class</h2>
 * <p>The value is one long in the header, but it is the one field where the relationship
 * between the in-memory copy and the persisted copy is load-bearing. Everything below the
 * checkpoint is expected to be in the graph; everything above it is replayed by the tail scan.
 * A persisted value ahead of the in-memory one loses entries silently, so every writer here
 * persists first and publishes second, and the compare-and-set rolls its in-memory update back
 * if the store fails. Gathering the four writers in one place is what keeps that ordering from
 * being re-derived, differently, at each of them.</p>
 *
 * <h2>Durability, stated plainly</h2>
 * <p>Three of the four writers land in the page cache only: the store is neither forced nor
 * ordered against the node index and data pages, so after a crash the persisted offset may be
 * ahead of, behind, or interleaved with the records it refers to. Only
 * {@link #compareAndSetSync} and {@link #reset} force. This is a deliberate trade - forcing on
 * every integration batch would put a sync on the ingest path - and it is safe because a
 * checkpoint that is behind causes replay, which is idempotent enough to be survivable, and
 * the graph's own torn-tail recovery covers the other side.</p>
 *
 * <h2>Monotonicity, and its one exception</h2>
 * <p>The checkpoint only moves forward while the log it indexes is the same log. {@link #reset}
 * exists for the case where it is not - compaction replaces the write log with an empty file,
 * and a byte offset into the discarded log means nothing in the new one.</p>
 */
final class HnswIntegrationCheckpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswIntegrationCheckpoint.class);

    private final HnswGraph graph;

    /** Atomic counter for last integrated offset (for live indexing). */
    private final AtomicLong lastIntegratedOffset = new AtomicLong(0);

    /**
     * Creates the checkpoint collaborator for one graph.
     *
     * @param graph The graph whose header carries the checkpoint
     */
    HnswIntegrationCheckpoint(HnswGraph graph) {
        this.graph = graph;
    }

    /**
     * Returns the last integrated offset.
     *
     * @return The last integrated offset
     */
    long get() {
        return lastIntegratedOffset.get();
    }

    /**
     * Adopts a value read back from the header, without writing anything.
     *
     * <p>For the restore paths only - attaching to an existing file and re-mapping after a
     * compaction. Both have just read this value out of the header, so persisting it again
     * would be writing the file back onto itself, and the monotonicity check the ordinary
     * setters make is meaningless against an in-memory value that belongs to a previous
     * mapping.</p>
     *
     * @param offset The offset recorded in the header
     */
    void adopt(long offset) {
        lastIntegratedOffset.set(offset);
    }

    /**
     * Persists then publishes a higher offset, under the write lock.
     *
     * @param offset The new last integrated offset (must be &gt;= 0)
     * @throws IllegalArgumentException if offset is negative
     */
    void set(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + offset);
        }

        graph.lock.writeLock().lock();
        try {
            // Check monotonicity - offset must only increase
            long current = lastIntegratedOffset.get();
            if (offset <= current) {
                // Not an increase, no-op
                return;
            }

            // PERSIST FIRST: write to the mapped file before updating memory, so a
            // reader can never observe an offset that was not written. This is
            // ordering only - the store is not forced to disk here.
            graph.mappedFile.writeLong(HnswGraphHeader.OFFSET_LAST_INTEGRATED, offset);

            // THEN update memory atomically
            lastIntegratedOffset.set(offset);

            LOGGER.trace("Updated lastIntegratedOffset from {} to {} (persisted)", current, offset);
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Atomically raises the offset, rolling the in-memory value back if the store fails.
     *
     * <p>The CAS and the mapped-file write are performed under the read lock so the pair cannot
     * straddle a compaction, which closes and replaces the mapping. Multiple concurrent writers
     * retry the CAS; the read lock does not serialise them against each other, only against
     * compaction.</p>
     *
     * @param newOffset The new offset value (must be &gt;= 0)
     * @return true if the offset was updated
     */
    boolean compareAndSet(long newOffset) {
        if (newOffset < 0) {
            return false;
        }

        graph.lock.readLock().lock();
        try {
            while (true) {
                long current = lastIntegratedOffset.get();
                if (newOffset <= current) {
                    // Not an increase
                    return false;
                }

                // Try to atomically update memory
                if (lastIntegratedOffset.compareAndSet(current, newOffset)) {
                    try {
                        graph.mappedFile.writeLong(
                            HnswGraphHeader.OFFSET_LAST_INTEGRATED, newOffset);
                    } catch (RuntimeException e) {
                        // Undo the CAS so the in-memory value never claims a
                        // checkpoint that was not written.
                        lastIntegratedOffset.compareAndSet(newOffset, current);
                        throw e;
                    }
                    LOGGER.trace("CAS updated lastIntegratedOffset from {} to {} (persisted)",
                        current, newOffset);
                    return true;
                }
                // CAS failed due to concurrent update, retry
            }
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Rebases the checkpoint onto a different write log, ignoring monotonicity, and forces it.
     *
     * @param offset the new checkpoint, normally the origin of the replacement log
     * @throws IllegalArgumentException if offset is negative
     */
    void reset(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + offset);
        }

        graph.lock.writeLock().lock();
        try {
            long current = lastIntegratedOffset.get();
            graph.mappedFile.writeLong(HnswGraphHeader.OFFSET_LAST_INTEGRATED, offset);
            graph.mappedFile.force();
            lastIntegratedOffset.set(offset);
            LOGGER.info("Reset lastIntegratedOffset from {} to {} for a replacement write log",
                current, offset);
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Raises the offset and flushes it, for checkpoints where durability matters.
     *
     * @param newOffset The new offset value
     * @return true if the offset was updated
     */
    boolean compareAndSetSync(long newOffset) {
        if (newOffset < 0) {
            return false;
        }

        graph.lock.writeLock().lock();
        try {
            long current = lastIntegratedOffset.get();
            if (newOffset <= current) {
                return false;
            }

            // Persist first with explicit flush
            graph.mappedFile.writeLong(HnswGraphHeader.OFFSET_LAST_INTEGRATED, newOffset);
            graph.mappedFile.flush();

            // Then update memory
            lastIntegratedOffset.set(newOffset);

            LOGGER.trace("Sync-updated lastIntegratedOffset from {} to {} (synced)", current, newOffset);
            return true;
        } finally {
            graph.lock.writeLock().unlock();
        }
    }
}
