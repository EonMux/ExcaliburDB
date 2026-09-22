package com.eonmux.excalibur.filter.lsh;

import com.eonmux.excalibur.core.mapped.MappedFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature 23 Verification Tests for LSH Hash Table with Memory Mapping
 */
class Feature23VerificationTest {

    private static final int DIMENSIONS = 128;
    private static final int NUM_BITS = 16;
    private static final long SEED = 42L;
    private static final int NUM_ENTRIES = 100;

    private Path tempDir;
    private Path tableFile;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("lsh_feature23_test");
        tableFile = tempDir.resolve("test_table.dat");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // ignore
                }
            });
        }
    }

    @Test
    @DisplayName("Should create memory-mapped hash table")
    void shouldCreateMemoryMappedHashTable() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            assertThat(table).isNotNull();
            assertThat(table.numBits()).isEqualTo(NUM_BITS);
            assertThat(table.dimensions()).isEqualTo(DIMENSIONS);
            assertThat(table.entryCount()).isEqualTo(0L);
        }
    }

    @Test
    @DisplayName("Should insert and query from memory-mapped table")
    void shouldInsertAndQueryFromMemoryMappedTable() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            // Insert entries
            for (int i = 0; i < NUM_ENTRIES; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            assertThat(table.entryCount()).isEqualTo(NUM_ENTRIES);

            // Query for inserted entries
            int found = 0;
            for (int i = 0; i < NUM_ENTRIES; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                Set<Long> candidates = table.query(vector, 3);
                if (candidates.contains((long) i)) {
                    found++;
                }
            }

            assertThat(found).isGreaterThan((int) (NUM_ENTRIES * 0.8)); // At least 80% recall
        }
    }

    @Test
    @DisplayName("Should persist data after flush")
    void shouldPersistDataAfterFlush() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            // Insert entries
            for (int i = 0; i < NUM_ENTRIES; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            // Flush to disk
            table.flush();

            // Verify file exists and non-zero size
            assertTrue(Files.exists(tableFile));
            assertTrue(Files.size(tableFile) > LshHashTable.HEADER_SIZE);
        }
    }

    @Test
    @DisplayName("Should persist data after close and reopen")
    void shouldPersistDataAfterCloseAndReopen() throws IOException {
        Path persistenceFile = tempDir.resolve("persistence_test.dat");

        // Create table and insert entries
        try (LshHashTable table = new LshHashTable(persistenceFile, DIMENSIONS, NUM_BITS, SEED)) {
            for (int i = 0; i < NUM_ENTRIES; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            table.flush();
        }

        // Reopen the table
        try (LshHashTable reopened = LshHashTable.load(persistenceFile)) {
            // Verify data persisted
            assertThat(reopened.entryCount()).isEqualTo(NUM_ENTRIES);

            // Query for inserted entries
            int found = 0;
            for (int i = 0; i < NUM_ENTRIES; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                Set<Long> candidates = reopened.query(vector, 3);
                if (candidates.contains((long) i)) {
                    found++;
                }
            }

            assertThat(found).isGreaterThan((int) (NUM_ENTRIES * 0.8)); // At least 80% recall
        }
    }

    @Test
    @DisplayName("Should support delete in memory-mapped table")
    void shouldSupportDeleteInMemoryMappedTable() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            // Insert entries
            for (int i = 0; i < 10; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            assertThat(table.entryCount()).isEqualTo(10);

            // Delete a specific entry
            boolean deleted = table.delete(5L);
            assertTrue(deleted);
            assertThat(table.entryCount()).isEqualTo(9);

            // Verify deleted entry is not returned in query
            float[] vector5 = createRandomVector(DIMENSIONS, 5);
            Set<Long> candidates = table.query(vector5, 3);
            assertThat(candidates).doesNotContain(5L);
        }
    }

    @Test
    @DisplayName("Should handle concurrent inserts")
    void shouldHandleConcurrentInserts() throws Exception {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            int numThreads = 4;
            int insertsPerThread = 25;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch latch = new CountDownLatch(numThreads);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < insertsPerThread; i++) {
                            long entryId = threadId * insertsPerThread + i;
                            float[] vector = createRandomVector(DIMENSIONS, (int) entryId);
                            table.insert(vector, entryId);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertThat(errors.get()).isEqualTo(0);
            assertThat(table.entryCount()).isEqualTo(numThreads * insertsPerThread);
        }
    }

    @Test
    @DisplayName("Should handle concurrent queries")
    void shouldHandleConcurrentQueries() throws Exception {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            // Insert entries
            for (int i = 0; i < 50; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            int numThreads = 4;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch latch = new CountDownLatch(numThreads);
            AtomicInteger errors = new AtomicInteger(0);
            AtomicInteger totalFound = new AtomicInteger(0);

            for (int t = 0; t < numThreads; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 10; i++) {
                            float[] vector = createRandomVector(DIMENSIONS, i);
                            Set<Long> candidates = table.query(vector, 3);
                            if (candidates.contains((long) i)) {
                                totalFound.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertThat(errors.get()).isEqualTo(0);
            assertThat(totalFound.get()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("Should extend file when capacity exceeded")
    void shouldExtendFileWhenCapacityExceeded() throws IOException {
        Path extendFile = tempDir.resolve("extend_test.dat");

        try (LshHashTable table = new LshHashTable(extendFile, DIMENSIONS, NUM_BITS, SEED)) {
            // Insert many entries to trigger bucket directory growth
            for (int i = 0; i < 2000; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            assertThat(table.entryCount()).isEqualTo(2000);
            assertThat(Files.size(extendFile)).isGreaterThanOrEqualTo(1024L);
        }
    }

    @Test
    @DisplayName("Should use save and load methods")
    void shouldUseSaveAndLoadMethods() throws IOException {
        Path saveFile = tempDir.resolve("saved.dat");

        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            // Insert entries
            for (int i = 0; i < NUM_ENTRIES; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            // Save to specific file
            table.save(saveFile);

            // Verify saved file exists
            assertTrue(Files.exists(saveFile));

            // Load from file
            try (LshHashTable loaded = LshHashTable.load(saveFile)) {
                // Verify loaded table has same data
                assertThat(loaded.entryCount()).isEqualTo(NUM_ENTRIES);
                assertThat(loaded.numBits()).isEqualTo(NUM_BITS);
                assertThat(loaded.dimensions()).isEqualTo(DIMENSIONS);
            }
        }
    }

    @Test
    @DisplayName("Should serialize to MemorySegment and deserialize back correctly")
    void shouldSerializeAndDeserializeCorrectly() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            // Insert entries
            for (int i = 0; i < NUM_ENTRIES; i++) {
                float[] vector = createRandomVector(DIMENSIONS, i);
                table.insert(vector, i);
            }

            long entryCount = table.entryCount();

            // Serialize to MemorySegment
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = table.toMemorySegment(arena);

                // Deserialize
                LshHashTable deserialized = LshHashTable.fromMemorySegment(segment);

                // Verify deserialized table
                assertThat(deserialized.entryCount()).isEqualTo(entryCount);
                assertThat(deserialized.numBits()).isEqualTo(NUM_BITS);
                assertThat(deserialized.dimensions()).isEqualTo(DIMENSIONS);
            }
        }
    }

    @Test
    @DisplayName("Should handle invalid magic number")
    void shouldHandleInvalidMagicNumber() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(100);
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 0xDEADBEEF); // Invalid magic
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, 4, 2); // Version

            assertThatThrownBy(() -> LshHashTable.fromMemorySegment(segment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid magic number");
        }
    }

    @Test
    @DisplayName("Should reject null vector")
    void shouldRejectNullVector() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            assertThatThrownBy(() -> table.insert(null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector cannot be null");
        }
    }

    @Test
    @DisplayName("Should reject wrong size vector")
    void shouldRejectWrongSizeVector() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            float[] wrongSize = new float[64];
            assertThatThrownBy(() -> table.insert(wrongSize, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector length");
        }
    }

    @Test
    @DisplayName("Should reject negative entry ID")
    void shouldRejectNegativeEntryId() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            float[] vector = createRandomVector(DIMENSIONS, 1);
            assertThatThrownBy(() -> table.insert(vector, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entryId must be non-negative");
        }
    }

    @Test
    @DisplayName("Should return path to backing file")
    void shouldReturnPathToBackingFile() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            Path path = table.path();
            assertThat(path).isNotNull();
            assertThat(path).isEqualTo(tableFile);
        }
    }

    @Test
    @DisplayName("Should return underlying MemorySegment")
    void shouldReturnUnderlyingMemorySegment() throws IOException {
        try (LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED)) {
            MemorySegment segment = table.segment();
            assertThat(segment).isNotNull();
            assertThat(segment.byteSize()).isGreaterThan(0L);
        }
    }

    @Test
    @DisplayName("Should throw exception when accessing segment after close")
    void shouldThrowExceptionWhenAccessingSegmentAfterClose() throws IOException {
        LshHashTable table = new LshHashTable(tableFile, DIMENSIONS, NUM_BITS, SEED);
        table.close();

        assertThatThrownBy(table::segment)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");
    }

    // Helper methods
    private float[] createRandomVector(int dimensions, int seed) {
        float[] vector = new float[dimensions];
        java.util.Random random = new java.util.Random(seed);
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        // Normalize
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
