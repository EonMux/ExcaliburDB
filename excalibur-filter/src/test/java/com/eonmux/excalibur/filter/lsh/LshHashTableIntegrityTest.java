package com.eonmux.excalibur.filter.lsh;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import com.eonmux.excalibur.core.mapped.MappedFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integrity tests for the persisted LSH hash table files.
 *
 * <p>An LSH table decides which segments a query even looks at. A flipped byte in the
 * bucket directory silently removes candidates, and the answer that comes back is short
 * rather than wrong-looking, so nothing downstream can tell.</p>
 *
 * <p>Every damaged-file case here names the byte it damages. The two checksums cover
 * different regions and are reached in a fixed order, so a test that only ever damages one
 * region leaves the other check unexercised - which is how a header-CRC comparison, a
 * truncation bound and a version whitelist all came to be deletable with the suite green.</p>
 */
@DisplayName("LshHashTable file integrity")
class LshHashTableIntegrityTest {

    private static final int DIMENSIONS = 16;
    private static final int NUM_BITS = 12;
    private static final long SEED = 42L;

    /** Inside the seed field, which sits in the header-CRC region and nothing else checks. */
    private static final long HEADER_REGION_BYTE = 20;

    private static final ValueLayout.OfLong LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a flipped byte in a table file is refused when the table is reopened")
    void flippedByteIsRefusedOnReopen() throws IOException {
        Path tableFile = writeTable("flip.dat");

        flipByte(tableFile, 200);

        assertThatThrownBy(() -> new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(tableFile.toString());
    }

    @Test
    @DisplayName("a flipped byte in a table file is refused when the index is reopened")
    void flippedByteIsRefusedWhenIndexReopens() throws IOException {
        Path directory = tempDir.resolve("index");
        LshConfig config = new LshConfig(DIMENSIONS, 2, NUM_BITS, SEED, 4);
        Random random = new Random(3);
        try (LshIndex index = LshIndex.open(config, directory)) {
            for (int i = 0; i < 20; i++) {
                index.insert(randomVector(random), i);
            }
        }

        Path tableFile = directory.resolve("lsh_table_0.dat");
        flipByte(tableFile, 200);

        assertThatThrownBy(() -> LshIndex.open(config, directory))
            .isInstanceOf(IOException.class)
            .hasMessageContaining(tableFile.toString());
    }

    @Test
    @DisplayName("an intact table still reopens with its entries")
    void intactTableStillReopens() throws IOException {
        Path tableFile = writeTable("intact.dat");

        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            assertThat(table.entryCount()).isEqualTo(20);
        }
    }

