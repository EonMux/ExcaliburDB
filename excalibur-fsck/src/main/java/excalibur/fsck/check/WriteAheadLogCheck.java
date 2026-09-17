package excalibur.fsck.check;

import io.excalibur.core.memory.MemorySegmentFactory;
import io.excalibur.store.CorruptLogException;
import io.excalibur.store.LogScan;

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
                "the log is empty, so there is nothing to replay", 0);
        }
        try (ReadOnlyMapping mapping = ReadOnlyMapping.of(file)) {
            return scan(file, relative, size, mapping);
        } catch (CorruptLogException e) {
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.CORRUPT,
                e.getMessage(), size);
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
     * @throws CorruptLogException if the log is damaged anywhere but its tail
     */
    private static FileCheck scan(Path file, String relative, long size,
                                  ReadOnlyMapping mapping) throws CorruptLogException {
        LogScan result = LogScan.of(
            MemorySegmentFactory.wrapNative(mapping.segment()), file);
        if (result.tornTail() != null) {
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.UNSEALED,
                "the log ends in a record torn by a crash at offset " + result.endOffset()
                    + ": " + result.tornTail() + ". Nothing readable follows it, so no "
                    + "acknowledged write was lost; the records before it all matched their "
                    + "checksums", size);
        }
        return new FileCheck(file, relative, FORMAT, null, CheckStatus.INTACT,
            "every record matched its checksum; the log ends cleanly at offset "
                + result.endOffset() + " with " + result.nextLsn() + " as the next lsn", size);
    }

    /**
     * Returns where a log ends, for the repair that trims a torn tail.
     *
     * @param file The log file
     * @return The scan, or null if the log could not be scanned at all
     * @throws CorruptLogException if the log is damaged anywhere but its tail
     * @throws IOException if the log cannot be mapped
     */
    public static LogScan scanFor(Path file) throws CorruptLogException, IOException {
        try (ReadOnlyMapping mapping = ReadOnlyMapping.of(file)) {
            if (mapping.size() == 0) {
                return null;
            }
            return LogScan.of(MemorySegmentFactory.wrapNative(mapping.segment()), file);
        }
    }
}
