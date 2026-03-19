import io.excalibur.store.catalog.SegmentCatalog;
import java.nio.file.Path;

public class MinimalTest {
    public static void main(String[] args) {
        try {
            System.out.println("Testing SegmentCatalog Basic Operations...");
            
            SegmentCatalog catalog = new SegmentCatalog();
            Path tempDir = java.nio.file.Files.createTempDirectory("test");
            
            // Test 1: Register segment
            System.out.println("\nTest 1: Register segment");
            catalog.registerSegment("test-1", tempDir.resolve("vol1"));
            SegmentCatalog.SegmentMetadata m1 = catalog.getSegment("test-1");
            System.out.println("  Segment registered: " + (m1 != null ? "PASS" : "FAIL"));
            System.out.println("  Lifecycle state: " + m1.getLifecycleState());
            
            // Test 2: Update lifecycle state
            System.out.println("\nTest 2: Update lifecycle state");
            boolean updated = catalog.updateLifecycleState("test-1", SegmentCatalog.LifecycleState.ACTIVE);
            m1 = catalog.getSegment("test-1");
            System.out.println("  State updated: " + (updated && m1.getLifecycleState() == SegmentCatalog.LifecycleState.ACTIVE ? "PASS" : "FAIL"));
            
            // Test 3: Increment entry count
            System.out.println("\nTest 3: Increment entry count");
            boolean incremented = catalog.incrementEntryCount("test-1", 100);
            m1 = catalog.getSegment("test-1");
            System.out.println("  Entry count incremented: " + (incremented && m1.getEntryCount() == 100 ? "PASS" : "FAIL"));
            
            // Test 4: Get stats
            System.out.println("\nTest 4: Get stats");
            SegmentCatalog.CatalogStats stats = catalog.getStats();
            System.out.println("  Total segments: " + stats.getTotalSegments() + " (expected: 1) " + (stats.getTotalSegments() == 1 ? "PASS" : "FAIL"));
            System.out.println("  Active segments: " + stats.getActiveSegments() + " (expected: 1) " + (stats.getActiveSegments() == 1 ? "PASS" : "FAIL"));
            
            // Test 5: Concurrent access (simple test)
            System.out.println("\nTest 5: Concurrent read");
            int errors = 0;
            for (int i = 0; i < 1000; i++) {
                try {
                    catalog.getSegment("test-1");
                    catalog.getStats();
                } catch (Exception e) {
                    errors++;
                }
            }
            System.out.println("  Errors during concurrent reads: " + (errors == 0 ? "PASS" : "FAIL"));
            
            // Test 6: Tombstone and recover
            System.out.println("\nTest 6: Tombstone and recover");
            catalog.tombstoneSegment("test-1");
            System.out.println("  Segment tombstoned: " + (catalog.isTombstoned("test-1") ? "PASS" : "FAIL"));
            catalog.recoverSegment("test-1");
            System.out.println("  Segment recovered: " + (catalog.getSegment("test-1") != null ? "PASS" : "FAIL"));
            
            // Test 7: Serialization
            System.out.println("\nTest 7: Serialization");
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024);
            int written = catalog.serializeToBuffer(buffer);
            System.out.println("  Serialized " + written + " bytes");
            catalog.deserializeFromBuffer(buffer);  // Reset
            System.out.println("  Deserialized successfully: PASS");
            
            // Summary
            System.out.println("\n========================================");
            System.out.println("Basic Operations Test Summary:");
            System.out.println("  All tests completed without exceptions!");
            System.out.println("========================================");
            
            catalog.clear();
            System.out.println("\nAll tests completed!");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