    @Test
    @DisplayName("a flipped byte in the header region is refused by both checking paths")
    void flippedHeaderByteIsRefused() throws IOException {
        Path tableFile = writeTable("header-flip.dat");

        flipByte(tableFile, HEADER_REGION_BYTE);

        assertThat(LshHashTable.verifyIntegrity(tableFile).status())
            .as("the seed, capacity and allocator cursor live here; nothing but the header "
                + "checksum stands between a flipped bit in them and a table that answers "
                + "every query from the wrong buckets")
            .isEqualTo(IntegrityStatus.CORRUPT);
        assertThatThrownBy(() -> new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a truncated table file is refused rather than digested past its end")
    void truncatedFileIsRefused() throws IOException {
        Path tableFile = writeTable("truncated.dat");

        // One byte short of the payload the seal covers: the smallest truncation the bound
        // check has to catch, and the one a lazy bound most easily lets through.
        long covered = readLong(tableFile, LshHashTableIntegrity.OFFSET_PAYLOAD_LENGTH);
        try (FileChannel channel = FileChannel.open(tableFile, StandardOpenOption.WRITE)) {
            channel.truncate(LshHashTable.HEADER_SIZE + covered - 1);
        }

        assertThat(LshHashTable.verifyIntegrity(tableFile).status())
            .as("the header still claims the payload the whole file used to hold; without a "
                + "bound the digest runs off the end and the verdict comes from an exception")
            .isEqualTo(IntegrityStatus.CORRUPT);
    }

    @Test
    @DisplayName("a header claiming more payload than the file holds is refused")
    void overlongPayloadClaimIsRefused() throws IOException {
        Path tableFile = writeTable("overlong.dat");
        long beyondAnyMapping = Files.size(tableFile) * 4;

        // The allocator cursor is moved with the claim, so the two agree and only the bound
        // check stands between this header and a digest over bytes that do not exist.
        editSealed(tableFile, segment -> {
            segment.set(LONG, LshHashTableIntegrity.OFFSET_PAYLOAD_LENGTH, beyondAnyMapping);
            segment.set(LONG, LshHashTable.OFFSET_ENTRY_STORAGE_OFFSET,
                LshHashTable.HEADER_SIZE + beyondAnyMapping);
        });

        assertThat(LshHashTable.verifyIntegrity(tableFile).status())
            .isEqualTo(IntegrityStatus.CORRUPT);
        assertThatThrownBy(() -> new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED))
            .as("the mapped path must refuse it too, and as a refusal rather than as an "
                + "out-of-bounds fault from the checksum helper")
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("the reserved bytes behind the integrity block are covered")
    void theReservedRunBehindTheIntegrityBlockIsCovered() throws IOException {
        Path tableFile = writeTable("reserved.dat");

        // No field lives here today. A region no checksum digests reports intact however it
        // is damaged, and the next field put there inherits that hole rather than the
        // protection its author will assume it has.
        flipByte(tableFile, 100);

        assertThat(LshHashTable.verifyIntegrity(tableFile).status())
            .as("every byte of the header must fall under one of the two checksums")
            .isEqualTo(IntegrityStatus.CORRUPT);
    }

    @Test
    @DisplayName("a version field downgraded from 3 to 2 is not welcomed as legacy")
    void downgradedVersionIsNotLegacy() throws IOException {
        Path tableFile = writeTable("downgrade.dat");

        writeInt(tableFile, LshHashTable.OFFSET_VERSION, 2);

        assertThat(LshHashTable.verifyIntegrity(tableFile).status())
            .as("a one-bit flip in the version field moves the bucket directory from 128 to "
                + "56, which empties every query while the table still reports its entry "
                + "count; reporting it as an unprotected legacy file certifies the damage")
            .isEqualTo(IntegrityStatus.CORRUPT);
    }

    @ParameterizedTest(name = "version {0}")
    @ValueSource(ints = {-1, 0, 4})
    @DisplayName("a version this format never had is corrupt, not legacy")
    void impossibleVersionsAreCorrupt(int version) throws IOException {
        // Built at the legacy layout on purpose. A version 3 file with a doctored version
        // field carries the format tag and is caught by that alone; only a file with no tag
        // puts the question to the version whitelist itself.
        Path tableFile = writeVersionTwoTable("version-" + version + ".dat");

        writeInt(tableFile, LshHashTable.OFFSET_VERSION, version);

        assertThat(LshHashTable.verifyIntegrity(tableFile).status())
            .as("only versions 1 and 2 were ever written without a checksum; every other "
                + "value in that field is damage, and the engine refuses to open it")
            .isEqualTo(IntegrityStatus.CORRUPT);
    }

    @Test
    @DisplayName("a version 1 file, which this build does not open, is unreadable, not legacy")
    void versionOneIsUnreadableBecauseItIsNotOpened() throws IOException {
        Path tableFile = writeVersionTwoTable("version-one.dat");

        writeInt(tableFile, LshHashTable.OFFSET_VERSION, 1);

        var report = LshHashTable.verifyIntegrity(tableFile);
        assertThat(report.status())
            .as("the engine refuses a version 1 table on open, so a verifier that calls it "
                + "legacy passes a file the node will not serve from")
            .isEqualTo(IntegrityStatus.UNREADABLE);
        assertThat(report.detail()).contains("format version 1").contains("versions 2 to 3");
        assertThatThrownBy(() -> new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED))
            .hasMessageContaining("version");
    }

    @Test
    @DisplayName("a genuine version 2 file is still reported legacy, not corrupt")
    void genuineLegacyFileIsStillLegacy() throws IOException {
        Path tableFile = writeVersionTwoTable("genuine-legacy.dat");

        assertThat(LshHashTable.verifyIntegrity(tableFile).status())
            .as("refusing an unprotected file for having no checksum it was never written "
                + "with would take a node down for reading its own history")
            .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);
    }

    @Test
    @DisplayName("closing a version 2 table leaves the bytes behind its header untouched")
    void versionTwoFileKeepsTheBytesBehindItsHeader() throws IOException {
        Path tableFile = writeVersionTwoTable("legacy.dat");

        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            assertThat(table.entryCount()).isZero();
        }

