package com.eonmux.excalibur.store;

import com.eonmux.excalibur.core.memory.MemorySegmentFactory;
import com.eonmux.excalibur.core.memory.Segment;
import com.eonmux.excalibur.store.format.LogEntry;
import com.eonmux.excalibur.store.format.UnsupportedWriteLogFormatException;
import com.eonmux.excalibur.store.format.WriteLogFormat;
import com.eonmux.excalibur.store.format.WriteLogHeader;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Where a write-ahead log on disk ends, what ended it, and which format it is in.
 *
 * <p>A log is mapped at its maximum size and filled sparsely, so the scan has three things to
 * tell apart at the point where it stops reading records. Unwritten bytes are the ordinary end
 * of the log. A record that will not read, with nothing but unwritten bytes after it, is a
 * tail torn by a crash: it was never acknowledged, so the log ends before it and nothing is
 * lost. A record that will not read with sound records after it is damage in the middle, and
 * every one of those later records was fsynced and acknowledged - that is not a recoverable
 * condition and {@link CorruptLogException} says so rather than letting the log open short.</p>
 *
 * <p>The two are separated by looking for the next record that both passes its checksum and
 * carries a higher LSN than the last readable one. Requiring the LSN to continue the sequence
 * is what keeps the search from mistaking a chance checksum match in the unwritten region for
 * a surviving record and turning an ordinary crash into a refusal to start.</p>
 *
 * <h2>The header</h2>
 * <p>Before any record is read, the scan settles which {@link WriteLogFormat} the log is in,
 * and there are four answers. A sound {@link WriteLogHeader} naming a version this build reads
 * puts the first record right after the header. A sound header naming any other version is a
 * newer build's log, refused with {@link UnsupportedWriteLogFormatException} before a single
 * record is read. A header that is present but not sound - its magic there, its checksum or
 * reserved field wrong - is damage, refused as {@link CorruptLogException} when any record
 * survives behind it; with nothing behind it, it is a header whose own write was torn as the
 * log was created, which lost nothing, and the log ends at offset 0 like any torn tail. And no
 * magic at all is a headerless log an older build wrote, read from offset 0 exactly as before -
 * unless offset 0 holds no readable record while a log's first record sits right after where a
 * header would end, which is a header that was wiped out rather than one never written.</p>
 *
 * <p>Two of those answers end with an empty log that its open gives a fresh header: a header
 * torn at creation, and a headerless log with nothing at offset 0. Both are established
 * rather than inferred, by finding no sound record anywhere in the file, because the next
 * appends land where records would be. A zeroed block that took the header and the first
 * record with it looks, at offset 0, exactly like a log nothing was written to; the records
 * behind it are what tell the two apart, and it is refused as damage.</p>
 *
 * <p>Every offset this scan reports is an offset in the file, header included, because that is
 * what an operator reading the file and a repair writing to it both need.</p>
 *
 * @param nextLsn   The LSN the next appended record should carry
 * @param endOffset Where the log ends, as an offset in the file, and where the next record is
 *                  written
 * @param tornTail  Why the record - or the header - at {@code endOffset} could not be read, or
 *                  null if the log ended at unwritten bytes
 * @param format    The format the log is written in
 */
public record LogScan(long nextLsn, long endOffset, String tornTail, WriteLogFormat format) {

