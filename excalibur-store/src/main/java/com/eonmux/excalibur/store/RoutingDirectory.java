package com.eonmux.excalibur.store;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.vector.durability.DurableFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The slot-to-segment routing table, on disk.
 *
 * <p>Routing is {@code hash & (width - 1)}, and several slots may share one segment. A split
 * hands the parent's slots to its two children by the bit it partitioned on - the lowest bit
 * those slots differ in, or the bit a doubling of the directory exposes when the parent owned a
 * single slot - and that bit is not recorded anywhere in the segment file names.
 *
 * <h2>Why guessing does not work</h2>
 * <p>Pointing each slot without a file of its own at whatever {@code slot - highestOneBit(slot)}
 * points at reproduces a directory grown by this build's splits, because each of them hands the
 * right child exactly the parent's slots that carry the new bit. It does not reproduce one grown
 * by a build whose every split doubled the directory and partitioned on the newest bit. There,
 * with segments 7 and then 3 split from a 64-wide directory, slot 195 belongs to the right child
 * of 3 — file {@code segment_131.dat} — and the rule sends it to {@code segment_3.dat} instead.
 * The data in 131 is not deleted; it simply stops being addressable, and the next write for those
 * keys lands in 3 while the old values stay in 131.
 *
 * <p>Both kinds of store leave the same file names behind, so no rule over file names can tell
 * which one it is looking at. The directory is state, so it is written down.
 *
 * <h2>Format</h2>
 * <pre>
 * [magic:8][version:4][width:4] then width * int32 canonical segment index, then [crc32c:4]
 * </pre>
 * Little-endian, matching the other on-disk structures. The file is written beside its
 * destination and moved into place, so a crash mid-write leaves the previous directory rather
 * than a half-written one.
 *
 * <h2>Why the whole file is checksummed</h2>
 * <p>Every slot value is a plausible segment index, so a damaged one does not look damaged: it
 * parses, it names a segment that is on disk, and it sends part of the keyspace there - which is
 * exactly the misrouting this file exists to prevent, arrived at from the other direction. So
 * the last four bytes are the CRC32C of every byte before them, the way the catalog's header
 * slots and records end with a checksum of what precedes them, and a file whose bytes do not
 * match it is treated as a directory that could not be read. The file must also be exactly as
 * long as its width says; a byte past the checksum is not something this build wrote.</p>
 *
 * <h2>Older and newer files</h2>
 * <p>Version 1 carried no checksum. It is still read, and applied exactly as before: its length
 * must cover its width, and nothing else about it can be checked. It is rewritten at the current
 * version the next time the directory is written - by the next split, or by a start that has to
 * reconstruct the directory. A file at a version above {@link #VERSION} was written by a newer
 * build whose layout this one cannot know, and is refused like any other unreadable file.</p>
 *
 * <h2>Durability</h2>
 * <p>The staged file is forced before the rename and the containing directory is forced after
 * it. A rename is what makes the new content authoritative, so bytes still sitting in the page
 * cache when it happens are content the file claims to have and does not; and the directory
 * entry the rename creates is metadata of its own, which a crash can lose while keeping the
 * file it names. Neither failure looks like a failure afterwards: the file parses, and every
 * slot in it is a plausible segment index.
 *
 * <h2>When it cannot be read</h2>
 * <p>{@link #load(Path)} reports no slots rather than throwing, because "no directory" is also
 * what a store written by an earlier build looks like. Deciding what to do about it belongs to
 * the caller, which is the only place that knows whether the heuristic above can still be
 * exact for the segments actually on disk — and, per the worked example, it cannot be once two
 * segments have split. What it does report is why a file that is present was not used, so that
 * the caller's refusal can say whether the file was cut short, failed its checksum or came from
 * a newer build.
 *
 * <p>{@link #decode(byte[])} is the one parser, public so that a checker can reach the same
 * verdict on the same bytes as the node that will open them.
 */
public final class RoutingDirectory {

    private static final Logger LOGGER = Logger.getLogger(RoutingDirectory.class.getName());

    /** File name inside the data directory. */
    public static final String FILE_NAME = "routing.dir";

    /** Distinguishes this file from anything else in the data directory. */
    public static final long MAGIC = 0x4558434C42524F55L;

    /** Format version this build writes: the slot table followed by a CRC32C of the file. */
    public static final int VERSION = 2;

    /** The version written before the checksum existed, still read and never written. */
    public static final int UNCHECKSUMMED_VERSION = 1;

    /** Bytes before the first slot entry: magic, version and width. */
    public static final int HEADER_BYTES = 16;

    /** Bytes of the trailing CRC32C, which covers every byte before it. */
    public static final int CHECKSUM_BYTES = 4;

    /**
     * What a readable directory holds.
     *
     * @param version          The format version it was written at
     * @param canonicalPerSlot Canonical segment index per slot, indexed by slot
     */
    public record Contents(int version, int[] canonicalPerSlot) {

        /**
         * Canonical constructor, defensively copying the slot table.
         *
         * @throws NullPointerException if the slot table is null
         */
        public Contents {
            canonicalPerSlot = Objects.requireNonNull(canonicalPerSlot, "canonicalPerSlot")
                .clone();
        }

        /**
         * Returns the slot table.
         *
         * @return A copy of the canonical segment index per slot
         */
        @Override
        public int[] canonicalPerSlot() {
            return canonicalPerSlot.clone();
        }

        /**
         * Reports whether the file's bytes were weighed against a checksum.
         *
         * @return true from {@link #VERSION} on
         */
        public boolean checksummed() {
            return version >= VERSION;
        }
    }

    /**
     * What looking for the directory found.
     *
     * @param canonicalPerSlot Canonical segment index per slot, or null when there is no usable
     *                         directory
     * @param problem          Why a file that is present was not used, or null when it was used
     *                         or is not there at all
     */
    record Loaded(int[] canonicalPerSlot, String problem) {
    }

    /**
     * A directory written at a version this build does not read.
     *
     * <p>Separate from every other refusal because it is not evidence of damage: the bytes may be
     * exactly what a newer build wrote. A node treats it as a directory it cannot use, and a
     * checker reports it as unreadable rather than corrupt.</p>
     */
    public static final class UnsupportedVersionException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int version;

        UnsupportedVersionException(int version) {
            super("the routing directory is format version " + version + ", which this build "
                + "does not read - it reads versions " + UNCHECKSUMMED_VERSION + " to " + VERSION
                + "; a newer build wrote it");
            this.version = version;
        }

        /**
         * Returns the version the file's header names.
         *
         * @return The version
         */
        public int version() {
            return version;
        }
    }

    private RoutingDirectory() {
        // Utility class
    }

    /**
     * Writes the directory.
     *
     * @param dataDir           The data directory
     * @param canonicalPerSlot  Canonical segment index for each slot, indexed by slot
     * @throws IOException if writing fails
     */
    static void write(Path dataDir, int[] canonicalPerSlot) throws IOException {
        ByteBuffer buffer = ByteBuffer
            .allocate(HEADER_BYTES + canonicalPerSlot.length * Integer.BYTES + CHECKSUM_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(canonicalPerSlot.length);
        for (int canonical : canonicalPerSlot) {
            buffer.putInt(canonical);
        }
        buffer.putInt((int) IndexChecksum.of(buffer.array(), 0, buffer.position()));

        Path temp = dataDir.resolve(FILE_NAME + ".tmp");
        Path destination = dataDir.resolve(FILE_NAME);
        try {
            writeAndForce(temp, buffer.array());
            Files.move(temp, destination,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // A staged file left behind would be picked up by nothing and re-staged over by
            // the next write, but it would also sit in the data directory looking like state.
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        DurableFiles.syncContainingDirectory(destination);
    }

    /**
     * Writes the staged file and forces its contents to storage.
     *
     * <p>Separate from the rename because the order is the whole point: content first, then
     * the name that makes it authoritative.
     *
     * @param temp    The staging path
     * @param content The directory's bytes
     * @throws IOException if the write or the force fails
     */
    private static void writeAndForce(Path temp, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer remaining = ByteBuffer.wrap(content);
            while (remaining.hasRemaining()) {
                channel.write(remaining);
            }
            channel.force(true);
        }
    }

    /**
     * Reads the directory, or returns null when there is none to read.
     *
     * <p>A file that is present but unusable reports the same nothing as a file that is
     * absent, because to a reader they are the same situation: this build has no record of how
     * slots were assigned. What to do about that is the caller's decision, not this one's —
     * see the class javadoc. Why the file was unusable is logged, and {@link #load(Path)}
     * returns it.
     *
     * @param dataDir The data directory
     * @return Canonical segment index per slot, or null
     */
    static int[] read(Path dataDir) {
        return load(dataDir).canonicalPerSlot();
    }

    /**
     * Reads the directory, saying why a file that is present was not used.
     *
     * @param dataDir The data directory
     * @return The slot table, or no slot table and - when a file was there - the reason
     */
    static Loaded load(Path dataDir) {
        Path path = dataDir.resolve(FILE_NAME);
        if (!Files.exists(path)) {
            return new Loaded(null, null);
        }
        String problem;
        try {
            return new Loaded(decode(Files.readAllBytes(path)).canonicalPerSlot(), null);
        } catch (IOException | RuntimeException e) {
            problem = e.getMessage();
            // Null is the documented answer - see above - but it is also the answer for a
            // directory that is simply absent, and the difference is the whole cost of the
            // rebuild the caller is about to do. Say which one this was.
            LOGGER.log(Level.WARNING, e,
                () -> "The routing directory at " + path + " is present but could not be used ("
                    + e.getMessage() + "); slot routing will be rebuilt as if it were absent");
        }
        return new Loaded(null, problem);
    }

    /**
     * Parses a directory's bytes, refusing any that are not exactly what a writer produced.
     *
     * <p>The one parser: the node's own read goes through it, and so does
     * {@code excalibur-fsck}, so the two cannot disagree about which files are usable.</p>
     *
     * @param bytes The whole file
     * @return The version and the slot table
     * @throws UnsupportedVersionException if a newer build wrote the file
     * @throws IOException if the bytes are not a routing directory, are cut short, or do not
     *                     match their checksum; the message says which
     */
    public static Contents decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < HEADER_BYTES) {
            throw new IOException("the routing directory is " + bytes.length + " bytes, too short "
                + "to hold its " + HEADER_BYTES + "-byte header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getLong(0) != MAGIC) {
            throw new IOException("the file does not begin with the routing directory's magic "
                + "number, so it is not one, or its first bytes were overwritten");
        }
        int version = buffer.getInt(Long.BYTES);
        if (version > VERSION) {
            throw new UnsupportedVersionException(version);
        }
        if (version < UNCHECKSUMMED_VERSION) {
            throw new IOException("the routing directory declares format version " + version
                + ", which no build writes; its header was overwritten");
        }
        int width = buffer.getInt(Long.BYTES + Integer.BYTES);
        if (width <= 0 || Integer.bitCount(width) != 1) {
            throw new IOException("the directory declares a width of " + width + ", and a "
                + "routing width is always a positive power of two");
        }
        long table = HEADER_BYTES + (long) width * Integer.BYTES;
        if (version == UNCHECKSUMMED_VERSION) {
            if (bytes.length < table) {
                throw new IOException("the directory declares " + width + " slots, which need "
                    + table + " bytes, and the file is " + bytes.length);
            }
        } else {
            requireChecksum(bytes, width, table);
        }
        int[] canonicalPerSlot = new int[width];
        for (int i = 0; i < width; i++) {
            canonicalPerSlot[i] = buffer.getInt(HEADER_BYTES + i * Integer.BYTES);
        }
        return new Contents(version, canonicalPerSlot);
    }

    /**
     * Confirms that a checksummed file is exactly as long as its width says, and that its
     * trailing CRC32C matches every byte before it.
     *
     * @param bytes The whole file
     * @param width The width its header declares
     * @param table Bytes up to the end of the slot table
     * @throws IOException if the length is wrong or the checksum did not match
     */
    private static void requireChecksum(byte[] bytes, int width, long table) throws IOException {
        long expected = table + CHECKSUM_BYTES;
        if (bytes.length != expected) {
            throw new IOException("the directory declares " + width + " slots, which with its "
                + "checksum need exactly " + expected + " bytes, and the file is " + bytes.length
                + (bytes.length < expected ? ": it was cut short" : ": something follows it"));
        }
        int covered = (int) table;
        int stored = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(covered);
        int actual = (int) IndexChecksum.of(bytes, 0, covered);
        if (stored != actual) {
            throw new IOException(String.format("the routing directory's checksum did not "
                + "match: it records %08x and its %d bytes digest to %08x, so at least one slot "
                + "may name the wrong segment", stored, covered, actual));
        }
    }
}
