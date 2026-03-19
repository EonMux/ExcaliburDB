package io.excalibur.store;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification tests for the Segment constructor.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Constructor parameter validation</li>
 *   <li>Capacity is power of 2 in valid range</li>
 *   <li>File creation and memory mapping</li>
 *   <li>Slot table initialization</li>
 *   <li>Thread-safety infrastructure</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Capacity MUST be a power of 2 in range [1024, 1048576]</li>
 *   <li>Load factor MUST be computed as entry_count / slot_count</li>
 *   <li>Memory segment MUST be backed by file</li>
 * </ul>
 */
@DisplayName("Segment Constructor Tests")
class SegmentConstructorTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private Path segmentFile;

    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
        segmentFile = tempDir.resolve("constructor-test-segment.bin");
    }

    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }

    // ==================== Parameter Validation Tests ====================

    @Nested
    @DisplayName("Parameter Validation")
    class ParameterValidationTests {

        @Test
        @DisplayName("Should reject null path")
        void testNullPath() {
            assertThrows(IllegalArgumentException.class, () ->
                new Segment(null, 10 * 1024 * 1024, arena));
        }

        @Test
        @DisplayName("Should reject null arena")
        void testNullArena() {
            assertThrows(IllegalArgumentException.class, () ->
                new Segment(segmentFile, 10 * 1024 * 1024, null));
        }

        @Test
        @DisplayName("Should reject zero capacity")
        void testZeroCapacity() {
            assertThrows(IllegalArgumentException.class, () ->
                new Segment(segmentFile, 0, arena));
        }

        @Test
        @DisplayName("Should reject negative capacity")
        void testNegativeCapacity() {
            assertThrows(IllegalArgumentException.class, () ->
                new Segment(segmentFile, -1, arena));
        }

        @Test
        @DisplayName("Should accept valid parameters")
        void testValidParameters() throws IOException {
            assertDoesNotThrow(() -> {
                Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
                segment.close();
            });
        }
    }

    // ==================== Capacity Validation Tests ====================

    @Nested
    @DisplayName("Capacity Validation")
    class CapacityValidationTests {

        @Test
        @DisplayName("Capacity should be power of 2")
        void testCapacityPowerOf2() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                int slotCount = segment.slotCount();
                assertTrue((slotCount & (slotCount - 1)) == 0,
                    "Slot count should be power of 2, got: " + slotCount);
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Capacity should be at least MIN_SEGMENT_CAPACITY")
        void testMinimumCapacity() throws IOException {
            Segment segment = new Segment(segmentFile, 1024, arena);
            try {
                int slotCount = segment.slotCount();
                assertTrue(slotCount >= Segment.MIN_SEGMENT_CAPACITY,
                    "Slot count should be >= " + Segment.MIN_SEGMENT_CAPACITY + ", got: " + slotCount);
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Capacity should be at most MAX_SEGMENT_CAPACITY")
        void testMaximumCapacity() throws IOException {
            Segment segment = new Segment(segmentFile, 100 * 1024 * 1024, arena);
            try {
                int slotCount = segment.slotCount();
                assertTrue(slotCount <= Segment.MAX_SEGMENT_CAPACITY,
                    "Slot count should be <= " + Segment.MAX_SEGMENT_CAPACITY + ", got: " + slotCount);
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Small capacity should be rounded up to MIN_SEGMENT_CAPACITY")
        void testSmallCapacityRounded() throws IOException {
            Segment segment = new Segment(segmentFile, 100, arena);
            try {
                int slotCount = segment.slotCount();
                assertEquals(Segment.MIN_SEGMENT_CAPACITY, slotCount,
                    "Small capacity should be rounded to MIN_SEGMENT_CAPACITY");
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Slot count must be validated as power of 2 in range [1024, 1048576]")
        void testSlotCountValidatedBySegmentConfig() throws IOException {
            // Test various capacity inputs and verify resulting slot count is valid
            long[] testCapacities = {
                1024,           // Minimum
                2 * 1024 * 1024,  // Medium
                50 * 1024 * 1024, // Large
                100 * 1024 * 1024 // Very large
            };

            for (long capacity : testCapacities) {
                Path testFile = tempDir.resolve("test-" + capacity + ".bin");
                Segment segment = new Segment(testFile, capacity, arena);
                try {
                    int slotCount = segment.slotCount();

                    // Verify the slot count passes SegmentConfig validation
                    // This will throw if the slot count is not a valid power of 2
                    // in the range [1024, 1048576]
                    int validated = SegmentConfig.validateCapacity(slotCount);
                    assertEquals(slotCount, validated,
                        "Slot count should pass SegmentConfig.validateCapacity");
                } finally {
                    segment.close();
                }
            }
        }

        @Test
        @DisplayName("All power-of-2 capacities in valid range should be accepted")
        void testAllValidPowerOf2Capacities() throws IOException {
            // Test all power-of-2 values in the valid range [1024, 1048576]
            // which corresponds to 2^10 through 2^20
            for (int exp = 10; exp <= 20; exp++) {
                int expectedSlotCount = 1 << exp;
                // Capacity bytes should produce the expected slot count
                // Formula: slotCount = roundToPowerOf2((capacityBytes / 2) / SLOT_SIZE)
                // So capacityBytes = slotCount * 2 * SLOT_SIZE
                long capacityBytes = (long) expectedSlotCount * 2 * 24; // SLOT_SIZE = 24

                Path testFile = tempDir.resolve("test-2pow" + exp + ".bin");
                Segment segment = new Segment(testFile, capacityBytes, arena);
                try {
                    int slotCount = segment.slotCount();

                    // Verify slot count matches expected
                    assertEquals(expectedSlotCount, slotCount,
                        "Slot count should be 2^" + exp + " = " + expectedSlotCount);

                    // Verify it passes validation
                    SegmentConfig.validateCapacity(slotCount);
                } finally {
                    segment.close();
                }
            }
        }

        @Test
        @DisplayName("Domain constraint: capacity must be power of 2 in [1024, 1048576]")
        void testDomainConstraintCapacity() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                int slotCount = segment.slotCount();

                // Domain correctness: capacity MUST be a power of 2
                assertTrue(SegmentConfig.isPowerOf2(slotCount),
                    "Slot count must be a power of 2, got: " + slotCount);

                // Domain correctness: capacity MUST be in range [1024, 1048576]
                assertTrue(slotCount >= SegmentConfig.MIN_SEGMENT_CAPACITY,
                    "Slot count must be >= " + SegmentConfig.MIN_SEGMENT_CAPACITY);
                assertTrue(slotCount <= SegmentConfig.MAX_SEGMENT_CAPACITY,
                    "Slot count must be <= " + SegmentConfig.MAX_SEGMENT_CAPACITY);
            } finally {
                segment.close();
            }
        }
    }

    // ==================== File Creation Tests ====================

    @Nested
    @DisplayName("File Creation")
    class FileCreationTests {

        @Test
        @DisplayName("Should create file if it does not exist")
        void testCreatesFile() throws IOException {
            assertFalse(Files.exists(segmentFile), "File should not exist before constructor");

            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

            try {
                assertTrue(Files.exists(segmentFile), "File should exist after constructor");
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Should create parent directories if needed")
        void testCreatesParentDirs() throws IOException {
            Path nestedFile = tempDir.resolve("subdir/nested/segment.bin");
            assertFalse(Files.exists(nestedFile.getParent()), "Parent dir should not exist");

            Segment segment = new Segment(nestedFile, 10 * 1024 * 1024, arena);

            try {
                assertTrue(Files.exists(nestedFile.getParent()), "Parent dir should exist");
                assertTrue(Files.exists(nestedFile), "File should exist");
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("File size should include slot table and data region")
        void testFileSize() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);

            try {
                long fileSize = Files.size(segmentFile);
                assertTrue(fileSize > 0, "File should have non-zero size");
                assertTrue(fileSize >= segment.slotCount() * 24L, // 24 bytes per slot
                    "File should be at least as large as slot table");
            } finally {
                segment.close();
            }
        }
    }

    // ==================== Memory Mapping Tests ====================

    @Nested
    @DisplayName("Memory Mapping")
    class MemoryMappingTests {

        @Test
        @DisplayName("Should return non-null MemorySegment")
        void testMemorySegmentNotNull() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                MemorySegment memSegment = segment.segment();
                assertNotNull(memSegment, "MemorySegment should not be null");
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("MemorySegment should have non-zero size")
        void testMemorySegmentSize() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                MemorySegment memSegment = segment.segment();
                assertTrue(memSegment.byteSize() > 0, "MemorySegment should have non-zero size");
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Arena should be accessible")
        void testArenaAccessible() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                Arena segmentArena = segment.arena();
                assertNotNull(segmentArena, "Arena should not be null");
                assertEquals(arena, segmentArena, "Arena should be the same as provided");
            } finally {
                segment.close();
            }
        }
    }

    // ==================== Initial State Tests ====================

    @Nested
    @DisplayName("Initial State")
    class InitialStateTests {

        @Test
        @DisplayName("Initial entry count should be 0")
        void testInitialEntryCount() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                assertEquals(0, segment.entryCount(), "Initial entry count should be 0");
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Initial load factor should be 0")
        void testInitialLoadFactor() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                assertEquals(0.0, segment.loadFactor(), 0.001, "Initial load factor should be 0");
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Should not be closed initially")
        void testNotClosedInitially() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                assertFalse(segment.isClosed(), "Segment should not be closed initially");
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Path should be accessible")
        void testPathAccessible() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                Path path = segment.path();
                assertNotNull(path, "Path should not be null");
                assertEquals(segmentFile, path, "Path should match provided file");
            } finally {
                segment.close();
            }
        }

        @Test
        @DisplayName("Size should be accessible")
        void testSizeAccessible() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                long size = segment.size();
                assertTrue(size > 0, "Size should be positive");
            } finally {
                segment.close();
            }
        }
    }

    // ==================== Load Factor Computation Tests ====================

    @Nested
    @DisplayName("Load Factor Computation")
    class LoadFactorComputationTests {

        @Test
        @DisplayName("Load factor should be entry_count / slot_count")
        void testLoadFactorFormula() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                // Insert some entries
                segment.put("key1".getBytes(), "value1".getBytes());
                segment.put("key2".getBytes(), "value2".getBytes());
                segment.put("key3".getBytes(), "value3".getBytes());

                int entryCount = segment.entryCount();
                int slotCount = segment.slotCount();
                double expectedLoadFactor = (double) entryCount / slotCount;
                double actualLoadFactor = segment.loadFactor();

                assertEquals(expectedLoadFactor, actualLoadFactor, 0.0001,
                    "Load factor should be entry_count / slot_count");
            } finally {
                segment.close();
            }
        }
    }

    // ==================== Thread Safety Tests ====================

    @Nested
    @DisplayName("Thread Safety Infrastructure")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should support concurrent reads after construction")
        void testConcurrentReads() throws Exception {
            // Use a shared arena for concurrent access - confined arenas only support
            // access from the owning thread
            try (Arena sharedArena = Arena.ofShared()) {
                Path sharedSegmentFile = tempDir.resolve("concurrent-test-segment.bin");
                Segment segment = new Segment(sharedSegmentFile, 10 * 1024 * 1024, sharedArena);

                try {
                    // Pre-populate with data
                    segment.put("key".getBytes(), "value".getBytes());

                    // Multiple threads should be able to read
                    Thread[] threads = new Thread[4];
                    boolean[] success = new boolean[4];

                    for (int i = 0; i < 4; i++) {
                        final int index = i;
                        threads[i] = new Thread(() -> {
                            try {
                                MemorySegment result = segment.get("key".getBytes());
                                success[index] = (result != null);
                            } catch (Exception e) {
                                success[index] = false;
                            }
                        });
                    }

                    for (Thread t : threads) t.start();
                    for (Thread t : threads) t.join();

                    for (int i = 0; i < 4; i++) {
                        assertTrue(success[i], "Thread " + i + " should have read successfully");
                    }
                } finally {
                    segment.close();
                }
            }
        }
    }

    // ==================== Lifecycle Tests ====================

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("close() should mark segment as closed")
        void testClose() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            assertFalse(segment.isClosed());
            segment.close();
            assertTrue(segment.isClosed());
        }

        @Test
        @DisplayName("Operations should fail after close")
        void testOperationsAfterClose() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            segment.put("key".getBytes(), "value".getBytes());
            segment.close();

            assertThrows(IllegalStateException.class, () ->
                segment.put("key2".getBytes(), "value2".getBytes()));

            assertThrows(IllegalStateException.class, () ->
                segment.get("key".getBytes()));

            assertThrows(IllegalStateException.class, () ->
                segment.delete("key".getBytes()));
        }

        @Test
        @DisplayName("flush() should work without error")
        void testFlush() throws IOException {
            Segment segment = new Segment(segmentFile, 10 * 1024 * 1024, arena);
            try {
                segment.put("key".getBytes(), "value".getBytes());
                assertDoesNotThrow(() -> segment.flush());
            } finally {
                segment.close();
            }
        }
    }
}