        byte[] behindHeader = readRange(tableFile, LshHashTable.HEADER_SIZE_V2,
            LshHashTableIntegrity.OFFSET_HEADER_CRC + Long.BYTES);
        assertThat(behindHeader)
            .as("in a version 2 file these bytes are bucket directory rows, not an integrity "
                + "block; stamping one over them rewrites live buckets and vouches for bytes "
                + "no checksum has ever covered")
            .containsOnly((byte) 0);
    }

    @Test
    @DisplayName("a chain that loops back on itself fails the query instead of hanging it")
    void loopedChainFailsRatherThanHangs() throws IOException {
        Path tableFile = writeTable("cycle.dat");

        // Left open rather than sealed: a cycle the CRC would have caught proves nothing
        // about the walk, because the file would be refused before any query reached it.
        long firstEntry = editSealed(tableFile, segment -> {
            long capacity = segment.get(LONG, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY);
            long head = firstOccupiedChainHead(segment, capacity);
            segment.set(LONG, head + LshHashTable.ENTRY_NEXT_OFFSET, head);
            LshHashTableIntegrity.markOpen(segment);
        });
        assertThat(firstEntry).isZero();

        // Not try-with-resources: a walk that never returns holds the table's read lock for
        // good, and close() waits for the write lock. Closing after the assertion keeps a
        // failure reported as a failure instead of as a hung build.
        LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED);
        assertThatThrownBy(() -> onADaemonThread(() -> queryEverything(table)))
            .as("an unbounded walk over a corrupted link hangs the query thread; "
                + "the table must name itself and fail instead")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(tableFile.toString());
        table.close();
    }

    /**
     * Runs a query on a daemon thread and fails if it has not returned within the timeout.
     *
     * <p>A daemon thread rather than the test's own: the defect under test is a walk that
     * never terminates, and a non-daemon thread still spinning on it keeps the whole test
     * JVM alive after the run has finished.</p>
     */
    private static void onADaemonThread(Runnable work) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lsh-chain-walk");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> running = executor.submit(work);
            try {
                running.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : new Exception(e);
            } catch (TimeoutException e) {
                throw new AssertionError("the chain walk did not terminate within 10 seconds, "
                    + "so a corrupted link hangs the query thread rather than failing it", e);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void queryEverything(LshHashTable table) {
        Random random = new Random(3);
        for (int i = 0; i < 20; i++) {
            table.queryExact(randomVector(random));
        }
    }

    /** Finds the chain head of the first bucket that holds at least one entry. */
    private static long firstOccupiedChainHead(MemorySegment segment, long capacity) {
        for (int i = 0; i < capacity; i++) {
            long bucket = LshHashTable.HEADER_SIZE + (long) i * LshHashTable.BUCKET_ENTRY_SIZE;
            long head = segment.get(LONG, bucket + LshHashTable.BUCKET_ENTRY_OFFSET);
            if (head != 0) {
                return head;
            }
        }
        throw new IllegalStateException("the fixture table holds no entries");
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

    /**
     * Writes a version 2 table file by hand: an empty directory behind a 56-byte header.
     *
     * <p>This build cannot produce one, and the point of the fixture is exactly that the
     * bytes a version 3 file spends on its integrity block are directory rows here.</p>
     */
    private Path writeVersionTwoTable(String name) throws IOException {
        int capacity = 1024;
        long size = LshHashTable.HEADER_SIZE_V2
            + (long) capacity * LshHashTable.BUCKET_ENTRY_SIZE + 4096;
        Path tableFile = tempDir.resolve(name);
        try (MappedFile file = MappedFile.create(tableFile, size)) {
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
        return tableFile;
    }

    /** Edits a sealed file in place and re-stamps the header checksum over the result. */
    private static long editSealed(Path file, java.util.function.Consumer<MemorySegment> edit)
            throws IOException {
        try (MappedFile mapped = MappedFile.open(file, Files.size(file))) {
            edit.accept(mapped.segment());
            mapped.segment().set(LONG, LshHashTableIntegrity.OFFSET_HEADER_CRC,
                IndexChecksum.of(mapped.segment(), 0,
                    LshHashTableIntegrity.HEADER_COVERED_BYTES));
            mapped.flush();
        }
        return 0;
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

    private static void writeInt(Path file, long offset, int value) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
            handle.seek(offset);
            handle.writeInt(Integer.reverseBytes(value));
        }
    }

    private static long readLong(Path file, long offset) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
            handle.seek(offset);
            return Long.reverseBytes(handle.readLong());
        }
    }

    private static byte[] readRange(Path file, long from, long to) throws IOException {
        byte[] bytes = new byte[(int) (to - from)];
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
            handle.seek(from);
            handle.readFully(bytes);
        }
        return bytes;
    }
}
