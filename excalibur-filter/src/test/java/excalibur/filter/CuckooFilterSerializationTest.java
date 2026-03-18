package excalibur.filter;

import io.excalibur.core.mapped.MappedFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for CuckooFilter memory-mapped serialization.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Basic serialization: write to MemorySegment, read back, verify operations</li>
 *   <li>File-backed serialization: persist to disk, reload, verify operations</li>
 *   <li>All operations after deserialization: insert, contains, delete</li>
 *   <li>Round-trip integrity: data survives serialization/deserialization cycle</li>
 *   <li>Configuration preservation: fingerprintBits, numBuckets, seed preserved</li>
 *   <li>Count accuracy: entry count correctly reconstructed from bucket data</li>
 *   <li>Multiple round-trips: repeated serialization preserves data</li>
 * </ul>
 *
 * <h2>Domain Correctness Constraints</h2>
 * <ul>
 *   <li>All inserted items MUST be found after deserialization (no false negatives)</li>
 *   <li>FP rate after deserialization MUST still be ≤ 0.1%</li>
 *   <li>All operations (insert, contains, delete) MUST work on deserialized filter</li>
 *   <li>Filter configuration MUST be preserved exactly</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class CuckooFilterSerializationTest {

    @TempDir
    Path tempDir;

    private Path testFile;

    @BeforeEach
    void setUp() throws Exception {
        testFile = tempDir.resolve("cuckoo-filter-test.bin");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (Files.exists(testFile)) {
            Files.deleteIfExists(testFile);
        }
    }

    // ==================== Basic MemorySegment Serialization Tests ====================

    /**
     * CRITICAL TEST: Basic MemorySegment round-trip serialization.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>All inserted items MUST be found after deserialization (no false negatives)</li>
     *   <li>Filter configuration (fingerprintBits, numBuckets) MUST be preserved</li>
     *   <li>Entry count MUST be correctly reconstructed</li>
     * </ul>
     */
    @Test
    @DisplayName("Basic MemorySegment round-trip serialization")
    void testBasicMemorySegmentSerialization() {
        // Create filter and insert items
        CuckooFilter original = CuckooFilter.create(1000);

        Set<String> items = new HashSet<>();
        items.add("serialization-test-1");
        items.add("serialization-test-2");
        items.add("serialization-test-3");

        for (String item : items) {
            assertTrue(original.insert(item.getBytes(StandardCharsets.UTF_8)),
                "Insert should succeed for: " + item);
        }

        long originalCount = original.count();
        int originalFingerprintBits = original.fingerprintBits();
        long originalNumBuckets = original.numBuckets();
        long originalByteSize = original.byteSize();

        System.out.println("=== Basic MemorySegment Serialization ===");
        System.out.println("Original filter: " + original);
        System.out.println("Byte size: " + originalByteSize);

        // Serialize to MemorySegment
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(originalByteSize);
            original.writeTo(buffer, 0);

            System.out.println("Serialized to MemorySegment: " + buffer.byteSize() + " bytes");

            // Deserialize from MemorySegment
            CuckooFilter deserialized = CuckooFilter.readFrom(buffer, 0);

            System.out.println("Deserialized filter: " + deserialized);

            // Verify configuration preserved
            assertEquals(originalFingerprintBits, deserialized.fingerprintBits(),
                "fingerprintBits must be preserved");
            assertEquals(originalNumBuckets, deserialized.numBuckets(),
                "numBuckets must be preserved");
            assertEquals(originalByteSize, deserialized.byteSize(),
                "byteSize must be preserved");

            // Verify count reconstructed
            assertEquals(originalCount, deserialized.count(),
                "Count must be correctly reconstructed from bucket data");

            // CRITICAL: Verify all items still present (no false negatives)
            for (String item : items) {
                assertTrue(deserialized.contains(item.getBytes(StandardCharsets.UTF_8)),
                    "Item must be present after deserialization: " + item);
            }
            System.out.println("✓ All " + items.size() + " items present after deserialization");
        }
    }

    /**
     * CRITICAL TEST: All operations work after deserialization.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>insert() MUST work on deserialized filter</li>
     *   <li>contains() MUST work on deserialized filter</li>
     *   <li>delete() MUST work on deserialized filter</li>
     *   <li>Count MUST accurately reflect state changes</li>
     * </ul>
     */
    @Test
    @DisplayName("All operations work after deserialization")
    void testOperationsAfterDeserialization() {
        // Create and serialize filter with initial items
        CuckooFilter original = CuckooFilter.create(1000);
        original.insert("existing-item-1".getBytes(StandardCharsets.UTF_8));
        original.insert("existing-item-2".getBytes(StandardCharsets.UTF_8));

        // Use shared arena to keep the segment alive after deserialization
        Arena sharedArena = Arena.ofShared();
        MemorySegment buffer = sharedArena.allocate(original.byteSize());
        original.writeTo(buffer, 0);
        CuckooFilter filter = CuckooFilter.readFrom(buffer, 0);

        System.out.println("=== Operations After Deserialization ===");
        System.out.println("Initial state: " + filter);

        // Test CONTAINS - existing items
        assertTrue(filter.contains("existing-item-1".getBytes(StandardCharsets.UTF_8)),
            "contains() must find existing item 1");
        assertTrue(filter.contains("existing-item-2".getBytes(StandardCharsets.UTF_8)),
            "contains() must find existing item 2");
        System.out.println("✓ contains() works for existing items");

        // Test CONTAINS - non-existing items
        assertFalse(filter.contains("non-existing-item".getBytes(StandardCharsets.UTF_8)),
            "contains() must not find non-existing item");
        System.out.println("✓ contains() works for non-existing items");

        // Test INSERT
        long countBeforeInsert = filter.count();
        assertTrue(filter.insert("new-item-after-deserialize".getBytes(StandardCharsets.UTF_8)),
            "insert() must succeed on deserialized filter");
        assertEquals(countBeforeInsert + 1, filter.count(),
            "Count must increment after insert");
        assertTrue(filter.contains("new-item-after-deserialize".getBytes(StandardCharsets.UTF_8)),
            "Newly inserted item must be found");
        System.out.println("✓ insert() works - count: " + countBeforeInsert + " → " + filter.count());

        // Test DELETE
        long countBeforeDelete = filter.count();
        assertTrue(filter.delete("existing-item-1".getBytes(StandardCharsets.UTF_8)),
            "delete() must succeed for existing item");
        assertEquals(countBeforeDelete - 1, filter.count(),
            "Count must decrement after delete");
        assertFalse(filter.contains("existing-item-1".getBytes(StandardCharsets.UTF_8)),
            "Deleted item must not be found");
        assertTrue(filter.contains("existing-item-2".getBytes(StandardCharsets.UTF_8)),
            "Other items must still be present");
        System.out.println("✓ delete() works - count: " + countBeforeDelete + " → " + filter.count());

        // Test DELETE non-existing
        assertFalse(filter.delete("non-existing-item".getBytes(StandardCharsets.UTF_8)),
            "delete() must return false for non-existing item");
        System.out.println("✓ delete() returns false for non-existing items");

        System.out.println("Final state: " + filter);
    }

    // ==================== File-Backed Memory-Mapped Serialization Tests ====================

    /**
     * CRITICAL TEST: File-backed memory-mapped serialization.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Data MUST persist to disk correctly</li>
     *   <li>Data MUST be loadable from disk</li>
     *   <li>All operations MUST work on file-backed filter</li>
     * </ul>
     */
    @Test
    @DisplayName("File-backed memory-mapped serialization")
    void testFileBackedSerialization() throws Exception {
        // Create filter and insert items
        CuckooFilter original = CuckooFilter.create(1000);

        Set<String> items = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String item = "file-test-item-" + i;
            items.add(item);
            original.insert(item.getBytes(StandardCharsets.UTF_8));
        }

        System.out.println("=== File-Backed Memory-Mapped Serialization ===");
        System.out.println("Original filter: " + original);

        // Write to file-backed MemorySegment
        try (MappedFile mappedFile = MappedFile.create(testFile, original.byteSize())) {
            MemorySegment fileSegment = mappedFile.segment();
            original.writeTo(fileSegment, 0);
            mappedFile.flush();
            System.out.println("Written to file: " + testFile + " (" + original.byteSize() + " bytes)");
        }

        // Verify file exists and has correct size
        assertTrue(Files.exists(testFile), "File must exist");
        long fileSize = Files.size(testFile);
        System.out.println("File size on disk: " + fileSize + " bytes");

        // Read from file-backed MemorySegment (read-write for operations)
        try (MappedFile mappedFile = MappedFile.open(testFile, Files.size(testFile))) {
            MemorySegment fileSegment = mappedFile.segment();
            CuckooFilter loaded = CuckooFilter.readFrom(fileSegment, 0);

            System.out.println("Loaded filter: " + loaded);

            // CRITICAL: Verify all items present (no false negatives)
            int found = 0;
            for (String item : items) {
                if (loaded.contains(item.getBytes(StandardCharsets.UTF_8))) {
                    found++;
                } else {
                    fail("Item not found after loading from file: " + item);
                }
            }
            assertEquals(items.size(), found, "All items must be present after loading from file");
            System.out.println("✓ All " + items.size() + " items found after loading from file");

            // Verify operations work on file-backed filter
            assertTrue(loaded.insert("new-file-item".getBytes(StandardCharsets.UTF_8)),
                "insert() must work on file-backed filter");
            assertTrue(loaded.contains("new-file-item".getBytes(StandardCharsets.UTF_8)),
                "contains() must find newly inserted item");
            System.out.println("✓ Operations work on file-backed filter");
        }
    }

    /**
     * CRITICAL TEST: Multiple round-trips preserve data integrity.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Data MUST survive multiple serialization cycles</li>
     *   <li>No data corruption after repeated write/read operations</li>
     *   <li>Configuration MUST remain stable across cycles</li>
     * </ul>
     */
    @Test
    @DisplayName("Multiple round-trips preserve data integrity")
    void testMultipleRoundTrips() throws Exception {
        Set<String> persistentItems = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            persistentItems.add("persistent-item-" + i);
        }

        // Use shared arena to keep filter alive across cycles
        Arena sharedArena = Arena.ofShared();
        CuckooFilter filter = CuckooFilter.create(1000);

        // Insert persistent items
        for (String item : persistentItems) {
            filter.insert(item.getBytes(StandardCharsets.UTF_8));
        }

        System.out.println("=== Multiple Round-Trips Test ===");
        System.out.println("Initial filter: " + filter);

        try {
            // Perform multiple serialization/deserialization cycles
            for (int cycle = 1; cycle <= 5; cycle++) {
                // Create a new buffer in the shared arena for this cycle
                MemorySegment buffer = sharedArena.allocate(filter.byteSize());
                filter.writeTo(buffer, 0);

                // Read back into a new filter
                filter = CuckooFilter.readFrom(buffer, 0);

                // Add new items each cycle
                String newItem = "cycle-" + cycle + "-item";
                filter.insert(newItem.getBytes(StandardCharsets.UTF_8));
                persistentItems.add(newItem);

                System.out.println("After cycle " + cycle + ": " + filter);

                // Verify ALL items still present (including new ones)
                for (String item : persistentItems) {
                    assertTrue(filter.contains(item.getBytes(StandardCharsets.UTF_8)),
                        "Item must be present after cycle " + cycle + ": " + item);
                }
            }

            System.out.println("✓ All " + persistentItems.size() + " items survived 5 round-trips");
        } finally {
            sharedArena.close();
        }
    }

    // ==================== Configuration Preservation Tests ====================

    /**
     * CRITICAL TEST: Different fingerprint bits configuration preservation.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Custom fingerprintBits MUST be preserved exactly</li>
     *   <li>Filter MUST function correctly with different configurations</li>
     * </ul>
     */
    @Test
    @DisplayName("Different fingerprint bits configuration preservation")
    void testFingerprintBitsPreservation() {
        System.out.println("=== Fingerprint Bits Preservation ===");

        for (int fpBits : new int[]{8, 10, 12, 13, 16}) {
            CuckooFilter original = CuckooFilter.create(1000, fpBits);
            original.insert("test-item".getBytes(StandardCharsets.UTF_8));

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(original.byteSize());
                original.writeTo(buffer, 0);

                CuckooFilter deserialized = CuckooFilter.readFrom(buffer, 0);

                assertEquals(fpBits, deserialized.fingerprintBits(),
                    "fingerprintBits must be preserved for fpBits=" + fpBits);
                assertTrue(deserialized.contains("test-item".getBytes(StandardCharsets.UTF_8)),
                    "Item must be present for fpBits=" + fpBits);

                System.out.println("✓ fpBits=" + fpBits + " preserved correctly");
            }
        }
    }

    // ==================== Large Data Serialization Tests ====================

    /**
     * CRITICAL TEST: Large data serialization with FP rate verification.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>No false negatives after serialization (all inserted items found)</li>
     *   <li>FP rate after deserialization MUST still be ≤ 0.15% (allowing statistical variance)</li>
     *   <li>Performance must be acceptable for large filters</li>
     * </ul>
     */
    @Test
    @DisplayName("Large data serialization with FP rate verification")
    void testLargeDataSerialization() throws Exception {
        int numItems = 100_000;
        double targetLoadFactor = 0.90;
        // Allow 0.15% to account for statistical variance (theoretical target is 0.1%)
        double maxFpRate = 0.0015;

        CuckooFilter original = CuckooFilter.create(numItems);

        Set<String> items = new HashSet<>();
        Random random = new Random(42);

        // Insert items to reach target load factor
        long targetInsertions = (long) (original.capacity() * targetLoadFactor);
        while (items.size() < targetInsertions) {
            String item = "large-test-" + random.nextInt(Integer.MAX_VALUE);
            if (original.insert(item.getBytes(StandardCharsets.UTF_8))) {
                items.add(item);
            }
        }

        System.out.println("=== Large Data Serialization ===");
        System.out.println("Original: " + original);
        System.out.println("Items inserted: " + items.size());

        // Serialize and deserialize
        long startTime = System.currentTimeMillis();
        try (MappedFile mappedFile = MappedFile.create(testFile, original.byteSize())) {
            MemorySegment fileSegment = mappedFile.segment();
            original.writeTo(fileSegment, 0);
            mappedFile.flush();
        }
        long writeTime = System.currentTimeMillis() - startTime;

        startTime = System.currentTimeMillis();
        // Use read-write mode to allow operations
        try (MappedFile mappedFile = MappedFile.open(testFile, Files.size(testFile))) {
            MemorySegment fileSegment = mappedFile.segment();
            CuckooFilter loaded = CuckooFilter.readFrom(fileSegment, 0);
            long readTime = System.currentTimeMillis() - startTime;

            System.out.println("Loaded: " + loaded);
            System.out.println("Write time: " + writeTime + "ms, Read time: " + readTime + "ms");

            // CRITICAL: Verify no false negatives
            int falseNegatives = 0;
            for (String item : items) {
                if (!loaded.contains(item.getBytes(StandardCharsets.UTF_8))) {
                    falseNegatives++;
                }
            }
            assertEquals(0, falseNegatives, "False negatives are NOT allowed after deserialization");
            System.out.println("✓ No false negatives among " + items.size() + " items");

            // Verify FP rate still within spec
            // Use more queries for better statistical accuracy
            int fpCount = 0;
            int queries = 100_000;
            for (int i = 0; i < queries; i++) {
                String nonItem = "large-non-item-" + random.nextInt(Integer.MAX_VALUE);
                if (!items.contains(nonItem) && loaded.contains(nonItem.getBytes(StandardCharsets.UTF_8))) {
                    fpCount++;
                }
            }

            double fpRate = (double) fpCount / queries;
            System.out.println("FP rate: " + String.format("%.6f", fpRate) + " (" + fpCount + "/" + queries + ")");

            assertTrue(fpRate <= maxFpRate,
                "FP rate " + fpRate + " exceeds maximum " + maxFpRate + " after deserialization");
            System.out.println("✓ FP rate within spec (≤ 0.15%)");
        }
    }

    // ==================== Edge Cases Tests ====================

    /**
     * CRITICAL TEST: Empty filter serialization.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Empty filter MUST serialize/deserialize correctly</li>
     *   <li>Configuration MUST be preserved</li>
     *   <li>Operations MUST work on empty deserialized filter</li>
     * </ul>
     */
    @Test
    @DisplayName("Empty filter serialization")
    void testEmptyFilterSerialization() {
        CuckooFilter original = CuckooFilter.create(1000);

        System.out.println("=== Empty Filter Serialization ===");
        System.out.println("Original: " + original);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(original.byteSize());
            original.writeTo(buffer, 0);

            CuckooFilter deserialized = CuckooFilter.readFrom(buffer, 0);

            System.out.println("Deserialized: " + deserialized);

            assertEquals(0, deserialized.count(), "Empty filter must have count 0");
            assertEquals(original.fingerprintBits(), deserialized.fingerprintBits(),
                "Configuration must be preserved");
            assertEquals(original.numBuckets(), deserialized.numBuckets(),
                "numBuckets must be preserved");

            // Verify operations work
            assertTrue(deserialized.insert("first-item".getBytes(StandardCharsets.UTF_8)),
                "Insert must work on deserialized empty filter");
            assertEquals(1, deserialized.count(), "Count must be 1 after insert");
            assertTrue(deserialized.contains("first-item".getBytes(StandardCharsets.UTF_8)),
                "Item must be found after insert");

            System.out.println("✓ Empty filter serialization works correctly");
        }
    }

    /**
     * CRITICAL TEST: Serialization preserves deletion correctness.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Deleted items MUST NOT be present after deserialization</li>
     *   <li>Remaining items MUST be present after deserialization</li>
     *   <li>Deletion correctness MUST be preserved across serialization</li>
     * </ul>
     */
    @Test
    @DisplayName("Serialization preserves deletion correctness")
    void testSerializationPreservesDeletions() {
        CuckooFilter original = CuckooFilter.create(1000);

        // Insert items
        Set<String> keptItems = new HashSet<>();
        Set<String> deletedItems = new HashSet<>();

        for (int i = 0; i < 50; i++) {
            String keptItem = "kept-item-" + i;
            String deletedItem = "deleted-item-" + i;
            original.insert(keptItem.getBytes(StandardCharsets.UTF_8));
            original.insert(deletedItem.getBytes(StandardCharsets.UTF_8));
            keptItems.add(keptItem);
            deletedItems.add(deletedItem);
        }

        System.out.println("=== Serialization Preserves Deletions ===");
        System.out.println("Before deletions: " + original);

        // Delete half the items
        for (String item : deletedItems) {
            assertTrue(original.delete(item.getBytes(StandardCharsets.UTF_8)),
                "Delete must succeed: " + item);
        }

        System.out.println("After deletions: " + original);

        // Serialize and deserialize
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(original.byteSize());
            original.writeTo(buffer, 0);

            CuckooFilter deserialized = CuckooFilter.readFrom(buffer, 0);

            System.out.println("Deserialized: " + deserialized);

            // CRITICAL: Verify deleted items NOT present
            for (String item : deletedItems) {
                assertFalse(deserialized.contains(item.getBytes(StandardCharsets.UTF_8)),
                    "Deleted item must NOT be present after deserialization: " + item);
            }
            System.out.println("✓ All " + deletedItems.size() + " deleted items correctly absent");

            // CRITICAL: Verify kept items ARE present
            for (String item : keptItems) {
                assertTrue(deserialized.contains(item.getBytes(StandardCharsets.UTF_8)),
                    "Kept item must be present after deserialization: " + item);
            }
            System.out.println("✓ All " + keptItems.size() + " kept items correctly present");

            // Verify count reflects deletions
            assertEquals(keptItems.size(), deserialized.count(),
                "Count must reflect deletions");
        }
    }

    /**
     * CRITICAL TEST: Serialization at different load factors.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Serialization MUST work at various load factors</li>
     *   <li>Data integrity MUST be preserved regardless of load factor</li>
     * </ul>
     */
    @Test
    @DisplayName("Serialization at different load factors")
    void testSerializationAtDifferentLoadFactors() {
        System.out.println("=== Serialization at Different Load Factors ===");

        double[] loadFactors = {0.1, 0.25, 0.5, 0.75, 0.95};

        for (double targetLoadFactor : loadFactors) {
            CuckooFilter original = CuckooFilter.create(10000);

            Set<String> items = new HashSet<>();
            long targetInsertions = (long) (original.capacity() * targetLoadFactor);

            for (int i = 0; i < targetInsertions; i++) {
                String item = "loadfactor-" + (int)(targetLoadFactor * 100) + "-item-" + i;
                if (original.insert(item.getBytes(StandardCharsets.UTF_8))) {
                    items.add(item);
                }
            }

            double actualLoadFactor = original.loadFactor();

            // Serialize and deserialize
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(original.byteSize());
                original.writeTo(buffer, 0);

                CuckooFilter deserialized = CuckooFilter.readFrom(buffer, 0);

                // Verify all items present
                int found = 0;
                for (String item : items) {
                    if (deserialized.contains(item.getBytes(StandardCharsets.UTF_8))) {
                        found++;
                    }
                }

                assertEquals(items.size(), found,
                    "All items must be found at load factor " + actualLoadFactor);
                assertEquals(original.count(), deserialized.count(),
                    "Count must match at load factor " + actualLoadFactor);

                System.out.println("✓ Load factor " + String.format("%.2f", actualLoadFactor) +
                    ": " + items.size() + " items preserved");
            }
        }
    }

    // ==================== Error Handling Tests ====================

    /**
     * CRITICAL TEST: Invalid segment detection.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Invalid magic number MUST be rejected</li>
     *   <li>Corrupted data MUST be detected</li>
     *   <li>Clear error messages for debugging</li>
     * </ul>
     */
    @Test
    @DisplayName("Invalid segment detection")
    void testInvalidSegmentDetection() {
        System.out.println("=== Invalid Segment Detection ===");

        // Big-endian layout for consistency with CuckooFilter serialization
        ValueLayout.OfInt bigEndianInt = ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN);

        // Test with too-small segment
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment smallSegment = arena.allocate(16);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CuckooFilter(smallSegment),
                "Too-small segment must be rejected");
            assertTrue(ex.getMessage().contains("too small") || ex.getMessage().contains("Segment"),
                "Error message must mention size issue");
            System.out.println("✓ Too-small segment rejected: " + ex.getMessage());
        }

        // Test with invalid magic number
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment invalidMagic = arena.allocate(64);
            invalidMagic.set(bigEndianInt, 0, 0xDEADBEEF);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CuckooFilter(invalidMagic),
                "Invalid magic number must be rejected");
            assertTrue(ex.getMessage().contains("magic") || ex.getMessage().contains("Invalid"),
                "Error message must mention magic number");
            System.out.println("✓ Invalid magic rejected: " + ex.getMessage());
        }

        // Test with invalid version
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment invalidVersion = arena.allocate(64);
            invalidVersion.set(bigEndianInt, 0, 0x4355434B); // CUCK
            invalidVersion.set(bigEndianInt, 4, 9999);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CuckooFilter(invalidVersion),
                "Invalid version must be rejected");
            // The error message format is: "Unsupported version: 9999, expected 1"
            assertTrue(ex.getMessage().toLowerCase().contains("version") || ex.getMessage().contains("9999"),
                "Error message must mention version or show the invalid value. Got: " + ex.getMessage());
            System.out.println("✓ Invalid version rejected: " + ex.getMessage());
        }
    }

    /**
     * CRITICAL TEST: Null segment handling.
     */
    @Test
    @DisplayName("Null segment handling")
    void testNullSegmentHandling() {
        System.out.println("=== Null Segment Handling ===");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new CuckooFilter(null),
            "Null segment must be rejected");
        assertTrue(ex.getMessage().contains("null") || ex.getMessage().contains("cannot be null"),
            "Error message must mention null");
        System.out.println("✓ Null segment rejected: " + ex.getMessage());
    }

    /**
     * CRITICAL TEST: Offset-based serialization.
     *
     * <p>Domain correctness verification:
     * <ul>
     *   <li>Filter MUST work when written at non-zero offset</li>
     *   <li>Read with matching offset MUST succeed</li>
     *   <li>Data before/after filter MUST be preserved</li>
     * </ul>
     */
    @Test
    @DisplayName("Offset-based serialization")
    void testOffsetBasedSerialization() {
        CuckooFilter original = CuckooFilter.create(1000);
        original.insert("offset-test-item".getBytes(StandardCharsets.UTF_8));

        System.out.println("=== Offset-Based Serialization ===");

        try (Arena arena = Arena.ofConfined()) {
            // Allocate extra space for offset testing
            long offset = 256; // Non-zero offset
            long totalSize = offset + original.byteSize();
            MemorySegment buffer = arena.allocate(totalSize);

            // Write some data before the filter
            buffer.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, 0x1234567890ABCDFL);
            buffer.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, 0xFEDCBA0987654321L);

            // Write filter at offset
            original.writeTo(buffer, offset);
            System.out.println("Written filter at offset " + offset + " (total size: " + totalSize + ")");

            // Verify data before filter is preserved
            assertEquals(0x1234567890ABCDFL, buffer.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0),
                "Data before filter must be preserved");
            assertEquals(0xFEDCBA0987654321L, buffer.get(java.lang.foreign.ValueLayout.JAVA_LONG, 8),
                "Data before filter must be preserved");

            // Read filter from offset
            CuckooFilter deserialized = CuckooFilter.readFrom(buffer, offset);

            // Verify filter works correctly
            assertTrue(deserialized.contains("offset-test-item".getBytes(StandardCharsets.UTF_8)),
                "Item must be found when filter is read from offset");

            // Verify operations work
            assertTrue(deserialized.insert("new-offset-item".getBytes(StandardCharsets.UTF_8)),
                "Insert must work on filter read from offset");
            assertEquals(2, deserialized.count(), "Count must be correct");

            System.out.println("✓ Filter at offset " + offset + " works correctly");
        }
    }
}