    /**
     * Reads a mapped log from the beginning to find where it ends.
     *
     * @param mapped      The whole mapped log file
     * @param logFilePath The log being scanned, for the failure message
     * @return What the scan found
     * @throws UnsupportedWriteLogFormatException if a sound header names a version this build
     *                                            does not read
     * @throws CorruptLogException if the header, or the start of a log with none, is damaged
     *                             with records behind it, or the log is damaged anywhere but
     *                             its tail
     */
    public static LogScan of(Segment mapped, Path logFilePath)
            throws UnsupportedWriteLogFormatException, CorruptLogException {
        long fileSize = mapped.byteSize();
        if (WriteLogHeader.hasMagic(mapped)) {
            return headed(mapped, logFilePath, fileSize);
        }
        if (headerWipedOut(mapped, fileSize)) {
            throw new CorruptLogException(logFilePath, 0,
                "Write-ahead log " + logFilePath + " has lost its header: its first "
                + WriteLogHeader.SIZE + " bytes are neither a header nor a record, and the "
                + "log's first record (lsn 0) is intact right after them. Only a log written "
                + "with a header puts its first record there, so this is damage to the header - "
                + "not a log from an older build, and not an empty one - and every record behind "
                + "it was fsynced and acknowledged. Refusing to open the log so the evidence "
                + "survives: restore this segment from a backup or a replica.");
        }
        LogScan headerless = records(mapped, logFilePath, WriteLogFormat.V1, fileSize);
        if (headerless.endOffset() == 0) {
            // Empty is the one reading of a headerless log that the log's open acts on - it is
            // given a header, and the next appends land where records would be - so it has to
            // be established rather than inferred from the first byte. A zeroed block at the
            // start of a log reads exactly like one nothing was written to.
            long survivor = nextSoundRecord(mapped, 0, fileSize, -1);
            if (survivor >= 0) {
                throw new CorruptLogException(logFilePath, 0,
                    "Write-ahead log " + logFilePath + " has nothing readable at its start - no "
                    + "header and no record - but a sound record at offset " + survivor + ". An "
                    + "empty log has no sound record anywhere in it, so this is damage to the "
                    + "start of the log, and every record behind it was fsynced and "
                    + "acknowledged. Refusing to open the log so the evidence survives: restore "
                    + "this segment from a backup or a replica.");
            }
        }
        return headerless;
    }

