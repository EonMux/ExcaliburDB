package excalibur.filter.lsh;

import io.excalibur.core.integrity.IndexChecksum;
import io.excalibur.core.integrity.IntegrityReport;
import io.excalibur.core.integrity.IntegrityStatus;
import io.excalibur.core.mapped.MappedFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests that a table's entries survive every way it can leave and re-enter this process.
 *
 * <p>The allocation cursor is the only record of where entry storage ends. It lives in the
 * header, and every path that reopens, copies or serializes a table reads it back. A cursor
 * that is restored to the wrong place does not fail: the next insert lands on top of a live
 * record, and the copy paths truncate the region they copy. Both losses are silent, and the
 * header goes on reporting the count it had before, so nothing downstream can tell.</p>
 */
@DisplayName("LshHashTable persistence")
class LshHashTablePersistenceTest {

    private static final int DIMENSIONS = 16;
    private static final int NUM_BITS = 12;
    private static final long SEED = 42L;
    private static final int BATCH = 20;

    /** Enough refused loads that a leaked descriptor per refusal is unmistakable. */
    private static final int REFUSALS = 200;

    private static final Path PROC_SELF_FD = Path.of("/proc/self/fd");

    private static final ValueLayout.OfLong LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a table reopened and written again keeps the entries it already held")
    void reopenThenInsertKeepsEveryEntry() throws IOException {
        Path file = tempDir.resolve("reopen.lsh");

        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, 0, BATCH);
        }
        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, BATCH, 2 * BATCH);
        }

        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            assertThat(foundIds(table, 2 * BATCH))
                .as("every entry written before the reopen must still be reachable; the "
                    + "allocator restarting at the region start overwrites them in place")
                .isEqualTo(2 * BATCH);
            assertThat(table.entryCount()).isEqualTo(2 * BATCH);
        }
    }

    @Test
    @DisplayName("a file whose header offsets disagree with its payload length is refused")
    void doctoredAllocatorCursorIsRefused() throws IOException {
        Path file = tempDir.resolve("doctored.lsh");
        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, 0, BATCH);
        }

        // Only the cursor moves, and only the header checksum is re-stamped over it. The
        // payload length the seal recorded stays where it was, so the file's two statements
        // about where entry storage ends now disagree by one record - which is exactly the
        // state a cursor that was never persisted leaves behind, and exactly what no check
        // anywhere looked at.
        long cursor = readLong(file, LshHashTable.OFFSET_ENTRY_STORAGE_OFFSET);
        restampHeader(file, segment -> segment.set(LONG,
            LshHashTable.OFFSET_ENTRY_STORAGE_OFFSET, cursor - LshHashTable.ENTRY_SIZE));

        IntegrityReport report = LshHashTable.verifyIntegrity(file);
        assertThat(report.status())
            .as("the allocator cursor and the sealed payload length describe the same "
                + "boundary; a file where they disagree has lost the end of its entry region")
            .isEqualTo(IntegrityStatus.CORRUPT);
        assertThatThrownBy(() -> new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("save() after a reopen writes a file that reloads with every entry")
    void saveAfterReopenPreservesEveryEntry() throws IOException {
        Path file = tempDir.resolve("source.lsh");
        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, 0, BATCH);
        }

        Path copy = tempDir.resolve("copy.lsh");
        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, BATCH, 2 * BATCH);
            table.save(copy);
        }

        assertThat(LshHashTable.verifyIntegrity(copy).status())
            .as("save() seals what it wrote, so the copy must verify")
            .isEqualTo(IntegrityStatus.INTACT);
        try (LshHashTable reloaded = LshHashTable.load(copy)) {
            assertThat(foundIds(reloaded, 2 * BATCH))
                .as("save() copies up to the allocation cursor; a cursor left at the region "
                    + "start copies the directory and none of the entries")
                .isEqualTo(2 * BATCH);
        }
    }

    @Test
    @DisplayName("load() returns a table holding every entry the file holds")
    void loadReturnsEveryEntry() throws IOException {
        Path file = tempDir.resolve("load.lsh");
        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, 0, BATCH);
        }

        try (LshHashTable loaded = LshHashTable.load(file)) {
            assertThat(foundIds(loaded, BATCH)).isEqualTo(BATCH);
            assertThat(loaded.entryCount()).isEqualTo(BATCH);
        }
    }

    @Test
    @DisplayName("load() refuses a version this build never wrote")
    void loadRefusesAnUnsupportedVersion() throws IOException {
        // A genuine version 1 layout, carrying no version 3 format tag. Doctoring a version
        // 3 file instead would be refused by the tag alone, and would say nothing about
        // which versions load() is willing to read.
        Path file = tempDir.resolve("v1.lsh");
        writeVersionTwoTable(file);
        writeInt(file, LshHashTable.OFFSET_VERSION, 1);

        assertThatThrownBy(() -> LshHashTable.load(file))
            .as("version 1 is a layout this build cannot read; load() used to accept it and "
                + "then throw out of the constructor, past the mapping it had opened")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Unsupported version: 1");

        // A mapping still held here would keep the file open.
        Files.delete(file);
    }

    @Test
    @DisplayName("load() releases the mapping it opened when it refuses a file")
    void loadReleasesItsMappingWhenItRefuses() throws IOException {
        // Counting descriptors is the only way to see this from outside: a refusal that
        // walks away from its mapping still refuses the file, with the right message, and
        // the only trace is a descriptor and a shared arena that are never reclaimed.
        assumeTrue(Files.isDirectory(PROC_SELF_FD),
            "open descriptors can only be counted where /proc/self/fd exists");

        Path file = tempDir.resolve("refused.lsh");
        writeVersionTwoTable(file);
        writeInt(file, LshHashTable.OFFSET_VERSION, 1);

        long before = openDescriptors();
        for (int i = 0; i < REFUSALS; i++) {
            assertThatThrownBy(() -> LshHashTable.load(file)).isInstanceOf(IOException.class);
        }
        long after = openDescriptors();

        assertThat(after - before)
            .as("%d refusals must not cost %d descriptors; load() maps the file before it "
                + "reads the header, so every refusal past that point owns the mapping",
                REFUSALS, REFUSALS)
            .isLessThan(REFUSALS / 2);
    }

    private static long openDescriptors() throws IOException {
        try (var entries = Files.list(PROC_SELF_FD)) {
            return entries.count();
        }
    }

    @Test
    @DisplayName("a table round-trips through a memory segment with every entry")
    void memorySegmentRoundTripsEveryEntry() throws IOException {
        Path file = tempDir.resolve("segment.lsh");
        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, 0, BATCH);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment image;
            try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
                insertRange(table, BATCH, 2 * BATCH);
                image = table.toMemorySegment(arena);
            }
            try (LshHashTable restored = LshHashTable.fromMemorySegment(image)) {
                assertThat(foundIds(restored, 2 * BATCH))
                    .as("the image is copied up to the allocation cursor, so a stale cursor "
                        + "drops every entry from it")
                    .isEqualTo(2 * BATCH);
            }
        }
    }

    @Test
    @DisplayName("a version 2 image round-trips through a memory segment with every entry")
    void memorySegmentRoundTripsALegacyImage() throws IOException {
        Path file = tempDir.resolve("legacy-segment.lsh");
        writeVersionTwoTable(file);
        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, 0, BATCH);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment image = readWholeFile(file, arena);

            try (LshHashTable restored = LshHashTable.fromMemorySegment(image)) {
                assertThat(foundIds(restored, BATCH))
                    .as("a version 2 image keeps its bucket directory at %d; a destination "
                        + "laid out for version 3 looks for it at %d and reads bucket rows "
                        + "out of nothing, so every query comes back empty",
                        LshHashTable.HEADER_SIZE_V2, LshHashTable.HEADER_SIZE)
                    .isEqualTo(BATCH);
            }
        }
    }

    @Test
    @DisplayName("fromMemorySegment refuses an image whose seal no longer describes it")
    void fromMemorySegmentRefusesAFlippedImage() throws IOException {
        Path file = tempDir.resolve("flip-image.lsh");
        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, 0, BATCH);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment image;
            try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
                image = table.toMemorySegment(arena);
            }
            long at = LshHashTable.HEADER_SIZE + 8;
            image.set(ValueLayout.JAVA_BYTE, at,
                (byte) (image.get(ValueLayout.JAVA_BYTE, at) ^ 0x40));

            assertThatThrownBy(() -> LshHashTable.fromMemorySegment(image))
                .as("toMemorySegment seals the image it writes; a seal nothing verifies "
                    + "protects nothing")
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a table over a borrowed mapping restores the seal it broke")
    void borrowedMappingIsResealedOnClose() throws IOException {
        Path file = tempDir.resolve("borrowed.lsh");
        try (LshHashTable table = new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED)) {
            insertRange(table, 0, BATCH);
        }
        assertThat(LshHashTable.verifyIntegrity(file).status())
            .isEqualTo(IntegrityStatus.INTACT);

        try (MappedFile mapped = MappedFile.open(file, Files.size(file))) {
            LshHashTable borrowed = new LshHashTable(mapped, DIMENSIONS, NUM_BITS, SEED);
            borrowed.close();
            mapped.flush();
        }

        assertThat(LshHashTable.verifyIntegrity(file).status())
            .as("attaching to a mapping this table does not own still breaks the seal, so "
                + "closing it must put the seal back; otherwise every borrow downgrades the "
                + "file to unsealed for good")
            .isEqualTo(IntegrityStatus.INTACT);
    }

    /**
     * Writes a version 2 table file by hand: an empty directory behind a 56-byte header.
     *
     * <p>This build cannot produce one, and the point of the fixture is that its bucket
     * directory sits at 56 rather than 128.</p>
     */
    private static void writeVersionTwoTable(Path path) throws IOException {
        int capacity = 1024;
        long size = LshHashTable.HEADER_SIZE_V2
            + (long) capacity * LshHashTable.BUCKET_ENTRY_SIZE + 64 * 1024;
        try (MappedFile file = MappedFile.create(path, size)) {
            MemorySegment segment = file.segment();
            ValueLayout.OfInt intLayout =
                ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            segment.set(intLayout, LshHashTable.OFFSET_MAGIC, LshHashTable.MAGIC);
            segment.set(intLayout, LshHashTable.OFFSET_VERSION,
                LshHashTable.VERSION_WITHOUT_CHECKSUM);
            segment.set(intLayout, LshHashTable.OFFSET_NUM_BITS, NUM_BITS);
            segment.set(intLayout, LshHashTable.OFFSET_DIMENSIONS, DIMENSIONS);
            segment.set(LONG, LshHashTable.OFFSET_SEED, SEED);
            segment.set(LONG, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY, capacity);
            segment.set(LONG, LshHashTable.OFFSET_ENTRY_STORAGE_OFFSET,
                LshHashTable.HEADER_SIZE_V2 + (long) capacity * LshHashTable.BUCKET_ENTRY_SIZE);
            file.flush();
        }
    }

    /** Reads a whole table file into an arena-backed image. */
    private static MemorySegment readWholeFile(Path file, Arena arena) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        MemorySegment image = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, image, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return image;
    }

    private static void insertRange(LshHashTable table, int from, int to) throws IOException {
        for (int i = from; i < to; i++) {
            table.insert(vector(i), i);
        }
    }

    private static int foundIds(LshHashTable table, int count) {
        int found = 0;
        for (int i = 0; i < count; i++) {
            if (table.queryExact(vector(i)).contains((long) i)) {
                found++;
            }
        }
        return found;
    }

    private static float[] vector(int id) {
        Random random = new Random(id * 31L + 7);
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }

    /**
     * Edits the header of a sealed file and re-stamps only the header checksum over it.
     *
     * <p>Re-sealing instead would recompute the payload length from the edited header and
     * paper over the very disagreement the edit is meant to create.</p>
     */
    private static void restampHeader(Path file, Consumer<MemorySegment> edit)
            throws IOException {
        try (MappedFile mapped = MappedFile.open(file, Files.size(file))) {
            edit.accept(mapped.segment());
            mapped.segment().set(LONG, LshHashTableIntegrity.OFFSET_HEADER_CRC,
                IndexChecksum.of(mapped.segment(), 0,
                    LshHashTableIntegrity.HEADER_COVERED_BYTES));
            mapped.flush();
        }
    }

    private static long readLong(Path file, long offset) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
            handle.seek(offset);
            return Long.reverseBytes(handle.readLong());
        }
    }

    private static void writeInt(Path file, long offset, int value) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
            handle.seek(offset);
            handle.writeInt(Integer.reverseBytes(value));
        }
    }
}
