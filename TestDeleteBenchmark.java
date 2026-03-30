import excalibur.filter.CuckooFilter;

public class TestDeleteBenchmark {
    public static void main(String[] args) {
        System.out.println("Testing CuckooFilter delete functionality...");
        System.out.println();

        // Test 1: Insert and delete at 50% load factor
        System.out.println("Test 1: Delete at 50% load factor");
        CuckooFilter filter = CuckooFilter.create(1_000_000, 13);
        int numEntries = 500_000;
        byte[][] keys = new byte[numEntries][];

        // Insert keys
        for (int i = 0; i < numEntries; i++) {
            byte[] key = new byte[16];
            key[0] = 'T';
            key[1] = 'E';
            key[2] = 'S';
            key[3] = 'T';
            key[4] = (byte) (i >> 24);
            key[5] = (byte) (i >> 16);
            key[6] = (byte) (i >> 8);
            key[7] = (byte) i;
            keys[i] = key;
            boolean inserted = filter.insert(key);
            if (!inserted) {
                System.err.println("FAIL: Could not insert key " + i + " at load factor " + filter.loadFactor());
                System.exit(1);
            }
        }

        double loadFactor = filter.loadFactor();
        System.out.println("  Inserted " + numEntries + " entries, load factor: " + String.format("%.4f", loadFactor));

        // Verify all keys are present
        for (int i = 0; i < numEntries; i++) {
            if (!filter.contains(keys[i])) {
                System.err.println("FAIL: Key " + i + " not found after insert");
                System.exit(1);
            }
        }
        System.out.println("  Verified all " + numEntries + " keys are present");

        // Test delete operations
        int deleteCount = 0;
        long startTime = System.nanoTime();

        for (int i = 0; i < numEntries; i++) {
            boolean deleted = filter.delete(keys[i]);
            if (!deleted) {
                System.err.println("FAIL: Could not delete key " + i);
                System.exit(1);
            }
            deleteCount++;
        }

        long endTime = System.nanoTime();
        double durationSec = (endTime - startTime) / 1_000_000_000.0;
        double opsPerSec = deleteCount / durationSec;

        System.out.println("  Deleted " + deleteCount + " entries in " + String.format("%.3f", durationSec) + " sec");
        System.out.println("  Delete throughput: " + String.format("%.0f", opsPerSec) + " ops/sec");

        // Verify all deleted keys are NOT present (no false negatives)
        for (int i = 0; i < numEntries; i++) {
            if (filter.contains(keys[i])) {
                System.err.println("FAIL: Deleted key " + i + " still found (false negative)");
                System.exit(1);
            }
        }
        System.out.println("  Verified all " + numEntries + " deleted keys are NOT present");

        // Test delete of non-existent key returns false
        byte[] nonExistentKey = new byte[16];
        nonExistentKey[0] = 'N';
        nonExistentKey[1] = 'O';
        nonExistentKey[2] = 'T';
        nonExistentKey[3] = 'F';
        boolean result = filter.delete(nonExistentKey);
        if (result) {
            System.err.println("FAIL: Delete of non-existent key returned true");
            System.exit(1);
        }
        System.out.println("  Verified delete of non-existent key returns false");

        System.out.println();
        System.out.println("Test 2: Delete at 95% load factor");
        filter = CuckooFilter.create(100_000, 13);
        int highLoadEntries = 95_000;
        keys = new byte[highLoadEntries][];

        for (int i = 0; i < highLoadEntries; i++) {
            byte[] key = new byte[16];
            key[0] = 'H';
            key[1] = 'I';
            key[2] = (byte) (i >> 24);
            key[3] = (byte) (i >> 16);
            key[4] = (byte) (i >> 8);
            key[5] = (byte) i;
            keys[i] = key;
            boolean inserted = filter.insert(key);
            if (!inserted) {
                System.err.println("FAIL: Could not insert key " + i + " at high load");
                System.exit(1);
            }
        }

        System.out.println("  Inserted " + highLoadEntries + " entries, load factor: " + String.format("%.4f", filter.loadFactor()));

        // Delete half
        startTime = System.nanoTime();
        for (int i = 0; i < highLoadEntries / 2; i++) {
            if (!filter.delete(keys[i])) {
                System.err.println("FAIL: Could not delete key " + i + " at high load");
                System.exit(1);
            }
        }
        endTime = System.nanoTime();
        durationSec = (endTime - startTime) / 1_000_000_000.0;
        opsPerSec = (highLoadEntries / 2) / durationSec;

        System.out.println("  Deleted " + (highLoadEntries / 2) + " entries at " + String.format("%.0f", opsPerSec) + " ops/sec");

        // Verify deleted keys not present
        for (int i = 0; i < highLoadEntries / 2; i++) {
            if (filter.contains(keys[i])) {
                System.err.println("FAIL: Deleted key " + i + " still found at high load");
                System.exit(1);
            }
        }
        System.out.println("  Verified all deleted keys are NOT present");

        // Verify remaining keys still present
        for (int i = highLoadEntries / 2; i < highLoadEntries; i++) {
            if (!filter.contains(keys[i])) {
                System.err.println("FAIL: Remaining key " + i + " not found");
                System.exit(1);
            }
        }
        System.out.println("  Verified all remaining keys are still present");

        System.out.println();
        System.out.println("Test 3: Delete and re-insert cycle");
        filter = CuckooFilter.create(10_000, 13);
        byte[] testKey = "testcyclekey".getBytes();
        for (int i = 0; i < 1000; i++) {
            filter.insert(testKey);
            if (!filter.contains(testKey)) {
                System.err.println("FAIL: Key not found after insert in cycle " + i);
                System.exit(1);
            }
            if (!filter.delete(testKey)) {
                System.err.println("FAIL: Could not delete in cycle " + i);
                System.exit(1);
            }
            if (filter.contains(testKey)) {
                System.err.println("FAIL: Key still found after delete in cycle " + i);
                System.exit(1);
            }
        }
        System.out.println("  Completed 1000 insert/delete cycles successfully");

        System.out.println();
        System.out.println("====================================");
        System.out.println("ALL BENCHMARK FUNCTIONALITY TESTS PASSED!");
        System.out.println("The CuckooFilterDeleteBenchmark is ready to run.");
        System.out.println("====================================");
    }
}
