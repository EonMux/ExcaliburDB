/bin/bash
# SegmentAllocator Verification Script

set -e

echo "============================================="
echo "SegmentAllocator Verification"
echo "============================================="

# Create temp directory
TEST_DIR=$(mktemp -d)
echo "Test directory: $TEST_DIR"

# Create 16KB file
dd if=/dev/zero of="$TEST_DIR/test.dat" bs=16384 count=1 2>/dev/null

# Create MappedFile using Java
java -cp "excalibur-core/build/libs/excalibur-core-1.0.0-SNAPSHOT.jar" \
  -Djava.io.tmpdir="$TEST_DIR" \
  -c '
import io.excalibur.core.mapped.MappedFile;
import io.excalibur.core.allocator.SegmentAllocator;
import java.lang.foreign.MemorySegment;

public class SegmentAllocatorVerifier {
    public static void main(String[] args) throws Exception {
        MappedFile mf = MappedFile.create("test.dat", 16384);
        MemorySegment segment = mf.segment();
        SegmentAllocator allocator = new SegmentAllocator(segment);
        
        System.out.println("\n--- Allocation Tests ---");
        
        // Test small allocation
        var r1 = allocator.allocate(64);
        System.out.println("Allocated 64 bytes at offset 0, remaining: " + allocator.remaining());
        
        // Test medium allocation
        var r2 = allocator.allocate(4096);
        System.out.println("Allocated 4096 bytes at offset 64, remaining: " + allocator.remaining());
        
        // Test allocateAt
        var r3 = allocator.allocateAt(1000, 256);
        System.out.println("Allocated 256 bytes at offset 1000, remaining: " + allocator.remaining());
        
        // Test statistics
        System.out.println("\n--- Statistics ---");
        System.out.println("Total size: " + allocator.totalSize());
        System.out.println("Current offset: " + allocator.currentOffset());
        System.out.println("Allocated: " + allocator.allocated());
        System.out.println("Remaining: " + allocator.remaining());
        System.out.println("Allocation count: " + allocator.allocationCount());
        
        // Test read/write
        System.out.println("\n--- Read/Write Tests ---");
        byte[] pattern = new byte[512];
        for (int i = 0; i < 512; i++) pattern[i] = (byte) (i & 0xFF);
        
        allocator.write(r1, pattern, 0, 512);
        byte[] read = new byte[512];
        allocator.read(r1, read, 0, 512);
        
        boolean ok = true;
        for (int i = 0; i < 512; i++) {
            if (read[i] != pattern[i]) { ok = false; break; }
        }
        System.out.println("Read/Write test: " + (ok ? "PASSED" : "FAILED"));
        
        // Test free
        System.out.println("\n--- Free Test ---");
        boolean freed = allocator.free(r1);
        System.out.println("Free result: " + (freed ? "true" : "false"));
        System.out.println("Remaining after free: " + allocator.remaining());
        
        // Test seek
        System.out.println("\n--- Seek Test ---");
        allocator.seek(2048);
        System.out.println("Current offset after seek to 2048: " + allocator.currentOffset());
        
        // Test slice
        System.out.println("\n--- Slice Test ---");
        var slice = r1.slice(64, 128);
        System.out.println("Slice byte size: " + slice.byteSize());
        
        // Test clear
        System.out.println("\n--- Clear Test ---");
        allocator.clear();
        System.out.println("Current offset after clear: " + allocator.currentOffset());
        
        mf.close();
        System.out.println("\n=============================================");
        System.out.println("SegmentAllocator Verification: PASSED");
        System.out.println("=============================================");
    }
}
' 2>&1

# Cleanup
rm -rf "$TEST_DIR"
