package io.excalibur.store;

import io.excalibur.store.format.SegmentEntry;

import java.lang.foreign.MemorySegment;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.Lock;

/**
 * The walk behind a {@link Segment.EntryIterator}: slot order, one entry at a time.
 *
 * <p>Compaction and the graph rebuilders read a whole segment this way, and they need every
 * entry exactly once and in a state that does not change mid-walk. So the cursor takes the
 * segment's read lock when it is created and holds it until it is closed - not per entry.
 * Writers are excluded for the duration, which is the price of a consistent walk and the
 * reason a caller is told to iterate promptly and close.</p>
 *
 * <p>It is a separate class from the iterator callers name because that iterator is published
 * API: {@code excalibur-shard} and the compaction workers compile against the nested type,
 * and keeping it to its five {@link java.util.Iterator} signatures leaves the traversal free
 * to be read and changed on its own terms.</p>
 *
 * <h2>Nothing is copied forward unverified</h2>
 * <p>A walk exists to write what it reads into a new file, so the slot is verified before its
 * data offset is followed and the record is verified before its bytes are copied, exactly as
 * {@link SplitSnapshotData} does for the same reason: damage carried into a fresh file
 * arrives there with a checksum asserting it is sound.</p>
 *
 * <p><strong>Thread safety:</strong> not thread-safe, and holds a lock. One thread per
 * cursor, and it must be closed on every path, or the segment can never be written again.</p>
 */
final class SegmentEntryCursor implements AutoCloseable {

    /** The segment being walked. */
    private final Segment segment;

    /** Whether tombstoned entries are returned rather than skipped. */
    private final boolean includeTombstones;

    /** The segment's read lock, held from construction until {@link #close()}. */
    private final Lock readLock;

    /** Next slot to examine. */
    private int currentSlot;

    /** Entry already found by {@link #hasNext()} and not yet handed out. */
    private SegmentEntry nextEntry;

    /** Whether the lock has been released. */
    private boolean closed;

    /**
     * Takes the segment's read lock and positions the cursor before the first slot.
     *
     * @param segment           The segment to walk
     * @param includeTombstones Whether to return tombstoned entries
     */
    SegmentEntryCursor(Segment segment, boolean includeTombstones) {
        this.segment = segment;
        this.includeTombstones = includeTombstones;
        this.readLock = segment.readLock();
        this.readLock.lock();
        this.currentSlot = 0;
        this.nextEntry = null;
        this.closed = false;
    }

    /**
     * Whether another entry remains.
     *
     * @return true if {@link #next()} would return an entry; false once closed or exhausted
     */
    boolean hasNext() {
        if (closed) {
            return false;
        }
        if (nextEntry != null) {
            return true;
        }
        nextEntry = findNext();
        return nextEntry != null;
    }

    /**
     * Returns the next entry in slot order.
     *
     * @return The entry
     * @throws IllegalStateException  if the cursor has been closed
     * @throws NoSuchElementException if no entries remain
     */
    SegmentEntry next() {
        if (closed) {
            throw new IllegalStateException("Iterator is closed");
        }
        if (nextEntry == null) {
            nextEntry = findNext();
        }
        if (nextEntry == null) {
            throw new NoSuchElementException("No more entries");
        }
        SegmentEntry result = nextEntry;
        nextEntry = null;
        return result;
    }

    /**
     * Releases the read lock. Idempotent, so it is safe on every path out.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            readLock.unlock();
        }
    }

    /**
     * Advances to the next slot holding an entry the caller asked for.
     *
     * @return The entry, or null once the slot table is exhausted
     */
    private SegmentEntry findNext() {
        while (currentSlot < segment.slotCount) {
            int slotIndex = currentSlot++;

            long slotOffset = (long) slotIndex * Segment.SLOT_SIZE;
            byte flags =
                segment.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);

            if ((flags & Segment.FLAG_OCCUPIED) == 0) {
                continue; // Empty slot
            }

            boolean isTombstoned = (flags & Segment.FLAG_TOMBSTONE) != 0;
            if (!includeTombstones && isTombstoned) {
                continue; // Skip tombstoned entries
            }

            return readEntryAtSlot(slotIndex, isTombstoned);
        }
        return null;
    }

    /**
     * Reads and verifies the record one slot names.
     *
     * @param slotIndex    The occupied slot
     * @param isTombstoned Whether that slot is flagged tombstoned
     * @return The entry
     * @throws CorruptSegmentEntryException if the slot or the record does not hold up
     */
    private SegmentEntry readEntryAtSlot(int slotIndex, boolean isTombstoned) {
        // Checked before its data offset is followed, for the reason the split snapshot checks
        // it: a compaction copies what it reads into a new file.
        segment.slots.verifySlot(segment.segment, slotIndex);
        long dataRegionOffset = segment.slots.readDataOffset(slotIndex);
        long entryOffset = segment.slotTableSize + dataRegionOffset;

        long keyHash =
            segment.segment.get(Segment.LONG_LAYOUT, entryOffset + Segment.ENTRY_KEY_HASH_OFFSET);
        int keyLen =
            segment.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        int valueLen =
            segment.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_VALUE_LEN_OFFSET);
        int distance = segment.segment.get(Segment.BYTE_LAYOUT,
            entryOffset + Segment.ENTRY_DISTANCE_OFFSET) & 0xFF;

        byte[] key = new byte[keyLen];
        segment.records.verifyEntry(segment.segment, entryOffset, slotIndex);
        segment.slots.verifySlotAgainstEntry(segment.segment, slotIndex, entryOffset);
        MemorySegment.copy(segment.segment, Segment.BYTE_LAYOUT,
            entryOffset + segment.records.keyBytesOffset(), key, 0, keyLen);

        byte[] value = new byte[valueLen];
        MemorySegment.copy(segment.segment, Segment.BYTE_LAYOUT,
            entryOffset + segment.records.keyBytesOffset() + keyLen, value, 0, valueLen);

        return new SegmentEntry(keyHash, key, value, distance, isTombstoned);
    }
}
