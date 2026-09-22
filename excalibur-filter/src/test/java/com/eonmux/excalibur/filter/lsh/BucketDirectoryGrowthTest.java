package com.eonmux.excalibur.filter.lsh;

import com.eonmux.excalibur.core.mapped.MappedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the directory-doubling step of an LSH table.
 *
 * <p>{@link LshHashTableTest} covers growth through the public API, which proves entries
 * survive but says nothing about the layout the caller is left with. These tests call the
 * move directly and check the contract the caller depends on: where the allocation cursor
 * ends up, and what the header now says about the file.</p>
 */
@DisplayName("Bucket directory growth")
class BucketDirectoryGrowthTest {

    private static final int DIMENSIONS = 32;
    private static final int NUM_BITS = 16;
    private static final long SEED = 7L;

    /** Enough entries to build chains without tripping the table's own growth threshold. */
    private static final int ENTRIES = 400;

    private static final long HEADROOM = 64L * 1024L;

    private static final ValueLayout.OfLong LONG_LAYOUT =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * Where entry storage starts, derived the way the table derives it.
     *
     * <p>The header field at {@link LshHashTable#OFFSET_ENTRY_STORAGE_OFFSET} holds the end
     * of the region, not the start; the start is implied by the directory capacity.</p>
     */
    private static long entryStorageStart(MemorySegment segment) {
        long capacity = segment.get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY);
        return LshHashTable.HEADER_SIZE + capacity * LshHashTable.BUCKET_ENTRY_SIZE;
    }

    @Test
    @DisplayName("the returned cursor points past the relocated entry region")
    void returnedCursorPointsPastRelocatedRegion(@TempDir Path dir) throws IOException {
        try (MappedFile file = emptyTable(dir.resolve("cursor.lsh"))) {
            long entryStorageEnd = populate(file);
            MemorySegment segment = file.segment();

            long oldStart = entryStorageStart(segment);
            long regionSize = entryStorageEnd - oldStart;

            long newEnd = BucketDirectoryGrowth.doubleCapacity(
                file, LshHashTable.HEADER_SIZE, entryStorageEnd, HEADROOM);

            long newStart = entryStorageStart(file.segment());
            assertThat(newEnd)
                .as("the cursor must land exactly at the end of the region as it now sits, "
                    + "or the next allocation overwrites a live entry")
                .isEqualTo(newStart + regionSize);
        }
    }

    @Test
    @DisplayName("the header describes the enlarged layout")
    void headerDescribesEnlargedLayout(@TempDir Path dir) throws IOException {
        try (MappedFile file = emptyTable(dir.resolve("header.lsh"))) {
            long entryStorageEnd = populate(file);
            long oldCapacity = file.segment()
                .get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY);
            long occupiedBucketsBefore =
                file.segment().get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_COUNT);

            BucketDirectoryGrowth.doubleCapacity(
                file, LshHashTable.HEADER_SIZE, entryStorageEnd, HEADROOM);

