package com.eonmux.excalibur.graph.file;

/**
 * What a read-only walk of an append-only log found, without opening it.
 *
 * <h2>Why a log has to be asked rather than opened</h2>
 * <p>Opening one of these logs is not a question. It creates a missing file, cuts a torn tail off
 * the end, and rewrites a file an earlier build wrote into the current version - each of them the
 * right thing for a node taking the log over, and each of them a write an offline checker must not
 * make, since the file it has just changed is no longer the one it was asked about. An inspection
 * walks the records with the code the open walks them with, reaches the same verdict, and writes
 * nothing.</p>
 *
 * <h2>What it reports</h2>
 * <p>Only logs the open would accept are reported at all: damage the open refuses, and a version
 * it does not read, are thrown by the inspection as they are by the open. What remains is one of
 * two shapes. Every byte after the header belongs to a record that matched its checksum, or the
 * log ends in bytes that do not read as a record with nothing readable after them - the shape a
 * process killed mid-append leaves, which the open cuts off, keeping every record before it. The
 * second is {@link #hasTornTail()}.</p>
 *
 * @param version     The format version in the log's header, which may be an earlier one the open
 *                    would rewrite
 * @param records     How many records matched their checksums
 * @param intactBytes Where the last of those records ends: the length the open leaves the file at
 * @param fileBytes   The file's length as read
 * @author ExcaliburDB
 */
public record LogInspection(int version, long records, long intactBytes, long fileBytes) {

    /**
     * @throws IllegalArgumentException if a count is negative, or the intact records run past the
     *                                  end of the file
     */
    public LogInspection {
        if (records < 0) {
            throw new IllegalArgumentException("records must not be negative, got " + records);
        }
        if (intactBytes < 0 || intactBytes > fileBytes) {
            throw new IllegalArgumentException("intact bytes " + intactBytes
                + " must lie within the file's " + fileBytes);
        }
    }

    /**
     * How many bytes at the end do not read as a record.
     *
     * @return Bytes the open would cut off; zero for a log that ends cleanly
     */
    public long tornBytes() {
        return fileBytes - intactBytes;
    }

    /**
     * Whether the log ends in a write that was cut short.
     *
     * @return true when the open would trim bytes off the end
     */
    public boolean hasTornTail() {
        return tornBytes() > 0;
    }
}
