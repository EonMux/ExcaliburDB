package excalibur.filter.lsh;

import io.excalibur.core.integrity.IntegrityStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the LSH table's verification entry point and its handling of older files.
 */
@DisplayName("LshHashTable verification")
class LshHashTableVerificationTest {

    private static final int DIMENSIONS = 16;
    private static final int NUM_BITS = 12;
    private static final long SEED = 42L;

    /** Bucket directory capacity a new table starts with, matching the table's own default. */
    private static final int LEGACY_BUCKET_CAPACITY = 1024;

    /** Header size of a version 2 file. */
    private static final int LEGACY_HEADER_SIZE = 56;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a closed table verifies as intact")
    void closedTableVerifiesIntact() throws IOException {
        Path tableFile = writeTable("sealed.dat");

        assertThat(LshHashTable.verifyIntegrity(tableFile).status())
            .isEqualTo(IntegrityStatus.INTACT);
    }

    @Test
    @DisplayName("a table a writer still holds is reported unsealed, not intact")
    void openTableIsReportedUnsealed() throws IOException {
        Path tableFile = tempDir.resolve("live.dat");
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            table.insert(randomVector(new Random(1)), 1L);

            assertThat(LshHashTable.verifyIntegrity(tableFile).status())
                .isEqualTo(IntegrityStatus.UNSEALED);
        }
    }

    @Test
    @DisplayName("a damaged table is reported corrupt, never treated as an empty table")
    void damagedTableIsReportedCorrupt() throws IOException {
        Path tableFile = writeTable("rotted.dat");
        flipByte(tableFile, 200);

        var report = LshHashTable.verifyIntegrity(tableFile);

        assertThat(report.status()).isEqualTo(IntegrityStatus.CORRUPT);
        assertThat(report.loadable()).isFalse();
    }

    @Test
    @DisplayName("a table written before checksums existed still opens, named as unprotected")
    void olderTableStillOpens() throws IOException {
        Path tableFile = tempDir.resolve("legacy.dat");
        Files.write(tableFile, legacyTable());

        assertThat(LshHashTable.verifyIntegrity(tableFile).status())
            .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);

        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            assertThat(table.entryCount()).isZero();
            table.insert(randomVector(new Random(2)), 9L);
            assertThat(table.entryCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a table file that is not there is reported missing")
    void absentTableIsReportedMissing() {
        assertThat(LshHashTable.verifyIntegrity(tempDir.resolve("absent.dat")).status())
            .isEqualTo(IntegrityStatus.MISSING);
    }

    /**
     * Builds an empty version 2 table file: the 56-byte header its layout had, followed by
     * the zeroed bucket directory the entry-storage offset points past.
     */
    private static byte[] legacyTable() {
        long entryStorage = LEGACY_HEADER_SIZE + (long) LEGACY_BUCKET_CAPACITY * 24;
        ByteBuffer buffer = ByteBuffer.allocate((int) entryStorage)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 0x4C534854);
        buffer.putInt(4, 2);
        buffer.putInt(8, NUM_BITS);
        buffer.putInt(12, DIMENSIONS);
        buffer.putLong(16, SEED);
        buffer.putLong(24, 0L);
        buffer.putLong(32, 0L);
        buffer.putLong(40, LEGACY_BUCKET_CAPACITY);
        buffer.putLong(48, entryStorage);
        return buffer.array();
    }

    private Path writeTable(String name) throws IOException {
        Path tableFile = tempDir.resolve(name);
        Random random = new Random(3);
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            for (int i = 0; i < 20; i++) {
                table.insert(randomVector(random), i);
            }
        }
        return tableFile;
    }

    private static float[] randomVector(Random random) {
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = random.nextFloat() - 0.5f;
        }
        return vector;
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
            handle.seek(offset);
            int original = handle.readUnsignedByte();
            handle.seek(offset);
            handle.writeByte(original ^ 0x40);
        }
    }
}