            MemorySegment segment = file.segment();
            long newCapacity = segment.get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY);
            assertThat(newCapacity).isEqualTo(oldCapacity * 2);
            assertThat(segment.get(LONG_LAYOUT, LshHashTable.OFFSET_ENTRY_STORAGE_OFFSET))
                .as("the header records where entry storage now ends: the relocated region "
                    + "sits immediately behind the enlarged directory, and the cursor in the "
                    + "header is what the next reopen restores the allocator from")
                .isEqualTo(LshHashTable.HEADER_SIZE
                    + newCapacity * LshHashTable.BUCKET_ENTRY_SIZE
                    + (long) ENTRIES * LshHashTable.ENTRY_SIZE);
            assertThat(segment.get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_COUNT))
                .as("every occupied bucket is counted: an understated count keeps the load "
                    + "factor below its threshold for good, and the directory saturates")
                .isEqualTo(occupiedBucketsBefore);
        }
    }

    @Test
    @DisplayName("every chain is still walkable once the entry region has moved")
    void chainsSurviveTheMove(@TempDir Path dir) throws IOException {
        try (MappedFile file = emptyTable(dir.resolve("chains.lsh"))) {
            // The table's own allocation cursor is left behind by a move made around it, so
            // nothing may be inserted through it after this point - only read.
            LshHashTable table = attach(file);
            long entryStorageEnd = fill(table, file);

            BucketDirectoryGrowth.doubleCapacity(
                file, LshHashTable.HEADER_SIZE, entryStorageEnd, HEADROOM);

            // Looking every entry up again proves the bucket heads and the next-entry links
            // inside the moved records were both rebased; missing either strands the chain.
            for (int i = 0; i < ENTRIES; i++) {
                assertThat(table.queryExact(vector(i)))
                    .as("entry %d must still be reachable after the region moved", i)
                    .contains((long) i);
            }
        }
    }

    /**
     * Creates an initialized, empty table file and hands back the mapping over it.
     *
     * <p>The mapping is the test's to own, because the growth step is called on it directly
     * rather than through a table. Writing the header means creating a table and closing it,
     * which is also what leaves the file sealed and therefore attachable.</p>
     */
    private static MappedFile emptyTable(Path path) throws IOException {
        return emptyTable(path, 1024 * 1024);
    }

    private static MappedFile emptyTable(Path path, long initialSize) throws IOException {
        new LshHashTable(path, DIMENSIONS, NUM_BITS, SEED, initialSize).close();
        return MappedFile.open(path, Files.size(path));
    }

    private static LshHashTable attach(MappedFile file) {
        return new LshHashTable(file, DIMENSIONS, NUM_BITS, SEED);
    }

    /** Fills a fresh table and hands back where its entry storage ends. */
    private long populate(MappedFile file) throws IOException {
        return fill(attach(file), file);
    }

    /**
     * Inserts the fixture entries and computes the table's private allocation cursor.
     *
     * <p>Entries are bump-allocated with no gaps and nothing is deleted, so the cursor is
     * the region start plus one record per entry.</p>
     */
    private long fill(LshHashTable table, MappedFile file) throws IOException {
        for (int i = 0; i < ENTRIES; i++) {
            table.insert(vector(i), i);
        }
        return entryStorageStart(file.segment()) + (long) ENTRIES * LshHashTable.ENTRY_SIZE;
    }

    @Test
    @DisplayName("an entry region longer than the directory grew by is moved without shredding")
    void overlappingRegionIsCopiedFromTheTopDown(@TempDir Path dir) throws IOException {
        // Overlap needs the region to be longer than the distance it travels, which is the
        // old directory size. The table's own growth threshold caps it at 0.7 entries per
        // bucket and so can never reach that - but a near-duplicate workload, which is what
        // LSH is for, piles thousands of entries into one bucket and does. Written directly
        // for that reason: the copy has to run high-to-low, and nothing else here proves it.
        try (MappedFile file = emptyTable(dir.resolve("overlap.lsh"), 8 * 1024 * 1024)) {
            MemorySegment segment = file.segment();
            long regionStart = entryStorageStart(segment);
            long capacity = segment.get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY);
            long delta = capacity * LshHashTable.BUCKET_ENTRY_SIZE;

            int records = (int) (2 * delta / LshHashTable.ENTRY_SIZE);
            for (int i = 0; i < records; i++) {
                long at = regionStart + (long) i * LshHashTable.ENTRY_SIZE;
                segment.set(LONG_LAYOUT, at + LshHashTable.ENTRY_ID, i);
                // A chain link on every other record, so the rebase is under the same load.
                segment.set(LONG_LAYOUT, at + LshHashTable.ENTRY_NEXT_OFFSET,
                    i % 2 == 0 ? 0L : at);
            }
            long regionSize = (long) records * LshHashTable.ENTRY_SIZE;
            assertThat(regionSize)
                .as("the fixture is only meaningful if source and destination overlap")
                .isGreaterThan(delta);

            long newEnd = BucketDirectoryGrowth.doubleCapacity(
                file, LshHashTable.HEADER_SIZE, regionStart + regionSize, HEADROOM);

            MemorySegment moved = file.segment();
            long newStart = newEnd - regionSize;
            for (int i = 0; i < records; i++) {
                long at = newStart + (long) i * LshHashTable.ENTRY_SIZE;
                assertThat(moved.get(LONG_LAYOUT, at + LshHashTable.ENTRY_ID))
                    .as("record %d must survive a copy whose source and destination overlap", i)
                    .isEqualTo(i);
                long expectedLink = i % 2 == 0
                    ? 0L
                    : regionStart + (long) i * LshHashTable.ENTRY_SIZE + (newStart - regionStart);
                assertThat(moved.get(LONG_LAYOUT, at + LshHashTable.ENTRY_NEXT_OFFSET))
                    .as("record %d's chain link must be rebased, not left behind", i)
                    .isEqualTo(expectedLink);
            }
        }
    }

    @Test
    @DisplayName("a table with no entries yet still grows its directory")
    void emptyRegionGrowsWithoutCopying(@TempDir Path dir) throws IOException {
        try (MappedFile file = emptyTable(dir.resolve("empty.lsh"))) {
            MemorySegment segment = file.segment();
            long regionStart = entryStorageStart(segment);
            long oldCapacity =
                segment.get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY);

            long newEnd = BucketDirectoryGrowth.doubleCapacity(
                file, LshHashTable.HEADER_SIZE, regionStart, HEADROOM);

            MemorySegment grown = file.segment();
            long newStart = entryStorageStart(grown);
            assertThat(newEnd)
                .as("with nothing to move, the cursor is the new region start")
                .isEqualTo(newStart);
            assertThat(grown.get(LONG_LAYOUT, LshHashTable.OFFSET_ENTRY_STORAGE_OFFSET))
                .as("the header cursor and the returned cursor are the same statement")
                .isEqualTo(newEnd);
            assertThat(grown.get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_DIR_CAPACITY))
                .isEqualTo(oldCapacity * 2);
            assertThat(grown.get(LONG_LAYOUT, LshHashTable.OFFSET_BUCKET_COUNT)).isZero();
        }
    }

    private static float[] vector(int seed) {
        Random random = new Random(seed);
        float[] vector = new float[DIMENSIONS];
        float norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += vector[i] * vector[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
