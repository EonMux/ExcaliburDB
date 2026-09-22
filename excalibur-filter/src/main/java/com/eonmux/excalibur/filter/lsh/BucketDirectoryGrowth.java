package com.eonmux.excalibur.filter.lsh;

import com.eonmux.excalibur.core.mapped.MappedFile;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Doubles the bucket directory of an LSH table file and repairs everything the move breaks.
 *
 * <p>The directory and the entry-storage region are laid out back to back in one file, so the
 * directory cannot grow without pushing the entry region to a higher offset. Every link in the
 * table - bucket to chain head, entry to next entry - is an absolute file offset, which makes
 * that push a four-step operation rather than an allocation:</p>
 *
 * <ol>
 *   <li>snapshot the directory rows to the heap, before anything overwrites them;</li>
 *   <li>extend the mapping if the enlarged layout no longer fits;</li>
 *   <li>copy the entry region up and rebase the next-entry link inside every record;</li>
 *   <li>rehash the snapshotted rows into the enlarged directory, rebasing each chain head.</li>
 * </ol>
 *
 * <p>Skipping step 3 or 4 leaves chains pointing into the rewritten directory, where the walk
 * in {@code LshHashTable.collectEntriesFromBucket} reads bucket rows as entry records. The
 * steps are kept together here for that reason: they are not independently useful, and a
 * partial application corrupts the table.</p>
 *
 * <p>This class does no locking. The caller holds the table's write lock for the whole
 * operation, because the file is inconsistent between the first write and the last.</p>
 */
final class BucketDirectoryGrowth {

    /** Chunk size used when relocating the entry-storage region. */
    private static final int RELOCATION_CHUNK_SIZE = 8192;

    private static final ValueLayout.OfInt INT_LAYOUT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_LAYOUT =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE_LAYOUT = ValueLayout.JAVA_BYTE;

    private BucketDirectoryGrowth() {
    }

    /**
     * Doubles the directory capacity in place and returns where entry storage now ends.
     *
     * <p>The header is left describing the new layout: capacity, the allocation cursor and
     * the occupied-bucket count are all rewritten. The caller holds the same cursor in memory
     * and must move it to the returned offset.</p>
     *
     * <p>Where the region starts is not read from the header and never was written there in
     * any meaningful sense: it is {@code bucketDirOffset + capacity * BUCKET_ENTRY_SIZE},
     * derived here from the capacity both before and after the move. The header field at
     * {@link LshHashTable#OFFSET_ENTRY_STORAGE_OFFSET} holds the cursor - where the region
     * ends - which is the one boundary nothing else in the file implies.</p>
     *
     * @param mappedFile      The table file; remapped in place if the new layout does not fit
     * @param bucketDirOffset Offset the directory starts at, fixed by the format version
     * @param entryStorageEnd Current end of entry storage, the caller's allocation cursor
     * @param headroomBytes   Spare bytes to leave past the relocated region, so the next few
     *                        allocations do not each force an extension
     * @return The end of entry storage after the move, for the caller's cursor
     * @throws IOException if the mapping cannot be extended
     */
    static long doubleCapacity(MappedFile mappedFile, long bucketDirOffset,
                               long entryStorageEnd, long headroomBytes) throws IOException {
        MemorySegment segment = mappedFile.segment();
        long oldCapacity = segment.get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY);
        long newCapacity = oldCapacity * 2;

        long oldEntryStorageOffset =
            bucketDirOffset + oldCapacity * LshHashTable.BUCKET_ENTRY_SIZE;
        long entryStorageSize = entryStorageEnd - oldEntryStorageOffset;

        long newEntryStorageOffset =
            bucketDirOffset + newCapacity * LshHashTable.BUCKET_ENTRY_SIZE;
        long relocationDelta = newEntryStorageOffset - oldEntryStorageOffset;
        long newFileSize = newEntryStorageOffset + entryStorageSize + headroomBytes;

        // Snapshot the directory before anything moves.
        BucketSnapshot snapshot = snapshotBuckets(segment, bucketDirOffset, oldCapacity);

        // Extend only when the mapping is genuinely too small: the caller grows the file in
        // doublings as it allocates entries, so it is frequently already large enough, and
        // MappedFile.extend() rejects a negative extension.
        if (newFileSize > mappedFile.size()) {
            mappedFile.extend(newFileSize - mappedFile.size());
            segment = mappedFile.segment(); // extend() remaps; the previous segment is dead
        }

        if (entryStorageSize > 0) {
            relocateEntryStorage(segment, oldEntryStorageOffset, newEntryStorageOffset,
                entryStorageSize);
            rebaseEntryLinks(segment, newEntryStorageOffset, entryStorageSize, relocationDelta);
        }

        segment.set(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY, newCapacity);
        segment.set(LONG_LAYOUT, LshHashTable.OFFSET_ENTRY_STORAGE_OFFSET,
            newEntryStorageOffset + entryStorageSize);

        rehashBuckets(segment, bucketDirOffset, snapshot, newCapacity, relocationDelta);

