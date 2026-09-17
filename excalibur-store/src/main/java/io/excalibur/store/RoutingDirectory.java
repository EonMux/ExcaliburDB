package io.excalibur.store;

import io.excalibur.vector.durability.DurableFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The slot-to-segment routing table, on disk.
 *
 * <p>Routing is {@code hash & (width - 1)}, and a split leaves several slots sharing one segment:
 * the left child keeps the parent's slots, the right child takes those slots offset by the old
 * width. Which slot belongs to which child is decided by the bit the split partitioned on, and
 * that bit is not recoverable from the segment file names afterwards.
 *
 * <h2>Why guessing does not work</h2>
 * <p>The engine used to rebuild the directory by pointing each slot without a file of its own at
 * {@code slot - highestOneBit(slot)}. That is right while only one segment has ever split. After
 * two splits it is not: with segments 7 and then 3 split from a 64-wide directory, slot 195
 * belongs to the right child of 3 — file {@code segment_131.dat} — and the rule sends it to
 * {@code segment_3.dat} instead. The data in 131 is not deleted; it simply stops being
 * addressable, and the next write for those keys lands in 3 while the old values stay in 131.
 *
 * <p>No rule over file names can do better, because two segments that split from a common
 * ancestor are indistinguishable by name from two that did not. The directory is state, so it is
 * written down.
 *
 * <h2>Format</h2>
 * <pre>
 * [magic:8][version:4][width:4] then width * int32 canonical segment index
 * </pre>
 * Little-endian, matching the other on-disk structures. The file is written beside its
 * destination and moved into place, so a crash mid-write leaves the previous directory rather
 * than a half-written one.
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
 * <p>{@link #read(Path)} reports nothing rather than throwing, because "no directory" is also
 * what a store written by an earlier build looks like. Deciding what to do about it belongs to
 * the caller, which is the only place that knows whether the heuristic above can still be
 * exact for the segments actually on disk — and, per the worked example, it cannot be once two
 * segments have split.
 */
public final class RoutingDirectory {

    private static final Logger LOGGER = Logger.getLogger(RoutingDirectory.class.getName());

    /** File name inside the data directory. */
    public static final String FILE_NAME = "routing.dir";

    /** Distinguishes this file from anything else in the data directory. */
    public static final long MAGIC = 0x4558434C42524F55L;

    /** Format version. */
    public static final int VERSION = 1;

    /** Bytes before the first slot entry: magic, version and width. */
    public static final int HEADER_BYTES = 16;

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
            .allocate(HEADER_BYTES + canonicalPerSlot.length * Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(canonicalPerSlot.length);
        for (int canonical : canonicalPerSlot) {
            buffer.putInt(canonical);
        }

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
     * <p>A file that is present but unreadable reports the same nothing as a file that is
     * absent, because to a reader they are the same situation: this build has no record of how
     * slots were assigned. What to do about that is the caller's decision, not this one's —
     * see the class javadoc.
     *
     * @param dataDir The data directory
     * @return Canonical segment index per slot, or null
     */
    static int[] read(Path dataDir) {
        Path path = dataDir.resolve(FILE_NAME);
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length < HEADER_BYTES) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.getLong() != MAGIC || buffer.getInt() != VERSION) {
                return null;
            }
            int width = buffer.getInt();
            if (width <= 0 || Integer.bitCount(width) != 1
                    || bytes.length < HEADER_BYTES + (long) width * Integer.BYTES) {
                return null;
            }
            int[] canonicalPerSlot = new int[width];
            for (int i = 0; i < width; i++) {
                canonicalPerSlot[i] = buffer.getInt();
            }
            return canonicalPerSlot;
        } catch (IOException | RuntimeException e) {
            // Null is the documented answer - see above - but it is also the answer for a
            // directory that is simply absent, and the difference is the whole cost of the
            // rebuild the caller is about to do. Say which one this was.
            LOGGER.log(Level.WARNING, e,
                () -> "The routing directory at " + path + " is present but could not be read; "
                    + "slot routing will be rebuilt as if it were absent");
            return null;
        }
    }
}
