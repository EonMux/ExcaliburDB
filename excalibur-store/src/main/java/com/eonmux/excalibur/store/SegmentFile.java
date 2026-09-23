package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.format.SegmentDataHeader;
import com.eonmux.excalibur.store.format.SegmentFormat;
import com.eonmux.excalibur.store.format.SlotChecksum;
import com.eonmux.excalibur.store.format.UnsupportedSegmentFormatException;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The file behind one {@link Segment}: opening it, working out what version it is, recovering
 * what it holds, and letting it go.
 *
 * <p>These belong together because they are the only operations that have to reason about a
 * file this build did not write. Everything else in a segment assumes a geometry and a format
 * that are already settled; this is where they get settled, and where getting them wrong
 * means silently misreading someone else's bytes.</p>
 *
 * <h2>The geometry is derived, not stored</h2>
 * <p>A segment file is a power-of-two slot table followed by a data region of at least one
 * megabyte, so the slot count can be recovered from the file size alone. The load-factor
 * threshold cannot: it is an operational setting about when to stop accepting inserts, not a
 * property of the layout, so it is supplied on every open. Defaulting it meant a segment
 * filled under a configured 0.9 was reopened at 0.7 and then refused the replay of entries it
 * had already acknowledged - which fails the start, and keeps failing it.</p>
 *
 * <h2>An old file is read as what it is</h2>
 * <p>A file carrying a {@link SegmentDataHeader} at the head of its data region is
 * checksummed; anything else predates the header and is {@link SegmentFormat#V1}. The two
 * cannot be confused - that class says why - so an old file is never mis-parsed as a new one,
 * nor the reverse, and an old file is left byte-for-byte alone rather than upgraded in place.
 * It gains protection by being rewritten, which a split or a compaction does.</p>
 *
 * <p>One legacy file is upgraded on open: one whose slot table is entirely empty. Its data
 * region holds nothing reachable, so stamping the header destroys no record and mis-describes
 * none, and giving every slot the checksum of the empty bytes it already holds vouches for
 * nothing. It means a segment that was created but never written does not spend the rest of
 * its life unable to detect damage.</p>
 *
 * <h2>Recovery consults no checksum</h2>
 * <p>{@link #recoverState()} walks the slot table counting occupancy and finding the end of
 * the last record, and checks nothing. A recovery that refused to open a segment because one
 * slot of a million had lost a bit would take the whole node down over a fault that costs one
 * key. The damage surfaces when that key is read, and {@link Segment#verifyIntegrity()} is
 * what surveys a file on purpose.</p>
 *
 * <p><strong>Thread safety:</strong> the static half runs before any segment exists.
 * {@link #recoverState()} runs before the segment is published to anyone.
 * {@link #close()} takes the segment's write lock and is idempotent.</p>
 */
final class SegmentFile {

    /** The segment whose file this is. */
    private final Segment owner;

    /**
     * Binds this file to its segment.
     *
     * @param owner The owning segment
     */
    SegmentFile(Segment owner) {
        this.owner = owner;
    }

    /**
     * A freshly created segment file: mapped, sized and stamped, with its geometry settled.
     *
     * @param channel       The open channel, which the segment takes over and closes
     * @param mapping       The read-write mapping over the whole file
     * @param slotCount     Slots the geometry worked out to, a power of two
     * @param slotTableSize Bytes the slot table occupies, ahead of the data region
     */
    record Created(FileChannel channel, MemorySegment mapping, int slotCount,
                   long slotTableSize) {
    }

    /**
     * Creates or truncates a segment file, sizes it, maps it and stamps its format header.
     *
     * <p>The byte figure is read as half slot table and half data, with a data region of at
     * least one megabyte added on top; {@link #capacityForSlots(int)} is the inverse, which a
     * caller reproducing an existing segment's geometry needs. A file created here is always
     * {@link SegmentFormat#CURRENT}, so the header that says so is written before any record
     * can be.</p>
     *
     * @param path          Where the file goes; missing parent directories are created
     * @param capacityBytes Requested capacity, read as described above
     * @param arena         Arena that will own the mapping
     * @return The channel, the mapping, and the geometry that was settled on
     * @throws IOException              if the file cannot be created, sized or mapped
     * @throws IllegalArgumentException if the resulting geometry is out of range
     */
    static Created create(Path path, long capacityBytes, Arena arena) throws IOException {
        // Estimate: 50% of capacity for slot table, 50% for data.
        long estimatedSlots = (capacityBytes / 2) / Segment.SLOT_SIZE;
        int slotCount = roundToPowerOf2(
            (int) Math.min(estimatedSlots, Segment.MAX_SEGMENT_CAPACITY));

        // Domain constraint: capacity MUST be a power of 2 in range [1024, 1048576].
        SegmentConfig.validateCapacity(slotCount);

        long slotTableSize = (long) slotCount * Segment.SLOT_SIZE;
        long minDataSize = 1024 * 1024; // At least 1MB for data
        long totalSize = slotTableSize + minDataSize;

        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        FileChannel channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
        channel.truncate(totalSize);
        MemorySegment mapping = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalSize, arena);

        // A file created here is always the current format. The marker that says so goes at
        // the head of the data region, and the first entry record starts after it.
        SegmentDataHeader.write(mapping, slotTableSize);
        return new Created(channel, mapping, slotCount, slotTableSize);
    }

    /**
     * Rejects a load-factor threshold an open-addressed table cannot work under.
     *
     * @param loadFactorThreshold The fraction of slots that may be occupied
     * @throws IllegalArgumentException unless it lies strictly inside (0, 1)
     */
    static void validateLoadFactorThreshold(double loadFactorThreshold) {
        if (loadFactorThreshold <= 0.0 || loadFactorThreshold >= 1.0
                || Double.isNaN(loadFactorThreshold)) {
            // At or above 1.0 an open-addressed table cannot terminate its probe; at or below
            // 0 it can never accept anything.
            throw new IllegalArgumentException(
                "loadFactorThreshold must be in (0, 1), got " + loadFactorThreshold);
        }
    }

    /**
     * Opens an existing segment file without truncating it, and recovers its state.
     *
     * <p>The slot count is derived from the file size rather than stored, which works because
     * the constructor always lays a file out the same way: a power-of-two slot table followed
     * by a data region of at least one megabyte. The threshold is <em>not</em> derived - it is
     * not in the file, because it governs when to stop accepting inserts rather than how the
     * file is laid out - so the caller has to supply the one the segment was filled under.</p>
     *
     * @param path                Segment file
     * @param arena               Arena owning the mapping
     * @param loadFactorThreshold Fraction of slots that may be occupied, in (0, 1)
     * @return The opened segment, with its entry counts and write offset recovered
     * @throws UnsupportedSegmentFormatException if a newer build wrote the file; it is left
     *                                           untouched
     * @throws java.io.IOException if the file cannot be opened
     */
    static Segment openExisting(Path path, Arena arena, double loadFactorThreshold)
            throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        if (arena == null) {
            throw new IllegalArgumentException("Arena cannot be null");
        }
        if (!Files.exists(path)) {
            throw new IOException("Segment file does not exist: " + path);
        }

        long fileSize = Files.size(path);

        // Minimum valid file size is the slot table + minimum data region
        // If file is too small, it's corrupted or empty
        if (fileSize < 1024) {
            throw new IOException("Segment file too small to be valid: " + path + " (size=" + fileSize + ")");
        }

        // Derive slot count from file size
        // The file format is: [slot_table | data_region]
        // Where data_region is at least 1MB (MIN_DATA_SIZE = 1024*1024)
        // So: fileSize = slotCount * SLOT_SIZE + dataRegionSize
        // And: dataRegionSize >= MIN_DATA_SIZE
        // Therefore: slotCount <= (fileSize - MIN_DATA_SIZE) / SLOT_SIZE
        int slotCount = deriveSlotCountForRecovery(fileSize);
        long slotTableSize = (long) slotCount * Segment.SLOT_SIZE;

        // Open file WITHOUT truncating - this is the key difference from the constructor
        FileChannel fileChannel = FileChannel.open(
            path,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
            // Note: No TRUNCATE_EXISTING - we want to preserve existing data
        );

        // Until the segment exists nothing else owns the channel, so a failure to map, measure
        // or recover the file closes it here; otherwise every retry of a start that trips on
        // this file keeps one more descriptor open.
        try {
            // Map the file into memory
            MemorySegment segment =
                fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize, arena);

            SegmentFormat format;
            try {
                format = detectFormat(segment, slotCount, slotTableSize);
            } catch (UnsupportedSegmentFormatException refused) {
                throw refused.in(path);
            }

            // Create a Segment instance using private constructor pattern
            // We need to recover the state from the file
            Segment result = new Segment(path, arena, fileChannel, segment, slotCount,
                slotTableSize, loadFactorThreshold, format);

            // Recover entry count and data offset by scanning the slot table
            result.file.recoverState();

            return result;
        } catch (IOException | RuntimeException | Error e) {
            try {
                fileChannel.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * Derives the slot count of an existing file from its size.
     *
     * <p>{@code slotCount = roundToPowerOf2((fileSize - 1MB) / SLOT_SIZE)}, the inverse of
     * what the constructor does.</p>
     *
     * @param fileSize The file size in bytes
     * @return The derived slot count
     */
    static int deriveSlotCountForRecovery(long fileSize) {
        // Minimum data region size (must match constructor)
        final long MIN_DATA_SIZE = 1024 * 1024;

        // Calculate slot table size by subtracting minimum data region
        long slotTableSize = fileSize - MIN_DATA_SIZE;
        if (slotTableSize <= 0) {
            // File is too small, use minimum
            return Segment.MIN_SEGMENT_CAPACITY;
        }

        // Calculate estimated slot count
        long estimatedSlots = slotTableSize / Segment.SLOT_SIZE;

        // Round to power of 2 (same logic as constructor)
        int slotCount = roundToPowerOf2((int) Math.min(estimatedSlots, Segment.MAX_SEGMENT_CAPACITY));

        // Validate the slot count
        SegmentConfig.validateCapacity(slotCount);

        return slotCount;
    }

    /**
     * The {@code capacityBytes} figure that produces exactly this many slots.
     *
     * <p>The constructor reads its byte figure as "half slot table, half data" and then adds a
     * fixed data region of its own on top. A caller reproducing an existing segment's geometry
     * therefore has to pass the slot table alone: adding a data region to the figure sizes the
     * next slot table from it, and a segment compacted or split repeatedly doubles its table
     * every generation while the data region it is meant to serve stays exactly as big.</p>
     *
     * @param slots How many slots the segment must have
     * @return The byte figure to construct it with
     */
    static long capacityForSlots(int slots) {
        return (long) slots * Segment.SLOT_SIZE * 2;
    }

    /**
     * Decides which entry format an existing file is written in.
     *
     * <p>A file carrying {@link SegmentDataHeader} at the head of its data region is
     * checksummed; anything else predates the header and is
     * {@link com.eonmux.excalibur.store.format.SegmentFormat#V1}. The two cannot be confused - see that class for why - so an
     * old file is never mis-parsed as a new one, nor the reverse.</p>
     *
     * <p>One legacy file is upgraded rather than accepted as it is: one whose slot table is
     * entirely empty. Its data region holds nothing, so stamping the header there destroys
     * no record and mis-describes none, and no slot names anything, so giving every slot the
     * checksum of the empty bytes it already holds destroys nothing either. It means a
     * segment that was created but never written does not spend the rest of its life unable
     * to detect damage. A legacy file with any entry in it is left alone: rewriting its
     * records into the wider header would mean moving every one of them, and rewriting its
     * slots would mean vouching for pointers this build never wrote - which is what a
     * compaction is for.</p>
     *
     * @param mapped        The mapped file
     * @param slotCount     Number of slots in the slot table
     * @param slotTableSize Offset at which the data region begins
     * @return The format the file is to be read and written in
     * @throws UnsupportedSegmentFormatException if the file is written in a format newer than
     *                                           this build reads; nothing is written to it
     */
    static SegmentFormat detectFormat(MemorySegment mapped, int slotCount,
                                              long slotTableSize)
            throws UnsupportedSegmentFormatException {
        SegmentFormat detected = SegmentDataHeader.detect(mapped, slotTableSize);
        if (detected.hasChecksum()) {
            return detected;
        }
        if (hasNoOccupiedSlot(mapped, slotCount)
                && slotTableSize + SegmentDataHeader.SIZE <= mapped.byteSize()) {
            SegmentDataHeader.write(mapped, slotTableSize);
            stampSlotChecksums(mapped, slotCount);
            return SegmentFormat.CURRENT;
        }
        return detected;
    }

    /**
     * Gives every slot of an upgraded file the checksum of the bytes it already holds.
     *
     * <p>Only ever called on a file with no occupied slot, so it vouches for nothing: it
     * records that each slot is empty, which is what the slot table actually says. Without
     * it the file would claim a version whose readers check slot checksums while carrying
     * none, and the first probe to walk over an empty slot would report damage that is not
     * there.</p>
     *
     * @param mapped    The mapped file
     * @param slotCount Number of slots in the slot table
     */
    static void stampSlotChecksums(MemorySegment mapped, int slotCount) {
        if (!SegmentFormat.CURRENT.hasSlotChecksum()) {
            return;
        }
        for (int i = 0; i < slotCount; i++) {
            SlotChecksum.store(mapped, SlotChecksum.offsetOf(i));
        }
    }

    /**
     * Reports whether a mapped file's slot table is entirely unoccupied.
     *
     * <p>Which is the same as saying its data region holds no record anything can reach:
     * a record is only ever reached through the slot that points at it.</p>
     *
     * @param mapped    The mapped file
     * @param slotCount Number of slots in the slot table
     * @return true if no slot is occupied
     */
    static boolean hasNoOccupiedSlot(MemorySegment mapped, int slotCount) {
        for (int i = 0; i < slotCount; i++) {
            long slotOffset = (long) i * Segment.SLOT_SIZE;
            byte flags = mapped.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);
            if ((flags & Segment.FLAG_OCCUPIED) != 0) {
                return false;
            }
        }
        return true;
    }

    static int roundToPowerOf2(int n) {
        if (n <= 0) {
            return Segment.MIN_SEGMENT_CAPACITY;
        }
        if (n >= Segment.MAX_SEGMENT_CAPACITY) {
            return Segment.MAX_SEGMENT_CAPACITY;
        }
        int power = Integer.highestOneBit(n);
        if (power < n) {
            power <<= 1;
        }
        return Math.max(power, Segment.MIN_SEGMENT_CAPACITY);
    }

    /**
     * Recovers the segment state by scanning the slot table.
     *
     * <p>No checksum is consulted here. A recovery that refused to open a segment because
     * one slot of a million had lost a bit would take the whole node down over a fault that
     * costs one key; the damage surfaces when that key is read, and
     * {@link Segment#verifyIntegrity()} is what surveys a file on purpose.</p>
     *
     * <p>This method scans all slots in the slot table to:</p>
     * <ul>
     *   <li>Count occupied slots (tombstoned included) into {@code entryCount}</li>
     *   <li>Count non-tombstoned slots into {@code liveEntryCount}</li>
     *   <li>Find the maximum data offset (for future writes)</li>
     * </ul>
     *
     * <p>This is called during recovery from an existing segment file.</p>
     */
    void recoverState() {
        int recoveredEntryCount = 0;
        int recoveredLiveCount = 0;
        long maxEndOffset = 0;  // Track the END of the last entry (offset + size)
        long segmentSize = owner.segment.byteSize();

        for (int i = 0; i < owner.slotCount; i++) {
            long slotOffset = (long) i * Segment.SLOT_SIZE;
            byte flags = owner.segment.get(Segment.BYTE_LAYOUT, slotOffset + Segment.SLOT_FLAGS_OFFSET);

            // Check if slot is occupied
            if ((flags & Segment.FLAG_OCCUPIED) != 0) {
                // Read the data offset for this entry (offset within data region)
                long dataRegionOffset = owner.slots.readDataOffset(i);

                // Calculate absolute entry offset
                long entryOffset = owner.slotTableSize + dataRegionOffset;

                // Validate entry offset is within bounds
                if (entryOffset + owner.records.entryHeaderSize() > segmentSize) {
                    // Entry header would be out of bounds - skip this slot
                    continue;
                }

                // Read entry size to calculate where this entry ends
                int keyLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
                int valueLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_VALUE_LEN_OFFSET);
                int entrySize = owner.records.entryHeaderSize() + keyLen + valueLen + Segment.ENTRY_TRAILER_SIZE;

                // Calculate where this entry ends
                long entryEndOffset = dataRegionOffset + entrySize;

                // Update max end offset
                if (entryEndOffset > maxEndOffset) {
                    maxEndOffset = entryEndOffset;
                }

                // Every occupied slot counts towards occupancy: a tombstoned slot is
                // never reused, so it still consumes probe budget. Live entries are
                // counted separately for the read-side index space.
                recoveredEntryCount++;
                if ((flags & Segment.FLAG_TOMBSTONE) == 0) {
                    recoveredLiveCount++;
                }
            }
        }

        owner.entryCount = recoveredEntryCount;
        owner.liveEntryCount = recoveredLiveCount;
        // Floored at the first writable offset: a segment with no entries recovers a maximum
        // end offset of zero, and taking that literally would put the next record on top of
        // the data region header and unmark the file as checksummed.
        owner.dataOffset = Math.max(maxEndOffset, owner.format.firstEntryOffset());
    }

    /**
     * Closes the segment and everything mapped alongside it.
     *
     * <p>Under the write lock, and idempotent. The order is deliberate: the mapping is forced
     * first and the term sketch written beside it while the write log that dates it is still
     * open, then each thing the segment owns a descriptor or a mapping for - its write log,
     * its HNSW graph, its LSH index, its file channel - is closed in turn, and a failure in
     * any one of them is logged rather than thrown, so that the ones after it still run. Each
     * of those was, at some point, leaked for the life of the process by a retired segment,
     * which is what eventually exhausts a node's descriptor and mapping budgets.</p>
     *
     * <p>A graph that compaction has carried onto a replacement segment is detached before
     * the parent is closed, so this only ever closes one nobody else is using.</p>
     */
    void close() {
        owner.lock.writeLock().lock();
        try {
            if (owner.closed) {
                return;
            }
            owner.closed = true;

            // Force any dirty pages to disk
            owner.segment.force();

            // Before the write log closes: the sketch is dated by the log's sequence number.
            owner.terms.persist();

            // Close the write-ahead log this segment owns, so its descriptor and
            // final force() are not leaked when the segment is retired.
            WriteLog log = owner.writeLog;
            if (log != null) {
                try {
                    log.close();
                } catch (IOException e) {
                    Segment.LOGGER.log(java.util.logging.Level.WARNING,
                        "Failed to close write log for segment " + owner.path, e);
                }
            }

            // Close the HNSW graph this segment owns. It holds a FileChannel, an Arena and a
            // whole-file mapping, and nothing else in production releases them -- so every retired
            // segment leaked one of each until the process exited, which is what eventually
            // exhausts the descriptor and mapping budgets. A graph carried onto a replacement by
            // compaction is detached first (see detachHnswGraph), so this only ever closes a graph
            // no one else is using.
            com.eonmux.excalibur.vector.hnsw.HnswGraph graph = owner.hnswGraph;
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception e) {
                    Segment.LOGGER.log(java.util.logging.Level.WARNING,
                        "Failed to close HNSW graph for segment " + owner.path, e);
                }
            }

            // Close the LSH index this segment owns. It maps one file per hash table plus a
            // descriptor, so leaving it open leaks every one of those mappings when the segment
            // is retired. Only the owner can release them.
            com.eonmux.excalibur.filter.lsh.LshIndex lsh = owner.lshIndex;
            if (lsh != null) {
                try {
                    lsh.close();
                } catch (Exception e) {
                    Segment.LOGGER.log(java.util.logging.Level.WARNING,
                        "Failed to close LSH index for segment " + owner.path, e);
                }
            }

            // Close the file channel
            // The segment counts as closed either way - callers must not retry - but a channel
            // that would not close is a descriptor this process keeps until it exits.
            try {
                owner.fileChannel.close();
            } catch (IOException e) {
                Segment.LOGGER.log(java.util.logging.Level.WARNING,
                    "Failed to close file channel for segment " + owner.path, e);
            }
        } finally {
            owner.lock.writeLock().unlock();
        }
    }
}