    /**
     * Scans a log file by path, through a read-only mapping of the file as it stands.
     *
     * <p>What the log's own open does before it maps the file for writing. That mapping is made
     * at the log's full capacity, which extends a shorter file, and a log this build has to
     * refuse - a newer build's, or one damaged in the middle - must be left exactly as it was
     * found. Scanning a read-only view first is what makes a refusal touch nothing.</p>
     *
     * @param logFilePath The log file
     * @return What the scan found
     * @throws UnsupportedWriteLogFormatException if a sound header names a version this build
     *                                            does not read
     * @throws CorruptLogException if the log cannot be opened as it stands
     * @throws IOException if the file cannot be read or mapped
     */
    public static LogScan of(Path logFilePath) throws IOException {
        try (FileChannel channel = FileChannel.open(logFilePath, StandardOpenOption.READ)) {
            long size = channel.size();
            if (MemorySegmentFactory.isPanamaAvailable()) {
                try (Arena arena = Arena.ofConfined()) {
                    return of(MemorySegmentFactory.wrapNative(
                        channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena)), logFilePath);
                }
            }
            return of(MemorySegmentFactory.wrapBuffer(
                channel.map(FileChannel.MapMode.READ_ONLY, 0, size)), logFilePath);
        }
    }

    /**
     * Returns where the log's first record is, as an offset in the file.
     *
     * @return The size of the header, or 0 for a headerless log
     */
    public long recordsStart() {
        return format.recordsStart();
    }

    /**
     * Reads a log that begins with the magic number.
     *
     * @throws UnsupportedWriteLogFormatException if its sound header names another version
     * @throws CorruptLogException if its header is damaged and records survive behind it
     */
    private static LogScan headed(Segment mapped, Path logFilePath, long fileSize)
            throws UnsupportedWriteLogFormatException, CorruptLogException {
        if (WriteLogHeader.isSound(mapped)) {
            int version = WriteLogHeader.version(mapped);
            WriteLogFormat format = WriteLogFormat.forHeaderVersion(version);
            if (format == null) {
                throw new UnsupportedWriteLogFormatException(logFilePath, version);
            }
            return records(mapped, logFilePath, format, fileSize);
        }
        long survivor = nextSoundRecord(mapped, WriteLogHeader.SIZE, fileSize, -1);
        if (survivor < 0) {
            // Nothing is written behind a header before the header itself is durable, and
            // nothing was: this is the header's own write, torn as the log was created. Whatever
            // version it was meant to name, no record of it exists to be misread.
            return new LogScan(0, 0, "the header's magic is present but its checksum does not "
                + "match, and no record follows it: the header's own write was torn as the log "
                + "was created", WriteLogFormat.CURRENT);
        }
        throw new CorruptLogException(logFilePath, 0,
            "Write-ahead log " + logFilePath + " has a damaged header: its magic is present but "
            + "its checksum does not match, so the version it names cannot be believed. This is "
            + "damage, not a log from a newer build - a newer build's header carries a checksum "
            + "that matches. Sound records follow it, the first at offset " + survivor + ", and "
            + "every one was fsynced and acknowledged. Refusing to open the log so the evidence "
            + "survives: restore this segment from a backup or a replica.");
    }

    /**
     * Reports whether a log with no magic is a headed log whose header was destroyed.
     *
     * <p>A headerless log's first record starts at offset 0, carries lsn 0 and runs past
     * offset {@value WriteLogHeader#SIZE}, so a sound lsn-0 record starting there is the first
     * record of a log written with a header - unless offset 0 itself holds a readable record,
     * which is what a headerless log with anything in it does.</p>
     */
    private static boolean headerWipedOut(Segment mapped, long fileSize) {
        return recordSizeAt(mapped, 0, fileSize) < 0
            && recordSizeAt(mapped, WriteLogHeader.SIZE, fileSize) > 0
            && mapped.getLong(WriteLogHeader.SIZE + LogEntry.LSN_OFFSET) == 0;
    }

    /**
     * Reads the records of a log whose format is settled, from its first record to its end.
     *
     * @throws CorruptLogException if the log is damaged anywhere but its tail
     */
    private static LogScan records(Segment mapped, Path logFilePath, WriteLogFormat format,
                                   long fileSize) throws CorruptLogException {
        long offset = format.recordsStart();
        long maxLsn = -1;

        while (offset < fileSize && !isUnwritten(mapped, offset)) {
            int size = recordSizeAt(mapped, offset, fileSize);
            if (size < 0) {
                return endsAt(mapped, logFilePath, format, offset, maxLsn, fileSize);
            }
            maxLsn = Math.max(maxLsn, mapped.getLong(offset + LogEntry.LSN_OFFSET));
            offset += size;
        }

        return new LogScan(maxLsn + 1, offset, null, format);
    }

    /**
     * Classifies the record that stopped the scan and either ends the log there or refuses it.
     *
     * @throws CorruptLogException if acknowledged records survive past the damage
     */
    private static LogScan endsAt(Segment mapped, Path logFilePath, WriteLogFormat format,
                                  long stopOffset, long maxLsn, long fileSize)
            throws CorruptLogException {
        String problem = problemAt(mapped, stopOffset, fileSize);
        long resumesAt = nextSoundRecord(mapped, stopOffset + 1, fileSize, maxLsn);

        if (resumesAt < 0) {
            return new LogScan(maxLsn + 1, stopOffset, problem, format);
        }

        throw new CorruptLogException(logFilePath, stopOffset,
            "Write-ahead log " + logFilePath + " is corrupt at offset " + stopOffset + ": "
            + problem + ". Sound records resume at offset " + resumesAt + " (lsn "
            + mapped.getLong(resumesAt + LogEntry.LSN_OFFSET) + ", against " + maxLsn
            + " for the last readable record), so this is not a tail torn by a crash - every "
            + "record after the damage was fsynced and acknowledged. Ending the log at the "
            + "damage would discard those committed writes without trace, and skipping the "
            + "damaged record would rebuild the segment from a state it was never in. "
            + "Refusing to open the log so the evidence survives: restore this segment from a "
            + "backup or a replica, or delete the log deliberately and accept the loss.");
    }

    /**
     * Finds the next record that reads cleanly and belongs after the last one that did.
     *
     * <p>The damaged record's own length fields cannot be trusted, so there is no record
     * boundary to resume from and every byte offset has to be tried. Eight unwritten bytes
     * rule out the eight offsets they cover, which is what keeps a search across a sparsely
     * filled 64 MiB log down to a bounded cost on a path that only a damaged log reaches.</p>
     *
     * @return The offset of the next sound record, or -1 if there is none
     */
    private static long nextSoundRecord(Segment mapped, long from, long fileSize, long maxLsn) {
        long probe = from;
        while (probe + LogEntry.HEADER_SIZE <= fileSize) {
            if (mapped.getLong(probe) == 0) {
                probe += Long.BYTES;
                continue;
            }
            if (recordSizeAt(mapped, probe, fileSize) > 0 && continuesFrom(mapped, probe, maxLsn)) {
                return probe;
            }
            probe++;
        }
        return -1;
    }

    /**
     * Whether a record that passed its checksum also sits where the sequence says it should.
     *
     * <p>LSNs increase by one per record and timestamps are positive, so a chance checksum
     * match on bytes that were never a record almost always fails one of the two.</p>
     */
    private static boolean continuesFrom(Segment mapped, long offset, long maxLsn) {
        return mapped.getLong(offset + LogEntry.LSN_OFFSET) > maxLsn
            && mapped.getLong(offset + LogEntry.TIMESTAMP_OFFSET) > 0;
    }

    /**
     * Returns the size of the record at this offset, or -1 if there is no readable record here.
     *
     * <p>The cheap checks come first because this runs at every byte offset of the log once a
     * resynchronisation is under way, and an opcode that is neither a put nor a delete rejects
     * almost all of them without touching the rest of the record.</p>
     */
    private static int recordSizeAt(Segment mapped, long offset, long fileSize) {
        if (offset + LogEntry.HEADER_SIZE > fileSize) {
            return -1;
        }
        byte opcode = mapped.getByte(offset + LogEntry.OPCODE_OFFSET);
        if (opcode != LogEntry.OPCODE_PUT && opcode != LogEntry.OPCODE_DELETE) {
            return -1;
        }
        int keyLen = mapped.getInt(offset + LogEntry.KEY_LEN_OFFSET);
        int valueLen = mapped.getInt(offset + LogEntry.VALUE_LEN_OFFSET);
        if (keyLen < 0 || valueLen < 0) {
            return -1;
        }
        long size = (long) LogEntry.ENTRY_OVERHEAD + keyLen + valueLen;
        if (offset + size > fileSize) {
            return -1;
        }
        return LogEntry.validateCrc32(mapped, offset) ? (int) size : -1;
    }

    /**
     * Describes why the record at this offset could not be read, for an operator to act on.
     *
     * <p>Only ever called once, at the offset where the scan stopped, so it repeats the checks
     * {@link #recordSizeAt} makes rather than making that method allocate a reason per byte.</p>
     */
    private static String problemAt(Segment mapped, long offset, long fileSize) {
        long remaining = fileSize - offset;
        if (remaining < LogEntry.HEADER_SIZE) {
            return "only " + remaining + " bytes remain, fewer than a record header";
        }
        byte opcode = mapped.getByte(offset + LogEntry.OPCODE_OFFSET);
        if (opcode != LogEntry.OPCODE_PUT && opcode != LogEntry.OPCODE_DELETE) {
            return "opcode 0x" + Integer.toHexString(opcode & 0xFF) + " is neither a put nor a delete";
        }
        int keyLen = mapped.getInt(offset + LogEntry.KEY_LEN_OFFSET);
        int valueLen = mapped.getInt(offset + LogEntry.VALUE_LEN_OFFSET);
        if (keyLen < 0 || valueLen < 0) {
            return "key length " + keyLen + " and value length " + valueLen + " are not lengths";
        }
        long size = (long) LogEntry.ENTRY_OVERHEAD + keyLen + valueLen;
        if (offset + size > fileSize) {
            return "a record of " + size + " bytes does not fit in the " + remaining + " that remain";
        }
        return "the checksum does not match the " + size + " bytes of the record";
    }

    /**
     * Whether nothing has ever been written at this offset.
     *
     * <p>The log is filled sparsely, so everything past the last record reads as zeroes. Zero
     * is not an opcode, which makes an unwritten slot the end of the log rather than a damaged
     * record - and the distinction has to be drawn before the checksum is consulted, because a
     * zeroed slot fails that check for the same reason real damage does.</p>
     */
    private static boolean isUnwritten(Segment mapped, long offset) {
        return mapped.getByte(offset + LogEntry.OPCODE_OFFSET) == 0;
    }
}
