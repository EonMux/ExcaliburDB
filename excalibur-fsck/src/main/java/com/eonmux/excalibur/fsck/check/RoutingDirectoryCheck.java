package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.store.RoutingDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads {@code routing.dir}, which says which segment each hash slot belongs to.
 *
 * <p>The store's own start-up answers "no directory" for a file that is absent and for a file
 * that is damaged alike, deliberately: to a node starting up those are the same situation. They
 * are not the same situation to an operator asking whether the data is intact, so the file is
 * parsed here with {@link RoutingDirectory#decode}, the parser that start-up uses, and whatever
 * it refuses is reported with its reason.</p>
 *
 * <p>From version 2 the file ends with a CRC32C of every byte before it, so a file the parser
 * accepts at that version is intact: every slot is the one written. Version 1 carried no
 * checksum; a version 1 file whose magic, width and length all agree is well-formed, which is
 * a weaker claim than intact and is reported as legacy-unprotected. A file a newer build wrote
 * is unreadable rather than corrupt, since nothing shows its bytes are damaged.</p>
 */
public final class RoutingDirectoryCheck {

    /** Name this format goes by in the report. */
    public static final String FORMAT = "routing directory";

    private RoutingDirectoryCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads the routing directory and verifies it.
     *
     * @param file     The routing directory file
     * @param relative Its path relative to the data directory
     * @return The verdict and the slot table, which is empty when nothing could be read
     */
    public static RoutingInspection check(Path file, String relative) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return new RoutingInspection(new FileCheck(file, relative, FORMAT, null,
                CheckStatus.UNREADABLE, "the file could not be read: " + e, -1), new int[0]);
        }
        RoutingDirectory.Contents contents;
        try {
            contents = RoutingDirectory.decode(bytes);
        } catch (RoutingDirectory.UnsupportedVersionException e) {
            IntegrityReport unreadable = IntegrityReport.unsupportedVersion(file, FORMAT,
                e.version(), RoutingDirectory.UNCHECKSUMMED_VERSION, RoutingDirectory.VERSION);
            return new RoutingInspection(IntegrityReports.from(unreadable, file, relative,
                "v" + e.version(), bytes.length), new int[0]);
        } catch (IOException e) {
            return new RoutingInspection(new FileCheck(file, relative, FORMAT, null,
                CheckStatus.CORRUPT, e.getMessage(), bytes.length), new int[0]);
        }
        return new RoutingInspection(verdict(file, relative, contents, bytes.length),
            contents.canonicalPerSlot());
    }

    /**
     * Phrases what reading an accepted file proved.
     *
     * @param file     The routing directory file
     * @param relative Its path relative to the data directory
     * @param contents What the parser read
     * @param size     The file's size
     * @return The verdict
     */
    private static FileCheck verdict(Path file, String relative,
                                     RoutingDirectory.Contents contents, long size) {
        int width = contents.canonicalPerSlot().length;
        String version = "v" + contents.version();
        if (contents.checksummed()) {
            return new FileCheck(file, relative, FORMAT, version, CheckStatus.INTACT,
                "the checksum matched the header and all " + width + " slot entries", size);
        }
        return new FileCheck(file, relative, FORMAT, version, CheckStatus.LEGACY_UNPROTECTED,
            "the header and all " + width + " slot entries are well-formed, but this version "
                + "carries no checksum, so the slot values themselves cannot be verified. The "
                + "node rewrites it with one the next time the directory changes", size);
    }

    /**
     * What reading the routing directory found.
     *
     * @param check The verdict on the file
     * @param slots Canonical segment index per hash slot, empty when nothing was read
     */
    public record RoutingInspection(FileCheck check, int[] slots) {

        /**
         * Canonical constructor, defensively copying the slot table.
         *
         * @throws NullPointerException if the verdict or the slot table is null
         */
        public RoutingInspection {
            Objects.requireNonNull(check, "check");
            slots = Objects.requireNonNull(slots, "slots").clone();
        }

        /**
         * Returns the slot table.
         *
         * @return A copy of the canonical segment index per slot
         */
        @Override
        public int[] slots() {
            return slots.clone();
        }
    }
}
