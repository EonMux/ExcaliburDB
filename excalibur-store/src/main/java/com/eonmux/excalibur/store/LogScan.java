package com.eonmux.excalibur.store;

import com.eonmux.excalibur.core.memory.Segment;
import com.eonmux.excalibur.store.format.LogEntry;

import java.nio.file.Path;

/**
 * Where a write-ahead log on disk ends, and what ended it.
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
 * @param nextLsn   The LSN the next appended record should carry
 * @param endOffset Where the log ends, and where the next record is written
 * @param tornTail  Why the record at {@code endOffset} could not be read, or null if the log
 *                  ended at unwritten bytes
 */
public record LogScan(long nextLsn, long endOffset, String tornTail) {

    /**
     * Reads a mapped log from the beginning to find where it ends.
     *
     * @param mapped      The whole mapped log file
     * @param logFilePath The log being scanned, for the failure message
     * @return What the scan found
     * @throws CorruptLogException if the log is damaged anywhere but its tail
     */
    public static LogScan of(Segment mapped, Path logFilePath) throws CorruptLogException {
        long fileSize = mapped.byteSize();
        long offset = 0;
        long maxLsn = -1;

        while (offset < fileSize && !isUnwritten(mapped, offset)) {
            int size = recordSizeAt(mapped, offset, fileSize);
            if (size < 0) {
                return endsAt(mapped, logFilePath, offset, maxLsn, fileSize);
            }
            maxLsn = Math.max(maxLsn, mapped.getLong(offset + LogEntry.LSN_OFFSET));
            offset += size;
        }

        return new LogScan(maxLsn + 1, offset, null);
    }

    /**
     * Classifies the record that stopped the scan and either ends the log there or refuses it.
     *
     * @throws CorruptLogException if acknowledged records survive past the damage
     */
    private static LogScan endsAt(Segment mapped, Path logFilePath, long stopOffset,
                                  long maxLsn, long fileSize) throws CorruptLogException {
        String problem = problemAt(mapped, stopOffset, fileSize);
        long resumesAt = nextSoundRecord(mapped, stopOffset + 1, fileSize, maxLsn);

        if (resumesAt < 0) {
            return new LogScan(maxLsn + 1, stopOffset, problem);
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
