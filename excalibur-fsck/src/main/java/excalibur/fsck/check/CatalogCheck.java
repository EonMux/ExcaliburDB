package excalibur.fsck.check;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.catalog.CatalogFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Reads {@code catalog.dat} without opening it as a catalog.
 *
 * <p>Opening it is not an option here. {@link CatalogFile}'s constructor migrates a version 1
 * file in place, rewrites the header when both slots fail, and creates a fresh file over a
 * blank one - all correct for a node starting up, and all forbidden to a checker. The header
 * and record layout is restated here against that class's own public constants, and the
 * arithmetic is pinned by a test that writes a catalog with {@link CatalogFile} and reads it
 * back with this.</p>
 */
public final class CatalogCheck {

    /** Name this format goes by in the report. */
    public static final String FORMAT = "segment catalog";

    /** The catalog's file name inside a data directory. */
    public static final String FILE_NAME = "catalog.dat";

    /** Bytes of a record that the record's own CRC covers. */
    private static final int RECORD_DATA_SIZE = CatalogFile.RECORD_SIZE - Integer.BYTES;

    /** Offset of the volume path field within a record. */
    private static final int VOLUME_PATH_OFFSET = Long.BYTES;

    /** Offset of the lifecycle state byte within a record. */
    private static final int LIFECYCLE_OFFSET =
        VOLUME_PATH_OFFSET + CatalogFile.VOLUME_PATH_SIZE + Integer.BYTES + 1;

