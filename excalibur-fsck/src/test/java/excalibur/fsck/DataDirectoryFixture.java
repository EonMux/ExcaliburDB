package excalibur.fsck;

import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import io.excalibur.store.format.SegmentDataHeader;
import io.excalibur.store.format.SegmentFormat;
import io.excalibur.store.format.SegmentLayout;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds and damages a real data directory for the fsck tests.
 *
 * <p>Real rather than hand-assembled: the directory is produced by running the storage engine
 * and closing it, so the catalog, the segment files and their logs are whatever this build
 * actually writes. A fixture that synthesised the bytes would go on passing after the format
 * changed underneath it, which is the one thing a corruption checker must not do.</p>
 */
final class DataDirectoryFixture {

    /** Value prefix written into every entry, used to locate payload bytes to damage. */
    static final String VALUE_PREFIX = "excalibur-fsck-fixture-value-";

    private DataDirectoryFixture() {
        throw new AssertionError("Utility class");
    }

    /**
     * Writes a small, healthy store into a directory and closes it cleanly.
     *
     * @param dataDir Directory to populate, created if absent
     * @param entries How many key/value pairs to store
     * @throws IOException if the store cannot be written
     */
    static void createStore(Path dataDir, int entries) throws IOException {
        Files.createDirectories(dataDir);
        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena, 2)) {
            engine.start();
            for (int i = 0; i < entries; i++) {
                engine.put(key(i), value(i));
            }
        }
    }

    /**
     * Returns the fixture's key for an index.
     *
     * @param index Entry index
     * @return The key bytes
     */
    static byte[] key(int index) {
        return ("excalibur-fsck-fixture-key-" + index).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the fixture's value for an index.
     *
     * @param index Entry index
     * @return The value bytes
     */
    static byte[] value(int index) {
        return (VALUE_PREFIX + index).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Lists the segment data files in a directory, in name order.
     *
     * @param dataDir The data directory
     * @return Every {@code segment_N.dat}
     * @throws IOException if the directory cannot be listed
     */
    static List<Path> segmentFiles(Path dataDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dataDir)) {
            stream.filter(p -> p.getFileName().toString().matches("segment_\\d+\\.dat"))
                .sorted()
                .forEach(files::add);
        }
        return files;
    }

    /**
     * Damages whichever segment file actually holds a fixture payload.
     *
     * <p>Which segment a key lands in is decided by its hash, so the test cannot name the
     * file in advance without restating the placement rule and going wrong when it changes.
     *
     * @param dataDir The data directory
     * @return The segment file that was damaged
     * @throws IOException if no segment holds a fixture payload
     */
    static Path flipPayloadByteInSomeSegment(Path dataDir) throws IOException {
        for (Path segment : segmentFiles(dataDir)) {
            if (holdsPayload(segment)) {
                flipPayloadByte(segment);
                return segment;
            }
        }
        throw new IOException("No segment under " + dataDir + " holds a fixture payload");
    }

    /**
     * Reports whether a segment file holds any fixture payload.
     *
     * @param segment The segment file
     * @return true if the fixture's value prefix appears in it
     * @throws IOException if the file cannot be read
     */
    private static boolean holdsPayload(Path segment) throws IOException {
        return indexOf(Files.readAllBytes(segment),
            VALUE_PREFIX.getBytes(StandardCharsets.UTF_8)) >= 0;
    }

    /**
     * Flips one bit inside an entry payload, producing a checksum mismatch.
     *
     * <p>The byte is found by searching for the fixture's value prefix, so the damage always
     * lands inside a record's checksummed region rather than in slack space, whatever the
     * file's layout happens to be.</p>
     *
     * @param segment The segment file to damage
     * @return The absolute file offset that was changed
     * @throws IOException if the file cannot be read or written, or holds no payload
     */
    static long flipPayloadByte(Path segment) throws IOException {
        byte[] contents = Files.readAllBytes(segment);
        byte[] needle = VALUE_PREFIX.getBytes(StandardCharsets.UTF_8);
        int at = indexOf(contents, needle);
        if (at < 0) {
            throw new IOException("No fixture payload found in " + segment);
        }
        long offset = at + needle.length - 1L;
        byte[] flipped = { (byte) (contents[(int) offset] ^ 0x40) };
        try (var channel = java.nio.channels.FileChannel.open(
                segment, StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(flipped), offset);
        }
        return offset;
    }

    /**
     * Rewrites a segment file's data region header so it reads as the older format.
     *
     * <p>Version 2 and version 3 lay their entry records out identically; the difference is
     * the checksum in each slot, which a version 2 reader never looks at. Changing the one
     * version field therefore turns a file this build wrote into a genuine, readable version
     * 2 file rather than a synthetic approximation of one - which is what makes it usable as
     * a fixture for "what does this tool say about a file a previous build left behind".</p>
     *
     * @param segment The segment file to rewrite
     * @throws IOException if the file cannot be read or written
     */
    static void downgradeSegmentToV2(Path segment) throws IOException {
        byte[] image = Files.readAllBytes(segment);
        int slotTableSize = Segment.deriveSlotCountForRecovery(image.length)
            * SegmentLayout.SLOT_SIZE;
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(image)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int version = SegmentFormat.V2.version();
        buffer.putInt(slotTableSize + 4, version);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(littleEndian(SegmentDataHeader.MAGIC));
        crc.update(littleEndian(version));
        crc.update(littleEndian(0));
        buffer.putInt(slotTableSize + 12, (int) crc.getValue());
        Files.write(segment, image);
    }

    /**
     * Renders an int as four little-endian bytes.
     *
     * @param value The value
     * @return Its little-endian encoding
     */
    private static byte[] littleEndian(int value) {
        return new byte[] {
            (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
        };
    }

    /**
     * Finds the first occurrence of a byte pattern.
     *
     * @param haystack Bytes to search
     * @param needle   Pattern to find
     * @return The index, or -1 if absent
     */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Records every file's size, modification time and content digest.
     *
     * <p>What "check mode did not touch the directory" is asserted against. The digest is
     * needed as well as the timestamp because a write that lands inside the filesystem's
     * timestamp granularity changes the bytes without changing the time.</p>
     *
     * @param root Directory to snapshot, walked recursively
     * @return Relative path to fingerprint, in path order
     * @throws IOException if the tree cannot be read
     */
    static Map<String, String> fingerprint(Path root) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                String name = root.relativize(path).toString();
                if (Files.isDirectory(path)) {
                    snapshot.put(name + "/", "directory");
                } else {
                    snapshot.put(name, fingerprintOf(path));
                }
            }
        }
        return snapshot;
    }

    /**
     * Fingerprints one file.
     *
     * @param path The file
     * @return Its size, modification time and SHA-256, rendered as text
     * @throws IOException if the file cannot be read
     */
    private static String fingerprintOf(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        byte[] hash = digest.digest(Files.readAllBytes(path));
        StringBuilder text = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            text.append(String.format("%02x", b));
        }
        return "size=" + Files.size(path)
            + " mtime=" + Files.getLastModifiedTime(path).toMillis()
            + " sha256=" + text;
    }
}
