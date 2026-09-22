package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.filter.lsh.LshHashTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies an {@code lsh_table_N.dat} locality-sensitive hash table.
 *
 * <p>The filter module's own verifier reads the file through a read-only channel and
 * weighs a CRC32C over the header and another over the bucket directory and entry storage.
 * A table a writer still holds open reports unsealed rather than corrupt.</p>
 */
public final class LshTableCheck {

    /** Prefix of an LSH table's file name inside an index directory. */
    public static final String FILE_PREFIX = "lsh_table_";

    private LshTableCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reports whether a file name is an LSH table's.
     *
     * @param fileName The file's name
     * @return true if the name matches {@code lsh_table_N.dat}
     */
    public static boolean matches(String fileName) {
        return fileName.matches("lsh_table_\\d+\\.dat");
    }

    /**
     * Verifies a hash table file.
     *
     * @param file     The table file
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    public static FileCheck check(Path file, String relative) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }
        return IntegrityReports.from(
            LshHashTable.verifyIntegrity(file), file, relative, null, size);
    }
}