        return newEntryStorageOffset + entryStorageSize;
    }

    /**
     * The bucket directory rows captured before a relocation.
     */
    private record BucketSnapshot(long[] hashes, long[] entryOffsets, int[] entryCounts) {
    }

    /**
     * Copies the bucket directory into heap arrays so the relocation and the rewrite of the
     * enlarged directory cannot destroy the rows still to be re-inserted.
     */
    private static BucketSnapshot snapshotBuckets(MemorySegment segment, long bucketDirOffset,
                                                  long capacity) {
        long[] hashes = new long[(int) capacity];
        long[] entryOffsets = new long[(int) capacity];
        int[] entryCounts = new int[(int) capacity];

        for (int i = 0; i < capacity; i++) {
            long bucketOffset = bucketDirOffset + (long) i * LshHashTable.BUCKET_ENTRY_SIZE;
            hashes[i] = segment.get(LONG_LAYOUT, bucketOffset + LshHashTable.BUCKET_HASH);
            entryOffsets[i] =
                segment.get(LONG_LAYOUT, bucketOffset + LshHashTable.BUCKET_ENTRY_OFFSET);
            entryCounts[i] =
                segment.get(INT_LAYOUT, bucketOffset + LshHashTable.BUCKET_ENTRY_COUNT);
        }

        return new BucketSnapshot(hashes, entryOffsets, entryCounts);
    }

    /**
     * Moves the entry-storage region to a higher offset.
     *
     * <p>Source and destination overlap whenever the directory grows by less than the size
     * of the region, so the copy runs from the high end downwards and never writes over
     * bytes it has not read yet.</p>
     *
     * @param segment    The mapped table
     * @param fromOffset Current start of the region
     * @param toOffset   New start of the region; must not be below {@code fromOffset}
     * @param size       Region size in bytes
     */
    private static void relocateEntryStorage(MemorySegment segment, long fromOffset,
                                             long toOffset, long size) {
        byte[] buffer = new byte[(int) Math.min(size, RELOCATION_CHUNK_SIZE)];
        long copied = 0;

        while (copied < size) {
            int chunkSize = (int) Math.min(buffer.length, size - copied);
            long readOffset = fromOffset + size - copied - chunkSize;
            long writeOffset = toOffset + size - copied - chunkSize;

            MemorySegment.copy(segment, BYTE_LAYOUT, readOffset, buffer, 0, chunkSize);
            MemorySegment.copy(buffer, 0, segment, BYTE_LAYOUT, writeOffset, chunkSize);
            copied += chunkSize;
        }
    }

    /**
     * Adds the relocation delta to every non-zero next-entry link inside the relocated region.
     * Records are fixed size and densely bump-allocated, so the region can be walked directly.
     */
    private static void rebaseEntryLinks(MemorySegment segment, long regionOffset,
                                         long regionSize, long delta) {
        long regionEnd = regionOffset + regionSize;

        for (long offset = regionOffset;
             offset + LshHashTable.ENTRY_SIZE <= regionEnd;
             offset += LshHashTable.ENTRY_SIZE) {
            long next = segment.get(LONG_LAYOUT, offset + LshHashTable.ENTRY_NEXT_OFFSET);
            if (next != 0) {
                segment.set(LONG_LAYOUT, offset + LshHashTable.ENTRY_NEXT_OFFSET, next + delta);
            }
        }
    }

    /**
     * Rehashes the snapshotted buckets into the enlarged directory.
     *
     * <p>Chain heads are rebased by the same delta the entry region moved by. Slots that were
     * emptied by deletion are dropped rather than re-inserted, which compacts the directory
     * and shortens every probe run; the header bucket count is recomputed to match.</p>
     */
    private static void rehashBuckets(MemorySegment segment, long bucketDirOffset,
                                      BucketSnapshot snapshot, long newCapacity,
                                      long relocationDelta) {
        for (int i = 0; i < newCapacity; i++) {
            long bucketOffset = bucketDirOffset + (long) i * LshHashTable.BUCKET_ENTRY_SIZE;
            segment.set(LONG_LAYOUT, bucketOffset + LshHashTable.BUCKET_HASH, 0L);
            segment.set(LONG_LAYOUT, bucketOffset + LshHashTable.BUCKET_ENTRY_OFFSET, 0L);
            segment.set(INT_LAYOUT, bucketOffset + LshHashTable.BUCKET_ENTRY_COUNT, 0);
        }

        long occupied = 0;

        for (int i = 0; i < snapshot.entryCounts().length; i++) {
            if (snapshot.entryCounts()[i] <= 0) {
                continue;
            }

            long hash = snapshot.hashes()[i];
            long entryOffset = snapshot.entryOffsets()[i];
            long rebasedEntryOffset = entryOffset == 0 ? 0 : entryOffset + relocationDelta;
            int newIndex = (int) ((hash & 0x7FFFFFFFFFFFFFFFL) % newCapacity);

            for (int j = 0; j < newCapacity; j++) {
                int index = (int) ((newIndex + j) % newCapacity);
                long bucketOffset =
                    bucketDirOffset + (long) index * LshHashTable.BUCKET_ENTRY_SIZE;
                int count = segment.get(INT_LAYOUT,
                    bucketOffset + LshHashTable.BUCKET_ENTRY_COUNT);

                if (count == 0) {
                    segment.set(LONG_LAYOUT, bucketOffset + LshHashTable.BUCKET_HASH, hash);
                    segment.set(LONG_LAYOUT, bucketOffset + LshHashTable.BUCKET_ENTRY_OFFSET,
                        rebasedEntryOffset);
                    segment.set(INT_LAYOUT, bucketOffset + LshHashTable.BUCKET_ENTRY_COUNT,
                        snapshot.entryCounts()[i]);
                    occupied++;
                    break;
                }
            }
        }

        segment.set(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_COUNT, occupied);
    }
}