    private CatalogCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads the catalog and every row it declares.
     *
     * @param file     The catalog file
     * @param relative Its path relative to the data directory
     * @return The verdict and the rows
     */
    public static CatalogInspection check(Path file, String relative) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return new CatalogInspection(new FileCheck(file, relative, FORMAT, null,
                CheckStatus.UNREADABLE, "the file could not be read: " + e, -1), List.of());
        }
        long size = bytes.length;
        if (size < CatalogFile.HEADER_SLOT_SIZE) {
            return new CatalogInspection(shortFile(file, relative, bytes), List.of());
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        HeaderSlot header = bestHeader(buffer);
        if (header == null) {
            return new CatalogInspection(noHeader(file, relative, bytes), List.of());
        }
        List<CatalogRecord> records = readRecords(buffer, header.count());
        return new CatalogInspection(
            verdict(file, relative, size, header, records), records);
    }

    /**
     * Phrases the verdict for a file too short to hold a header.
     *
     * @param file     The catalog file
     * @param relative Its path relative to the data directory
     * @param bytes    The whole file
     * @return The verdict
     */
    private static FileCheck shortFile(Path file, String relative, byte[] bytes) {
        if (isBlank(bytes)) {
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.UNCHECKED,
                "the file is " + bytes.length + " zero bytes: a catalog that was created and "
                    + "never written, which has lost nothing", bytes.length);
        }
        return new FileCheck(file, relative, FORMAT, null, CheckStatus.CORRUPT,
            "the file is " + bytes.length + " bytes, too short to hold a header, and is not "
                + "blank, so it has been truncated", bytes.length);
    }

    /**
     * Phrases the verdict for a file with no header slot that checksums.
     *
     * @param file     The catalog file
     * @param relative Its path relative to the data directory
     * @param bytes    The whole file
     * @return The verdict
     */
    private static FileCheck noHeader(Path file, String relative, byte[] bytes) {
        if (isBlank(bytes)) {
            return new FileCheck(file, relative, FORMAT, null, CheckStatus.UNCHECKED,
                "every byte is zero: a catalog that was created and never written",
                bytes.length);
        }
        return new FileCheck(file, relative, FORMAT, null, CheckStatus.CORRUPT,
            "neither header slot carries a magic number and a matching CRC, so the segment "
                + "count cannot be read. The rows are individually checksummed and may still "
                + "be recoverable; this build's catalog rebuilds the count from them on open, "
                + "but it cannot be done from here without writing", bytes.length);
    }

    /**
     * Weighs the rows against the header and reaches a verdict.
     *
     * @param file     The catalog file
     * @param relative Its path relative to the data directory
     * @param size     The file's size
     * @param header   The header slot in effect
     * @param records  The rows read
     * @return The verdict
     */
    private static FileCheck verdict(Path file, String relative, long size,
                                     HeaderSlot header, List<CatalogRecord> records) {
        String version = "v" + header.version();
        long bad = records.stream().filter(record -> !record.crcValid()).count();
        if (bad > 0) {
            return new FileCheck(file, relative, FORMAT, version, CheckStatus.CORRUPT,
                bad + " of the " + records.size() + " rows the header counts fail their own "
                    + "CRC. Each row is checksummed separately, so the others are still "
                    + "readable, but this catalog no longer describes every segment it did",
                size);
        }
        if (header.version() != CatalogFile.VERSION) {
            return new FileCheck(file, relative, FORMAT, version, CheckStatus.LEGACY_UNPROTECTED,
                "the header is version " + header.version() + ", which predates the checksummed "
                    + "two-slot header; opening the store migrates it to version "
                    + CatalogFile.VERSION, size);
        }
        return new FileCheck(file, relative, FORMAT, version, CheckStatus.INTACT,
            "header slot " + header.slot() + " (generation " + header.generation()
                + ") matched its CRC, and all " + records.size() + " rows matched theirs", size);
    }

    /**
     * Reads the rows the header counts.
     *
     * @param buffer The whole file
     * @param count  How many rows the header declares
     * @return The rows, in file order
     */
    private static List<CatalogRecord> readRecords(ByteBuffer buffer, int count) {
        List<CatalogRecord> records = new ArrayList<>(Math.max(count, 0));
        for (int index = 0; index < count; index++) {
            long position = CatalogFile.HEADER_SIZE + (long) index * CatalogFile.RECORD_SIZE;
            if (position + CatalogFile.RECORD_SIZE > buffer.capacity()) {
                break;
            }
            records.add(readRecord(buffer, (int) position, index));
        }
        return records;
    }

    /**
     * Reads one row and checks it against its own CRC.
     *
     * @param buffer   The whole file
     * @param position Absolute offset of the row
     * @param index    Position of the row in the file
     * @return The row
     */
    private static CatalogRecord readRecord(ByteBuffer buffer, int position, int index) {
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), position, RECORD_DATA_SIZE);
        int stored = buffer.getInt(position + RECORD_DATA_SIZE);
        boolean valid = stored == (int) crc.getValue();
        long segmentId = buffer.getLong(position);
        String path = fixedString(buffer.array(), position + VOLUME_PATH_OFFSET,
            CatalogFile.VOLUME_PATH_SIZE);
        LifecycleState state = lifecycleAt(buffer, position + LIFECYCLE_OFFSET);
        return new CatalogRecord(index, segmentId, fileNameOf(path), state, valid);
    }

    /**
     * Decodes a lifecycle state byte, tolerating a value this build does not know.
     *
     * @param buffer   The whole file
     * @param position Absolute offset of the byte
     * @return The state, or null if the byte names none
     */
    private static LifecycleState lifecycleAt(ByteBuffer buffer, int position) {
        try {
            return LifecycleState.fromValue(buffer.get(position) & 0xFF);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reduces a stored volume path to the file name a data directory would hold.
     *
     * @param path The path field's value, or null
     * @return The file name, or null
     */
    private static String fileNameOf(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Path name = Path.of(path).getFileName();
        return name == null ? null : name.toString();
    }

    /**
     * Reads a UTF-8 string out of a fixed-width, null-padded field.
     *
     * @param bytes     The whole file
     * @param offset    Offset of the field
     * @param fieldSize Width of the field
     * @return The decoded string, or null if the field is empty
     */
    private static String fixedString(byte[] bytes, int offset, int fieldSize) {
        int length = fieldSize;
        for (int i = 0; i < fieldSize; i++) {
            if (bytes[offset + i] == 0) {
                length = i;
                break;
            }
        }
        return length > 0 ? new String(bytes, offset, length, StandardCharsets.UTF_8) : null;
    }

    /**
     * Finds the newest header slot that passes its own CRC.
     *
     * @param buffer The whole file
     * @return The slot in effect, or null if neither passes
     */
    private static HeaderSlot bestHeader(ByteBuffer buffer) {
        HeaderSlot best = null;
        for (int slot = 0; slot < 2; slot++) {
            HeaderSlot candidate = readHeaderSlot(buffer, slot);
            if (candidate != null && (best == null || candidate.generation() > best.generation())) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Reads one header slot if it is present and checksums.
     *
     * @param buffer The whole file
     * @param slot   Index of the slot, 0 or 1
     * @return The slot, or null if it has no magic number or fails its CRC
     */
    private static HeaderSlot readHeaderSlot(ByteBuffer buffer, int slot) {
        int base = slot * CatalogFile.HEADER_SLOT_SIZE;
        if (base + CatalogFile.HEADER_SLOT_SIZE > buffer.capacity()) {
            return null;
        }
        if (buffer.getInt(base) != CatalogFile.MAGIC_NUMBER) {
            return null;
        }
        int version = buffer.getInt(base + 4);
        int count = buffer.getInt(base + 8);
        long timestamp = buffer.getLong(base + 12);
        long generation = buffer.getLong(base + 20);
        int stored = buffer.getInt(base + 28);
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), base, 28);
        if (stored != (int) crc.getValue() || count < 0 || count > CatalogFile.MAX_SEGMENTS) {
            return null;
        }
        return new HeaderSlot(slot, version, count, timestamp, generation);
    }

    /**
     * Reports whether nothing has ever been written to the file.
     *
     * @param bytes The whole file
     * @return true if every byte is zero
     */
    private static boolean isBlank(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * One header slot that passed its checksum.
     *
     * @param slot       Index of the slot, 0 or 1
     * @param version    The format version it names
     * @param count      How many rows it counts
     * @param timestamp  When it was written
     * @param generation Its write counter, which decides which slot is in effect
     */
    private record HeaderSlot(int slot, int version, int count, long timestamp, long generation) {
    }
}
