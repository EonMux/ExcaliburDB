package io.excalibur.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
 * than a half-written one — and a half-written directory is worse than none, because the
 * fallback below at least knows it is guessing.
 */
final class RoutingDirectory {

    /** File name inside the data directory. */
    static final String FILE_NAME = "routing.dir";

    /** Distinguishes this file from anything else in the data directory. */
    static final long MAGIC = 0x4558434C42524F55L;

    /** Format version. */
    static final int VERSION = 1;

    private static final int HEADER_BYTES = 16;

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
        Files.write(temp, buffer.array());
        Files.move(temp, dataDir.resolve(FILE_NAME),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Reads the directory, or returns null when there is none to read.
     *
     * <p>A file that is present but unreadable returns null rather than throwing: the caller can
     * still fall back to rebuilding, and refusing to start over a corrupt routing file would be
     * a worse outcome than starting with a directory the engine says out loud it guessed.
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
            return null;
        }
    }
}
