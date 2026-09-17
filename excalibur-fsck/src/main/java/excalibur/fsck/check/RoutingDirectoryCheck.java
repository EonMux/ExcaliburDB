package excalibur.fsck.check;

import io.excalibur.store.RoutingDirectory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads {@code routing.dir}, which says which segment each hash slot belongs to.
 *
 * <p>The store's own reader answers null for a file that is absent and for a file that is
 * damaged alike, deliberately: to a node starting up those are the same situation. They are
 * not the same situation to an operator asking whether the data is intact, so the header is
 * parsed here instead, against the format's public constants.</p>
 *
 * <p>Nothing in this format is checksummed. A file whose magic, version and width all agree
 * and whose length matches its width is well-formed, which is a weaker claim than intact and
 * is reported as such.</p>
 */
public final class RoutingDirectoryCheck {

    /** Name this format goes by in the report. */
    public static final String FORMAT = "routing directory";

    private RoutingDirectoryCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads the routing directory's header and slot table.
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
        if (bytes.length < RoutingDirectory.HEADER_BYTES) {
            return corrupt(file, relative, bytes.length,
                "the file is " + bytes.length + " bytes, too short to hold its "
                    + RoutingDirectory.HEADER_BYTES + "-byte header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getLong(0) != RoutingDirectory.MAGIC) {
            return corrupt(file, relative, bytes.length,
                "the file does not begin with the routing directory's magic number, so it is "
                    + "not one, or its first bytes were overwritten");
        }
        int version = buffer.getInt(8);
        if (version != RoutingDirectory.VERSION) {
            return corrupt(file, relative, bytes.length,
                "the file is format version " + version + ", which this build cannot read; it "
                    + "supports version " + RoutingDirectory.VERSION);
        }
        return withWidth(file, relative, bytes, buffer);
    }

    /**
     * Validates the width field and reads the slot table behind it.
     *
     * @param file     The routing directory file
     * @param relative Its path relative to the data directory
     * @param bytes    The whole file
     * @param buffer   A little-endian view of it
     * @return The verdict and the slot table
     */
    private static RoutingInspection withWidth(Path file, String relative, byte[] bytes,
                                               ByteBuffer buffer) {
        int width = buffer.getInt(12);
        if (width <= 0 || Integer.bitCount(width) != 1) {
            return corrupt(file, relative, bytes.length,
                "the directory declares a width of " + width + ", and a routing width is "
                    + "always a positive power of two");
        }
        long needed = RoutingDirectory.HEADER_BYTES + (long) width * Integer.BYTES;
        if (bytes.length < needed) {
            return corrupt(file, relative, bytes.length,
                "the directory declares " + width + " slots, which need " + needed
                    + " bytes, and the file is " + bytes.length);
        }
        int[] slots = new int[width];
        for (int i = 0; i < width; i++) {
            slots[i] = buffer.getInt(RoutingDirectory.HEADER_BYTES + i * Integer.BYTES);
        }
        return new RoutingInspection(new FileCheck(file, relative, FORMAT, "v" + version(buffer),
            CheckStatus.LEGACY_UNPROTECTED,
            "the header and all " + width + " slot entries are well-formed, but this format "
                + "carries no checksum, so the slot values themselves cannot be verified",
            bytes.length), slots);
    }

    /**
     * Reads the version field.
     *
     * @param buffer A little-endian view of the file
     * @return The version
     */
    private static int version(ByteBuffer buffer) {
        return buffer.getInt(8);
    }

    /**
     * Builds a corrupt verdict with no slot table.
     *
     * @param file     The routing directory file
     * @param relative Its path relative to the data directory
     * @param size     The file's size
     * @param detail   What is wrong
     * @return The inspection
     */
    private static RoutingInspection corrupt(Path file, String relative, long size,
                                             String detail) {
        return new RoutingInspection(new FileCheck(file, relative, FORMAT, null,
            CheckStatus.CORRUPT, detail, size), new int[0]);
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
