package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.core.memory.MemorySegmentFactory;
import com.eonmux.excalibur.store.CorruptLogException;
import com.eonmux.excalibur.store.LogScan;
import com.eonmux.excalibur.store.format.UnsupportedWriteLogFormatException;
import com.eonmux.excalibur.store.format.WriteLogFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies a {@code segment_N.wal} file, and says which kind of end it has.
 *
 * <p>The scan is the store's own {@link LogScan}, so a torn tail and damage in the middle
 * are told apart here exactly as they are on the recovery path - which matters more than
 * anywhere else in this tool. A tail torn by a crash lost only records nobody was ever told
 * about, and trimming it is safe. Damage with sound records behind it lost records that
 * were fsynced and acknowledged, and trimming that destroys committed writes. Deciding
 * between the two by any rule other than the one recovery uses would make the repair mode
 * and the node disagree about the same file.</p>
 *
 * <p>The same scan reads the log's header first, and the header can settle the verdict before
 * any record is weighed. One naming a version this build does not read is
 * {@link CheckStatus#UNREADABLE}: the node refuses such a log and leaves it untouched, nothing
 * shows its bytes are damaged, and its records are laid out in a way this build does not know.
 * A damaged header is {@link CheckStatus#CORRUPT}, since the version it names cannot be
 * believed. A log with no header at all was written before the header existed: its records
 * carry their own checksums and are weighed like any others, but nothing in the file names its
 * format, so the most it can be is {@link CheckStatus#LEGACY_UNPROTECTED}.</p>
 */
public final class WriteAheadLogCheck {

    /** Name this format goes by in the report. */
    public static final String FORMAT = "write-ahead log";

    private WriteAheadLogCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Scans a log from the beginning to find where, and how, it ends.
     *
     * @param file     The log file
     * @param relative Its path relative to the data directory
     * @return What the scan found
     */
    public static FileCheck check(Path file, String relative) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.UNREADABLE,
                "its size could not be read: " + e, -1);
        }
        if (size == 0) {
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.INTACT,
                "the log is empty, so there is nothing to replay; the node writes its header "
                    + "when it next opens it", 0);
        }
        try (ReadOnlyMapping mapping = ReadOnlyMapping.of(file)) {
            return scan(file, relative, size, mapping);
        } catch (UnsupportedWriteLogFormatException refused) {
            return unsupportedVersion(file, relative, size, refused);
        } catch (CorruptLogException e) {
            // needsOperator, and this is the one place in the tool where the flag carries its
            // full weight. The only repair on offer for a CORRUPT file is to move the whole
            // file aside, and the records behind damage in a log were fsynced and acknowledged
            // before the writer was told the write had happened. Setting them aside is losing
            // committed writes - which is exactly what this tool promises --repair will never
            // do - and the planner would otherwise file the log under derived state that a
            // reindex rebuilds. Nothing rebuilds a log.
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.CORRUPT,
                e.getMessage(), size, true);
        } catch (IOException | RuntimeException e) {
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.UNREADABLE,
                "it could not be mapped and scanned: " + e, size);
        }
    }

    /**
     * Runs the store's own scan over a read-only mapping.
     *
     * @param file     The log file
     * @param relative Its path relative to the data directory
     * @param size     The file's size
     * @param mapping  The read-only mapping
     * @return What the scan found
     * @throws UnsupportedWriteLogFormatException if a newer build wrote the log
     * @throws CorruptLogException if the log is damaged anywhere but its tail
     */
    private static FileCheck scan(Path file, String relative, long size,
                                  ReadOnlyMapping mapping)
            throws UnsupportedWriteLogFormatException, CorruptLogException {
        LogScan result = LogScan.of(
            MemorySegmentFactory.wrapNative(mapping.segment()), file);
        String version = "v" + result.format().version();
        if (result.tornTail() != null && result.endOffset() < result.recordsStart()) {
            return new FileCheck(file, relative, FORMAT, version, CheckStatus.UNSEALED,
                "the log's header is torn: " + result.tornTail() + ". Nothing was ever "
                    + "written behind it, so no acknowledged write was lost; the node writes a "
                    + "fresh header when it next opens the log", size);
        }
        if (result.tornTail() != null) {
            return new FileCheck(file, relative, FORMAT, version, CheckStatus.UNSEALED,
                "the log ends in a record torn by a crash at offset " + result.endOffset()
                    + ": " + result.tornTail() + ". Nothing readable follows it, so no "
                    + "acknowledged write was lost; the records before it all matched their "
                    + "checksums", size);
        }
        if (!result.format().hasHeader()) {
            return new FileCheck(file, relative, FORMAT, version,
                CheckStatus.LEGACY_UNPROTECTED, legacyDetail(result), size);
        }
        return new FileCheck(file, relative, FORMAT, version, CheckStatus.INTACT,
            "the header and every record matched their checksums; the log ends cleanly at "
                + "offset " + result.endOffset() + " with " + result.nextLsn()
                + " as the next lsn", size);
    }

    /**
     * Phrases what a headerless log was shown to be, and what it was not.
     *
     * @param result The scan
     * @return The detail
     */
    private static String legacyDetail(LogScan result) {
        if (result.endOffset() == 0) {
            return "the log holds no record and carries no header naming its format, as a "
                + "build before format version " + WriteLogFormat.CURRENT.version() + " left "
                + "it. There is nothing in it to lose, and the node writes its header when it "
                + "next opens it";
        }
        return "every record matched its checksum and the log ends cleanly at offset "
            + result.endOffset() + ", but it carries no header naming its format: a build "
            + "before format version " + WriteLogFormat.CURRENT.version() + " wrote it, and "
            + "nothing in the file says which layout its records are in. It keeps that format "
            + "until a compaction or a split gives its segment a fresh log";
    }

    /**
     * Builds the verdict on a log whose sound header names a version this build does not read.
     *
     * <p>Unreadable, not corrupt: the header's checksum matches, so nothing shows the bytes are
     * damaged, and the records are not walked at all, since their layout is the unknown
     * version's. A corrupt or unsealed verdict would put the log in line to be moved aside or
     * trimmed, and a newer build's log is neither this tool's to move nor this build's to
     * trim.</p>
     *
     * @param file     The log file
     * @param relative Its path relative to the data directory
     * @param size     The file's size
     * @param refused  What the header named
     * @return The verdict
     */
    private static FileCheck unsupportedVersion(Path file, String relative, long size,
                                                UnsupportedWriteLogFormatException refused) {
        return new FileCheck(file, relative, FORMAT, "v" + refused.version(),
            CheckStatus.UNREADABLE, "a header whose CRC matches names format version "
                + refused.version() + ", and this build reads versions " + refused.oldest()
                + " to " + refused.newest() + ": a newer build wrote it. The node refuses to "
                + "open it and leaves it untouched, and its records were not walked", size);
    }

    /**
     * Returns where a log ends, for the repair that trims a torn tail.
     *
     * @param file The log file
     * @return The scan, or null if the log is empty
     * @throws UnsupportedWriteLogFormatException if a newer build wrote the log, which is not
     *                                            this build's to trim
     * @throws CorruptLogException if the log is damaged anywhere but its tail
     * @throws IOException if the log cannot be mapped
     */
    public static LogScan scanFor(Path file) throws IOException {
        try (ReadOnlyMapping mapping = ReadOnlyMapping.of(file)) {
            if (mapping.size() == 0) {
                return null;
            }
            return LogScan.of(MemorySegmentFactory.wrapNative(mapping.segment()), file);
        }
    }
}
